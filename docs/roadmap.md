# openSkill roadmap

These are planned directions, not claims of implemented features or delivery dates.

## Before public launch

- Confirm publication rights, choose a license, and review notices required by dependencies.
- Create the `openSkill` repository under the confirmed owner and run CI on a clean public runner.
- Complete a tracked-file/history review and enable a private security-reporting channel.
- Record a reproducible local demo with synthetic inputs and document known limitations.

## Next: standalone experience

- A self-contained visual workbench for import, composition, run inspection and export.
- English/Chinese UI and API examples that do not require external accounts.
- A documented, tested extension contract for organization-neutral adapters.
- Tagged releases with checksums, compatibility notes and verified quick-start instructions.

## Later: shared deployments

- Trusted identity integration, database migrations and real MySQL integration tests.
- Isolated agent runners, network policy, resource quotas and credential mediation.
- Retention, observability, load testing and operational runbooks.

## Community positioning

Lead with the concrete workflow: **import → compose → run → inspect → export**. Be explicit that deterministic skills and model-backed instruction skills have different runtime requirements. Present the current backend honestly; do not imply that the planned visual editor, hosted service or production isolation already exists.
