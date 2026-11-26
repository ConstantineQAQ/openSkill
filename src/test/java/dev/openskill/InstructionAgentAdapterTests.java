package dev.openskill;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.openskill.domain.*;
import dev.openskill.infrastructure.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** 真实启动受控假 CLI，不调用模型、不发送用户资料、不执行上传的测试脚本。 */
class InstructionAgentAdapterTests {
  @TempDir Path temp;
  SkillPackages packages = mock(SkillPackages.class);

  private JsonNode skill() {
    return Json.obj("id", "fixture", "version", 1, "config", Json.obj("markdown", "# fixture"));
  }

  private byte[] zip(String... entries) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (int i = 0; i < entries.length; i += 2) {
        zip.putNextEntry(new ZipEntry(entries[i]));
        zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private Path cli(String body) throws Exception {
    Path command = temp.resolve("fake-codex");
    Files.writeString(
        command,
        """
        #!/bin/sh
        set -eu
        workspace=''; output=''; sandbox=''; ignore_config=false; ignore_rules=false
        for arg in "$@"; do
          case "$arg" in *fixture-test-only*) exit 91;; esac
        done
        while [ "$#" -gt 0 ]; do
          case "$1" in
            --cd) workspace="$2"; shift;;
            --output-last-message) output="$2"; shift;;
            --sandbox) sandbox="$2"; shift;;
            --ignore-user-config) ignore_config=true;;
            --ignore-rules) ignore_rules=true;;
          esac
          shift
        done
        [ "$sandbox" = workspace-write ] && ! $ignore_config && $ignore_rules
        [ -f "$CODEX_HOME/config.toml" ]
        [ "$HOME" != "$workspace" ]
        [ "$SKILL_RUNNER_MODEL_TOKEN" = fixture-test-only ]
        ! grep -q fixture-test-only "$CODEX_HOME/config.toml"
        [ "${SKILL_TEST_SERVER_SECRET:-}" = '' ]
        printf '%s' "$workspace" > "$0.workspace"
        cat > "$0.prompt"
        """
            + body);
    assertTrue(command.toFile().setExecutable(true));
    return command;
  }

  private InstructionAgentAdapter adapter(Path command, int timeout) {
    return new InstructionAgentAdapter(
        packages, provider(), "codex", command.toString(), "local", timeout, false);
  }

  private AgentModelProvider provider() {
    return new AgentModelProvider(
        "server",
        temp.resolve("must-not-read").toString(),
        "https://fixture.invalid/v1",
        "fixture-model",
        "fixture-test-only",
        "");
  }

  private void cleaned(Path command) throws Exception {
    Path workspace = Path.of(Files.readString(Path.of(command + ".workspace")));
    assertFalse(Files.exists(workspace.getParent()), "试运行临时目录必须清理");
  }

  @Test
  void markdownAndInputReachProcessAndOutputReturns() throws Exception {
    when(packages.find("fixture", 1)).thenReturn(Optional.empty());
    Path command =
        cli(
            """
            [ "$(cat "$workspace/skill/SKILL.md")" = '# fixture' ]
            cp "$workspace/input.json" "$0.input"
            printf '%s' '{"success":true,"text":"fixture done"}' > "$output"
            """);
    JsonNode input = Json.obj("prompt", "hello", "context", Json.obj("region", "cn"));
    JsonNode output = adapter(command, 5).executeSkill(skill(), input, () -> {});
    assertEquals(input, Json.parse(Files.readString(Path.of(command + ".input"))));
    assertEquals("fixture done", output.path("text").asText());
    assertEquals("codex", output.path("executor").asText());
    assertTrue(Files.readString(Path.of(command + ".prompt")).contains("skill/SKILL.md"));
    cleaned(command);
  }

  @Test
  void nestedZipKeepsResourcesAndDoesNotExecuteScriptsAutomatically() throws Exception {
    byte[] archive =
        zip(
            "nested/SKILL.md",
            "# packaged",
            "nested/references/example.txt",
            "资源内容",
            "nested/scripts/task.sh",
            "exit 99",
            "nested/assets/.hidden",
            "binary placeholder");
    when(packages.find("fixture", 1)).thenReturn(Optional.of(archive));
    Path command =
        cli(
            """
            [ -f "$workspace/skill/nested/scripts/task.sh" ]
            [ -f "$workspace/skill/nested/assets/.hidden" ]
            [ "$(cat "$workspace/skill/nested/references/example.txt")" = '资源内容' ]
            printf '%s' '{"success":true,"text":"资源内容"}' > "$output"
            """);
    assertEquals(
        "资源内容",
        adapter(command, 5).executeSkill(skill(), Json.obj(), () -> {}).path("text").asText());
    assertTrue(Files.readString(Path.of(command + ".prompt")).contains("skill/nested/SKILL.md"));
    cleaned(command);
  }

  @Test
  void disabledOrEnterpriseNeverStartsProcess() {
    for (var adapter :
        List.of(
            new InstructionAgentAdapter(
                packages, provider(), "disabled", "absent", "local", 5, false),
            new InstructionAgentAdapter(
                packages, provider(), "codex", "absent", "container", 5, false))) {
      assertEquals(
          "AGENT_NOT_CONFIGURED",
          assertThrows(
                  ApiException.class, () -> adapter.executeSkill(skill(), Json.obj(), () -> {}))
              .code());
      assertFalse(adapter.catalog().getFirst().path("available").asBoolean());
    }
    verifyNoInteractions(packages);
  }

  @Test
  void missingCommandReportsActionableFailure() {
    when(packages.find("fixture", 1)).thenReturn(Optional.empty());
    var error =
        assertThrows(
            ApiException.class,
            () ->
                adapter(temp.resolve("not-installed"), 5)
                    .executeSkill(skill(), Json.obj(), () -> {}));
    assertEquals("AGENT_START_FAILED", error.code());
    assertTrue(error.getMessage().contains("SKILL_AGENT_COMMAND"));
  }

  @Test
  void loginFailureIsSanitizedAndCleansWorkspace() throws Exception {
    when(packages.find("fixture", 1)).thenReturn(Optional.empty());
    Path command = cli("echo '401 unauthorized secret-do-not-return'; exit 1\n");
    var error =
        assertThrows(
            ApiException.class,
            () -> adapter(command, 5).executeSkill(skill(), Json.obj(), () -> {}));
    assertEquals("AGENT_EXECUTION_FAILED", error.code());
    assertTrue(error.getMessage().contains("SKILL_MODEL_API_KEY"));
    assertFalse(error.getMessage().contains("secret-do-not-return"));
    cleaned(command);
  }

  @Test
  void localProviderReachesProcessAndAuthFailureMentionsLocalConfig() throws Exception {
    when(packages.find("fixture", 1)).thenReturn(Optional.empty());
    Path home = Files.createDirectory(temp.resolve("local-config"));
    Files.writeString(
        home.resolve("config.toml"),
        """
        model_provider = "third-party"
        model = "fixture-model"
        [model_providers.third-party]
        base_url = "https://fixture.invalid/v1"
        experimental_bearer_token = "fixture-test-only"
        """);
    var provider = new AgentModelProvider("local-codex", home.toString(), "", "", "", "");
    Path command = cli("echo '401 unauthorized fixture-test-only'; exit 1\n");
    var adapter =
        new InstructionAgentAdapter(
            packages, provider, "codex", command.toString(), "local", 5, false);
    var error =
        assertThrows(ApiException.class, () -> adapter.executeSkill(skill(), Json.obj(), () -> {}));
    assertTrue(error.getMessage().contains("本地 Codex 配置"));
    assertFalse(error.getMessage().contains("fixture-test-only"));
    assertEquals("local-codex", adapter.catalog().getFirst().path("providerSource").asText());
    cleaned(command);
    assertTrue(Files.isRegularFile(home.resolve("config.toml")));
  }

  @Test
  void timeoutStopsProcessAndRemovesWorkspace() throws Exception {
    when(packages.find("fixture", 1)).thenReturn(Optional.empty());
    Path command = cli("printf '%s' $$ > \"$0.pid\"; sleep 20\n");
    var error =
        assertThrows(
            ApiException.class,
            () -> adapter(command, 1).executeSkill(skill(), Json.obj(), () -> {}));
    assertEquals("AGENT_TIMEOUT", error.code());
    long pid = Long.parseLong(Files.readString(Path.of(command + ".pid")));
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    cleaned(command);
  }

  @Test
  void cancellationReachesProcessCheckpoint() throws Exception {
    when(packages.find("fixture", 1)).thenReturn(Optional.empty());
    Path command = cli("sleep 20\n");
    var error =
        assertThrows(
            ApiException.class,
            () ->
                adapter(command, 5)
                    .executeSkill(
                        skill(),
                        Json.obj(),
                        () -> {
                          if (Files.exists(Path.of(command + ".workspace")))
                            throw new ApiException(409, "CANCELLED", "取消测试");
                        }));
    assertEquals("CANCELLED", error.code());
    cleaned(command);
  }

  @Test
  void zipSlipRejectedBeforeCliStarts() throws Exception {
    Path outside = temp.resolve("escaped.txt");
    when(packages.find("fixture", 1))
        .thenReturn(Optional.of(zip("SKILL.md", "# fixture", outside.toString(), "no")));
    Path command = cli("touch \"$output\"\n");
    var error =
        assertThrows(
            ApiException.class,
            () -> adapter(command, 5).executeSkill(skill(), Json.obj(), () -> {}));
    assertEquals("PACKAGE_PATH_INVALID", error.code());
    assertFalse(Files.exists(outside));
    assertFalse(Files.exists(Path.of(command + ".workspace")));
  }

  @Test
  void businessFailureIsNotReportedAsSucceeded() throws Exception {
    when(packages.find("fixture", 1)).thenReturn(Optional.empty());
    Path command = cli("printf '%s' '{\"success\":false,\"text\":\"缺少依赖资源\"}' > \"$output\"\n");
    var error =
        assertThrows(
            ApiException.class,
            () -> adapter(command, 5).executeSkill(skill(), Json.obj(), () -> {}));
    assertEquals("AGENT_TASK_FAILED", error.code());
    assertEquals("缺少依赖资源", error.getMessage());
    cleaned(command);
  }

  @Test
  void oversizedFinalOutputIsRejectedAndCleaned() throws Exception {
    when(packages.find("fixture", 1)).thenReturn(Optional.empty());
    Path command = cli("dd if=/dev/zero of=\"$output\" bs=1000001 count=1 2>/dev/null\n");
    var error =
        assertThrows(
            ApiException.class,
            () -> adapter(command, 5).executeSkill(skill(), Json.obj(), () -> {}));
    assertEquals("AGENT_OUTPUT_TOO_LARGE", error.code());
    cleaned(command);
  }
}
