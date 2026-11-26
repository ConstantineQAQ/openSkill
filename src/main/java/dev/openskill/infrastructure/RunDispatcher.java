package dev.openskill.infrastructure;

public interface RunDispatcher {
  void dispatch(String runId) throws Exception;
}
