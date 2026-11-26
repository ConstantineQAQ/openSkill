package dev.openskill.application;

import dev.openskill.domain.ApiException;
import dev.openskill.infrastructure.SkillPackages;
import dev.openskill.infrastructure.Transactions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.node.ObjectNode;

@Service
public class SkillPackageService {
  private static final Logger LOG = LogManager.getLogger(SkillPackageService.class);
  private final CatalogService catalog;
  private final SkillPackages packages;
  private final Transactions tx;

  public SkillPackageService(CatalogService catalog, SkillPackages packages, Transactions tx) {
    this.catalog = catalog;
    this.packages = packages;
    this.tx = tx;
  }

  public ObjectNode importZip(MultipartFile file) {
    LOG.info("开始导入 Skill 完整包，文件大小={} 字节", file.getSize());
    String markdown;
    byte[] archive;
    try {
      Path temporary = Files.createTempFile("skill-import-", ".zip");
      try {
        file.transferTo(temporary);
        markdown = readMarkdown(temporary);
        archive = Files.readAllBytes(temporary);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (ZipException e) {
      LOG.warn("Skill 完整包读取失败，ZIP 格式无法解析");
      throw new ApiException(400, "PACKAGE_IMPORT_FAILED", "无法读取 Skill ZIP 包");
    } catch (IOException e) {
      LOG.error("Skill 完整包暂存失败", e);
      throw new ApiException(500, "PACKAGE_STORAGE_FAILED", "Skill 包暂存失败，请稍后重试");
    }
    ObjectNode skill =
        tx.run(
            () -> {
              ObjectNode imported = catalog.importMarkdown(markdown);
              packages.insert(
                  imported.path("id").asText(), imported.path("version").asInt(), archive);
              return imported;
            });
    LOG.info(
        "Skill 完整包导入成功，技能ID={}，版本={}，原包大小={} 字节",
        skill.path("id").asText(),
        skill.path("version").asInt(),
        archive.length);
    return skill;
  }

  private String readMarkdown(Path path) throws IOException {
    // 通过中央目录定位说明，不遍历解压资源，也不将包内路径写入文件系统。
    try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
      var entries =
          zip.stream()
              .filter(entry -> !entry.isDirectory())
              .filter(
                  entry ->
                      entry.getName().equals("SKILL.md")
                          || entry.getName().matches("[^/]+/SKILL\\.md"))
              .toList();
      if (entries.size() != 1) throw ApiException.bad("ZIP 根目录或单层 Skill 目录下需要有唯一的 SKILL.md");
      try (var input = zip.getInputStream(entries.getFirst())) {
        // 与现有 Markdown 接口的 100000 字符上限一致，避免无界解压说明文本。
        var reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8);
        var markdown = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while (markdown.length() <= 100000
            && (count = reader.read(buffer, 0, Math.min(buffer.length, 100001 - markdown.length())))
                != -1) markdown.append(buffer, 0, count);
        return markdown.toString();
      }
    }
  }
}
