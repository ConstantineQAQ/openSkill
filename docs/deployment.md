# openSkill deployment notes

## Local development

Use JDK 21 and run `./mvnw clean verify`. The wrapper downloads Maven on first use. Public dependencies are declared in `pom.xml`; no private repository is required by this project. A populated cache allows `./mvnw -o clean verify`.

Build outputs:

- `target/openskill-0.1.0-SNAPSHOT.jar`: application server.
- `target/openskill-0.1.0-SNAPSHOT-skill-runtime.jar`: standalone deterministic CLI, also used by the ZIP exporter.

Run from the project directory. The default `local` profile uses an independent `data/openskill` H2 database, seeds synthetic examples, and binds to `127.0.0.1:4312`. `SKILL_LOG_DIR` controls the log directory. Existing data and logs must not be added to Git.

Local authentication is a fixed development identity. It is not suitable for shared access, even behind a reverse proxy. Test scripts use temporary servers and isolated databases, not the developer's persistent H2 database.

## Optional agent execution

Agent execution is disabled by default in both local and server profiles. Importing packages and running deterministic skills remains available.

For trusted, single-user local use only:

| Variable | Purpose |
| --- | --- |
| `SKILL_AGENT_MODE=codex` | Explicitly enable the local agent adapter |
| `SKILL_AGENT_COMMAND` | CLI path; defaults to `codex` |
| `SKILL_MODEL_SOURCE=server` | Read model configuration from the server environment; this does not change the authentication profile |
| `SKILL_MODEL_BASE_URL` | Provider's Responses-compatible base URL |
| `SKILL_MODEL_NAME` | Provider's model identifier |
| `SKILL_MODEL_API_KEY` | Secret injected into the Java process environment |
| `SKILL_MODEL_REASONING_EFFORT` | Optional, only if the provider/model supports it |
| `SKILL_AGENT_NETWORK_ENABLED` | Tool network access; defaults to `false` |

The existing adapter invokes `codex exec` with `--ignore-rules`, `--ephemeral`, `--sandbox workspace-write`, `--output-schema`, and an isolated configuration. CLI compatibility must be checked against the installed version. The repository's automated agent tests use a fake CLI and do not establish compatibility with all real CLI versions/providers.

Opt-in `SKILL_MODEL_SOURCE=local-codex` reads the selected API-key provider from `SKILL_AGENT_CODEX_HOME`, then `CODEX_HOME`, then the user's `.codex` directory. It does not copy personal `auth.json`, plugins or MCP configuration. If a provider uses `env_key`, that variable must exist in the Java process environment. This mode does not support account-login authentication. Missing configuration fails explicitly; there is no automatic provider fallback.

Each task gets a temporary isolated configuration and resource directory. Model credentials are injected into the CLI environment, not package definitions or command-line arguments. Tool shells receive a restricted environment. This is **not full filesystem/network isolation**: only run trusted packages. The model provider may receive task data and accessed resources.

The server/container identity mode deliberately refuses local CLI execution. Do not switch a shared service to local authentication to bypass this boundary. An isolated multi-tenant runner is future work.

## MySQL/server configuration template

`application-server.properties` is an integration starting point, not a turnkey production deployment:

```sh
# Inject these through your deployment secret manager:
# SKILL_MYSQL_URL, SKILL_MYSQL_USERNAME, SKILL_MYSQL_PASSWORD
java -jar target/openskill-0.1.0-SNAPSHOT.jar --spring.profiles.active=server
```

Before starting, provision and review `src/main/resources/db/schema.sql` for the target MySQL environment. Automatic schema initialization is disabled. Configure TLS and database permissions according to your environment. MySQL has not been exercised by the local H2 test suite.

A trusted identity integration must populate `HttpServletRequest.getUserPrincipal()`, the `skill-*` roles and the **server-side** `skill.workspace` request attribute. User-supplied identity headers are not sufficient. Without the integration, API access fails closed. Session writes require the CSRF token exposed by `/api/auth/csrf`.

The server template remains bound to loopback. Only change the binding when the identity, ingress, TLS and network boundaries have been implemented and tested. It does not implement login or a complete deployment topology.

## Reliability and operations

The application persists asynchronous run snapshots and an outbox in the same transaction. A database worker dispatches run IDs, claims execution leases, records step events, and detects stale work. Recovery does not claim to roll back or exactly-once replay external side effects. Scheduled runs use database polling rather than an external scheduler.

Health and Prometheus endpoints live under `/internal/actuator`; identity filtering also applies to these routes. Use an authenticated operational path in server mode. Review log, event and run retention before shared use. The project does not yet supply quotas, distributed concurrency limits, database migrations, production alerting, or load-test evidence.

## Frontend

No frontend is included. A separately developed UI can be served on the same origin or through Spring's static-resource configuration. It must implement the actual API contract and the session/CSRF handshake for container identity mode. Do not point the public repository at a developer's private frontend path.
