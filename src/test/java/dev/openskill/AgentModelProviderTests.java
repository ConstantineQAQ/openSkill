package dev.openskill;

import static org.junit.jupiter.api.Assertions.*;

import dev.openskill.domain.ApiException;
import dev.openskill.infrastructure.AgentModelProvider;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.*;

class AgentModelProviderTests {
  @TempDir Path temp;

  private AgentModelProvider local(String content) throws Exception {
    Path home = Files.createDirectory(temp.resolve("personal"));
    Files.writeString(home.resolve("config.toml"), content);
    return new AgentModelProvider("local-codex", home.toString(), "", "", "", "");
  }

  private Map<String, String> configure(AgentModelProvider provider, Map<String, String> env)
      throws Exception {
    Map<String, String> child = new HashMap<>();
    child.put("PATH", "/usr/bin:/bin");
    provider.configure(Files.createTempDirectory(temp, "execution-"), env, child);
    return child;
  }

  private TomlParseResult config(Map<String, String> child) throws Exception {
    var config = Toml.parse(Path.of(child.get("CODEX_HOME"), "config.toml"));
    assertFalse(config.hasErrors());
    return config;
  }

  @Test
  void localConfigBearerIsSelectedWithoutPersonalPluginsOrSecretsOnDisk() throws Exception {
    var provider =
        local(
            """
            model_provider = "custom.provider"
            model = "selected-model"
            model_reasoning_effort = "high"
            notify = ["must-not-run"]
            model_catalog_json = "/must-not-read"
            [model_providers."custom.provider"]
            name = "Private provider"
            base_url = "https://fixture.invalid/v1"
            wire_api = "responses"
            requires_openai_auth = false
            experimental_bearer_token = "fixture-only-secret"
            [mcp_servers.private]
            command = "must-not-run"
            [plugins.unrelated]
            enabled = true
            """);
    var child = configure(provider, Map.of("DB_PASSWORD", "must-not-copy"));
    var config = config(child);
    assertEquals("selected-model", config.getString("model"));
    assertEquals("high", config.getString("model_reasoning_effort"));
    assertEquals("skill_platform", config.getString("model_provider"));
    assertEquals(
        "https://fixture.invalid/v1", config.getString("model_providers.skill_platform.base_url"));
    assertEquals("fixture-only-secret", child.get("SKILL_RUNNER_MODEL_TOKEN"));
    assertFalse(child.containsKey("DB_PASSWORD"));
    for (String forbidden : List.of("notify", "mcp_servers", "plugins", "model_catalog_json"))
      assertFalse(config.contains(forbidden));
    Path file = Path.of(child.get("CODEX_HOME"), "config.toml");
    assertFalse(Files.readString(file).contains("fixture-only-secret"));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertEquals(
        "rwx------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
    assertEquals("none", config.getString("shell_environment_policy.inherit"));
    assertFalse(
        config.getTable("shell_environment_policy.set").contains("SKILL_RUNNER_MODEL_TOKEN"));
    assertEquals(child.get("HOME"), config.getString("shell_environment_policy.set.HOME"));
    // 原配置不变，且第二次运行可获取 本地 Codex 配置 已切换的模型。
    Path original = temp.resolve("personal/config.toml");
    Files.writeString(
        original, Files.readString(original).replace("selected-model", "switched-model"));
    assertEquals("switched-model", config(configure(provider, Map.of())).getString("model"));
  }

  @Test
  void activeProfileEnvironmentKeyAndHeadersAreHandled() throws Exception {
    var provider =
        local(
            """
            model_provider = "not-selected"
            model = "old"
            profile = "work"
            [profiles.work]
            model_provider = "selected"
            model = "profile-model"
            [model_providers.selected]
            base_url = "https://fixture.invalid/v1"
            env_key = "VENDOR_KEY"
            [model_providers.selected.http_headers]
            "X-Static" = "header-secret"
            [model_providers.selected.env_http_headers]
            "X-Tenant" = "TENANT_HEADER"
            """);
    var child =
        configure(provider, Map.of("VENDOR_KEY", "env-secret", "TENANT_HEADER", "tenant-value"));
    var config = config(child);
    assertEquals("profile-model", config.getString("model"));
    assertEquals("env-secret", child.get("SKILL_RUNNER_MODEL_TOKEN"));
    TomlTable headers = config.getTable("model_providers.skill_platform.env_http_headers");
    assertEquals("header-secret", child.get(headers.getString("X-Static")));
    assertEquals("tenant-value", child.get(headers.getString("X-Tenant")));
    assertFalse(
        Files.readString(Path.of(child.get("CODEX_HOME"), "config.toml"))
            .contains("header-secret"));
    assertFalse(child.containsKey("VENDOR_KEY"));
  }

  @Test
  void serverIsIndependentFromCodexHomeAndEscapesTomlStrings() throws Exception {
    var provider =
        new AgentModelProvider(
            "server",
            "/does-not-exist",
            "https://fixture.invalid/v1",
            "model\"\nname",
            "server-secret",
            "medium");
    var child = configure(provider, Map.of("CODEX_HOME", "/must-not-read"));
    var config = config(child);
    assertEquals("model\"\nname", config.getString("model"));
    assertEquals("server-secret", child.get("SKILL_RUNNER_MODEL_TOKEN"));
    assertEquals("responses", config.getString("model_providers.skill_platform.wire_api"));
    assertFalse(config.getBoolean("model_providers.skill_platform.requires_openai_auth"));
  }

  @Test
  void invalidTomlDoesNotExposeOriginalErrorOrSecret() throws Exception {
    var provider = local("model = \"leak-this-secret");
    var error = assertThrows(ApiException.class, () -> configure(provider, Map.of()));
    assertEquals("AGENT_PROVIDER_NOT_CONFIGURED", error.code());
    assertFalse(error.toString().contains("leak-this-secret"));
    assertNull(error.getCause());
  }

  @Test
  void missingEnvironmentCredentialGivesActionableFailure() throws Exception {
    var provider =
        local(
            """
            model_provider = "selected"
            model = "model"
            [model_providers.selected]
            base_url = "https://fixture.invalid/v1"
            env_key = "NOT_IN_JAVA"
            """);
    var error = assertThrows(ApiException.class, () -> configure(provider, Map.of()));
    assertTrue(error.getMessage().contains("env_key"));
  }

  @Test
  void missingServerConfigNeverFallsBackToLocal() {
    var provider = new AgentModelProvider("server", "/must-not-read", "", "", "", "");
    var error = assertThrows(ApiException.class, () -> configure(provider, Map.of()));
    assertTrue(error.getMessage().contains("SKILL_MODEL_BASE_URL"));
  }

  @Test
  void missingLocalFileHasClearError() {
    var provider = new AgentModelProvider("local-codex", "/does-not-exist", "", "", "", "");
    var error = assertThrows(ApiException.class, () -> configure(provider, Map.of()));
    assertTrue(error.getMessage().contains("SKILL_AGENT_CODEX_HOME"));
  }

  @Test
  void unknownSourceNeverFallsBack() {
    var provider = new AgentModelProvider("typo", "", "", "", "", "");
    assertThrows(ApiException.class, () -> configure(provider, Map.of()));
  }
}
