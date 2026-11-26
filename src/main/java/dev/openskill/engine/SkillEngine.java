package dev.openskill.engine;

import dev.openskill.domain.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

@Service
public class SkillEngine {
  private final MappingEngine mapping;
  private final WorkflowRules rules;
  private final Map<String, ExternalAdapter> external = new HashMap<>();
  private final boolean demo;

  public SkillEngine(
      MappingEngine mapping,
      WorkflowRules rules,
      List<ExternalAdapter> adapters,
      @Value("${skill.demo.enabled:false}") boolean demo) {
    this.mapping = mapping;
    this.rules = rules;
    this.demo = demo;
    adapters.forEach(a -> external.put(a.operation(), a));
  }

  public JsonNode execute(JsonNode skill, JsonNode input, Runnable checkpoint) {
    return executeTracked(skill, input, checkpoint, "", null, r -> {});
  }

  private JsonNode executeTracked(
      JsonNode skill,
      JsonNode input,
      Runnable checkpoint,
      String nodePath,
      String parentExecutionId,
      Consumer<ObjectNode> onStep) {
    checkpoint.run();
    rules.data(input, skill.path("inputs"), "Skill 输入");
    JsonNode output;
    if ("composite".equals(skill.path("kind").asText())) {
      ObjectNode run =
          workflowTracked(
              skill.path("workflow"), input, null, checkpoint, onStep, nodePath, parentExecutionId);
      if ("empty".equals(run.path("status").asText()))
        throw new ApiException(400, "EMPTY_RESULT", run.path("message").asText());
      if (!"succeeded".equals(run.path("status").asText()))
        throw new ApiException(400, "EXECUTION_FAILED", run.path("error").asText());
      output = run.path("output");
    } else output = atomic(skill, input, checkpoint);
    checkpoint.run();
    rules.data(output, skill.path("outputs"), "Skill 输出");
    if (output.toString().length() > 1000000) throw ApiException.bad("单步结果超过 1MB 限制");
    return output;
  }

  private JsonNode atomic(JsonNode skill, JsonNode input, Runnable checkpoint) {
    JsonNode config = skill.path("config");
    String op = skill.path("operation").asText();
    return switch (op) {
      case "template" -> {
        Matcher matcher =
            Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}").matcher(config.path("template").asText());
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
          JsonNode v = mapping.path(input, matcher.group(1));
          if (v.isMissingNode()) throw ApiException.bad("模板字段不存在：" + matcher.group(1));
          matcher.appendReplacement(
              text, Matcher.quoteReplacement(v.isString() ? v.asText() : mapping.pretty(v)));
        }
        matcher.appendTail(text);
        yield Json.obj("text", text.toString());
      }
      case "filter" -> {
        ArrayNode values = Json.arr();
        for (JsonNode v : input.path("items"))
          if (mapping
              .jsString(mapping.path(v, config.path("field").asText()))
              .equals(mapping.jsString(config.path("value")))) values.add(v);
        yield Json.obj("items", values);
      }
      case "select" ->
          Json.obj("value", mapping.path(input.path("data"), config.path("path").asText()));
      case "notification-preview" ->
          Json.obj(
              "preview",
              true,
              "sent",
              false,
              "destination",
              input.path("destination"),
              "message",
              input.path("message"));
      case "demo-app", "demo-alarms", "demo-logs" -> {
        if (!demo) throw new ApiException(409, "DEMO_DISABLED", "生产环境禁用演示适配器");
        yield demo(op, input);
      }
      default -> {
        ExternalAdapter a = external.get(op);
        if (a == null) throw new ApiException(503, "ADAPTER_UNAVAILABLE", "执行适配器未配置：" + op);
        yield a.executeSkill(skill, input, checkpoint);
      }
    };
  }

  private JsonNode demo(String op, JsonNode input) {
    if (op.equals("demo-app"))
      return Json.obj(
          "appId",
          input.path("app"),
          "owner",
          "演示团队 · 平台研发",
          "environment",
          input.path("environment"),
          "services",
          List.of("order-api", "inventory-worker"),
          "demo",
          true);
    if (op.equals("demo-alarms"))
      return Json.obj(
          "alarms",
          input.path("appId").asText().equals("empty-demo")
              ? Json.arr()
              : Json.node(
                  List.of(
                      Json.obj(
                          "id",
                          "AL-001",
                          "severity",
                          "critical",
                          "service",
                          "order-api",
                          "message",
                          "接口响应时间超过演示阈值",
                          "count",
                          18),
                      Json.obj(
                          "id",
                          "AL-002",
                          "severity",
                          "warning",
                          "service",
                          "inventory-worker",
                          "message",
                          "消费延迟",
                          "count",
                          6),
                      Json.obj(
                          "id",
                          "AL-003",
                          "severity",
                          "critical",
                          "service",
                          "order-api",
                          "message",
                          "连接池等待超时",
                          "count",
                          9))),
          "date",
          input.path("date"),
          "demo",
          true);
    ArrayNode logs = Json.arr();
    input
        .path("alarms")
        .forEach(
            a ->
                logs.add(
                    Json.obj(
                        "alarmId",
                        a.path("id"),
                        "service",
                        a.path("service"),
                        "level",
                        "ERROR",
                        "message",
                        "[模拟日志] "
                            + a.path("message").asText()
                            + "；trace=demo-"
                            + a.path("id").asText(),
                        "count",
                        a.path("count"))));
    return Json.obj("logs", logs, "count", logs.size(), "demo", true);
  }

  public ObjectNode workflow(
      JsonNode workflow,
      JsonNode input,
      String upTo,
      Runnable checkpoint,
      Consumer<ObjectNode> onStep) {
    return workflowTracked(workflow, input, upTo, checkpoint, onStep, "", null);
  }

  private ObjectNode workflowTracked(
      JsonNode workflow,
      JsonNode input,
      String upTo,
      Runnable checkpoint,
      Consumer<ObjectNode> onStep,
      String prefix,
      String parentExecutionId) {
    rules.workflow(workflow);
    rules.data(input, workflow.path("inputs"), "工作流输入");
    if (upTo != null
        && Json.list(workflow.path("steps")).stream()
            .noneMatch(s -> upTo.equals(s.path("id").asText()))) throw ApiException.bad("试跑节点不存在");
    ObjectNode outputs = Json.obj(), context = Json.obj("input", input, "steps", outputs);
    ArrayNode records = Json.arr();
    JsonNode output = Json.obj();
    for (JsonNode step : workflow.path("steps")) {
      checkpoint.run();
      long start = System.nanoTime();
      String nodePath =
          prefix.isEmpty() ? step.path("id").asText() : prefix + "/" + step.path("id").asText();
      String executionId = Json.id();
      ObjectNode record =
          Json.obj(
              "executionId",
              executionId,
              "nodePath",
              nodePath,
              "parentExecutionId",
              parentExecutionId,
              "stepId",
              step.path("id"),
              "title",
              step.path("skill").path("title"),
              "version",
              step.path("skill").path("version"),
              "startedAt",
              Json.now());
      try {
        ObjectNode args = Json.obj();
        for (JsonNode f : step.path("skill").path("inputs")) {
          String key = f.path("key").asText();
          if (step.path("bindings").has(key))
            args.set(key, mapping.apply(step.path("bindings").path(key), context));
        }
        record.set("input", args);
        output =
            executeTracked(step.path("skill"), args, checkpoint, nodePath, executionId, onStep);
        outputs.set(step.path("id").asText(), output);
        record.set("output", output);
        List<JsonNode> lists =
            output.properties().stream()
                .map(Map.Entry::getValue)
                .filter(JsonNode::isArray)
                .toList();
        boolean empty = !lists.isEmpty() && lists.stream().allMatch(JsonNode::isEmpty);
        record.put("status", empty ? "empty" : "succeeded");
        record.put("durationMs", (System.nanoTime() - start) / 1000000);
        records.add(record);
        onStep.accept(record);
        if (empty && "stop".equals(step.path("onEmpty").asText()))
          return Json.obj(
              "status", "empty", "steps", records, "output", output, "message", "节点返回空结果，已按策略停止");
        if (step.path("id").asText().equals(upTo)) break;
      } catch (ApiException e) {
        if (Set.of("CANCELLED", "RUN_TIMEOUT", "LEASE_LOST").contains(e.code())) throw e;
        boolean empty = e.code().equals("EMPTY_RESULT");
        record.put("status", empty ? "empty" : "failed");
        record.put("durationMs", (System.nanoTime() - start) / 1000000);
        record.put(empty ? "message" : "error", e.getMessage());
        records.add(record);
        onStep.accept(record);
        return Json.obj(
            "status",
            empty ? "empty" : "failed",
            "steps",
            records,
            "output",
            Json.obj(),
            empty ? "message" : "error",
            e.getMessage());
      } catch (Exception e) {
        record.put("status", "failed");
        record.put("durationMs", (System.nanoTime() - start) / 1000000);
        record.put("error", "执行适配器异常");
        records.add(record);
        onStep.accept(record);
        return Json.obj(
            "status", "failed", "steps", records, "output", Json.obj(), "error", "执行适配器异常");
      }
    }
    return Json.obj("status", "succeeded", "steps", records, "output", output);
  }
}
