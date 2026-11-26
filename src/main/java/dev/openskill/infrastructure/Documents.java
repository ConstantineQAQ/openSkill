package dev.openskill.infrastructure;

import dev.openskill.domain.*;
import dev.openskill.security.Actor;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.node.ObjectNode;

@Repository
public class Documents {
  public enum Kind {
    ASSET("skill_assets"),
    VERSION("skill_versions"),
    WORKFLOW("workflow_drafts"),
    SCHEDULE("schedules"),
    CREDENTIAL("credential_bindings");
    final String table;

    Kind(String table) {
      this.table = table;
    }
  }

  private final JdbcTemplate jdbc;

  public Documents(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ObjectNode> find(Kind kind, String id) {
    Actor a = Actor.current();
    return jdbc
        .query(
            "SELECT payload,revision FROM "
                + kind.table
                + " WHERE workspace=? AND owner_id=? AND id=?",
            (r, n) -> {
              ObjectNode v = Json.copy(Json.parse(r.getString(1)));
              v.put("revision", r.getLong(2));
              return v;
            },
            a.workspace(),
            a.user(),
            id)
        .stream()
        .findFirst();
  }

  public Optional<ObjectNode> findByName(Kind kind, String name) {
    Actor actor = Actor.current();
    return jdbc
        .query(
            "SELECT payload FROM " + kind.table + " WHERE workspace=? AND owner_id=? AND name=?",
            (row, n) -> Json.copy(Json.parse(row.getString(1))),
            actor.workspace(),
            actor.user(),
            name)
        .stream()
        .findFirst();
  }

  public ObjectNode get(Kind kind, String id) {
    return find(kind, id).orElseThrow(ApiException::missing);
  }

  public List<ObjectNode> all(Kind kind) {
    Actor a = Actor.current();
    return jdbc.query(
        "SELECT payload,revision FROM "
            + kind.table
            + " WHERE workspace=? AND owner_id=? ORDER BY updated_at DESC,id",
        (r, n) -> {
          ObjectNode v = Json.copy(Json.parse(r.getString(1)));
          v.put("revision", r.getLong(2));
          return v;
        },
        a.workspace(),
        a.user());
  }

  public ObjectNode page(Kind kind, String keyword, String status, int page, int size) {
    Actor a = Actor.current();
    String where = " WHERE workspace=? AND owner_id=?";
    List<Object> args = new ArrayList<>(List.of(a.workspace(), a.user()));
    if (kind == Kind.ASSET && status == null) where += " AND status <> 'deleted'";
    if (keyword != null && !keyword.isBlank()) {
      where += " AND name LIKE ?";
      args.add("%" + keyword + "%");
    }
    if (status != null) {
      where += " AND status=?";
      args.add(status);
    }
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + kind.table + where, Long.class, args.toArray());
    args.add(size);
    args.add((long) (page - 1) * size);
    var items =
        jdbc.query(
            "SELECT payload,revision FROM "
                + kind.table
                + where
                + " ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
            (r, n) -> {
              ObjectNode v = Json.copy(Json.parse(r.getString(1)));
              v.put("revision", r.getLong(2));
              return v;
            },
            args.toArray());
    return Json.obj("items", items, "total", total, "page", page, "pageSize", size);
  }

  public ObjectNode insert(Kind kind, String key, ObjectNode value, String status) {
    Actor a = Actor.current();
    String now = Json.now();
    ObjectNode v = value.deepCopy();
    v.put("revision", 1);
    jdbc.update(
        "INSERT INTO "
            + kind.table
            + "(workspace,id,owner_id,name,status,revision,payload,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,1,?,?,?)",
        a.workspace(),
        key,
        a.user(),
        v.path("name").asText(key),
        status,
        v.toString(),
        now,
        now);
    return v;
  }

  public ObjectNode update(Kind kind, String key, ObjectNode value, String status, long expected) {
    Actor a = Actor.current();
    ObjectNode v = value.deepCopy();
    v.put("revision", expected + 1);
    int n =
        jdbc.update(
            "UPDATE "
                + kind.table
                + " SET name=?,status=?,revision=revision+1,payload=?,updated_at=? WHERE"
                + " workspace=? AND owner_id=? AND id=? AND revision=?",
            v.path("name").asText(key),
            status,
            v.toString(),
            Json.now(),
            a.workspace(),
            a.user(),
            key,
            expected);
    if (n != 1) throw ApiException.conflict("资源已更新，请刷新后重试");
    return v;
  }

  public ObjectNode lock(Kind kind, String key) {
    Actor a = Actor.current();
    var found =
        jdbc.query(
            "SELECT payload,revision FROM "
                + kind.table
                + " WHERE workspace=? AND owner_id=? AND id=? FOR UPDATE",
            (r, n) -> {
              ObjectNode v = Json.copy(Json.parse(r.getString(1)));
              v.put("revision", r.getLong(2));
              return v;
            },
            a.workspace(),
            a.user(),
            key);
    if (found.isEmpty()) throw ApiException.missing();
    return found.getFirst();
  }

  public void delete(Kind kind, String key) {
    Actor a = Actor.current();
    jdbc.update(
        "DELETE FROM " + kind.table + " WHERE workspace=? AND owner_id=? AND id=?",
        a.workspace(),
        a.user(),
        key);
  }

  public void scheduleTime(String id, long at) {
    Actor a = Actor.current();
    jdbc.update(
        "UPDATE schedules SET next_at=? WHERE workspace=? AND owner_id=? AND id=?",
        at,
        a.workspace(),
        a.user(),
        id);
  }
}
