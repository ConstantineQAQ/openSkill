package dev.openskill.infrastructure;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Transactions {
  private final TransactionTemplate template;

  public Transactions(PlatformTransactionManager manager) {
    template = new TransactionTemplate(manager);
  }

  public <T> T run(Supplier<T> action) {
    return template.execute(s -> action.get());
  }

  public void run(Runnable action) {
    template.executeWithoutResult(s -> action.run());
  }
}
