package dev.openskill.application;

import static dev.openskill.infrastructure.Documents.Kind.*;

import dev.openskill.domain.*;
import dev.openskill.engine.*;
import dev.openskill.infrastructure.*;
import java.util.*;
import java.util.regex.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

@Service
public class CatalogService {
  private static final Logger LOG = LogManager.getLogger(CatalogService.class);
  private final Documents docs;
  private final Transactions tx;
  private final WorkflowRules rules;
  private final Audit audit;

  public CatalogService(Documents docs, Transactions tx, WorkflowRules rules, Audit audit) {
    this.docs = docs;
    this.tx = tx;
    this.rules = rules;
    this.audit = audit;
  }

  public ObjectNode skill(String id, int version) {
    return InstructionSkills.executable(docs.get(VERSION, id + ":" + version));
  }

  public ObjectNode create(JsonNode body) {
    if (!Set.of("template", "filter", "select", "notification-preview")
            .contains(body.path("operation").asText())
        || body.path("kind").asText().equals("composite"))
      throw ApiException.bad("此接口仅制作受限原子 Skill");
    return tx.run(
        () -> {
          String id = body.path("id").asText();
          int version = 1;
          if (id.isEmpty()) id = Json.id();
          else {
            ObjectNode asset = docs.lock(ASSET, id);
            ObjectNode previous = skill(id, asset.path("latestVersion").asInt());
            if (previous.path("demo").asBoolean()
                || previous.path("kind").asText().equals("composite"))
              throw ApiException.bad("此 Skill 不允许直接修改");
            version = asset.path("latestVersion").asInt() + 1;
          }
          ObjectNode s =
              Json.obj(
                  "id",
                  id,
                  "name",
                  body.path("name"),
                  "title",
                  body.path("title"),
                  "description",
                  body.path("description"),
                  "kind",
                  "atomic",
                  "version",
                  version,
                  "operation",
                  body.path("operation"),
                  "config",
                  body.path("config"),
                  "inputs",
                  body.path("inputs"),
                  "outputs",
                  body.path("outputs"),
                  "category",
                  body.path("category").asText("自制技能"),
                  "createdAt",
                  Json.now(),
                  "demo",
                  false);
          rules.skill(s);
          persist(s);
          audit.record("skill.create-version", id + ":" + version);
          return s;
        });
  }

  public void persist(ObjectNode skill) {
    String id = skill.path("id").asText();
    ObjectNode asset = skill.deepCopy();
    asset.remove("workflow");
    asset.remove("config");
    asset.put("latestVersion", skill.path("version").asInt());
    var previous = docs.find(ASSET, id);
    if (previous.isPresent()) {
      asset.put(
          "archived",
          !previous.get().path("deleted").asBoolean()
              && previous.get().path("archived").asBoolean());
      docs.update(
          ASSET,
          id,
          asset,
          asset.path("archived").asBoolean() ? "archived" : "active",
          previous.get().path("revision").asLong());
    } else {
      asset.put("archived", false);
      docs.insert(ASSET, id, asset, "active");
    }
    docs.insert(VERSION, id + ":" + skill.path("version").asInt(), skill, "published");
  }

  public ObjectNode importMarkdown(String markdown) {
    if (markdown == null || markdown.length() > 100000)
      throw ApiException.bad("请导入 100000 字符以内的 SKILL.md");
    Matcher m = Pattern.compile("^---\\r?\\n([\\s\\S]+?)\\r?\\n---(?:\\r?\\n|$)").matcher(markdown);
    if (!m.find()) throw ApiException.bad("SKILL.md 需要 YAML frontmatter");
    Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(m.group(1));
    if (!(value instanceof Map<?, ?> front)
        || !(front.get("name") instanceof String)
        || !(front.get("description") instanceof String))
      throw ApiException.bad("name / description 必须为文本");
    ObjectNode skill =
        Json.obj(
            "id",
            Json.id(),
            "name",
            front.get("name"),
            "title",
            front.get("name"),
            "description",
            front.get("description"),
            "kind",
            "atomic",
            "operation",
            "instructions",
            "version",
            1,
            "inputs",
            Json.arr(),
            "outputs",
            Json.arr(),
            "config",
            Json.obj("markdown", markdown),
            "category",
            "导入文档",
            "createdAt",
            Json.now());
    skill.setAll(InstructionSkills.executable(skill));
    rules.skill(skill);
    return tx.run(
        () -> {
          docs.findByName(ASSET, skill.path("name").asText())
              .ifPresent(
                  existing -> {
                    ObjectNode locked = docs.lock(ASSET, existing.path("id").asText());
                    skill.put("id", locked.path("id").asText());
                    skill.put("version", locked.path("latestVersion").asInt() + 1);
                  });
          persist(skill);
          audit.record("skill.import", skill.path("id").asText());
          LOG.info(
              "Skill 说明已写入当前事务，技能ID={}，版本={}",
              skill.path("id").asText(),
              skill.path("version").asInt());
          return skill;
        });
  }

  public ObjectNode canonical(JsonNode workflow) {
    if (!workflow.isObject()
        || !workflow.path("steps").isArray()
        || workflow.path("steps").size() > 12) throw ApiException.bad("工作流格式无效");
    ObjectNode w = Json.copy(workflow);
    ArrayNode steps = Json.arr();
    for (JsonNode step : workflow.path("steps")) {
      ObjectNode s = Json.copy(step);
      s.set(
          "skill",
          skill(
              step.path("skill").path("id").asText(), step.path("skill").path("version").asInt()));
      steps.add(s);
    }
    w.set("steps", steps);
    return w;
  }

  public ObjectNode saveWorkflow(JsonNode body) {
    return tx.run(
        () -> {
          ObjectNode w = canonical(body);
          String id = w.path("id").asText();
          if (id.isEmpty()) {
            id = Json.id();
            w.put("id", id);
          }
          w.put("updatedAt", Json.now());
          var old = docs.find(WORKFLOW, id);
          ObjectNode saved;
          if (old.isPresent()) {
            long expected =
                body.has("revision")
                    ? body.path("revision").asLong()
                    : old.get().path("revision").asLong();
            saved = docs.update(WORKFLOW, id, w, "draft", expected);
          } else saved = docs.insert(WORKFLOW, id, w, "draft");
          audit.record("workflow.save", id);
          return saved;
        });
  }

  public ObjectNode validate(JsonNode body) {
    ObjectNode w = canonical(body);
    rules.workflow(w);
    List<String> warnings = new ArrayList<>();
    ArrayNode dependencies = Json.arr();
    w.path("steps")
        .forEach(
            s -> {
              warnings.addAll(rules.safety(s.path("skill")));
              dependencies.add(
                  Json.obj(
                      "name",
                      s.path("skill").path("name"),
                      "version",
                      s.path("skill").path("version")));
            });
    return Json.obj("valid", true, "warnings", warnings, "dependencies", dependencies);
  }

  public ObjectNode publish(JsonNode body) {
    return tx.run(
        () -> {
          ObjectNode draft = docs.lock(WORKFLOW, body.path("id").asText());
          if (body.has("revision")
              && draft.path("revision").asLong() != body.path("revision").asLong())
            throw ApiException.conflict("草稿已发生变化");
          ObjectNode w = canonical(body);
          rules.workflow(w);
          var previous =
              docs.all(ASSET).stream()
                  .filter(s -> w.path("id").asText().equals(s.path("workflowId").asText()))
                  .findFirst();
          String id = previous.map(s -> s.path("id").asText()).orElseGet(Json::id);
          int version =
              previous.map(s -> docs.lock(ASSET, id).path("latestVersion").asInt() + 1).orElse(1);
          ObjectNode skill =
              Json.obj(
                  "id",
                  id,
                  "name",
                  w.path("name"),
                  "title",
                  w.path("title"),
                  "description",
                  w.path("description"),
                  "kind",
                  "composite",
                  "version",
                  version,
                  "config",
                  Json.obj(),
                  "inputs",
                  w.path("inputs"),
                  "outputs",
                  w.path("steps").get(w.path("steps").size() - 1).path("skill").path("outputs"),
                  "workflow",
                  w,
                  "workflowId",
                  w.path("id"),
                  "category",
                  "组合技能",
                  "createdAt",
                  Json.now());
          rules.skill(skill);
          persist(skill);
          audit.record("skill.publish", id + ":" + version);
          return skill;
        });
  }

  public ObjectNode deleteWorkflow(String id, long revision) {
    return tx.run(
        () -> {
          ObjectNode draft = docs.lock(WORKFLOW, id);
          if (draft.path("revision").asLong() != revision) throw ApiException.conflict("草稿已发生变化");
          docs.delete(WORKFLOW, id);
          audit.record("workflow.delete", id);
          return Json.obj("ok", true);
        });
  }

  public List<ObjectNode> visibleSkills() {
    Set<String> visible = new HashSet<>();
    docs.all(ASSET).stream()
        .filter(a -> !a.path("deleted").asBoolean() && !a.path("archived").asBoolean())
        .forEach(a -> visible.add(a.path("id").asText()));
    return docs.all(VERSION).stream()
        .filter(s -> visible.contains(s.path("id").asText()))
        .map(InstructionSkills::executable)
        .toList();
  }

  /** 逻辑删除：固定版本仍可被已有编排、任务和运行记录引用。 */
  public ObjectNode deleteSkill(String id) {
    return tx.run(
        () -> {
          ObjectNode asset = docs.lock(ASSET, id);
          asset.put("deleted", true);
          asset.put("archived", true);
          docs.update(ASSET, id, asset, "deleted", asset.path("revision").asLong());
          audit.record("skill.delete", id);
          LOG.info("技能已从技能库删除，技能ID={}，历史版本和已有引用保留", id);
          return Json.obj("id", id, "deleted", true);
        });
  }

  public ObjectNode archive(String id, boolean archived, long revision) {
    return tx.run(
        () -> {
          ObjectNode asset = docs.get(ASSET, id);
          asset.put("archived", archived);
          ObjectNode saved =
              docs.update(
                  ASSET,
                  id,
                  asset,
                  asset.path("deleted").asBoolean() ? "deleted" : archived ? "archived" : "active",
                  revision);
          audit.record("skill.archive", id);
          return saved;
        });
  }

  public ArrayNode dependencies(String id, int version, String direction) {
    JsonNode target = skill(id, version);
    ArrayNode result = Json.arr();
    if ("reverse".equals(direction)) {
      for (ObjectNode s : docs.all(VERSION)) {
        ArrayNode refs = Json.arr();
        walk(s, refs);
        if (Json.list(refs).stream()
            .anyMatch(
                r -> r.path("id").asText().equals(id) && r.path("version").asInt() == version))
          result.add(Json.obj("type", "skill", "id", s.path("id"), "version", s.path("version")));
      }
      for (ObjectNode s : docs.all(SCHEDULE))
        if (s.path("skillId").asText().equals(id) && s.path("version").asInt() == version)
          result.add(Json.obj("type", "schedule", "id", s.path("id")));
    } else walk(target, result);
    return result;
  }

  public void walk(JsonNode s, ArrayNode out) {
    if ("composite".equals(s.path("kind").asText()))
      for (JsonNode step : s.path("workflow").path("steps")) {
        JsonNode ref = step.path("skill");
        out.add(
            Json.obj(
                "id", ref.path("id"), "name", ref.path("name"), "version", ref.path("version")));
        walk(ref, out);
      }
  }
}
