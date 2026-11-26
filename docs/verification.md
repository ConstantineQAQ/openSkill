# Verification record

Date: 2026-11-26 (Asia/Shanghai).

## Passed locally

- JDK 21, Maven wrapper: `./mvnw -o -B clean verify`.
- **75 tests**, zero failures, errors or skipped tests.
- `python3 scripts/smoke.py`: application/runtime branding, disabled-by-default agent, README greeting example, synchronous execution, mappings, database outbox, asynchronous runs, one-time scheduling, ZIP export and standalone CLI result parity.
- `python3 scripts/smoke-package.py`: multipart upload above 2 MB, byte-identical original ZIP export, binary resources/modes/comments, persistence across restart and immutable reimport versions.
- `python3 scripts/smoke-agent.py`: Markdown/ZIP instruction runs, resource preservation, downstream mappings, deletion/history and reimport using a controlled fake CLI.
- Source-tree scan for removed organization markers and personal absolute paths: no findings in distributable files.
- Resolved dependency-tree scan for removed private dependency namespaces: no findings.
- Common private-key/token pattern scan: no findings in distributable files. This is not an exhaustive secret audit.
- Runtime data, logs, build output, IDE metadata and environment files are excluded by `.gitignore`.

## Passed on GitHub Actions

- Repository: `ConstantineQAQ/openSkill`; branch: `main` (private at the time of the initial verification).
- Verified MIT publication commit: `442b4d60085d74583f466dbdb419b67d3eb641fc`.
- Successful workflow: `Build and smoke tests`, run [`45777795008`](https://github.com/ConstantineQAQ/openSkill/actions/runs/45777795008).
- GitHub-hosted Ubuntu, Temurin JDK 21 and Python 3.12: Maven build/tests and all three smoke suites passed.
- The initial run `45776533337` successfully resolved dependencies and built from a runner without a pre-existing project cache, but its workflow smoke read the demo catalog before initialization finished.
- The smoke now waits for the seeded workflow within its existing bounded readiness loop. Local delayed-readiness/timeout checks and all three smoke suites passed again before the successful CI run.
- The local source commit and the remote `main` tree were compared after upload and matched.

## Public publication checks

- GitHub reports `private: false`, visibility `public`, and SPDX license `MIT`.
- The repository page is accessible without authentication; the unauthenticated raw `LICENSE` matches the local file byte for byte.
- GitHub private vulnerability reporting is enabled. The reporting entry is documented in [SECURITY.md](../SECURITY.md).
- No tagged release has been created.

## Not verified / not completed

- Live model/provider compatibility: agent regression uses a fake CLI and makes no real model calls.
- MySQL, production identity/credential integration, isolated runners, load tests or multi-tenant deployment.
- A visual workbench; none is included.

Publication rights, the MIT license and public visibility were confirmed by the owner on 2026-11-26. The repository `ConstantineQAQ/openSkill` exists. Project commits use the account-specific GitHub noreply identity rather than the machine-wide corporate identity; global Git configuration is not changed.
