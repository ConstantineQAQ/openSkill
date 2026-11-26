package dev.openskill.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 给存量 Markdown Skill 补充执行契约，不修改已保存的原包和版本。 */
public final class InstructionSkills {
  private InstructionSkills() {}

  public static ObjectNode executable(JsonNode value) {
    ObjectNode skill = Json.copy(value);
    if ("instructions".equals(skill.path("operation").asText())) {
      if (skill.path("inputs").isEmpty())
        skill.set(
            "inputs",
            Json.node(
                java.util.List.of(
                    Json.obj("key", "prompt", "label", "执行要求", "type", "string", "required", false),
                    Json.obj(
                        "key", "context", "label", "上下文数据", "type", "object", "required", false))));
      if (skill.path("outputs").isEmpty())
        skill.set(
            "outputs",
            Json.node(
                java.util.List.of(
                    Json.obj("key", "text", "label", "执行结果", "type", "string", "required", true))));
    }
    if ("composite".equals(skill.path("kind").asText())) {
      ObjectNode workflow = workflow(skill.path("workflow"));
      skill.set("workflow", workflow);
      if (!workflow.path("steps").isEmpty())
        skill.set(
            "outputs",
            workflow
                .path("steps")
                .get(workflow.path("steps").size() - 1)
                .path("skill")
                .path("outputs"));
    }
    return skill;
  }

  public static ObjectNode workflow(JsonNode value) {
    ObjectNode workflow = Json.copy(value);
    for (JsonNode step : workflow.path("steps"))
      ((ObjectNode) step).set("skill", executable(step.path("skill")));
    return workflow;
  }
}
