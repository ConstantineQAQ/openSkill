package dev.openskill.infrastructure;

import tools.jackson.databind.node.ObjectNode;

/** Enterprise secret-center integration. Implementations must not return secret material. */
public interface CredentialVerifier {
  ObjectNode test(String approvedSecretReference);
}
