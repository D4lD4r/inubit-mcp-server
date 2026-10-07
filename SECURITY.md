# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 0.4.x | yes |
| older | no |

## Reporting a vulnerability

Please report vulnerabilities **privately** through GitHub's private vulnerability reporting:
open the repository's **Security** tab and choose **"Report a vulnerability"**. Do **not** open a
public issue, pull request or discussion for a suspected vulnerability.

Include the version (`java -jar inubit-mcp-server-<version>.jar --version`), what you observed and
the steps to reproduce it. Never include real credentials, host names, certificates, log excerpts
or other data of an INUBIT installation; reproduce with fictitious values (`*.example.test`) where
possible.

You can expect an acknowledgement within a few days. Fixes are released as a new patch version and
described in the [changelog](CHANGELOG.md) and the GitHub security advisory.

## Scope

Reports are especially welcome for:

- **Credential handling**: credentials leaking into tool results, error messages, logs, audit
  records, process arguments or files; credentials read from anywhere other than the profile's
  environment variables; the login back-off (`AUTH_FAILED`) being bypassed.
- **Write tools** (`restart_process`, `kill_process`): acting without effective write access, on a
  production group without `write.productionOptIn`, on more than one process instance, without or
  with a reused, foreign or stale confirmation code, or without an audit record.
- **Development tools** (`import_artifacts`, `restore_backup`, `set_active`, `tag_artifacts`,
  `run_e2e_test`): writing to a node that is not a development stage, importing past a conflict or
  a failed check, tagging beyond the requested diagram groups (owner-wide), or a missing rollback.
- **Deployments** (`deploy_release`): deploying into a group that is not the configured successor
  of its source, skipping a stage, writing to a package-only or production group without the
  configured permission, acting without or with a stale server-issued code, deploying key
  material, or continuing after a failed node.
- **Secrets in artifacts**: secret values or key material reaching the workspace, its git history,
  reports, the deployment ledger, tool results, logs or audit records; a target receiving the
  source's secret values instead of its own. Backups and import packages hold secret values by
  design and must stay owner-only.
- **TLS and certificate pinning**: connections that succeed although the chain, the pin or the host
  name check should fail, or a pin that can be bypassed.
- Isolation between profiles (credentials, audit directories, workspaces, backups, packages,
  confirmation codes, temporary data).

Out of scope: vulnerabilities of INUBIT itself or of the INUBIT client (StartCLI) — report those to
the vendor — and issues that require an attacker who already controls the user's account or
configuration files.
