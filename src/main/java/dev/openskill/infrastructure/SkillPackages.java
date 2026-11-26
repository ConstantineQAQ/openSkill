package dev.openskill.infrastructure;

import dev.openskill.domain.Json;
import dev.openskill.security.Actor;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 原始 ZIP 与目录元数据分开存储，列表查询不会加载包体。 */
@Repository
public class SkillPackages {
  private final JdbcTemplate jdbc;

  public SkillPackages(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(String skillId, int version, byte[] content) {
    Actor actor = Actor.current();
    jdbc.update(
        "INSERT INTO skill_packages(workspace,skill_id,version,owner_id,content,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        actor.workspace(),
        skillId,
        version,
        actor.user(),
        content,
        Json.now());
  }

  public Optional<byte[]> find(String skillId, int version) {
    Actor actor = Actor.current();
    return jdbc
        .query(
            "SELECT content FROM skill_packages WHERE workspace=? AND skill_id=? AND version=? AND"
                + " owner_id=?",
            (rs, row) -> rs.getBytes("content"),
            actor.workspace(),
            skillId,
            version,
            actor.user())
        .stream()
        .findFirst();
  }
}
