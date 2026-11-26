package dev.openskill.domain;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class Json {
  public static final JsonMapper MAPPER = JsonMapper.builder().build();

  private Json() {}

  public static ObjectNode obj() {
    return MAPPER.createObjectNode();
  }

  public static ObjectNode obj(Object... pairs) {
    ObjectNode n = obj();
    for (int i = 0; i < pairs.length; i += 2) n.set((String) pairs[i], node(pairs[i + 1]));
    return n;
  }

  public static ArrayNode arr() {
    return MAPPER.createArrayNode();
  }

  public static JsonNode node(Object o) {
    return o instanceof JsonNode j ? j : MAPPER.valueToTree(o);
  }

  public static JsonNode parse(String s) {
    return MAPPER.readTree(s);
  }

  public static String text(JsonNode n, String key) {
    return n.path(key).asText();
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String now() {
    return java.time.Instant.now().toString();
  }

  public static List<JsonNode> list(JsonNode n) {
    List<JsonNode> l = new ArrayList<>();
    n.forEach(l::add);
    return l;
  }

  public static ObjectNode copy(JsonNode n) {
    return (ObjectNode) n.deepCopy();
  }

  public static JsonNode redact(JsonNode n) {
    if (n.isObject()) {
      ObjectNode out = obj();
      n.properties()
          .forEach(
              e ->
                  out.set(
                      e.getKey(),
                      e.getKey().matches("(?i).*(password|token|secret|cookie|authorization).*")
                          ? node("[REDACTED]")
                          : redact(e.getValue())));
      return out;
    }
    if (n.isArray()) {
      ArrayNode out = arr();
      n.forEach(v -> out.add(redact(v)));
      return out;
    }
    return n.deepCopy();
  }
}
