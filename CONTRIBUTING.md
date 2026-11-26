# Contributing to openSkill

openSkill is an early-stage, API-first project. Read the README and roadmap before making changes. The project is licensed under [MIT](LICENSE). The owner has approved public publication; contributions should preserve third-party licenses and notices.

## Development

1. Install JDK 21 and Python 3.
2. Build and test with `./mvnw clean verify`.
3. Run all three smoke scripts under `scripts/` after changing API, packaging, execution or configuration behavior.
4. Use only synthetic test data. Never depend on a private Maven mirror, internal URL, personal account, local absolute path or real model key.

## Changes

- Keep patches focused; add or update tests for changed behavior.
- Prefer GET for reads and POST for new write endpoints. Preserve documented compatibility unless intentionally migrating it.
- Avoid unnecessary fallback logic and unrelated validation changes.
- Keep model execution opt-in and local access loopback-only.
- Preserve immutable published versions and original imported packages.
- Document what was actually tested. Fake-CLI tests are not real-provider verification; H2 tests are not MySQL verification.
- Update both READMEs when changing setup, scope or safety boundaries.

Use an issue for a concrete bug or a scoped proposal; include reproduction steps and sanitized logs. Do not post vulnerabilities or secrets publicly. See `SECURITY.md`.
