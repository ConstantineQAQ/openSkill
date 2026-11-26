package dev.openskill.infrastructure;

import dev.openskill.application.RunService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "skill.dispatcher.mode", havingValue = "database")
public class LocalRunDispatcher implements RunDispatcher {
  private final RunService runs;

  public LocalRunDispatcher(RunService runs) {
    this.runs = runs;
  }

  public void dispatch(String id) {
    runs.process(id);
  }
}
