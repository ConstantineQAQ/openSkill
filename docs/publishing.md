# Publication checklist

Status as of 2026-11-26: the owner has confirmed publication rights. The private repository `ConstantineQAQ/openSkill` exists, and the initial source synchronization is being prepared. No public release or open-source license has been granted.

## Owner decisions and repository setup

- [x] The owner confirmed the right to publish the source and derivative work.
- [x] Create `ConstantineQAQ/openSkill` through the authenticated GitHub MCP and verify private visibility.
- [x] Connect this independent local project to the new repository without importing the source project's history.
- [x] Use the account-specific GitHub noreply identity for local project commits; leave global Git identity unchanged.
- [ ] Select and add an open-source license with the appropriate copyright holder and dependency notices. No license is assumed by this repository.
- [ ] Confirm public launch and change repository visibility only after the license is settled.

## Technical release checks

- [x] Review only intended source/configuration/documentation files; exclude runtime data, logs, IDE state, personal configuration and existing internal history.
- [x] Scan the distributable tree for organization markers, common secret patterns, private dependency coordinates and personal paths. This is not an exhaustive secret audit.
- [x] Run `./mvnw -o -B clean verify` and all three Python smoke scripts locally; see [verification scope](verification.md).
- [ ] Synchronize the initial source to GitHub and verify that its file tree matches the local commit.
- [ ] Verify the Maven wrapper and all dependencies on a clean GitHub runner without a private mirror/cache.
- [ ] Verify real CLI/provider compatibility separately if advertising model-backed execution.
- [ ] Configure a private security-reporting channel before public launch.

## Repository metadata

Name: `openSkill`

Description: `Local-first skill packaging, workflow composition, and traceable execution in Java.`

Suggested topics: `agent-skills`, `workflow`, `java`, `spring-boot`, `automation`, `local-first`.

Position the first release as a developer preview. Do not claim production adoption, a bundled visual editor, full sandbox isolation, or verified compatibility with untested providers. Private source synchronization is not a public release or a license grant.
