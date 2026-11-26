package dev.openskill.application;

import static dev.openskill.infrastructure.Documents.Kind.SCHEDULE;

import dev.openskill.domain.*;
import dev.openskill.engine.WorkflowRules;
import dev.openskill.infrastructure.*;
import dev.openskill.security.Actor;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ScheduleService {
  private final Documents docs;
  private final Transactions tx;
  private final CatalogService catalog;
  private final RunService runs;
  private final WorkflowRules rules;
  private final Audit audit;
  private final JdbcTemplate jdbc;

  public ScheduleService(
      Documents docs,
      Transactions tx,
      CatalogService catalog,
      RunService runs,
      WorkflowRules rules,
      Audit audit,
      JdbcTemplate jdbc) {
    this.docs = docs;
    this.tx = tx;
    this.catalog = catalog;
    this.runs = runs;
    this.rules = rules;
    this.audit = audit;
    this.jdbc = jdbc;
  }

  public Instant next(JsonNode config, Instant after) {
    ZoneId zone = ZoneId.of(config.path("timezone").asText());
    return switch (config.path("frequency").asText()) {
      case "once" -> Instant.parse(config.path("at").asText());
      case "interval" -> {
        int minutes = config.path("intervalMinutes").asInt();
        if (minutes < 1 || minutes > 10080) throw ApiException.bad("间隔必须为 1–10080 分钟");
        yield after.plusSeconds(minutes * 60L);
      }
      case "daily" -> {
        LocalTime time = LocalTime.parse(config.path("time").asText());
        // Minute iteration preserves the original DST policy: skip a missing local time,
        // and allow both occurrences of a repeated local time.
        Instant candidate =
            after.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(60);
        int i = 0;
        while (i++ < 60 * 49) {
          if (candidate.atZone(zone).toLocalTime().equals(time)) break;
          candidate = candidate.plusSeconds(60);
        }
        if (i > 60 * 49) throw ApiException.bad("无法计算下一次执行时间");
        yield candidate;
      }
      default -> throw ApiException.bad("不支持的调度类型");
    };
  }

  public ObjectNode relative(JsonNode input, String timezone, Instant now) {
    ObjectNode result = Json.copy(input);
    LocalDate date = now.atZone(ZoneId.of(timezone)).toLocalDate();
    input
        .properties()
        .forEach(
            e -> {
              if (e.getValue().has("$relative")) {
                LocalDate resolved =
                    switch (e.getValue().path("$relative").asText()) {
                      case "today" -> date;
                      case "yesterday" -> date.minusDays(1);
                      default -> throw ApiException.bad("只支持 today/yesterday 相对日期");
                    };
                result.put(e.getKey(), resolved.toString());
              }
            });
    return result;
  }

  private void executable(JsonNode config) {
    JsonNode skill = catalog.skill(config.path("skillId").asText(), config.path("version").asInt());
    if (!"composite".equals(skill.path("kind").asText()))
      throw ApiException.bad("定时任务需选择已发布的组合 Skill");
    var issues = rules.safety(skill);
    if (!issues.isEmpty()) throw ApiException.bad(String.join("；", issues));
    rules.data(
        relative(config.path("input"), config.path("timezone").asText(), Instant.now()),
        skill.path("inputs"),
        "任务输入");
  }

  public ObjectNode save(String id, JsonNode body) {
    return tx.run(
        () -> {
          executable(body);
          Instant at = next(body, Instant.now());
          if (body.path("frequency").asText().equals("once") && !at.isAfter(Instant.now()))
            throw ApiException.bad("一次性执行时间必须在未来");
          ObjectNode old = id == null ? null : docs.lock(SCHEDULE, id);
          ObjectNode value = Json.copy(body);
          value.put("id", id == null ? Json.id() : id);
          value.put("enabled", old == null || old.path("enabled").asBoolean());
          value.put("identity", Actor.current().user());
          value.put("overlap", "skip");
          value.put("missed", "coalesce");
          value.put("nextAt", at.toString());
          value.put("createdAt", old == null ? Json.now() : old.path("createdAt").asText());
          ObjectNode saved =
              old == null
                  ? docs.insert(SCHEDULE, value.path("id").asText(), value, "enabled")
                  : docs.update(
                      SCHEDULE,
                      id,
                      value,
                      value.path("enabled").asBoolean() ? "enabled" : "paused",
                      body.path("revision").asLong());
          docs.scheduleTime(
              saved.path("id").asText(), saved.path("enabled").asBoolean() ? at.toEpochMilli() : 0);
          audit.record("schedule.save", saved.path("id").asText());
          return saved;
        });
  }

  public ObjectNode enable(String id, boolean enabled) {
    return tx.run(
        () -> {
          ObjectNode value = docs.lock(SCHEDULE, id);
          value.put("enabled", enabled);
          if (enabled) {
            executable(value);
            Instant at = next(value, Instant.now());
            if (!at.isAfter(Instant.now())) throw ApiException.bad("此一次性任务时间已过");
            value.put("nextAt", at.toString());
          }
          ObjectNode saved =
              docs.update(
                  SCHEDULE,
                  id,
                  value,
                  enabled ? "enabled" : "paused",
                  value.path("revision").asLong());
          docs.scheduleTime(
              id, enabled ? Instant.parse(value.path("nextAt").asText()).toEpochMilli() : 0);
          audit.record(enabled ? "schedule.enable" : "schedule.pause", id);
          return saved;
        });
  }

  public ObjectNode delete(String id) {
    return tx.run(
        () -> {
          docs.delete(SCHEDULE, id);
          audit.record("schedule.delete", id);
          return Json.obj("ok", true);
        });
  }

  public ObjectNode preview(JsonNode config) {
    Instant after = Instant.now();
    var times = Json.arr();
    for (int i = 0; i < 5; i++) {
      after = next(config, after);
      times.add(after.toString());
      if (config.path("frequency").asText().equals("once")) break;
    }
    return Json.obj(
        "occurrences",
        times,
        "timezone",
        config.path("timezone"),
        "resolvedInput",
        config.has("input")
            ? relative(config.path("input"), config.path("timezone").asText(), Instant.now())
            : Json.obj());
  }

  public ObjectNode trigger(String id) {
    return tx.run(
        () -> {
          ObjectNode schedule = docs.lock(SCHEDULE, id);
          if (runs.activeSchedule(id)) throw ApiException.conflict("此任务已有执行中的实例");
          return enqueue(schedule, Instant.now());
        });
  }

  private ObjectNode enqueue(JsonNode config, Instant at) {
    executable(config);
    ObjectNode run =
        runs.submit(
            Json.obj(
                "type",
                "skill",
                "skillId",
                config.path("skillId"),
                "version",
                config.path("version")),
            relative(config.path("input"), config.path("timezone").asText(), at),
            300,
            "schedule",
            true,
            config.path("id").asText(),
            null);
    audit.record("schedule.trigger", config.path("id").asText());
    return run;
  }

  public void tick(Instant now) {
    var due =
        jdbc.queryForList(
            "SELECT workspace,owner_id,id FROM schedules WHERE next_at>0 AND next_at<=? ORDER BY"
                + " next_at LIMIT 100",
            now.toEpochMilli());
    for (var row : due) {
      Actor previous = Actor.optional();
      Actor.set(new Actor((String) row.get("workspace"), (String) row.get("owner_id")));
      try {
        tx.run(
            () -> {
              ObjectNode s = docs.lock(SCHEDULE, (String) row.get("id"));
              String id = s.path("id").asText();
              if (!s.path("enabled").asBoolean()
                  || Instant.parse(s.path("nextAt").asText()).isAfter(now)) return null;
              boolean once = s.path("frequency").asText().equals("once");
              s.put("enabled", !once);
              s.put("lastAt", now.toString());
              if (!once) s.put("nextAt", next(s, now).toString());
              if (runs.activeSchedule(id)) {
                s.put("lastOutcome", "skipped_overlap");
                audit.record("schedule.skipped-overlap", id);
              } else {
                s.put("lastRunId", enqueue(s, now).path("id").asText());
                s.put("lastOutcome", "queued");
              }
              docs.update(
                  SCHEDULE, id, s, once ? "completed" : "enabled", s.path("revision").asLong());
              docs.scheduleTime(
                  id, once ? 0 : Instant.parse(s.path("nextAt").asText()).toEpochMilli());
              return null;
            });
      } catch (ApiException e) {
        if (e.status() != 404)
          org.slf4j.LoggerFactory.getLogger(getClass())
              .warn("定时任务分发失败，任务ID={}，错误码={}", row.get("id"), e.code());
      } finally {
        if (previous == null) Actor.clear();
        else Actor.set(previous);
      }
    }
  }
}
