package dev.openskill.api;

import dev.openskill.domain.Json;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/internal")
public class InternalController {
  private final JdbcTemplate jdbc;
  private final PrometheusMeterRegistry registry;

  public InternalController(JdbcTemplate jdbc, PrometheusMeterRegistry registry) {
    this.jdbc = jdbc;
    this.registry = registry;
  }

  @GetMapping("/health/liveness")
  public ObjectNode liveness() {
    return Json.obj("status", "UP");
  }

  @GetMapping("/health/readiness")
  public ResponseEntity<ObjectNode> readiness() {
    try {
      jdbc.queryForObject("SELECT COUNT(*) FROM execution_outbox", Long.class);
      return ResponseEntity.ok(Json.obj("status", "UP", "database", "UP"));
    } catch (org.springframework.dao.DataAccessException e) {
      return ResponseEntity.status(503).body(Json.obj("status", "DOWN", "database", "DOWN"));
    }
  }

  @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
  public String metrics() {
    return registry.scrape();
  }
}
