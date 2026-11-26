# openSkill maintenance

This is the primary independently maintained project. Keep changes in this repository; do not modify sibling source projects or import their history, data, logs, credentials or proprietary integrations.

## Boundaries

- Use organization-neutral namespaces, examples, dependencies and deployment instructions.
- Preserve the `dev.openskill` Java package and the `openSkill` display name.
- Default startup remains loopback-only, with synthetic examples and model execution disabled.
- Do not add unnecessary fallbacks or unrelated validation.
- Prefer GET for reads and POST for new writes, while preserving documented compatibility.
- Do not claim a working UI, public repository, license, production readiness or real-provider verification without evidence.
- The owner confirmed publication rights, the MIT license and public visibility for `ConstantineQAQ/openSkill` on 2026-11-26.
- Preserve the MIT license and third-party notices. Public repository access does not imply a tagged release, production readiness or verified real-provider compatibility.

## Verification

Use JDK 21. Run `./mvnw clean verify`, followed by `python3 scripts/smoke.py`, `python3 scripts/smoke-package.py`, and `python3 scripts/smoke-agent.py`. The agent smoke is deliberately offline/fake-CLI. Keep README setup instructions and examples runnable without private services or personal absolute paths.
