# openSkill

**Package skills. Compose workflows. Run with traceable results.**

openSkill is a local-first, API-first Java backend for turning reusable skills into versioned, composable workflows. Import `SKILL.md` or ZIP packages, map outputs between steps, run on demand or on a schedule, and export deterministic workflows with a standalone Java runtime.

[简体中文](README.zh-CN.md) · [API reference](docs/api.md) · [Deployment](docs/deployment.md) · [Roadmap](docs/roadmap.md)

> **Early-stage developer preview.** A working local prototype, not a production-ready multi-tenant service. There is no bundled visual editor. Publication rights are confirmed; the repository remains private and the open-source license is undecided; see [publication checklist](docs/publishing.md).

## Why openSkill?

- **Skills as versioned assets:** import Markdown or complete packages, keep immutable versions and original resources, and reuse published composites.
- **Deterministic composition:** explicit input/output contracts, parameter mappings, sequential steps, and bounded nesting.
- **Inspectable runs:** synchronous and asynchronous execution, persisted step results, resumable SSE events, cancellation, and explicit retries.
- **Scheduled execution:** one-time, daily, and interval schedules with explicit time zones and overlap handling.
- **Portable deterministic workflows:** export a ZIP with definitions and a standalone Java CLI; no Node.js runtime required.
- **Optional agent execution:** imported instruction skills can use an explicitly configured local Codex CLI. Agent execution is disabled by default.

Not an arbitrary DAG engine, an autonomous multi-agent platform, a general-purpose script sandbox, or an authentication provider. The notification adapter produces previews; it does not deliver messages. Demo application, alarm, and log skills use synthetic data.

## Quick start

Requirements: JDK 21 and internet access for Maven dependencies (or an existing Maven cache). The Maven wrapper is included; Python 3 is needed only for smoke tests.

```sh
./mvnw clean verify
java -jar target/openskill-0.1.0-SNAPSHOT.jar
```

On Windows use `mvnw.cmd`. The local server listens on **127.0.0.1:4312**, persists H2 data under `data/`, and writes logs under `logs/`. Start from the project directory so the export runtime can be located. Override the port with `--server.port=4313` if needed.

```sh
curl http://127.0.0.1:4312/api/bootstrap
```

### Create and run your first skill

This example needs no model account or external service:

```sh
curl -X POST http://127.0.0.1:4312/api/skills \
  -H 'Content-Type: application/json' \
  --data-binary @examples/greeting-skill.json
```

Copy the returned `id` into the next request:

```sh
curl -X POST http://127.0.0.1:4312/api/node-run \
  -H 'Content-Type: application/json' \
  -d '{"skillId":"<returned-id>","version":1,"input":{"name":"World"}}'
```

The result contains `output.text`: `Hello, World!`.

### Run the regression suite

```sh
./mvnw clean verify
python3 scripts/smoke.py
python3 scripts/smoke-package.py
python3 scripts/smoke-agent.py
```

Local verification on 2026-11-26: **75 tests and all three smoke suites passed**. See [verification scope](docs/verification.md).

The smoke tests launch temporary local servers and use isolated databases. The agent smoke test uses a controlled fake CLI, not a paid model or your personal account.

## Optional instruction-skill execution

Importing and exporting instruction packages does not require a model. Executing them does. Enable execution **only for trusted skills on your own machine**:

```sh
export SKILL_AGENT_MODE=codex
export SKILL_MODEL_SOURCE=server
export SKILL_MODEL_BASE_URL='https://your-provider.example/v1'
export SKILL_MODEL_NAME='your-model-name'
# Set SKILL_MODEL_API_KEY in your process environment or secret manager.
# Do not commit it or put it into skill packages.
java -jar target/openskill-0.1.0-SNAPSHOT.jar
```

Install a compatible `codex` executable separately, or set `SKILL_AGENT_COMMAND`. This adapter expects the Responses protocol; a Chat Completions-only endpoint is not interchangeable. CLI flags are version-sensitive; see [deployment notes](docs/deployment.md). Tool network access is off unless `SKILL_AGENT_NETWORK_ENABLED=true`. Model execution may send instructions, inputs, and accessed resources to the configured provider.

Opt-in `SKILL_MODEL_SOURCE=local-codex` reads only the selected API-key provider from local configuration. Neither this mode nor personal model configuration is enabled by default. Shared-server/container identity mode deliberately rejects local CLI execution until an isolated runner is implemented.

## Project layout

```text
src/main/java/dev/openskill/
  api/             HTTP endpoints and error mapping
  application/     Catalog, workflow, run, schedule and export services
  engine/          Mapping, contracts, composition and adapter SPI
  infrastructure/  JDBC, transactions, outbox, workers and packages
  security/        Identity, role and CSRF boundaries
  domain/          JSON and domain errors
  cli/             Standalone deterministic runtime
src/main/resources/db/schema.sql
examples/          Synthetic, credential-free examples
scripts/           Packaged-application smoke tests
```

Stack: Java 21, Spring Boot 4.1.3, Spring MVC/JDBC, Jackson 3, H2 locally, and a MySQL deployment configuration template. Core code depends on public libraries only; no organization-specific SDKs, services, registries, or build profiles are required.

## Safety and current limits

- Local mode is **single-user and loopback-only**. Do not expose it through a public reverse proxy.
- Imported packages may contain executable content. A CLI workspace sandbox is not a hardened multi-tenant isolation boundary.
- Container mode expects a trusted principal, roles, and a server-populated workspace attribute; it does not ship a complete login integration.
- MySQL deployment, production identity integration, secret stores, isolation, quotas, retention, and load testing need further work.
- Workflow execution is sequential, currently limited to 12 steps with bounded nesting. Exactly-once external side effects are not promised.
- This repository currently contains the backend and CLI only. An independent visual workbench is planned.

## Contributing and publication

Start with [CONTRIBUTING.md](CONTRIBUTING.md) and [the roadmap](docs/roadmap.md). Do not submit secrets or internal service data. For security-sensitive reports, follow [SECURITY.md](SECURITY.md).

**License status:** no open-source license has been granted yet. The owner has confirmed publication rights. A license decision and public-launch confirmation are still required before public distribution. Dependencies retain their respective licenses. The project must not be advertised as licensed open source until that decision is recorded.
