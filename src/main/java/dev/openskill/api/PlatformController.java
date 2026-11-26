package dev.openskill.api;

import static dev.openskill.infrastructure.Documents.Kind.CREDENTIAL;

import dev.openskill.application.CredentialService;
import dev.openskill.domain.*;
import dev.openskill.engine.ExternalAdapter;
import dev.openskill.infrastructure.*;
import dev.openskill.security.Actor;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api")
public class PlatformController {
  private final CredentialService credentials;
  private final Documents docs;
  private final Audit audit;
  private final List<ExternalAdapter> adapters;
  private final boolean local;

  public PlatformController(
      CredentialService credentials,
      Documents docs,
      Audit audit,
      List<ExternalAdapter> adapters,
      @Value("${skill.auth.mode}") String mode) {
    this.credentials = credentials;
    this.docs = docs;
    this.audit = audit;
    this.adapters = adapters;
    this.local = mode.equals("local");
  }

  @GetMapping("/me")
  public ObjectNode me() {
    return Json.obj(
        "id",
        Actor.current().user(),
        "workspaceId",
        Actor.current().workspace(),
        "authMode",
        local ? "local" : "container");
  }

  @GetMapping("/me/permissions")
  public ObjectNode permissions(HttpServletRequest request) {
    return Json.obj(
        "permissions",
        List.of("read", "edit", "publish", "execute", "schedule", "export", "credentials", "admin")
            .stream()
            .filter(
                p ->
                    local
                        || request.isUserInRole("skill-admin")
                        || request.isUserInRole("skill-" + p))
            .toList());
  }

  @GetMapping("/auth/csrf")
  public ObjectNode csrf(HttpServletRequest request) {
    var session = request.getSession();
    if (session.getAttribute("skill.csrf") == null) session.setAttribute("skill.csrf", Json.id());
    return Json.obj("token", session.getAttribute("skill.csrf"), "headerName", "X-CSRF-Token");
  }

  @PostMapping("/auth/logout")
  public ObjectNode logout(HttpServletRequest request) throws jakarta.servlet.ServletException {
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    request.logout();
    return Json.obj("ok", true);
  }

  private List<JsonNode> catalog() {
    List<JsonNode> result = new ArrayList<>();
    for (String op : List.of("template", "filter", "select", "notification-preview"))
      result.add(
          Json.obj(
              "id",
              op,
              "operation",
              op,
              "available",
              !op.equals("instructions"),
              "unattended",
              !Set.of("notification-preview", "instructions").contains(op)));
    adapters.forEach(a -> result.addAll(a.catalog()));
    return result;
  }

  @GetMapping("/adapters")
  public ObjectNode adapters() {
    return Json.obj("items", catalog());
  }

  @GetMapping("/adapters/{id}")
  public JsonNode adapter(@PathVariable String id) {
    return catalog().stream()
        .filter(a -> id.equals(a.path("id").asText()))
        .findFirst()
        .orElseThrow(ApiException::missing);
  }

  @GetMapping("/credentials")
  public ObjectNode credentials(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return docs.page(CREDENTIAL, null, null, page, pageSize);
  }

  @PostMapping("/credentials")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode create(@RequestBody JsonNode body) {
    return credentials.save(null, body);
  }

  @PatchMapping("/credentials/{id}")
  public ObjectNode update(@PathVariable String id, @RequestBody JsonNode body) {
    return credentials.save(id, body);
  }

  @DeleteMapping("/credentials/{id}")
  public ObjectNode delete(@PathVariable String id) {
    return credentials.delete(id);
  }

  @PostMapping("/credentials/{id}/test")
  public ObjectNode test(@PathVariable String id) {
    return credentials.test(id);
  }

  @GetMapping("/audit-events")
  public ObjectNode audit(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return audit.page(page, pageSize);
  }
}
