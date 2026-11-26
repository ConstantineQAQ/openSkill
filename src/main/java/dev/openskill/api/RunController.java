package dev.openskill.api;

import dev.openskill.application.RunService;
import dev.openskill.domain.*;
import dev.openskill.engine.MappingEngine;
import dev.openskill.infrastructure.Idempotency;
import dev.openskill.security.Actor;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api")
public class RunController {
  private final RunService runs;
  private final MappingEngine mapping;
  private final Idempotency keys;
  private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

  public RunController(RunService runs, MappingEngine mapping, Idempotency keys) {
    this.runs = runs;
    this.mapping = mapping;
    this.keys = keys;
  }

  @PostMapping("/run")
  public ObjectNode run(@RequestBody Requests.RunBody body) {
    return runs.sync(body.workflow(), body.input(), body.upTo());
  }

  @PostMapping("/node-run")
  public ObjectNode node(@RequestBody Requests.NodeRunBody body) {
    return runs.node(body.skillId(), body.version(), body.input());
  }

  @PostMapping("/mapping-preview")
  public ObjectNode preview(@RequestBody Requests.MappingBody body) {
    return mapping.preview(body.binding(), body.context());
  }

  @PostMapping("/runs")
  public ResponseEntity<ObjectNode> submit(
      @RequestBody Requests.AsyncRunBody body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    if ("inline".equals(body.target().path("type").asText()))
      throw ApiException.bad("异步接口仅执行已保存工作流或固定 Skill 版本");
    ObjectNode run =
        keys.call(
            "run.submit",
            key,
            Json.node(body),
            () ->
                runs.submit(
                    body.target(),
                    body.input(),
                    body.timeoutSeconds() == null ? 300 : body.timeoutSeconds(),
                    "manual",
                    true,
                    null,
                    null));
    return ResponseEntity.accepted()
        .header(HttpHeaders.LOCATION, "/api/runs/" + run.path("id").asText())
        .body(run);
  }

  @GetMapping("/runs")
  public ObjectNode list(
      @RequestParam Map<String, String> filters,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return runs.page(filters, page, pageSize);
  }

  @GetMapping("/runs/{id}")
  public ObjectNode get(@PathVariable String id) {
    return runs.get(id);
  }

  @GetMapping("/runs/{id}/steps")
  public ObjectNode steps(
      @PathVariable String id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    List<JsonNode> steps = Json.list(runs.get(id).path("steps"));
    return Json.obj(
        "items",
        steps.stream().skip((long) (page - 1) * pageSize).limit(pageSize).toList(),
        "total",
        steps.size(),
        "page",
        page,
        "pageSize",
        pageSize);
  }

  @GetMapping("/runs/{id}/logs")
  public ObjectNode logs(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long cursor,
      @RequestParam(defaultValue = "100") int limit) {
    return runs.events(id, cursor, limit);
  }

  @PostMapping("/runs/{id}/cancel")
  public ObjectNode cancel(@PathVariable String id) {
    return runs.cancel(id);
  }

  @PostMapping("/runs/{id}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ObjectNode retry(
      @PathVariable String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return keys.call("run.retry", key, Json.obj("runId", id), () -> runs.retry(id));
  }

  @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long last,
      @RequestParam(defaultValue = "0") long cursor) {
    runs.get(id);
    Actor actor = Actor.current();
    SseEmitter emitter = new SseEmitter(60000L);
    AtomicBoolean closed = new AtomicBoolean();
    emitter.onCompletion(() -> closed.set(true));
    emitter.onTimeout(() -> closed.set(true));
    emitter.onError(e -> closed.set(true));
    streams.submit(
        () -> {
          Actor.set(actor);
          long offset = Math.max(last, cursor);
          long end = System.currentTimeMillis() + 55000;
          try {
            while (!closed.get() && System.currentTimeMillis() < end) {
              ObjectNode batch = runs.events(id, offset, 100);
              for (JsonNode event : batch.path("items")) {
                offset = event.path("id").asLong();
                emitter.send(
                    SseEmitter.event()
                        .id(Long.toString(offset))
                        .name(event.path("type").asText())
                        .data(event.path("data"), MediaType.APPLICATION_JSON));
              }
              boolean terminal =
                  !Set.of("queued", "running", "cancelling")
                      .contains(runs.get(id).path("status").asText());
              if (terminal && batch.path("items").size() < 100) break;
              emitter.send(SseEmitter.event().comment("heartbeat"));
              Thread.sleep(1000);
            }
            emitter.complete();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
          } catch (Exception e) {
            emitter.completeWithError(e);
          } finally {
            Actor.clear();
          }
        });
    return emitter;
  }

  @PreDestroy
  public void close() {
    streams.shutdownNow();
  }
}
