package dev.openskill.api;

import tools.jackson.databind.JsonNode;

public final class Requests {
  private Requests() {}

  public record WorkflowBody(JsonNode workflow) {}

  public record RunBody(JsonNode workflow, JsonNode input, String upTo) {}

  public record NodeRunBody(String skillId, int version, JsonNode input) {}

  public record ImportBody(String markdown) {}

  public record MappingBody(JsonNode binding, JsonNode context) {}

  public record EnableBody(boolean enabled) {}

  public record AsyncRunBody(JsonNode target, JsonNode input, Integer timeoutSeconds) {}

  public record CredentialBody(String name, String secretRef, Long revision) {}

  public record ArchiveBody(boolean archived, long revision) {}
}
