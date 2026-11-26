# Security

openSkill is a developer preview, not a hardened hosted service. Only run trusted skills locally. Do not expose local authentication to other users, and do not interpret the CLI workspace sandbox as multi-tenant isolation.

## Reporting

Do not include credentials, customer data, private endpoints, exploitable payloads or unredacted logs in public issues. Private vulnerability reporting is enabled. Use [Security → Report a vulnerability](https://github.com/ConstantineQAQ/openSkill/security/advisories/new) to contact the maintainer privately; do not report vulnerabilities in public issues.

## Secret handling

Inject keys at runtime; keep environment files, database contents and logs outside version control. Imported packages can contain arbitrary resources: review them before use and before re-export. Rotate any credential that was accidentally exposed; deleting it from the current tree does not remove it from history.

## Support status

No production security support commitment or supported release line has been established. Production identity, isolated execution, resource quotas and operational hardening remain work in progress.
