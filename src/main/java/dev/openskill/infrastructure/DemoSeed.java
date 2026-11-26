package dev.openskill.infrastructure;

import dev.openskill.application.CatalogService;
import dev.openskill.domain.Json;
import dev.openskill.security.Actor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "skill.demo.enabled", havingValue = "true")
public class DemoSeed implements ApplicationRunner {
  private final Documents docs;
  private final CatalogService catalog;
  private final Transactions tx;

  public DemoSeed(Documents docs, CatalogService catalog, Transactions tx) {
    this.docs = docs;
    this.catalog = catalog;
    this.tx = tx;
  }

  public void run(ApplicationArguments args) throws Exception {
    Actor.set(new Actor("local", "local-developer"));
    try (var in = new ClassPathResource("demo-seed.json").getInputStream()) {
      var seed = Json.MAPPER.readTree(in);
      tx.run(
          () -> {
            if (docs.all(Documents.Kind.ASSET).isEmpty()) {
              seed.path("skills").forEach(s -> catalog.persist(Json.copy(s)));
              catalog.saveWorkflow(seed.path("workflow"));
            }
          });
    } finally {
      Actor.clear();
    }
  }
}
