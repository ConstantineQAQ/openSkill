package dev.openskill.engine;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Implemented by approved infrastructure adapters, never by uploaded executable code. */
public interface ExternalAdapter {
  String operation();

  JsonNode execute(JsonNode config, JsonNode input);

  default JsonNode executeSkill(JsonNode skill, JsonNode input, Runnable checkpoint) {
    return execute(skill.path("config"), input);
  }

  default List<JsonNode> catalog() {
    return List.of();
  }
}
