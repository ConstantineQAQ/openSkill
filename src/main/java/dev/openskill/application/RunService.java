package dev.openskill.application;

import dev.openskill.domain.*;
import dev.openskill.engine.*;
import dev.openskill.infrastructure.*;
import dev.openskill.security.Actor;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

@Service
public class RunService {
  private final JdbcTemplate jdbc;
  private final Transactions tx;
  private final CatalogService catalog;
  private final Documents docs;
  private final WorkflowRules rules;
  private final SkillEngine engine;
  private final Audit audit;
  private final Map<String, String> leases = new ConcurrentHashMap<>();

  public RunService(
      JdbcTemplate jdbc,
      Transactions tx,
      CatalogService catalog,
      WorkflowRules rules,
      SkillEngine engine,
      Audit audit,
      Documents docs) {
    this.docs = docs;
    this.jdbc = jdbc;
    this.tx = tx;
    this.catalog = catalog;
    this.rules = rules;
    this.engine = engine;
    this.audit = audit;
  }

  public ObjectNode submit(
      JsonNode target,
      JsonNode input,
      int timeout,
      String source,
      boolean enqueue,
      String scheduleId,
      String parentId) {
    JsonNode workflow;
    String skillId = null;
    int version = 0;
    if ("skill".equals(target.path("type").asText())) {
      skillId = target.path("skillId").asText();
      version = target.path("version").asInt();
      JsonNode skill = catalog.skill(skillId, version);
      if (skill.path("kind").asText().equals("composite")) workflow = skill.path("workflow");
      else {
        ObjectNode bindings = Json.obj();
        for (JsonNode field : skill.path("inputs")) {
          String key = field.path("key").asText();
          if (input.has(key))
            bindings.set(
                key,
                Json.obj(
                    "mode",
                    "path",
                    "path",
                    "input." + key,
                    "transform",
                    "identity",
                    "confirmed",
                    true));
        }
        workflow =
            Json.obj(
                "id",
                skillId,
                "name",
                skill.path("name"),
                "title",
                skill.path("title"),
                "description",
                skill.path("description"),
                "inputs",
                skill.path("inputs"),
                "sampleInput",
                input,
                "steps",
                Json.node(
                    List.of(
                        Json.obj(
                            "id",
                            "node",
                            "skill",
                            skill,
                            "bindings",
                            bindings,
                            "onEmpty",
                            "stop"))));
      }
    } else if ("inline".equals(target.path("type").asText()))
      workflow = catalog.canonical(target.path("workflow"));
    else if ("workflow".equals(target.path("type").asText())) {
      // Only the exact persisted revision is executable through the async API.
      var draft = docs.get(Documents.Kind.WORKFLOW, target.path("workflowId").asText());
      if (draft.path("revision").asLong() != target.path("revision").asLong())
        throw ApiException.conflict("工作流修订已变化");
      workflow = catalog.canonical(draft);
    } else throw ApiException.bad("不支持的执行目标");
    rules.workflow(workflow);
    rules.data(input, workflow.path("inputs"), "工作流输入");
    if (timeout < 1 || timeout > 3600) throw ApiException.bad("执行超时范围为 1–3600 秒");
    ObjectNode snapshot = Json.copy(workflow);
    if (target.has("upTo")) snapshot.set("_upTo", target.path("upTo"));
    return insert(
        snapshot, input, timeout, source, enqueue, scheduleId, parentId, skillId, version);
  }

  private ObjectNode insert(
      JsonNode workflow,
      JsonNode input,
      int timeout,
      String source,
      boolean enqueue,
      String scheduleId,
      String parentId,
      String skillId,
      int version) {
    return tx.run(
        () -> {
          Actor a = Actor.current();
          String id = Json.id(), now = Json.now();
          ObjectNode run =
              Json.obj(
                  "id",
                  id,
                  "title",
                  workflow.path("title"),
                  "workflowId",
                  workflow.path("id"),
                  "input",
                  Json.redact(input),
                  "status",
                  "queued",
                  "steps",
                  Json.arr(),
                  "startedAt",
                  now,
                  "source",
                  source);
          if (scheduleId != null) run.put("scheduleId", scheduleId);
          if (parentId != null) run.put("parentRunId", parentId);
          if (skillId != null) {
            run.put("skillId", skillId);
            run.put("version", version);
          }
          jdbc.update(
              "INSERT INTO"
                  + " runs(id,workspace,owner_id,status,skill_id,workflow_id,schedule_id,payload,snapshot,input_json,created_at,updated_at,timeout_seconds)"
                  + " VALUES(?,?,?,'queued',?,?,?,?,?,?,?,?,?)",
              id,
              a.workspace(),
              a.user(),
              skillId,
              workflow.path("id").asText(),
              scheduleId,
              run.toString(),
              workflow.toString(),
              input.toString(),
              now,
              now,
              timeout);
          if (enqueue)
            jdbc.update(
                "INSERT INTO execution_outbox(run_id,status,available_at) VALUES(?,'pending',?)",
                id,
                System.currentTimeMillis());
          event(id, a.workspace(), "queued", Json.obj("status", "queued"));
          audit.record("run.create", id);
          return run;
        });
  }

  public ObjectNode sync(JsonNode workflow, JsonNode input, String upTo) {
    ObjectNode target = Json.obj("type", "inline", "workflow", workflow);
    if (upTo != null) target.put("upTo", upTo);
    ObjectNode run = submit(target, input, 300, "manual", false, null, null);
    process(run.path("id").asText());
    return get(run.path("id").asText());
  }

  public ObjectNode node(String id, int version, JsonNode input) {
    long start = System.nanoTime();
    audit.record("skill.test", id + ":" + version);
    try {
      return Json.obj(
          "status",
          "succeeded",
          "output",
          Json.redact(
              engine.execute(
                  catalog.skill(id, version),
                  input,
                  () -> {
                    if (System.nanoTime() - start > java.time.Duration.ofSeconds(300).toNanos())
                      throw new ApiException(408, "RUN_TIMEOUT", "单节点试运行超时");
                  })),
          "durationMs",
          (System.nanoTime() - start) / 1000000);
    } catch (ApiException e) {
      if (!e.code().equals("EMPTY_RESULT")) throw e;
      return Json.obj(
          "status",
          "empty",
          "output",
          Json.obj(),
          "message",
          e.getMessage(),
          "durationMs",
          (System.nanoTime() - start) / 1000000);
    }
  }

  public ObjectNode get(String id) {
    Actor a = Actor.current();
    var items =
        jdbc.query(
            "SELECT payload,status FROM runs WHERE id=? AND workspace=? AND owner_id=?",
            (r, n) -> {
              ObjectNode result = Json.copy(Json.parse(r.getString(1)));
              result.put("status", r.getString(2));
              return result;
            },
            id,
            a.workspace(),
            a.user());
    if (items.isEmpty()) throw ApiException.missing();
    return items.getFirst();
  }

  public ObjectNode page(Map<String, String> filters, int page, int size) {
    Actor a = Actor.current();
    String where = " WHERE workspace=? AND owner_id=?";
    List<Object> args = new ArrayList<>(List.of(a.workspace(), a.user()));
    Map<String, String> columns =
        Map.of(
            "status",
            "status",
            "skillId",
            "skill_id",
            "workflowId",
            "workflow_id",
            "scheduleId",
            "schedule_id");
    for (var e : columns.entrySet())
      if (filters.containsKey(e.getKey())) {
        where += " AND " + e.getValue() + "=?";
        args.add(filters.get(e.getKey()));
      }
    if (filters.containsKey("from")) {
      where += " AND created_at>=?";
      args.add(java.time.Instant.parse(filters.get("from")).toString());
    }
    if (filters.containsKey("to")) {
      where += " AND created_at<=?";
      args.add(java.time.Instant.parse(filters.get("to")).toString());
    }
    long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM runs" + where, Long.class, args.toArray());
    args.add(size);
    args.add((long) (page - 1) * size);
    var items =
        jdbc.query(
            "SELECT payload,status FROM runs"
                + where
                + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            (r, n) -> {
              ObjectNode v = Json.copy(Json.parse(r.getString(1)));
              v.put("status", r.getString(2));
              return v;
            },
            args.toArray());
    return Json.obj("items", items, "total", total, "page", page, "pageSize", size);
  }

  public ObjectNode cancel(String id) {
    return tx.run(
        () -> {
          Actor actor = Actor.current();
          var locked =
              jdbc.query(
                  "SELECT payload,status FROM runs WHERE id=? AND workspace=? AND owner_id=? FOR"
                      + " UPDATE",
                  (r, n) -> {
                    ObjectNode v = Json.copy(Json.parse(r.getString(1)));
                    v.put("status", r.getString(2));
                    return v;
                  },
                  id,
                  actor.workspace(),
                  actor.user());
          if (locked.isEmpty()) throw ApiException.missing();
          ObjectNode run = locked.getFirst();
          if (Set.of("queued", "running", "cancelling").contains(run.path("status").asText())) {
            String status =
                run.path("status").asText().equals("queued") ? "cancelled" : "cancelling";
            run.put("status", status);
            if (status.equals("cancelled")) run.put("finishedAt", Json.now());
            jdbc.update(
                "UPDATE runs SET status=?,cancel_requested=TRUE,payload=?,updated_at=? WHERE id=?"
                    + " AND status IN ('queued','running','cancelling')",
                status,
                run.toString(),
                Json.now(),
                id);
            event(id, Actor.current().workspace(), status, Json.obj("status", status));
            audit.record("run.cancel", id);
          }
          return get(id);
        });
  }

  public ObjectNode retry(String id) {
    ObjectNode old = get(id);
    if (!Set.of("failed", "interrupted", "cancelled", "timed_out")
        .contains(old.path("status").asText())) throw ApiException.conflict("此状态不支持重试");
    var row =
        jdbc.queryForMap("SELECT snapshot,input_json,timeout_seconds FROM runs WHERE id=?", id);
    return insert(
        Json.parse((String) row.get("snapshot")),
        Json.parse((String) row.get("input_json")),
        ((Number) row.get("timeout_seconds")).intValue(),
        "retry",
        true,
        null,
        id,
        old.path("skillId").isMissingNode() ? null : old.path("skillId").asText(),
        old.path("version").asInt());
  }

  public ObjectNode events(String id, long cursor, int limit) {
    get(id);
    var items =
        jdbc.query(
            "SELECT seq,kind,payload,created_at FROM run_events WHERE run_id=? AND seq>? ORDER BY"
                + " seq LIMIT ?",
            (r, n) ->
                Json.obj(
                    "id",
                    r.getLong(1),
                    "type",
                    r.getString(2),
                    "data",
                    Json.parse(r.getString(3)),
                    "createdAt",
                    r.getString(4)),
            id,
            cursor,
            limit);
    return Json.obj(
        "items",
        items,
        "nextCursor",
        items.isEmpty() ? cursor : items.getLast().path("id").asLong());
  }

  private void event(String id, String workspace, String type, JsonNode data) {
    jdbc.update(
        "INSERT INTO run_events(run_id,workspace,kind,payload,created_at) VALUES(?,?,?,?,?)",
        id,
        workspace,
        type,
        Json.redact(data).toString(),
        Json.now());
  }

  public void process(String id) {
    String lease = Json.id();
    long now = System.currentTimeMillis();
    int claimed =
        jdbc.update(
            "UPDATE runs SET status='running',lease_owner=?,lease_until=?,deadline=CAST(? AS"
                + " DECIMAL(19,0))+timeout_seconds*1000 WHERE id=? AND status='queued' AND"
                + " cancel_requested=FALSE",
            lease,
            now + 60000,
            now,
            id);
    if (claimed != 1) return;
    leases.put(id, lease);
    Actor previous = Actor.optional();
    try {
      var row = jdbc.queryForMap("SELECT * FROM runs WHERE id=?", id);
      Actor actor = new Actor((String) row.get("workspace"), (String) row.get("owner_id"));
      Actor.set(actor);
      ObjectNode run = Json.copy(Json.parse((String) row.get("payload")));
      run.put("status", "running");
      run.put("startedAt", Json.now());
      ArrayNode records = Json.arr();
      try {
        tx.run(
            () -> {
              saveProgress(id, lease, run);
              event(id, actor.workspace(), "running", Json.obj("status", "running"));
            });
        JsonNode workflow = Json.parse((String) row.get("snapshot"));
        String upTo = workflow.has("_upTo") ? workflow.path("_upTo").asText() : null;
        ObjectNode result =
            engine.workflow(
                workflow,
                Json.parse((String) row.get("input_json")),
                upTo,
                () -> checkpoint(id, lease),
                record -> {
                  records.add(Json.redact(record));
                  run.set("steps", records);
                  tx.run(
                      () -> {
                        saveProgress(id, lease, run);
                        event(id, actor.workspace(), "step", record);
                      });
                });
        checkpoint(id, lease);
        run.setAll(Json.copy(Json.redact(result)));
        run.set("steps", records);
      } catch (ApiException e) {
        run.put(
            "status",
            switch (e.code()) {
              case "CANCELLED" -> "cancelled";
              case "RUN_TIMEOUT" -> "timed_out";
              case "LEASE_LOST" -> "interrupted";
              default -> "failed";
            });
        run.put("error", e.getMessage());
      } catch (Exception e) {
        run.put("status", "failed");
        run.put("error", "执行适配器异常；请通过运行 ID 查询服务日志");
        org.slf4j.LoggerFactory.getLogger(getClass()).error("运行执行异常，运行ID={}", id, e);
      } finally {
        run.put("finishedAt", Json.now());
        String status = run.path("status").asText();
        tx.run(
            () -> {
              int changed =
                  jdbc.update(
                      "UPDATE runs SET status=?,payload=?,updated_at=?,lease_until=0 WHERE id=? AND"
                          + " lease_owner=? AND status IN ('running','cancelling')",
                      status,
                      run.toString(),
                      Json.now(),
                      id,
                      lease);
              if (changed == 1) event(id, actor.workspace(), status, Json.obj("status", status));
            });
      }
    } finally {
      leases.remove(id);
      if (previous == null) Actor.clear();
      else Actor.set(previous);
    }
  }

  private void saveProgress(String id, String lease, ObjectNode payload) {
    int n =
        jdbc.update(
            "UPDATE runs SET payload=?,updated_at=? WHERE id=? AND lease_owner=? AND status IN"
                + " ('running','cancelling')",
            payload.toString(),
            Json.now(),
            id,
            lease);
    if (n != 1) throw new ApiException(409, "LEASE_LOST", "执行租约已失效");
  }

  private void checkpoint(String id, String lease) {
    var r =
        jdbc.queryForMap(
            "SELECT status,lease_owner,deadline,cancel_requested FROM runs WHERE id=?", id);
    if (!lease.equals(r.get("lease_owner"))
        || !Set.of("running", "cancelling").contains(r.get("status")))
      throw new ApiException(409, "LEASE_LOST", "执行租约已失效");
    if (Boolean.TRUE.equals(r.get("cancel_requested")))
      throw new ApiException(409, "CANCELLED", "执行已按请求取消");
    if (System.currentTimeMillis() > ((Number) r.get("deadline")).longValue())
      throw new ApiException(408, "RUN_TIMEOUT", "运行超时，后续步骤未执行");
  }

  public void heartbeat() {
    long now = System.currentTimeMillis();
    leases.forEach(
        (id, lease) ->
            jdbc.update(
                "UPDATE runs SET lease_until=? WHERE id=? AND lease_owner=? AND status IN"
                    + " ('running','cancelling')",
                now + 60000,
                id,
                lease));
  }

  public void recover() {
    long now = System.currentTimeMillis();
    var expired =
        jdbc.queryForList(
            "SELECT id,payload,workspace FROM runs WHERE status IN ('running','cancelling') AND"
                + " lease_until<?",
            now);
    for (var r : expired) {
      ObjectNode run = Json.copy(Json.parse((String) r.get("payload")));
      run.put("status", "interrupted");
      run.put("error", "执行节点失联，未自动重试外部操作");
      run.put("finishedAt", Json.now());
      tx.run(
          () -> {
            int changed =
                jdbc.update(
                    "UPDATE runs SET status='interrupted',payload=?,updated_at=? WHERE id=? AND"
                        + " status IN ('running','cancelling') AND lease_until<?",
                    run.toString(),
                    Json.now(),
                    r.get("id"),
                    now);
            if (changed == 1)
              event(
                  (String) r.get("id"),
                  (String) r.get("workspace"),
                  "interrupted",
                  Json.obj("status", "interrupted"));
          });
    }
  }

  public boolean activeSchedule(String id) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM runs WHERE workspace=? AND owner_id=? AND schedule_id=? AND"
                + " status IN ('queued','running','cancelling')",
            Integer.class,
            Actor.current().workspace(),
            Actor.current().user(),
            id)
        > 0;
  }
}
