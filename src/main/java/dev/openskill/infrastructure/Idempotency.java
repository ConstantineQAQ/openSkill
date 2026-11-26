package dev.openskill.infrastructure;

import dev.openskill.domain.*;
import dev.openskill.security.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class Idempotency {
  private final JdbcTemplate jdbc;
  private final Transactions tx;

  public Idempotency(JdbcTemplate jdbc, Transactions tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  public ObjectNode call(
      String operation, String key, JsonNode request, Supplier<ObjectNode> action) {
    if (key == null) return action.get();
    Actor a = Actor.current();
    String hash = hash(key), requestHash = hash(canonical(request));
    try {
      return tx.run(
          () -> {
            jdbc.update(
                "INSERT INTO"
                    + " idempotency_keys(workspace,actor,operation,key_hash,request_hash,response_json)"
                    + " VALUES(?,?,?,?,?,'{}')",
                a.workspace(),
                a.user(),
                operation,
                hash,
                requestHash);
            ObjectNode result = action.get();
            jdbc.update(
                "UPDATE idempotency_keys SET response_json=? WHERE workspace=? AND actor=? AND"
                    + " operation=? AND key_hash=?",
                result.toString(),
                a.workspace(),
                a.user(),
                operation,
                hash);
            return result;
          });
    } catch (DuplicateKeyException e) {
      var rows =
          jdbc.queryForList(
              "SELECT request_hash,response_json FROM idempotency_keys WHERE workspace=? AND"
                  + " actor=? AND operation=? AND key_hash=?",
              a.workspace(),
              a.user(),
              operation,
              hash);
      if (rows.isEmpty()) throw e;
      var row = rows.getFirst();
      if (!requestHash.equals(row.get("request_hash")))
        throw ApiException.conflict("同一幂等键不能用于不同请求");
      return Json.copy(Json.parse((String) row.get("response_json")));
    }
  }

  private String canonical(JsonNode n) {
    return sorted(n).toString();
  }

  private JsonNode sorted(JsonNode n) {
    if (n.isObject()) {
      ObjectNode result = Json.obj();
      TreeMap<String, JsonNode> fields = new TreeMap<>();
      n.properties().forEach(e -> fields.put(e.getKey(), e.getValue()));
      fields.forEach((k, v) -> result.set(k, sorted(v)));
      return result;
    }
    if (n.isArray()) {
      var result = Json.arr();
      n.forEach(v -> result.add(sorted(v)));
      return result;
    }
    return n;
  }

  private String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
