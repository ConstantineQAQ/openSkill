package dev.openskill;

import static org.junit.jupiter.api.Assertions.*;

import dev.openskill.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class IdentityFilterTests {
  private final IdentityFilter filter = new IdentityFilter("container");

  private MockHttpServletRequest authenticated(String method, String path) {
    var r = new MockHttpServletRequest(method, path);
    r.setUserPrincipal(() -> "employee");
    r.setAttribute("skill.workspace", "team");
    return r;
  }

  @Test
  void rejectsHeaderSpoofing() throws Exception {
    var r = new MockHttpServletRequest("GET", "/api/bootstrap");
    r.addHeader("X-User", "admin");
    r.addHeader("X-Workspace", "team");
    var s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> fail());
    assertEquals(401, s.getStatus());
  }

  @Test
  void enforcesRolesAndWorkspace() throws Exception {
    var r = authenticated("GET", "/api/bootstrap");
    var s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> fail());
    assertEquals(403, s.getStatus());
    r.addUserRole("skill-read");
    r.removeAttribute("skill.workspace");
    s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> fail());
    assertEquals(403, s.getStatus());
  }

  @Test
  void csrfOnAuthorizedWrite() throws Exception {
    var r = authenticated("POST", "/api/skills");
    r.addUserRole("skill-edit");
    var s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> fail());
    assertEquals(403, s.getStatus());
    r.getSession().setAttribute("skill.csrf", "test-token");
    r.addHeader("X-CSRF-Token", "test-token");
    s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> assertEquals(new Actor("team", "employee"), Actor.current()));
    assertEquals(200, s.getStatus());
    assertNull(Actor.optional());
  }

  @Test
  void postSkillDeletionStillRequiresEditPermissionAndCsrf() throws Exception {
    var r = authenticated("POST", "/api/skills/fixture/delete");
    r.addUserRole("skill-read");
    r.setContentType("application/json");
    r.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> fail());
    assertEquals(403, s.getStatus());
    r.addUserRole("skill-edit");
    s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> fail());
    assertEquals(403, s.getStatus());
    r.getSession().setAttribute("skill.csrf", "test-token");
    r.addHeader("X-CSRF-Token", "test-token");
    s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> assertEquals(new Actor("team", "employee"), Actor.current()));
    assertEquals(200, s.getStatus());
    assertNull(Actor.optional());
  }

  @Test
  void multipartImportStillRequiresCsrfAndEditPermission() throws Exception {
    var r = authenticated("POST", "/api/skills/import");
    r.setContentType("multipart/form-data; boundary=test");
    r.setContent(new byte[] {1});
    r.addUserRole("skill-edit");
    var s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> fail());
    assertEquals(403, s.getStatus());
    r.getSession().setAttribute("skill.csrf", "test-token");
    r.addHeader("X-CSRF-Token", "test-token");
    s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> assertEquals(new Actor("team", "employee"), Actor.current()));
    assertEquals(200, s.getStatus());
    assertNull(Actor.optional());
  }

  @Test
  void exportRoleIsDistinctFromRead() throws Exception {
    var r = authenticated("GET", "/api/skills/one/1/export");
    r.addUserRole("skill-read");
    var s = new MockHttpServletResponse();
    filter.doFilter(r, s, (a, b) -> fail());
    assertEquals(403, s.getStatus());
  }

  @Test
  void rejectsLocalModeOnRemoteAddress() throws Exception {
    var r = new MockHttpServletRequest("GET", "/api/bootstrap");
    r.setRemoteAddr("192.0.2.1");
    var s = new MockHttpServletResponse();
    new IdentityFilter("local").doFilter(r, s, (a, b) -> fail());
    assertEquals(403, s.getStatus());
  }
}
