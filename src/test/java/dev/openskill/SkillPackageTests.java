package dev.openskill;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import dev.openskill.application.SkillPackageService;
import dev.openskill.domain.Json;
import dev.openskill.infrastructure.SkillPackages;
import dev.openskill.infrastructure.Transactions;
import dev.openskill.security.Actor;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillPackageTests {
  @Autowired MockMvc mvc;
  @Autowired SkillPackages packages;
  @Autowired SkillPackageService service;
  @Autowired Transactions tx;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void clear() {
    Actor.clear();
  }

  private MockHttpServletResponse call(AbstractMockHttpServletRequestBuilder<?> request, int status)
      throws Exception {
    var response =
        mvc.perform(
                request.with(
                    r -> {
                      r.setRemoteAddr("127.0.0.1");
                      r.setServerName("localhost");
                      return r;
                    }))
            .andReturn()
            .getResponse();
    assertEquals(status, response.getStatus(), response.getContentAsString());
    return response;
  }

  private String markdown(String name) {
    return "---\nname: " + name + "\ndescription: 完整导入测试\n---\n\n# 原文保持不变\n";
  }

  private byte[] zip(String prefix, String name) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      zip.setComment("保留压缩包注释");
      entry(zip, prefix + "SKILL.md", markdown(name).getBytes(StandardCharsets.UTF_8));
      entry(
          zip,
          prefix + "scripts/run.py",
          "raise RuntimeError('不得执行上传脚本')".getBytes(StandardCharsets.UTF_8));
      entry(
          zip,
          prefix + "references/嵌套/资料.md",
          "secret-body-do-not-log".getBytes(StandardCharsets.UTF_8));
      entry(zip, prefix + "assets/binary.dat", new byte[] {0, 1, 2, (byte) 255, 0, 9});
      entry(zip, prefix + "empty/", new byte[0]);
      entry(zip, prefix + ".config", "保留隐藏文件".getBytes(StandardCharsets.UTF_8));
    }
    return bytes.toByteArray();
  }

  private void entry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  private MockMultipartFile file(byte[] content) {
    return new MockMultipartFile("file", "完整技能.zip", "application/zip", content);
  }

  private JsonNode upload(byte[] content) throws Exception {
    return Json.parse(
        call(
                multipart("/api/skills/import")
                    .file(file(content))
                    .header("Origin", "http://localhost")
                    .header("Sec-Fetch-Site", "same-origin"),
                201)
            .getContentAsString());
  }

  @Test
  void rootPackageRoundTripsByteForByteAndIsNotInBootstrap() throws Exception {
    byte[] original = zip("", "root-" + Json.id());
    JsonNode skill = upload(original);
    var download = call(get("/api/skills/" + skill.path("id").asText() + "/1/export"), 200);
    assertEquals("application/zip", download.getContentType());
    assertTrue(download.getHeader("Content-Disposition").contains(".zip"));
    assertArrayEquals(original, download.getContentAsByteArray());
    assertEquals("instructions", skill.path("operation").asText());
    assertEquals(
        markdown(skill.path("name").asText()), skill.path("config").path("markdown").asText());
    String bootstrap = call(get("/api/bootstrap"), 200).getContentAsString();
    assertFalse(bootstrap.contains("secret-body-do-not-log"));
  }

  @Test
  void nestedPackageRetainsDirectoriesBinaryAndComments() throws Exception {
    byte[] original = zip("my-skill/", "nested-" + Json.id());
    JsonNode skill = upload(original);
    assertArrayEquals(
        original,
        call(get("/api/skills/" + skill.path("id").asText() + "/1/export"), 200)
            .getContentAsByteArray());
  }

  @Test
  void packageIsScopedByOwnerWorkspaceAndVersion() throws Exception {
    byte[] original = zip("", "isolation-" + Json.id());
    JsonNode skill = upload(original);
    String id = skill.path("id").asText();
    Actor.set(new Actor("local", "local-developer"));
    assertArrayEquals(original, packages.find(id, 1).orElseThrow());
    assertTrue(packages.find(id, 2).isEmpty());
    Actor.set(new Actor("local", "another-owner"));
    assertTrue(packages.find(id, 1).isEmpty());
    Actor.set(new Actor("another-workspace", "local-developer"));
    assertTrue(packages.find(id, 1).isEmpty());
  }

  @Test
  void packageAndMetadataRollbackTogether() throws Exception {
    String name = "rollback-" + Json.id();
    byte[] original = zip("", name);
    Actor.set(new Actor("local", "local-developer"));
    long count = jdbc.queryForObject("SELECT COUNT(*) FROM skill_packages", Long.class);
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.run(
                () -> {
                  service.importZip(file(original));
                  throw new IllegalStateException("模拟事务提交失败");
                }));
    assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM skill_packages", Long.class));
    assertEquals(
        0, jdbc.queryForObject("SELECT COUNT(*) FROM skill_assets WHERE name=?", Long.class, name));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT COUNT(*) FROM skill_versions WHERE name=?", Long.class, name));
  }

  @Test
  void markdownEndpointAndGeneratedExportStayCompatible() throws Exception {
    String md = markdown("markdown-" + Json.id());
    var response =
        call(
            post("/api/skills/import")
                .contentType("application/json")
                .content(Json.obj("markdown", md).toString()),
            201);
    String id = Json.parse(response.getContentAsString()).path("id").asText();
    byte[] exported = call(get("/api/skills/" + id + "/1/export"), 200).getContentAsByteArray();
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exported))) {
      assertEquals("SKILL.md", zip.getNextEntry().getName());
      assertEquals(md, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void reimportCreatesImmutableVersionsAndUpgradesMarkdownImports() throws Exception {
    String name = "versions-" + Json.id();
    String id =
        Json.parse(
                call(
                        post("/api/skills/import")
                            .contentType("application/json")
                            .content(Json.obj("markdown", markdown(name)).toString()),
                        201)
                    .getContentAsString())
            .path("id")
            .asText();
    byte[] original = zip("first/", name);
    byte[] updated = zip("second/", name);
    JsonNode second = upload(original);
    JsonNode third = upload(updated);
    assertEquals(id, second.path("id").asText());
    assertEquals(id, third.path("id").asText());
    assertEquals(2, second.path("version").asInt());
    assertEquals(3, third.path("version").asInt());
    assertArrayEquals(
        original, call(get("/api/skills/" + id + "/2/export"), 200).getContentAsByteArray());
    assertArrayEquals(
        updated, call(get("/api/skills/" + id + "/3/export"), 200).getContentAsByteArray());
    call(get("/api/skills/" + id + "/1/export"), 200);
  }

  @Test
  void unreadableOrMissingDescriptionPackagesReturnClientErrors() throws Exception {
    call(multipart("/api/skills/import").file(file(new byte[] {1, 2, 3})), 400);
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      entry(zip, "scripts/run.py", new byte[] {1});
    }
    call(multipart("/api/skills/import").file(file(bytes.toByteArray())), 400);
    call(multipart("/api/skills/import"), 400);
  }

  @Test
  void existingMarkdownSizeLimitAlsoAppliesInsideZip() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      entry(
          zip,
          "SKILL.md",
          (markdown("large-" + Json.id()) + "x".repeat(110000)).getBytes(StandardCharsets.UTF_8));
    }
    call(multipart("/api/skills/import").file(file(bytes.toByteArray())), 400);
  }

  @Test
  void crossOriginUploadIsStillRejected() throws Exception {
    call(
        multipart("/api/skills/import")
            .file(file(zip("", "cross-" + Json.id())))
            .header("Origin", "https://untrusted.example")
            .header("Sec-Fetch-Site", "cross-site"),
        403);
  }

  @Test
  void chineseLogsUseLog4j2AndIncludeTraceWithoutPackageContents() throws Exception {
    var root = (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
    var logs = new CapturedLogs();
    logs.start();
    root.addAppender(logs);
    try {
      byte[] content = zip("", "logging-" + Json.id());
      var response = call(multipart("/api/skills/import").file(file(content)), 201);
      String trace = response.getHeader("X-Trace-Id");
      String id = Json.parse(response.getContentAsString()).path("id").asText();
      call(get("/api/skills/" + id + "/1/export"), 200);
      assertTrue(
          logs.events.stream()
              .anyMatch(
                  e ->
                      e.getMessage().getFormattedMessage().contains("Skill 完整包导入成功")
                          && trace.equals(e.getContextData().getValue("traceId"))));
      assertTrue(
          logs.events.stream()
              .anyMatch(e -> e.getMessage().getFormattedMessage().contains("Skill 导出成功")));
      assertTrue(
          logs.events.stream()
              .anyMatch(e -> e.getMessage().getFormattedMessage().contains("接口请求结束")));
      assertFalse(
          logs.events.stream()
              .anyMatch(
                  e -> e.getMessage().getFormattedMessage().contains("secret-body-do-not-log")));
      assertEquals(
          "org.apache.logging.slf4j.Log4jLoggerFactory",
          org.slf4j.LoggerFactory.getILoggerFactory().getClass().getName());
    } finally {
      root.removeAppender(logs);
      logs.stop();
    }
  }

  private static final class CapturedLogs extends AbstractAppender {
    final List<LogEvent> events = new ArrayList<>();

    CapturedLogs() {
      super("package-test", null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }

  @Test
  void postDeletionHidesAllVersionsButPreservesPackagesAndReimportRestoresAsset() throws Exception {
    String name = "delete-" + Json.id();
    byte[] first = zip("", name), second = zip("nested/", name);
    JsonNode one = upload(first), two = upload(second);
    String id = one.path("id").asText();
    assertEquals(2, two.path("version").asInt());
    call(get("/api/skills/" + id + "/delete"), 405);
    call(delete("/api/skills/" + id), 405);
    var beforeDelete = Json.parse(call(get("/api/bootstrap"), 200).getContentAsString());
    assertEquals(
        2,
        Json.list(beforeDelete.path("skills")).stream()
            .filter(v -> id.equals(v.path("id").asText()))
            .count());
    var deleted =
        Json.parse(
            call(
                    post("/api/skills/" + id + "/delete")
                        .contentType("application/json")
                        .content("{}"),
                    200)
                .getContentAsString());
    assertEquals(id, deleted.path("id").asText());
    assertTrue(deleted.path("deleted").asBoolean());
    call(post("/api/skills/" + id + "/delete"), 200);
    var boot = Json.parse(call(get("/api/bootstrap"), 200).getContentAsString());
    assertTrue(
        Json.list(boot.path("skills")).stream().noneMatch(v -> id.equals(v.path("id").asText())));
    var page =
        Json.parse(call(get("/api/skills").param("keyword", name), 200).getContentAsString());
    assertEquals(0, page.path("total").asInt());
    assertArrayEquals(
        first, call(get("/api/skills/" + id + "/1/export"), 200).getContentAsByteArray());
    assertArrayEquals(
        second, call(get("/api/skills/" + id + "/2/export"), 200).getContentAsByteArray());
    assertEquals(
        2,
        Json.parse(call(get("/api/skills/" + id + "/versions"), 200).getContentAsString())
            .path("items")
            .size());
    JsonNode restored = upload(second);
    assertEquals(id, restored.path("id").asText());
    assertEquals(3, restored.path("version").asInt());
    boot = Json.parse(call(get("/api/bootstrap"), 200).getContentAsString());
    assertEquals(
        3,
        Json.list(boot.path("skills")).stream()
            .filter(v -> id.equals(v.path("id").asText()))
            .count());
  }
}
