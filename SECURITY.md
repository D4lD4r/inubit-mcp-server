# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x | yes |
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
- **TLS and certificate pinning**: connections that succeed although the chain, the pin or the host
  name check should fail, or a pin that can be bypassed.
- Isolation between profiles (credentials, audit directories, confirmation codes, temporary data).

Out of scope: vulnerabilities of INUBIT itself or of the INUBIT client (StartCLI) — report those to
the vendor — and issues that require an attacker who already controls the user's account or
configuration files.
