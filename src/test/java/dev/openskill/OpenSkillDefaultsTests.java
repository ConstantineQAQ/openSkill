package dev.openskill;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class OpenSkillDefaultsTests {
  private Properties properties(String resource) throws IOException {
    Properties properties = new Properties();
    try (var input = getClass().getResourceAsStream("/" + resource)) {
      properties.load(input);
    }
    return properties;
  }

  @Test
  void applicationHasIndependentNameAndPort() throws IOException {
    var config = properties("application.properties");
    assertEquals("openSkill", config.getProperty("spring.application.name"));
    assertEquals("4312", config.getProperty("server.port"));
    assertEquals("local", config.getProperty("spring.profiles.default"));
  }

  @Test
  void localStartupDoesNotEnableAnAgentOrReadPersonalModelConfiguration() throws IOException {
    var config = properties("application-local.properties");
    assertEquals("127.0.0.1", config.getProperty("server.address"));
    assertEquals("${SKILL_AGENT_MODE:disabled}", config.getProperty("skill.agent.mode"));
    assertEquals("${SKILL_MODEL_SOURCE:server}", config.getProperty("skill.agent.provider.source"));
    assertTrue(config.getProperty("spring.datasource.url").startsWith("jdbc:h2:file:./data/openskill;"));
  }

  @Test
  void serverTemplateUsesTrustedIdentityAndDatabaseWorker() throws IOException {
    var config = properties("application-server.properties");
    assertEquals("container", config.getProperty("skill.auth.mode"));
    assertEquals("database", config.getProperty("skill.dispatcher.mode"));
    assertEquals("disabled", config.getProperty("skill.agent.mode"));
    assertEquals("false", config.getProperty("skill.demo.enabled"));
    assertEquals("never", config.getProperty("spring.sql.init.mode"));
    assertEquals("127.0.0.1", config.getProperty("server.address"));
  }
}
