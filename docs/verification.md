# Local verification record

Date: 2026-11-26 (Asia/Shanghai).

## Passed

- JDK 21, Maven wrapper: `./mvnw -o -B clean verify`.
- **75 tests**, zero failures, errors or skipped tests.
- `python3 scripts/smoke.py`: application/runtime branding, disabled-by-default agent, README greeting example, synchronous execution, mappings, database outbox, asynchronous runs, one-time scheduling, ZIP export and standalone CLI result parity.
- `python3 scripts/smoke-package.py`: multipart upload above 2 MB, byte-identical original ZIP export, binary resources/modes/comments, persistence across restart and immutable reimport versions.
- `python3 scripts/smoke-agent.py`: Markdown/ZIP instruction runs, resource preservation, downstream mappings, deletion/history and reimport using a controlled fake CLI.
- Source-tree scan for removed organization markers and personal absolute paths: no findings in distributable files.
- Resolved dependency-tree scan for removed private dependency namespaces: no findings.
- Common private-key/token pattern scan: no findings in distributable files. This is not an exhaustive secret audit.
- Runtime data, logs, build output, IDE metadata and environment files are excluded by `.gitignore`.

## Not verified / not completed

- Building from an empty cache on a clean public CI runner. Local builds used an existing Maven cache; outbound Maven repository checks timed out in this environment.
- Live model/provider compatibility: agent regression uses a fake CLI and makes no real model calls.
- MySQL, production identity/credential integration, isolated runners, load tests or multi-tenant deployment.
- A visual workbench; none is included.
- License selection, public launch and live-provider verification.
- GitHub CI execution and initial code synchronization are tracked in the publication checklist.

Publication rights were confirmed by the owner on 2026-11-26. The private repository `ConstantineQAQ/openSkill` exists. Project commits use the account-specific GitHub noreply identity rather than the machine-wide corporate identity; global Git configuration is not changed.
