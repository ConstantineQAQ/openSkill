package dev.openskill.api;

import static dev.openskill.infrastructure.Documents.Kind.SCHEDULE;

import dev.openskill.application.ScheduleService;
import dev.openskill.domain.Json;
import dev.openskill.infrastructure.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
  private final ScheduleService service;
  private final Documents docs;
  private final Idempotency keys;

  public ScheduleController(ScheduleService service, Documents docs, Idempotency keys) {
    this.service = service;
    this.docs = docs;
    this.keys = keys;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode create(
      @RequestBody JsonNode body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return keys.call("schedule.create", key, body, () -> service.save(null, body));
  }

  @GetMapping
  public ObjectNode list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return docs.page(SCHEDULE, keyword, status, page, pageSize);
  }

  @GetMapping("/{id}")
  public ObjectNode get(@PathVariable String id) {
    return docs.get(SCHEDULE, id);
  }

  @PutMapping("/{id}")
  public ObjectNode update(@PathVariable String id, @RequestBody JsonNode body) {
    return service.save(id, body);
  }

  @PatchMapping("/{id}")
  public ObjectNode enable(@PathVariable String id, @RequestBody Requests.EnableBody body) {
    return service.enable(id, body.enabled());
  }

  @DeleteMapping("/{id}")
  public ObjectNode delete(@PathVariable String id) {
    return service.delete(id);
  }

  @PostMapping("/preview")
  public ObjectNode preview(@RequestBody JsonNode body) {
    return service.preview(body);
  }

  @PostMapping("/{id}/trigger")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ObjectNode trigger(
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return keys.call(
        "schedule.trigger", key, Json.obj("scheduleId", id), () -> service.trigger(id));
  }
}
