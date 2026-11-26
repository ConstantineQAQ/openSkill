package dev.openskill.infrastructure;

import dev.openskill.domain.ApiException;
import dev.openskill.domain.Json;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tomlj.*;

/** 仅由服务端选择供应商；不读取 Skill 或接口输入中的模型配置。 */
@Component
public class AgentModelProvider {
  private final String source;
  private final String codexHome;
  private final String baseUrl;
  private final String model;
  private final String apiKey;
  private final String reasoningEffort;

  public AgentModelProvider(
      @Value("${skill.agent.provider.source:server}") String source,
      @Value("${skill.agent.provider.codex-home}") String codexHome,
      @Value("${skill.agent.provider.base-url:}") String baseUrl,
      @Value("${skill.agent.provider.model:}") String model,
      @Value("${skill.agent.provider.api-key:}") String apiKey,
      @Value("${skill.agent.provider.reasoning-effort:}") String reasoningEffort) {
    this.source = source;
    this.codexHome = codexHome;
    this.baseUrl = baseUrl;
    this.model = model;
    this.apiKey = apiKey;
    this.reasoningEffort = reasoningEffort;
  }

  public String source() {
    return source;
  }

  /** 每次执行重新读取本地选中供应商，使供应商切换在下次试运行生效。 */
  public void configure(Path directory, Map<String, String> inherited, Map<String, String> child) {
    try {
      Settings settings =
          switch (source) {
            case "local-codex" -> local(inherited);
            case "server" -> server();
            default -> throw problem("不支持的模型配置来源，请设置 SKILL_MODEL_SOURCE=local-codex 或 server");
          };
      Path home = privateDirectory(directory.resolve("home"));
      Path runtime = privateDirectory(directory.resolve("codex-home"));
      child.put("HOME", home.toString());
      child.put("CODEX_HOME", runtime.toString());
      // 临时配置中不写密钥，进程参数也不携带密钥。工具子进程不继承此环境变量。
      String tokenVariable = "SKILL_RUNNER_MODEL_TOKEN";
      child.put(tokenVariable, settings.token);
      StringBuilder config = new StringBuilder();
      value(config, "model_provider", "skill_platform");
      value(config, "model", settings.model);
      if (settings.reasoning != null && !settings.reasoning.isBlank())
        value(config, "model_reasoning_effort", settings.reasoning);
      config.append("disable_response_storage = true\n");
      config.append("[model_providers.skill_platform]\n");
      value(config, "name", "Skill Platform");
      value(config, "base_url", settings.baseUrl);
      value(config, "wire_api", settings.wireApi);
      value(config, "env_key", tokenVariable);
      config.append("requires_openai_auth = false\n");
      if (!settings.headers.isEmpty()) {
        config.append("[model_providers.skill_platform.env_http_headers]\n");
        int index = 0;
        for (var header : settings.headers.entrySet()) {
          String variable = "SKILL_RUNNER_HEADER_" + index++;
          value(config, header.getKey(), variable);
          child.put(variable, header.getValue());
        }
      }
      // 与 Java 环境白名单是两层独立策略：模型凭据只供 CLI，不继承到工具 shell。
      config.append("[shell_environment_policy]\ninherit = \"none\"\n");
      config.append("[shell_environment_policy.set]\n");
      for (String key : List.of("HOME", "PATH", "TMPDIR", "LANG", "LC_ALL", "SYSTEMROOT"))
        if (child.containsKey(key)) value(config, key, child.get(key));
      Path file =
          Files.createFile(
              runtime.resolve("config.toml"),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(file, config);
    } catch (ApiException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      // TOML 解析错误可能包含密钥所在行，禁止传播原始错误及 cause。
      throw problem("无法读取或生成模型配置，请检查 Codex 配置文件格式、路径与临时目录权限；未加载个人完整配置");
    }
  }

  private Settings server() {
    if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank())
      throw problem("服务端模型未配置完整，请设置 SKILL_MODEL_BASE_URL、SKILL_MODEL_NAME 和 SKILL_MODEL_API_KEY");
    return new Settings(model, baseUrl, "responses", apiKey, reasoningEffort, Map.of());
  }

  private Settings local(Map<String, String> environment) throws IOException {
    Path file = Path.of(codexHome).resolve("config.toml");
    if (!Files.isRegularFile(file))
      throw problem("未找到本地 Codex 配置，请先在本地 Codex 配置中切换到所需供应商，或设置 SKILL_AGENT_CODEX_HOME");
    TomlParseResult root = Toml.parse(file);
    if (root.hasErrors()) throw problem("本地 Codex 配置格式错误，请检查 config.toml；错误原文已隐藏");
    TomlTable profile = null;
    String selectedProfile = root.getString("profile");
    if (selectedProfile != null) profile = root.getTable(List.of("profiles", selectedProfile));
    String providerName = selected(root, profile, "model_provider");
    String selectedModel = selected(root, profile, "model");
    if (providerName == null || selectedModel == null)
      throw problem("本地 Codex 未配置第三方供应商或模型，请先在本地 Codex 配置中选择供应商");
    TomlTable provider = root.getTable(List.of("model_providers", providerName));
    if (provider == null) throw problem("本地选中的第三方供应商配置不存在，请检查本地 Codex 配置中的 model_providers");
    if (Boolean.TRUE.equals(provider.getBoolean("requires_openai_auth")))
      throw problem("当前供应商要求 OpenAI 登录；请在本地 Codex 配置中选择使用第三方 API 密钥的供应商");
    String token = provider.getString("experimental_bearer_token");
    if (token == null || token.isBlank()) {
      String variable = provider.getString("env_key");
      token = variable == null ? null : environment.get(variable);
    }
    if (token == null || token.isBlank())
      throw problem("本地供应商缺少 API 密钥：请检查本地 Codex 配置中的供应商密钥，或将其 env_key 对应环境变量配置到 Java 启动环境");
    String url = provider.getString("base_url");
    if (url == null || url.isBlank()) throw problem("本地第三方供应商缺少 base_url，请检查本地 Codex 配置");
    Map<String, String> headers = new LinkedHashMap<>();
    TomlTable literals = provider.getTable("http_headers");
    if (literals != null)
      for (String key : literals.keySet()) headers.put(key, literals.getString(List.of(key)));
    TomlTable variables = provider.getTable("env_http_headers");
    if (variables != null) {
      for (String key : variables.keySet()) {
        String value = environment.get(variables.getString(List.of(key)));
        if (value == null) throw problem("本地供应商的 env_http_headers 环境变量未在 Java 启动环境中配置");
        headers.put(key, value);
      }
    }
    return new Settings(
        selectedModel,
        url,
        provider.getString("wire_api", () -> "responses"),
        token,
        selected(root, profile, "model_reasoning_effort"),
        headers);
  }

  private static String selected(TomlTable root, TomlTable profile, String key) {
    return profile != null && profile.contains(key) ? profile.getString(key) : root.getString(key);
  }

  private static Path privateDirectory(Path path) throws IOException {
    return Files.createDirectory(
        path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }

  private static void value(StringBuilder config, String key, String value) {
    // JSON 字符串转义是此处 TOML basic string 所需的转义子集，包括引号与换行。
    config.append(Json.node(key)).append(" = ").append(Json.node(value)).append('\n');
  }

  private static ApiException problem(String message) {
    return new ApiException(503, "AGENT_PROVIDER_NOT_CONFIGURED", message);
  }

  // 不使用自动生成 toString 的 record，避免调试日志意外打印凭据。
  private static final class Settings {
    final String model, baseUrl, wireApi, token, reasoning;
    final Map<String, String> headers;

    Settings(
        String model,
        String baseUrl,
        String wireApi,
        String token,
        String reasoning,
        Map<String, String> headers) {
      this.model = model;
      this.baseUrl = baseUrl;
      this.wireApi = wireApi;
      this.token = token;
      this.reasoning = reasoning;
      this.headers = headers;
    }
  }
}
