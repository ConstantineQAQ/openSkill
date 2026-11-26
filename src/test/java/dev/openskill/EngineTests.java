package dev.openskill;

import static org.junit.jupiter.api.Assertions.*;

import dev.openskill.domain.*;
import dev.openskill.engine.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineTests {
  private final MappingEngine mapping = new MappingEngine();

  @Test
  void mappingFilterCounts() {
    var context =
        Json.parse(
            "{\"input\":{\"items\":[{\"severity\":\"high\"},{\"severity\":\"low\"},{\"severity\":\"high\"}]}}");
    var b =
        Json.obj(
            "mode",
            "path",
            "path",
            "input.items",
            "transform",
            "identity",
            "filter",
            Json.obj("field", "severity", "value", "high"));
    var out = mapping.preview(b, context);
    assertEquals(3, out.path("beforeCount").asInt());
    assertEquals(2, out.path("count").asInt());
  }

  @Test
  void fiveTransforms() {
    var ctx = Json.parse("{\"input\":{\"items\":[{\"n\":1},{\"n\":2}],\"words\":[\"a\",\"b\"]}}");
    var b = Json.obj("mode", "path", "path", "input.items", "transform", "first");
    assertEquals(1, mapping.apply(b, ctx).path("n").asInt());
    b.put("transform", "pluck");
    b.put("argument", "n");
    assertEquals("[1,2]", mapping.apply(b, ctx).toString());
    b.put("transform", "json");
    assertTrue(mapping.apply(b, ctx).asText().contains("\n"));
    b.put("path", "input.words");
    b.put("transform", "join");
    b.put("argument", ";");
    assertEquals("a;b", mapping.apply(b, ctx).asText());
    b.put("transform", "identity");
    assertEquals(2, mapping.apply(b, ctx).size());
  }

  @Test
  void forbiddenPathsAndDataTypes() {
    assertThrows(ApiException.class, () -> mapping.path(Json.obj(), "input.__proto__"));
    assertThrows(
        ApiException.class,
        () ->
            new WorkflowRules()
                .data(
                    Json.obj("value", "one"),
                    Json.node(
                        List.of(Json.obj("key", "value", "type", "number", "required", true))),
                    "input"));
  }

  @Test
  void demoDisabledIsExplicit() {
    var engine = new SkillEngine(mapping, new WorkflowRules(), List.of(), false);
    var skill = Json.obj("operation", "demo-app", "inputs", Json.arr(), "outputs", Json.arr());
    assertEquals(
        "DEMO_DISABLED",
        assertThrows(ApiException.class, () -> engine.execute(skill, Json.obj(), () -> {})).code());
  }

  @Test
  void redactionKeepsNonSecretFields() {
    var result =
        Json.redact(
            Json.obj("password", "secret", "input", Json.obj("token", "a", "name", "alice")));
    assertEquals("[REDACTED]", result.path("password").asText());
    assertEquals("alice", result.path("input").path("name").asText());
  }

  @Test
  void instructionTextFeedsDownstreamAndStandaloneNodeSkipsPredecessor() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    ExternalAdapter agent =
        new ExternalAdapter() {
          public String operation() {
            return "instructions";
          }

          public tools.jackson.databind.JsonNode execute(
              tools.jackson.databind.JsonNode config, tools.jackson.databind.JsonNode input) {
            calls.incrementAndGet();
            return Json.obj("text", "agent answer");
          }
        };
    var engine = new SkillEngine(mapping, new WorkflowRules(), List.of(agent), false);
    var instruction =
        InstructionSkills.executable(
            Json.obj(
                "id",
                "agent",
                "name",
                "agent",
                "title",
                "智能体",
                "operation",
                "instructions",
                "inputs",
                Json.arr(),
                "outputs",
                Json.arr()));
    var template =
        Json.obj(
            "id",
            "template",
            "name",
            "template",
            "title",
            "模板",
            "operation",
            "template",
            "config",
            Json.obj("template", "结果：{{value}}"),
            "inputs",
            Json.arr().add(Json.obj("key", "value", "type", "string", "required", true)),
            "outputs",
            Json.arr().add(Json.obj("key", "text", "type", "string", "required", true)));
    var steps =
        Json.arr()
            .add(
                Json.obj(
                    "id", "agent", "skill", instruction, "bindings", Json.obj(), "onEmpty", "stop"))
            .add(
                Json.obj(
                    "id",
                    "format",
                    "skill",
                    template,
                    "onEmpty",
                    "stop",
                    "bindings",
                    Json.obj(
                        "value",
                        Json.obj(
                            "mode",
                            "path",
                            "path",
                            "steps.agent.text",
                            "transform",
                            "identity",
                            "confirmed",
                            true))));
    var w = Json.obj("inputs", Json.arr(), "steps", steps);
    var result = engine.workflow(w, Json.obj(), null, () -> {}, record -> {});
    assertEquals("succeeded", result.path("status").asText());
    assertEquals("结果：agent answer", result.path("output").path("text").asText());
    assertEquals(1, calls.get());
    assertEquals(
        "结果：独立参数",
        engine.execute(template, Json.obj("value", "独立参数"), () -> {}).path("text").asText());
    assertEquals(1, calls.get(), "单节点执行不调用智能体前置节点");
  }
}
