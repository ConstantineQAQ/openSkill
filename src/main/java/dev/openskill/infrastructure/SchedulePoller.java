package dev.openskill.infrastructure;

import dev.openskill.application.ScheduleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "skill.worker.enabled", havingValue = "true")
public class SchedulePoller {
  private final ScheduleService schedules;

  public SchedulePoller(ScheduleService schedules) {
    this.schedules = schedules;
  }

  @Scheduled(fixedDelayString = "${skill.schedule.poll-ms:1000}")
  public void poll() {
    schedules.tick(java.time.Instant.now());
  }
}
