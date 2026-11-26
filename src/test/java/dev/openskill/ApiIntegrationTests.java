package dev.openskill;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import dev.openskill.application.*;
import dev.openskill.domain.*;
import dev.openskill.infrastructure.*;
import dev.openskill.security.Actor;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTests {
  @Autowired MockMvc mvc;
  @Autowired RunService runs;
  @Autowired ScheduleService schedules;
  @Autowired CatalogService catalog;
  @Autowired Documents docs;
  @Autowired JdbcTemplate jdbc;
  @Autowired RequestMappingHandlerMapping requestMappingHandlerMapping;

  private JsonNode call(MockHttpServletRequestBuilder request, int status) throws Exception {
    var response =
        mvc.perform(
                request.with(
                    r -> {
                      r.setRemoteAddr("127.0.0.1");
                      r.setServerName("localhost");
                      return r;
                    }))
            .andReturn()
            .getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return Json.parse(response.getContentAsString());
  }

  private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, JsonNode body) {
    return request.contentType("application/json").content(body.toString());
  }

  private ObjectNode seed() throws Exception {
    return Json.copy(call(get("/api/workflows/daily-inspection"), 200));
  }

  private ObjectNode workflow() throws Exception {
    ObjectNode w = seed();
    w.put("id", Json.id());
    w.put("name", "flow-" + Json.id());
    w.remove("revision");
    return Json.copy(call(body(post("/api/workflows"), w), 200));
  }

  private void actor() {
    Actor.set(new Actor("local", "local-developer"));
  }

  @AfterEach
  void clear() {
    Actor.clear();
  }

  @Test
  void all51HandlersRegistered() {
    long n =
        requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
            .filter(e -> e.getValue().getBeanType().getPackageName().endsWith(".api"))
            .count();
    assertEquals(51, n);
  }

  @Test
  void compatibilityRunCanonicalizesPayload() throws Exception {
    ObjectNode w = seed();
    ((ObjectNode) w.path("steps").get(0).path("skill")).put("operation", "instructions");
    JsonNode result =
        call(body(post("/api/run"), Json.obj("workflow", w, "input", w.path("sampleInput"))), 200);
    assertEquals("succeeded", result.path("status").asText());
    assertEquals(4, result.path("steps").size());
    assertTrue(result.path("output").path("text").asText().contains("order-service"));
    assertEquals(3, result.path("steps").get(1).path("output").path("alarms").size());
    assertEquals(2, result.path("steps").get(2).path("input").path("alarms").size());
  }

  @Test
  void emptyAndUpToContracts() throws Exception {
    var w = seed();
    var input = Json.copy(w.path("sampleInput"));
    input.put("app", "empty-demo");
    var result = call(body(post("/api/run"), Json.obj("workflow", w, "input", input)), 200);
    assertEquals("empty", result.path("status").asText());
    assertEquals(2, result.path("steps").size());
    result =
        call(
            body(
                post("/api/run"),
                Json.obj("workflow", w, "input", w.path("sampleInput"), "upTo", "s1")),
            200);
    assertEquals(1, result.path("steps").size());
  }

  @Test
  void nodeRunDoesNotCreateHistory() throws Exception {
    long before = jdbc.queryForObject("SELECT COUNT(*) FROM runs", Long.class);
    var result =
        call(
            body(
                post("/api/node-run"),
                Json.obj(
                    "skillId",
                    "app-info",
                    "version",
                    1,
                    "input",
                    Json.obj("app", "demo", "environment", "test"))),
            200);
    assertEquals("succeeded", result.path("status").asText());
    assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM runs", Long.class));
  }

  @Test
  void draftCanBeIncompleteButPublishCannot() throws Exception {
    var w = workflow();
    ((ObjectNode) w.path("steps").get(0)).set("bindings", Json.obj());
    var saved = call(body(post("/api/workflows"), w), 200);
    call(body(post("/api/publish"), Json.obj("workflow", saved)), 400);
  }

  @Test
  void unconfirmedMappingRejected() throws Exception {
    var w = seed();
    ((ObjectNode) w.path("steps").get(0).path("bindings").path("app")).put("confirmed", false);
    call(body(post("/api/validate"), Json.obj("workflow", w)), 400);
  }

  @Test
  void versionSnapshotsAndOptimisticRevision() throws Exception {
    var w = workflow();
    var v1 = call(body(post("/api/publish"), Json.obj("workflow", w)), 201);
    var v2 = call(body(post("/api/publish"), Json.obj("workflow", w)), 201);
    assertEquals(1, v1.path("version").asInt());
    assertEquals(2, v2.path("version").asInt());
    assertEquals(v1.path("id"), v2.path("id"));
    var loaded = call(get("/api/skills/" + v1.path("id").asText() + "/versions/1"), 200);
    assertEquals(1, loaded.path("version").asInt());
    call(body(post("/api/workflows"), w), 200);
    call(body(post("/api/workflows"), w), 409);
    call(get("/api/skills/" + v1.path("id").asText() + "/versions"), 200);
    call(get("/api/skills/" + v1.path("id").asText() + "/versions/1/dependencies"), 200);
  }

  @Test
  void asyncIdempotencyAndDuplicateDelivery() throws Exception {
    var w = workflow();
    var request =
        Json.obj(
            "target",
            Json.obj(
                "type", "workflow", "workflowId", w.path("id"), "revision", w.path("revision")),
            "input",
            w.path("sampleInput"));
    String key = Json.id();
    var first = call(body(post("/api/runs").header("Idempotency-Key", key), request), 202);
    var second = call(body(post("/api/runs").header("Idempotency-Key", key), request), 202);
    assertEquals(first.path("id"), second.path("id"));
    String id = first.path("id").asText();
    actor();
    runs.process(id);
    runs.process(id);
    assertEquals("succeeded", runs.get(id).path("status").asText());
    Actor.clear();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM run_events WHERE run_id=? AND kind='running'",
            Integer.class,
            id));
    call(get("/api/runs/" + id + "/steps"), 200);
    call(get("/api/runs/" + id + "/logs"), 200);
    request.set("input", Json.obj("different", true));
    call(body(post("/api/runs").header("Idempotency-Key", key), request), 409);
  }

  @Test
  void cancelQueuedAndRetryKeepsParent() throws Exception {
    var w = workflow();
    var run =
        call(
            body(
                post("/api/runs"),
                Json.obj(
                    "target",
                    Json.obj(
                        "type",
                        "workflow",
                        "workflowId",
                        w.path("id"),
                        "revision",
                        w.path("revision")),
                    "input",
                    w.path("sampleInput"))),
            202);
    String id = run.path("id").asText();
    assertEquals(
        "cancelled",
        call(body(post("/api/runs/" + id + "/cancel"), Json.obj()), 200).path("status").asText());
    actor();
    runs.process(id);
    assertEquals("cancelled", runs.get(id).path("status").asText());
    Actor.clear();
    var retry = call(body(post("/api/runs/" + id + "/retry"), Json.obj()), 202);
    assertEquals(id, retry.path("parentRunId").asText());
    assertNotEquals(id, retry.path("id").asText());
  }

  @Test
  void expiredLeaseIsNotAutomaticallyRetried() throws Exception {
    var w = workflow();
    actor();
    var run =
        runs.submit(
            Json.obj("type", "inline", "workflow", w),
            w.path("sampleInput"),
            60,
            "manual",
            false,
            null,
            null);
    String id = run.path("id").asText();
    jdbc.update("UPDATE runs SET status='running',lease_until=1 WHERE id=?", id);
    runs.recover();
    assertEquals("interrupted", runs.get(id).path("status").asText());
  }

  @Test
  void onceScheduleDispatchIsTransactionalAndDeduplicated() throws Exception {
    var w = workflow();
    var skill = call(body(post("/api/publish"), Json.obj("workflow", w)), 201);
    String at = Instant.now().plusSeconds(3600).toString();
    var schedule =
        call(
            body(
                post("/api/schedules"),
                Json.obj(
                    "name",
                    "one-" + Json.id(),
                    "skillId",
                    skill.path("id"),
                    "version",
                    1,
                    "input",
                    w.path("sampleInput"),
                    "frequency",
                    "once",
                    "at",
                    at,
                    "timezone",
                    "Asia/Shanghai")),
            201);
    String id = schedule.path("id").asText();
    schedules.tick(Instant.parse(at).plusSeconds(1));
    schedules.tick(Instant.parse(at).plusSeconds(2));
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE schedule_id=?", Integer.class, id));
    var saved = call(get("/api/schedules/" + id), 200);
    assertFalse(saved.path("enabled").asBoolean());
    call(body(delete("/api/schedules/" + id), Json.obj()), 200);
  }

  @Test
  void relativeDateAndDstPreview() throws Exception {
    assertEquals(
        "2026-11-24",
        schedules
            .relative(
                Json.obj("date", Json.obj("$relative", "yesterday")),
                "Asia/Shanghai",
                Instant.parse("2026-11-24T17:00:00Z"))
            .path("date")
            .asText());
    var config = Json.obj("frequency", "daily", "time", "02:30", "timezone", "America/New_York");
    assertEquals(
        Instant.parse("2026-03-09T06:30:00Z"),
        schedules.next(config, Instant.parse("2026-03-08T05:00:00Z")));
    assertEquals(
        5,
        call(
                body(
                    post("/api/schedules/preview"),
                    Json.obj(
                        "frequency",
                        "interval",
                        "intervalMinutes",
                        10,
                        "timezone",
                        "Asia/Shanghai")),
                200)
            .path("occurrences")
            .size());
  }

  @Test
  void ownerAndWorkspaceIsolation() throws Exception {
    var w = workflow();
    Actor.set(new Actor("local", "someone-else"));
    assertThrows(
        ApiException.class, () -> docs.get(Documents.Kind.WORKFLOW, w.path("id").asText()));
    Actor.set(new Actor("other-space", "local-developer"));
    assertThrows(
        ApiException.class, () -> docs.get(Documents.Kind.WORKFLOW, w.path("id").asText()));
  }

  @Test
  void importRequiresConfiguredAgent() throws Exception {
    var skill =
        call(
            body(
                post("/api/skills/import"),
                Json.obj(
                    "markdown",
                    "---\nname: doc-" + Json.id() + "\ndescription: Example\n---\nHello")),
            201);
    var result =
        call(
            body(
                post("/api/node-run"),
                Json.obj("skillId", skill.path("id"), "version", 1, "input", Json.obj())),
            503);
    assertEquals("AGENT_NOT_CONFIGURED", result.path("code").asText());
    var response =
        mvc.perform(get("/api/skills/" + skill.path("id").asText() + "/1/export"))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus());
    assertEquals("application/zip", response.getContentType());
  }

  @Test
  void credentialTestingDoesNotFakeSuccess() throws Exception {
    var c =
        call(
            body(
                post("/api/credentials"),
                Json.obj("name", "cred-" + Json.id(), "secretRef", "credential-center/example")),
            201);
    String id = c.path("id").asText();
    call(body(post("/api/credentials/" + id + "/test"), Json.obj()), 503);
    call(
        body(
            patch("/api/credentials/" + id),
            Json.obj("name", "renamed", "secretRef", "credential-center/other", "revision", 1)),
        200);
    call(delete("/api/credentials/" + id), 200);
  }

  @Test
  void readPlatformRoutesAndCrossOriginBoundary() throws Exception {
    for (String route :
        List.of(
            "/api/bootstrap",
            "/api/skills",
            "/api/workflows",
            "/api/runs",
            "/api/schedules",
            "/api/me",
            "/api/me/permissions",
            "/api/auth/csrf",
            "/api/adapters",
            "/api/adapters/template",
            "/api/credentials",
            "/api/audit-events",
            "/internal/health/liveness",
            "/internal/health/readiness")) call(get(route), 200);
    call(body(post("/api/node-run").header("Origin", "https://evil.example"), Json.obj()), 403);
    assertEquals(200, mvc.perform(get("/internal/metrics")).andReturn().getResponse().getStatus());
  }

  @Test
  void atomicCreateAndEditProduceNewVersion() throws Exception {
    var body =
        Json.obj(
            "name",
            "template-" + Json.id(),
            "title",
            "Template",
            "description",
            "text",
            "operation",
            "template",
            "config",
            Json.obj("template", "Hello {{name}}"),
            "inputs",
            Json.node(List.of(Json.obj("key", "name", "type", "string", "required", true))),
            "outputs",
            Json.node(List.of(Json.obj("key", "text", "type", "string", "required", true))));
    var one = call(body(post("/api/skills"), body), 201);
    body.set("id", one.path("id"));
    assertEquals(2, call(body(post("/api/skills"), body), 201).path("version").asInt());
    call(
        body(
            patch("/api/skills/" + one.path("id").asText()),
            Json.obj("archived", true, "revision", 2)),
        200);
  }

  @Test
  void schedulePauseUpdateTriggerAndOverlap() throws Exception {
    var w = workflow();
    var skill = call(body(post("/api/publish"), Json.obj("workflow", w)), 201);
    var schedule =
        call(
            body(
                post("/api/schedules"),
                Json.obj(
                    "name",
                    "repeat-" + Json.id(),
                    "skillId",
                    skill.path("id"),
                    "version",
                    1,
                    "input",
                    w.path("sampleInput"),
                    "frequency",
                    "interval",
                    "intervalMinutes",
                    5,
                    "timezone",
                    "Asia/Shanghai")),
            201);
    String id = schedule.path("id").asText();
    call(body(patch("/api/schedules/" + id), Json.obj("enabled", false)), 200);
    var enabled = call(body(patch("/api/schedules/" + id), Json.obj("enabled", true)), 200);
    var update = Json.copy(enabled);
    update.put("intervalMinutes", 10);
    var saved = call(body(put("/api/schedules/" + id), update), 200);
    call(body(post("/api/schedules/" + id + "/trigger"), Json.obj()), 202);
    call(body(post("/api/schedules/" + id + "/trigger"), Json.obj()), 409);
    schedules.tick(Instant.parse(saved.path("nextAt").asText()).plusSeconds(1));
    assertEquals(
        "skipped_overlap", call(get("/api/schedules/" + id), 200).path("lastOutcome").asText());
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE schedule_id=?", Integer.class, id));
    call(delete("/api/schedules/" + id), 200);
  }

  @Test
  void nestedCompositionRecordsNodePaths() throws Exception {
    var w = workflow();
    var published = call(body(post("/api/publish"), Json.obj("workflow", w)), 201);
    var outer = workflow();
    var bindings = Json.obj();
    w.path("inputs")
        .forEach(
            f ->
                bindings.set(
                    f.path("key").asText(),
                    Json.obj(
                        "mode",
                        "path",
                        "path",
                        "input." + f.path("key").asText(),
                        "transform",
                        "identity")));
    outer.set(
        "steps",
        Json.node(
            List.of(
                Json.obj(
                    "id", "nested", "skill", published, "bindings", bindings, "onEmpty", "stop"))));
    var run =
        call(
            body(post("/api/run"), Json.obj("workflow", outer, "input", outer.path("sampleInput"))),
            200);
    assertEquals("succeeded", run.path("status").asText());
    assertEquals(5, run.path("steps").size());
    assertEquals("nested/s1", run.path("steps").get(0).path("nodePath").asText());
    assertFalse(run.path("steps").get(0).path("parentExecutionId").isNull());
  }

  @Test
  void concurrentRunClaimExecutesOnce() throws Exception {
    var w = workflow();
    actor();
    var run =
        runs.submit(
            Json.obj("type", "inline", "workflow", w),
            w.path("sampleInput"),
            60,
            "manual",
            false,
            null,
            null);
    String id = run.path("id").asText();
    Actor.clear();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var one = pool.submit(() -> runs.process(id));
      var two = pool.submit(() -> runs.process(id));
      one.get();
      two.get();
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM run_events WHERE run_id=? AND kind='running'",
            Integer.class,
            id));
    assertEquals("succeeded", call(get("/api/runs/" + id), 200).path("status").asText());
  }

  @Test
  void concurrentSchedulersEnqueueOnce() throws Exception {
    var w = workflow();
    var skill = call(body(post("/api/publish"), Json.obj("workflow", w)), 201);
    var at = Instant.now().plusSeconds(4000);
    var schedule =
        call(
            body(
                post("/api/schedules"),
                Json.obj(
                    "name",
                    "parallel-" + Json.id(),
                    "skillId",
                    skill.path("id"),
                    "version",
                    1,
                    "input",
                    w.path("sampleInput"),
                    "frequency",
                    "once",
                    "at",
                    at.toString(),
                    "timezone",
                    "Asia/Shanghai")),
            201);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var one = pool.submit(() -> schedules.tick(at.plusSeconds(1)));
      var two = pool.submit(() -> schedules.tick(at.plusSeconds(1)));
      one.get();
      two.get();
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM runs WHERE schedule_id=?",
            Integer.class,
            schedule.path("id").asText()));
    call(delete("/api/schedules/" + schedule.path("id").asText()), 200);
  }

  @Test
  void workflowDeleteAndLogout() throws Exception {
    var w = workflow();
    String url = "/api/workflows/" + w.path("id").asText();
    call(delete(url).param("revision", "99"), 409);
    call(delete(url).param("revision", w.path("revision").asText()), 200);
    call(get(url), 404);
    call(body(post("/api/auth/logout"), Json.obj()), 200);
  }

  @Test
  void mappingPreviewEndpoint() throws Exception {
    var result =
        call(
            body(
                post("/api/mapping-preview"),
                Json.obj(
                    "binding",
                    Json.obj("mode", "constant", "value", "value"),
                    "context",
                    Json.obj())),
            200);
    assertEquals("value", result.path("value").asText());
  }

  @Test
  void sseReplaysPersistedEvents() throws Exception {
    var w = workflow();
    var run =
        call(body(post("/api/run"), Json.obj("workflow", w, "input", w.path("sampleInput"))), 200);
    String id = run.path("id").asText();
    var pending =
        mvc.perform(get("/api/runs/" + id + "/events").header("Last-Event-ID", "0")).andReturn();
    assertTrue(pending.getRequest().isAsyncStarted());
    pending.getAsyncResult(5000);
    var response = mvc.perform(asyncDispatch(pending)).andReturn().getResponse();
    assertEquals(200, response.getStatus());
    assertTrue(response.getContentAsString().contains("event:succeeded"));
    assertTrue(response.getContentAsString().contains("id:"));
  }

  @Test
  void unknownRouteRemains404() throws Exception {
    call(get("/api/does-not-exist"), 404);
  }

  @Test
  void terminalStatusAndEventRollbackTogether() throws Exception {
    ObjectNode w = workflow();
    actor();
    String id =
        runs.submit(
                Json.obj(
                    "type", "workflow", "workflowId", w.path("id"), "revision", w.path("revision")),
                w.path("sampleInput"),
                60,
                "manual",
                false,
                null,
                null)
            .path("id")
            .asText();
    jdbc.execute(
        "ALTER TABLE run_events ADD CONSTRAINT test_terminal_event_failure CHECK "
            + "(NOT (run_id='"
            + id
            + "' AND kind='succeeded'))");
    try {
      assertThrows(org.springframework.dao.DataAccessException.class, () -> runs.process(id));
      assertEquals("running", runs.get(id).path("status").asText());
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM run_events WHERE run_id=? AND kind='succeeded'",
              Integer.class,
              id));
    } finally {
      jdbc.execute("ALTER TABLE run_events DROP CONSTRAINT test_terminal_event_failure");
      jdbc.update("UPDATE runs SET lease_until=0 WHERE id=?", id);
      runs.recover();
    }
    assertEquals("interrupted", runs.get(id).path("status").asText());
  }

  @Test
  void deletionIsOwnerScopedAndExistingWorkflowStillExecutes() throws Exception {
    actor();
    var skill =
        catalog.create(
            Json.obj(
                "name",
                "delete-template-" + Json.id(),
                "title",
                "删除测试",
                "operation",
                "template",
                "config",
                Json.obj("template", "kept"),
                "inputs",
                Json.arr(),
                "outputs",
                Json.arr().add(Json.obj("key", "text", "type", "string", "required", true))));
    String id = skill.path("id").asText();
    var w =
        catalog.saveWorkflow(
            Json.obj(
                "id",
                Json.id(),
                "name",
                "kept-flow",
                "title",
                "保留编排",
                "inputs",
                Json.arr(),
                "steps",
                Json.arr()
                    .add(
                        Json.obj(
                            "id",
                            "only",
                            "skill",
                            skill,
                            "bindings",
                            Json.obj(),
                            "onEmpty",
                            "stop"))));
    for (Actor other :
        List.of(new Actor("elsewhere", "local-developer"), new Actor("local", "other"))) {
      Actor.set(other);
      assertEquals(404, assertThrows(ApiException.class, () -> catalog.deleteSkill(id)).status());
    }
    actor();
    catalog.deleteSkill(id);
    assertEquals("kept", runs.sync(w, Json.obj(), null).path("output").path("text").asText());
  }

  @Test
  void legacyContractsAreProjectedWithoutRewritingVersionsAndAsyncOptionalInputsRemainAbsent()
      throws Exception {
    actor();
    var imported =
        catalog.importMarkdown("---\nname: legacy-" + Json.id() + "\ndescription: 测试\n---\n# test");
    var legacy = imported.deepCopy();
    legacy.set("inputs", Json.arr());
    legacy.set("outputs", Json.arr());
    jdbc.update(
        "UPDATE skill_versions SET payload=? WHERE id=?",
        legacy.toString(),
        legacy.path("id").asText() + ":1");
    var w =
        Json.obj(
            "id",
            Json.id(),
            "name",
            "legacy-flow",
            "title",
            "旧编排",
            "inputs",
            Json.arr(),
            "steps",
            Json.arr()
                .add(
                    Json.obj(
                        "id",
                        "agent",
                        "skill",
                        legacy,
                        "bindings",
                        Json.obj(),
                        "onEmpty",
                        "stop")));
    docs.insert(Documents.Kind.WORKFLOW, w.path("id").asText(), w, "draft");
    var loaded = call(get("/api/workflows/" + w.path("id").asText()), 200);
    assertEquals(
        "prompt",
        loaded.path("steps").get(0).path("skill").path("inputs").get(0).path("key").asText());
    assertEquals(1, loaded.path("revision").asInt());
    actor();
    assertTrue(
        docs.get(Documents.Kind.VERSION, legacy.path("id").asText() + ":1")
            .path("inputs")
            .isEmpty());
    var run =
        runs.submit(
            Json.obj("type", "skill", "skillId", imported.path("id"), "version", 1),
            Json.obj("prompt", "test"),
            30,
            "manual",
            false,
            null,
            null);
    String id = run.path("id").asText();
    var snapshot =
        Json.parse(jdbc.queryForObject("SELECT snapshot FROM runs WHERE id=?", String.class, id));
    assertTrue(snapshot.path("steps").get(0).path("bindings").has("prompt"));
    assertFalse(snapshot.path("steps").get(0).path("bindings").has("context"));
    runs.process(id);
    assertTrue(runs.get(id).path("error").asText().contains("执行器"));
    // A workflow containing an instruction Skill can be published, but still needs its executor to
    // run.
    var published = catalog.publish(loaded);
    assertEquals("text", published.path("outputs").get(0).path("key").asText());
  }
}
