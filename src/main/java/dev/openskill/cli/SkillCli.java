package dev.openskill.cli;

import dev.openskill.domain.*;
import dev.openskill.engine.*;
import java.nio.file.*;
import java.util.List;

/** A standalone Java entry point using exactly the server's mapping and execution engine. */
public final class SkillCli {
  private SkillCli() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: java -jar skill-runtime.jar definition.json input.json [--demo]");
      System.exit(2);
    }
    var skill = Json.parse(Files.readString(Path.of(args[0])));
    var input = Json.parse(Files.readString(Path.of(args[1])));
    var engine =
        new SkillEngine(
            new MappingEngine(),
            new WorkflowRules(),
            List.of(),
            args.length > 2 && args[2].equals("--demo"));
    try {
      System.out.println(engine.execute(skill, input, () -> {}));
    } catch (ApiException e) {
      System.err.println(Json.obj("error", e.getMessage(), "code", e.code()));
      System.exit(1);
    }
  }
}
