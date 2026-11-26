package dev.openskill.engine;

import dev.openskill.domain.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

@Component
public class MappingEngine {
  public JsonNode path(JsonNode root, String path) {
    if (!path.matches("[\\w-]+(?:\\.[\\w-]+)*")) throw ApiException.bad("字段路径不合法：" + path);
    JsonNode value = root;
    for (String part : path.split("\\.")) {
      if (Set.of("__proto__", "constructor", "prototype").contains(part))
        throw ApiException.bad("禁止访问此字段");
      if (value.isArray() && part.matches("\\d+")) value = value.path(Integer.parseInt(part));
      else value = value.path(part);
    }
    return value;
  }

  public JsonNode apply(JsonNode b, JsonNode context) {
    if ("constant".equals(b.path("mode").asText())) return b.path("value").deepCopy();
    JsonNode value = path(context, b.path("path").asText());
    if (value.isMissingNode()) throw ApiException.bad("来源字段不存在：" + b.path("path").asText());
    if (b.path("filter").hasNonNull("field")) {
      if (!value.isArray()) throw ApiException.bad("筛选只适用于列表");
      ArrayNode filtered = Json.arr();
      JsonNode original = value;
      original.forEach(
          v -> {
            if (jsString(path(v, b.path("filter").path("field").asText()))
                .equals(jsString(b.path("filter").path("value")))) filtered.add(v);
          });
      value = filtered;
    }
    return switch (b.path("transform").asText()) {
      case "identity" -> value;
      case "first" -> {
        array(value);
        yield value.isEmpty() ? NullNode.getInstance() : value.get(0);
      }
      case "pluck" -> {
        array(value);
        ArrayNode out = Json.arr();
        for (JsonNode v : value) {
          JsonNode item = path(v, b.path("argument").asText());
          if (item.isMissingNode()) throw ApiException.bad("列表中不存在字段");
          out.add(item);
        }
        yield out;
      }
      case "join" -> {
        array(value);
        String delimiter = b.path("argument").asText();
        if (delimiter.isEmpty()) delimiter = ", ";
        List<String> parts = new ArrayList<>();
        value.forEach(v -> parts.add((v.isObject() || v.isArray()) ? v.toString() : jsString(v)));
        yield Json.node(String.join(delimiter, parts));
      }
      case "json" -> Json.node(pretty(value));
      default -> throw ApiException.bad("不支持的转换方式");
    };
  }

  public ObjectNode preview(JsonNode binding, JsonNode context) {
    JsonNode source =
        "constant".equals(binding.path("mode").asText())
            ? binding.path("value")
            : path(context, binding.path("path").asText());
    JsonNode value = apply(binding, context);
    return Json.obj(
        "source",
        Json.redact(source),
        "value",
        Json.redact(value),
        "beforeCount",
        count(source),
        "count",
        count(value));
  }

  public String pretty(JsonNode n) {
    return pretty(n, 0);
  }

  private String pretty(JsonNode n, int depth) {
    if (!n.isObject() && !n.isArray()) return n.toString();
    if (n.isEmpty()) return n.isArray() ? "[]" : "{}";
    String pad = "  ".repeat(depth + 1);
    List<String> lines = new ArrayList<>();
    if (n.isArray()) n.forEach(v -> lines.add(pad + pretty(v, depth + 1)));
    else
      n.properties()
          .forEach(
              e -> lines.add(pad + Json.node(e.getKey()) + ": " + pretty(e.getValue(), depth + 1)));
    return (n.isArray() ? "[" : "{")
        + "\n"
        + String.join(",\n", lines)
        + "\n"
        + "  ".repeat(depth)
        + (n.isArray() ? "]" : "}");
  }

  public String jsString(JsonNode n) {
    if (n.isMissingNode()) return "undefined";
    if (n.isNull()) return "null";
    if (n.isObject()) return "[object Object]";
    if (n.isArray()) {
      List<String> values = new ArrayList<>();
      n.forEach(v -> values.add(v.isNull() ? "" : jsString(v)));
      return String.join(",", values);
    }
    return n.asText();
  }

  private int count(JsonNode n) {
    return n.isNull() || n.isMissingNode() ? 0 : n.isArray() ? n.size() : 1;
  }

  private void array(JsonNode n) {
    if (!n.isArray()) throw ApiException.bad("该转换只适用于列表");
  }
}
