# Publication checklist

Status as of 2026-11-26: the owner has confirmed publication rights, selected the MIT license and authorized making `ConstantineQAQ/openSkill` public. The MIT license is included; the visibility change is in progress. GitHub Actions has passed the build and all three smoke suites. No tagged release has been created.

## Owner decisions and repository setup

- [x] The owner confirmed the right to publish the source and derivative work.
- [x] Create `ConstantineQAQ/openSkill` through the authenticated GitHub MCP and verify private visibility.
- [x] Connect this independent local project to the new repository without importing the source project's history.
- [x] Use the account-specific GitHub noreply identity for local project commits; leave global Git identity unchanged.
- [x] Add the owner-selected [MIT license](../LICENSE), copyright 2026 Yaning Wang. Dependencies and third-party components retain their own licenses and notices.
- [x] The owner authorized public repository visibility after selecting MIT.
- [ ] Change visibility to public and verify unauthenticated access and GitHub license recognition.

## Technical release checks

- [x] Review only intended source/configuration/documentation files; exclude runtime data, logs, IDE state, personal configuration and existing internal history.
- [x] Scan the distributable tree for organization markers, common secret patterns, private dependency coordinates and personal paths. This is not an exhaustive secret audit.
- [x] Run `./mvnw -o -B clean verify` and all three Python smoke scripts locally; see [verification scope](verification.md).
- [x] Synchronize the initial source to GitHub and verify that its file tree matches the local commit.
- [x] Verify the Maven wrapper and dependencies on a GitHub-hosted Ubuntu runner. The first run built successfully without a pre-existing project cache; the full build and all smoke suites passed in run `45776845683` after fixing a demo-readiness race.
- [ ] Verify real CLI/provider compatibility separately if advertising model-backed execution.
- [ ] Configure a private security-reporting channel before public launch.

## Synchronization

The repository was created and remote contents were verified through GitHub MCP. Its file-write operation returned HTTP 403 with the current token, so the initial source and subsequent updates were pushed through the existing authenticated Git transport. No token is stored in this repository.

The local `codex/initial-import` branch tracks `origin/main`; repository-local `push.default=upstream` makes a plain `git push` update that upstream. Future development can use separate `codex/` branches and pull requests. This setup does not run unattended synchronization.

## Repository metadata

Name: `openSkill`

Description: `Local-first skill packaging, workflow composition, and traceable execution in Java.`

Suggested topics: `agent-skills`, `workflow`, `java`, `spring-boot`, `automation`, `local-first`.

Position the first release as a developer preview. Do not claim production adoption, a bundled visual editor, full sandbox isolation, or verified compatibility with untested providers. Public source availability is not a tagged release or a production-readiness guarantee.
