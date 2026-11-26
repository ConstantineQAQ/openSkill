package dev.openskill.application;

import dev.openskill.domain.*;
import dev.openskill.infrastructure.Audit;
import dev.openskill.infrastructure.SkillPackages;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class ExportService {
  private static final Logger LOG = LogManager.getLogger(ExportService.class);
  private final SkillPackages packages;
  private final Path runtime;
  private final CatalogService catalog;
  private final Audit audit;

  public ExportService(
      @Value(
              "${skill.export.runtime-jar:target/openskill-0.1.0-SNAPSHOT-skill-runtime.jar}")
          String runtime,
      CatalogService catalog,
      Audit audit,
      SkillPackages packages) {
    this.runtime = Path.of(runtime);
    this.catalog = catalog;
    this.audit = audit;
    this.packages = packages;
  }

  public byte[] bundle(JsonNode skill) {
    LOG.info("开始导出 Skill，技能ID={}，版本={}", skill.path("id").asText(), skill.path("version").asInt());
    var original = packages.find(skill.path("id").asText(), skill.path("version").asInt());
    if (original.isPresent()) return exported(skill, original.get(), "原始完整包");
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      boolean instructions = "instructions".equals(skill.path("operation").asText());
      String markdown =
          instructions
              ? skill.path("config").path("markdown").asText()
              : "---\nname: "
                  + skill.path("name").toString()
                  + "\ndescription: "
                  + skill.path("description").toString()
                  + "\n---\n# "
                  + skill.path("title").asText()
                  + "\n\n"
                  + "Run the immutable definition with Java 21: `java -jar skill-runtime.jar"
                  + " definition.json input.json`.\n"
                  + "Read README.md before running.\n";
      entry(zip, "SKILL.md", markdown.getBytes(StandardCharsets.UTF_8));
      entry(zip, "definition.json", skill.toString().getBytes(StandardCharsets.UTF_8));
      var dependencies = Json.arr();
      catalog.walk(skill, dependencies);
      entry(zip, "dependencies.json", dependencies.toString().getBytes(StandardCharsets.UTF_8));
      if (!instructions) {
        if (!Files.isRegularFile(runtime))
          throw new ApiException(
              503,
              "EXPORT_RUNTIME_UNAVAILABLE",
              "请先执行 mvn package，并配置 skill.export.runtime-jar 指向 Java CLI 产物");
        zip.putNextEntry(new ZipEntry("skill-runtime.jar"));
        Files.copy(runtime, zip);
        zip.closeEntry();
        JsonNode sample = skill.path("workflow").path("sampleInput");
        entry(
            zip,
            "input.json",
            (sample.isMissingNode() ? "{}" : sample.toString()).getBytes(StandardCharsets.UTF_8));
        entry(
            zip,
            "README.md",
            ("# Java Skill bundle\n\n"
                 + "Requires Java 21. Run:\n\n"
                 + "```sh\n"
                 + "java -jar skill-runtime.jar definition.json input.json\n"
                 + "```\n\n"
                 + "This package shares the Java server engine; Node.js is not used. Demo fixtures"
                 + " require an explicit `--demo` argument. Server-side adapters are not available"
                 + " in the standalone CLI. This ZIP carries definitions, not credentials or"
                 + " scheduling. Instruction skills (including those nested in composites) require"
                 + " the server-side agent executor and original resource packages; this standalone"
                 + " CLI does not include an agent or model credentials.\n")
                .getBytes(StandardCharsets.UTF_8));
      }
      zip.finish();
      return exported(skill, bytes.toByteArray(), "生成包");
    } catch (IOException e) {
      LOG.error("Skill 导出失败，技能ID={}", skill.path("id").asText(), e);
      throw new ApiException(500, "EXPORT_FAILED", "生成 Skill 包失败");
    }
  }

  private byte[] exported(JsonNode skill, byte[] content, String source) {
    audit.record("skill.export", skill.path("id").asText() + ":" + skill.path("version").asInt());
    LOG.info(
        "Skill 导出成功，技能ID={}，版本={}，来源={}，包大小={} 字节",
        skill.path("id").asText(),
        skill.path("version").asInt(),
        source,
        content.length);
    return content;
  }

  private void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }
}
