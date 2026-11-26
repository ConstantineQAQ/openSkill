package dev.openskill.infrastructure;

import dev.openskill.application.RunService;
import dev.openskill.domain.Json;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "skill.worker.enabled", havingValue = "true")
public class ExecutionWorker {
  private final JdbcTemplate jdbc;
  private final RunDispatcher dispatcher;
  private final RunService runs;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore slots = new Semaphore(8);

  public ExecutionWorker(JdbcTemplate jdbc, RunDispatcher dispatcher, RunService runs) {
    this.jdbc = jdbc;
    this.dispatcher = dispatcher;
    this.runs = runs;
  }

  @Scheduled(fixedDelayString = "${skill.worker.poll-ms}")
  public void poll() {
    long now = System.currentTimeMillis();
    var ids =
        jdbc.queryForList(
            "SELECT run_id FROM execution_outbox WHERE (status='pending' AND available_at<=?) OR"
                + " (status='sending' AND lease_until<?) ORDER BY available_at LIMIT 8",
            String.class,
            now,
            now);
    for (String id : ids) {
      if (!slots.tryAcquire()) break;
      String lease = Json.id();
      int n =
          jdbc.update(
              "UPDATE execution_outbox SET status='sending',lease_owner=?,lease_until=? WHERE"
                  + " run_id=? AND ((status='pending' AND available_at<=?) OR (status='sending' AND"
                  + " lease_until<?))",
              lease,
              now + 60000,
              id,
              now,
              now);
      if (n == 0) {
        slots.release();
        continue;
      }
      executor.submit(
          () -> {
            try {
              dispatcher.dispatch(id);
              jdbc.update(
                  "UPDATE execution_outbox SET status='sent' WHERE run_id=? AND lease_owner=?",
                  id,
                  lease);
            } catch (Exception e) {
              jdbc.update(
                  "UPDATE execution_outbox SET"
                      + " status='pending',attempts=attempts+1,available_at=?,last_error=? WHERE"
                      + " run_id=? AND lease_owner=?",
                  System.currentTimeMillis() + 10000,
                  e.getClass().getSimpleName(),
                  id,
                  lease);
            } finally {
              slots.release();
            }
          });
    }
  }

  @Scheduled(fixedDelay = 10000)
  public void heartbeat() {
    runs.heartbeat();
    runs.recover();
  }

  @PreDestroy
  public void close() {
    executor.shutdown();
  }
}
