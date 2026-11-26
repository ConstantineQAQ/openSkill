package dev.openskill.engine;

import dev.openskill.domain.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Existing openSkill contract rules, kept separate from draft persistence. */
@Component
public class WorkflowRules {
  public boolean matches(JsonNode value, String type) {
    return switch (type) {
      case "string" -> value.isString();
      case "number" -> value.isNumber();
      case "boolean" -> value.isBoolean();
      case "array" -> value.isArray();
      case "object" -> value.isObject();
      default -> false;
    };
  }

  public void data(JsonNode input, JsonNode fields, String label) {
    if (!input.isObject()) throw ApiException.bad(label + "必须为对象");
    for (JsonNode field : fields) {
      JsonNode v = input.path(field.path("key").asText());
      if (v.isMissingNode() || v.isNull() || (v.isString() && v.asText().isEmpty())) {
        if (field.path("required").asBoolean())
          throw ApiException.bad(label + "缺少必填字段：" + field.path("key").asText());
        continue;
      }
      if (!matches(v, field.path("type").asText()))
        throw ApiException.bad(label + "字段类型不匹配：" + field.path("key").asText());
    }
  }

  public void fields(JsonNode fields) {
    if (!fields.isArray() || fields.size() > 30) throw ApiException.bad("字段必须是数组，最多 30 项");
    Set<String> keys = new HashSet<>();
    for (JsonNode f : fields) {
      String key = f.path("key").asText();
      if (!key.matches("[a-zA-Z][\\w]{0,39}")
          || Set.of("constructor", "prototype", "__proto__").contains(key)
          || !keys.add(key)) throw ApiException.bad("字段名重复或不合法");
      if (!Set.of("string", "number", "boolean", "array", "object")
          .contains(f.path("type").asText())) throw ApiException.bad("不支持的字段类型");
    }
  }

  public void skill(JsonNode s) {
    skill(s, 0, new HashSet<>());
  }

  private void skill(JsonNode s, int depth, Set<String> ancestors) {
    if (!s.path("name").asText().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
        || s.path("name").asText().length() > 63) throw ApiException.bad("Skill 标识不合法");
    if (s.path("title").asText().isBlank() || s.path("title").asText().length() > 80)
      throw ApiException.bad("请输入 1–80 字名称");
    fields(s.path("inputs"));
    fields(s.path("outputs"));
    if (depth > 3 || ancestors.contains(s.path("id").asText()))
      throw ApiException.bad("组合嵌套过深或检测到递归依赖");
    if ("composite".equals(s.path("kind").asText())) {
      Set<String> next = new HashSet<>(ancestors);
      next.add(s.path("id").asText());
      workflow(s.path("workflow"), depth + 1, next);
    } else if (!Set.of(
            "template",
            "filter",
            "select",
            "notification-preview",
            "instructions",
            "demo-app",
            "demo-alarms",
            "demo-logs")
        .contains(s.path("operation").asText())) throw ApiException.bad("不支持的执行适配器");
  }

  public void workflow(JsonNode w) {
    workflow(w, 0, new HashSet<>());
  }

  private void workflow(JsonNode w, int depth, Set<String> ancestors) {
    fields(w.path("inputs"));
    JsonNode steps = w.path("steps");
    if (!steps.isArray() || steps.isEmpty() || steps.size() > 12)
      throw ApiException.bad("工作流需包含 1–12 个步骤");
    Map<String, JsonNode> previous = new HashMap<>();
    for (JsonNode step : steps) {
      String id = step.path("id").asText();
      if (!id.matches("[a-zA-Z][\\w-]{0,50}") || previous.containsKey(id))
        throw ApiException.bad("步骤标识重复或不合法");
      skill(step.path("skill"), depth, ancestors);
      if (!Set.of("stop", "continue").contains(step.path("onEmpty").asText()))
        throw ApiException.bad("请选择空结果策略");
      for (JsonNode field : step.path("skill").path("inputs")) {
        JsonNode b = step.path("bindings").path(field.path("key").asText());
        if (b.isMissingNode()) {
          if (field.path("required").asBoolean())
            throw ApiException.bad("缺少必填映射：" + field.path("key").asText());
          continue;
        }
        if (b.has("confirmed") && !b.path("confirmed").asBoolean())
          throw ApiException.bad("请确认建议映射：" + field.path("key").asText());
        if ("constant".equals(b.path("mode").asText())) {
          if (!matches(b.path("value"), field.path("type").asText()))
            throw ApiException.bad("固定值类型错误");
          continue;
        }
        String path = b.path("path").asText();
        if (!"path".equals(b.path("mode").asText()) || !path.matches("(input|steps)\\.[\\w.-]+"))
          throw ApiException.bad("来源路径无效");
        String[] parts = path.split("\\.");
        for (String part : parts)
          if (Set.of("__proto__", "constructor", "prototype").contains(part))
            throw ApiException.bad("禁止访问此字段");
        JsonNode sourceFields;
        if ("input".equals(parts[0])) sourceFields = w.path("inputs");
        else {
          if (parts.length < 3 || !previous.containsKey(parts[1]))
            throw ApiException.bad("引用了后续或不存在的步骤");
          sourceFields = previous.get(parts[1]).path("skill").path("outputs");
        }
        String key = parts[0].equals("input") ? parts[1] : parts[2];
        JsonNode source =
            Json.list(sourceFields).stream()
                .filter(f -> f.path("key").asText().equals(key))
                .findFirst()
                .orElseThrow(() -> ApiException.bad("来源字段未声明：" + path));
        String transform = b.path("transform").asText();
        if (!Set.of("identity", "first", "pluck", "join", "json").contains(transform))
          throw ApiException.bad("不支持的转换方式");
        boolean direct = parts.length == (parts[0].equals("input") ? 2 : 3);
        if (direct
            && (Set.of("first", "pluck", "join").contains(transform)
                || b.path("filter").has("field"))
            && !source.path("type").asText().equals("array")) throw ApiException.bad("该转换只适用于列表");
        if (transform.equals("pluck") && b.path("argument").asText().isEmpty())
          throw ApiException.bad("提取字段需要字段名");
        String result =
            switch (transform) {
              case "json", "join" -> "string";
              case "pluck" -> "array";
              case "identity" -> direct ? source.path("type").asText() : "";
              default -> "";
            };
        if (!result.isEmpty() && !result.equals(field.path("type").asText()))
          throw ApiException.bad("字段类型不匹配");
      }
      previous.put(id, step);
    }
  }

  public List<String> safety(JsonNode skill) {
    if ("composite".equals(skill.path("kind").asText())) {
      List<String> issues = new ArrayList<>();
      skill.path("workflow").path("steps").forEach(s -> issues.addAll(safety(s.path("skill"))));
      return issues;
    }
    return switch (skill.path("operation").asText()) {
      case "instructions" -> List.of("智能体 Skill 需要已配置的执行器；试运行会使用模型服务及其获准的工具");
      case "notification-preview" -> List.of("消息仅预览，不支持无人值守执行");
      default -> List.of();
    };
  }
}
