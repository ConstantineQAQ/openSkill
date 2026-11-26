package dev.openskill.application;

import static dev.openskill.infrastructure.Documents.Kind.CREDENTIAL;

import dev.openskill.domain.*;
import dev.openskill.infrastructure.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CredentialService {
  private final Documents docs;
  private final Transactions tx;
  private final Audit audit;
  private final ObjectProvider<CredentialVerifier> verifier;

  public CredentialService(
      Documents docs, Transactions tx, Audit audit, ObjectProvider<CredentialVerifier> verifier) {
    this.docs = docs;
    this.tx = tx;
    this.audit = audit;
    this.verifier = verifier;
  }

  public ObjectNode save(String id, JsonNode body) {
    return tx.run(
        () -> {
          ObjectNode value =
              Json.obj(
                  "id",
                  id == null ? Json.id() : id,
                  "name",
                  body.path("name"),
                  "secretRef",
                  body.path("secretRef"));
          ObjectNode saved =
              id == null
                  ? docs.insert(CREDENTIAL, value.path("id").asText(), value, "active")
                  : docs.update(CREDENTIAL, id, value, "active", body.path("revision").asLong());
          audit.record("credential.save", saved.path("id").asText());
          return saved;
        });
  }

  public ObjectNode delete(String id) {
    return tx.run(
        () -> {
          docs.get(CREDENTIAL, id);
          docs.delete(CREDENTIAL, id);
          audit.record("credential.delete", id);
          return Json.obj("ok", true);
        });
  }

  public ObjectNode test(String id) {
    ObjectNode c = docs.get(CREDENTIAL, id);
    CredentialVerifier backend = verifier.getIfAvailable();
    if (backend == null)
      throw new ApiException(503, "CREDENTIAL_BACKEND_UNCONFIGURED", "尚未接入企业凭据中心，不会模拟凭据测试成功");
    ObjectNode result = backend.test(c.path("secretRef").asText());
    audit.record("credential.test", id);
    return result;
  }
}
