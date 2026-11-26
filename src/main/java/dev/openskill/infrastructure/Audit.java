package dev.openskill.infrastructure;

import dev.openskill.domain.Json;
import dev.openskill.security.Actor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

@Service
public class Audit {
  private final JdbcTemplate jdbc;

  public Audit(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(String action, String resource) {
    Actor a = Actor.current();
    jdbc.update(
        "INSERT INTO audit_events(workspace,actor,action,resource_id,trace_id,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        a.workspace(),
        a.user(),
        action,
        resource,
        MDC.get("traceId"),
        Json.now());
  }

  public ObjectNode page(int page, int size) {
    Actor a = Actor.current();
    var items =
        jdbc.query(
            "SELECT * FROM audit_events WHERE workspace=? AND actor=? ORDER BY seq DESC LIMIT ?"
                + " OFFSET ?",
            (r, n) ->
                Json.obj(
                    "id",
                    r.getLong("seq"),
                    "actor",
                    r.getString("actor"),
                    "action",
                    r.getString("action"),
                    "resourceId",
                    r.getString("resource_id"),
                    "traceId",
                    r.getString("trace_id"),
                    "createdAt",
                    r.getString("created_at")),
            a.workspace(),
            a.user(),
            size,
            (long) (page - 1) * size);
    return Json.obj(
        "items",
        items,
        "total",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_events WHERE workspace=? AND actor=?",
            Long.class,
            a.workspace(),
            a.user()),
        "page",
        page,
        "pageSize",
        size);
  }
}
