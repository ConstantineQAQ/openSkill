package dev.openskill.api;

import static dev.openskill.infrastructure.Documents.Kind.*;

import dev.openskill.application.*;
import dev.openskill.domain.*;
import dev.openskill.infrastructure.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api")
public class CatalogController {
  private final CatalogService catalog;
  private final Documents docs;
  private final RunService runs;
  private final Idempotency keys;
  private final ExportService exports;
  private final SkillPackageService packages;
  private final String mode;
  private final boolean demo;

  public CatalogController(
      CatalogService catalog,
      Documents docs,
      RunService runs,
      Idempotency keys,
      ExportService exports,
      SkillPackageService packages,
      @Value("${skill.dispatcher.mode}") String mode,
      @Value("${skill.demo.enabled:false}") boolean demo) {
    this.catalog = catalog;
    this.docs = docs;
    this.runs = runs;
    this.keys = keys;
    this.exports = exports;
    this.packages = packages;
    this.mode = mode;
    this.demo = demo;
  }

  @GetMapping("/bootstrap")
  public ObjectNode bootstrap() {
    return Json.obj(
        "skills",
        catalog.visibleSkills(),
        "workflows",
        docs.all(WORKFLOW).stream().map(InstructionSkills::workflow).toList(),
        "schedules",
        docs.all(SCHEDULE),
        "runs",
        runs.page(Map.of(), 1, 100).path("items"),
        "runtime",
        Json.obj(
            "mode", mode, "name", "openSkill Java Runtime", "version", "0.1.0-SNAPSHOT", "demo", demo));
  }

  @PostMapping("/skills")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode create(
      @RequestBody JsonNode body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return keys.call("skill.create", key, body, () -> catalog.create(body));
  }

  @PostMapping(value = "/skills/import", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode importSkill(@RequestBody Requests.ImportBody body) {
    return catalog.importMarkdown(body.markdown());
  }

  @PostMapping(value = "/skills/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode importPackage(@RequestPart("file") MultipartFile file) {
    return packages.importZip(file);
  }

  @GetMapping("/skills")
  public ObjectNode skills(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return docs.page(ASSET, keyword, status, page, pageSize);
  }

  @GetMapping("/skills/{id}/versions")
  public ObjectNode versions(@PathVariable String id) {
    docs.get(ASSET, id);
    return Json.obj(
        "items",
        docs.all(VERSION).stream()
            .filter(s -> id.equals(s.path("id").asText()))
            .map(InstructionSkills::executable)
            .sorted(Comparator.comparingInt((ObjectNode s) -> s.path("version").asInt()).reversed())
            .toList());
  }

  @GetMapping("/skills/{id}/versions/{version}")
  public ObjectNode version(@PathVariable String id, @PathVariable int version) {
    return catalog.skill(id, version);
  }

  @GetMapping("/skills/{id}/versions/{version}/dependencies")
  public ObjectNode dependencies(
      @PathVariable String id,
      @PathVariable int version,
      @RequestParam(defaultValue = "forward") String direction) {
    return Json.obj("items", catalog.dependencies(id, version, direction));
  }

  @PostMapping("/skills/{id}/delete")
  public ObjectNode deleteSkill(@PathVariable String id) {
    return catalog.deleteSkill(id);
  }

  @PatchMapping("/skills/{id}")
  public ObjectNode archive(@PathVariable String id, @RequestBody Requests.ArchiveBody body) {
    return catalog.archive(id, body.archived(), body.revision());
  }

  @GetMapping("/skills/{id}/{version}/export")
  public ResponseEntity<byte[]> export(@PathVariable String id, @PathVariable int version) {
    JsonNode skill = catalog.skill(id, version);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(skill.path("name").asText() + "-v" + version + ".zip")
                .build()
                .toString())
        .body(exports.bundle(skill));
  }

  @PostMapping("/workflows")
  public ObjectNode save(@RequestBody JsonNode workflow) {
    return catalog.saveWorkflow(workflow);
  }

  @GetMapping("/workflows")
  public ObjectNode workflows(
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    ObjectNode result = docs.page(WORKFLOW, keyword, null, page, pageSize);
    result.set(
        "items",
        Json.node(
            Json.list(result.path("items")).stream().map(InstructionSkills::workflow).toList()));
    return result;
  }

  @GetMapping("/workflows/{id}")
  public ObjectNode workflow(@PathVariable String id) {
    return InstructionSkills.workflow(docs.get(WORKFLOW, id));
  }

  @DeleteMapping("/workflows/{id}")
  public ObjectNode delete(@PathVariable String id, @RequestParam long revision) {
    return catalog.deleteWorkflow(id, revision);
  }

  @PostMapping("/validate")
  public ObjectNode validate(@RequestBody Requests.WorkflowBody body) {
    return catalog.validate(body.workflow());
  }

  @PostMapping("/publish")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode publish(
      @RequestBody Requests.WorkflowBody body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return keys.call("skill.publish", key, body.workflow(), () -> catalog.publish(body.workflow()));
  }
}
