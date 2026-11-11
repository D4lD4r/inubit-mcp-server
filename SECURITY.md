# Security policy

## Supported versions

Security fixes go into the latest release only (see the
[releases](https://github.com/D4lD4r/inubit-mcp-server/releases)); older versions are not
supported.

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
  records, process arguments or files; credentials read from anywhere other than the source the
  profile configures for the node (the credential store entries it derives, or the AWS Systems
  Manager Parameter Store parameters it names) — in particular from environment variables; the
  login back-off (`AUTH_FAILED`: at most two failed logins per node per 60 s) being bypassed.
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
- **Workbench archives** (`export_archives`): the archives hold the workflows' secrets (passwords,
  keys, certificates, saved test messages) **in clear text** by design, so the tool must write
  nothing without `acknowledgeClearTextSecrets: true`, nothing on a production group without
  `archives.productionOptIn`, and never replace an existing file. For the **client
  confirmation** the server annotates the tool as not read-only so that clients ask before
  calling it; whether a client asks depends on its approval setting, and the acknowledgement does
  not replace it. Reports are welcome for an archive written without the acknowledgement or the
  production opt-in, for a tool not annotated that way, for archive content or a secret value
  reaching a tool result, log or audit record, and for an archive written where the folder rules
  forbid it.
  `targetFolder` is refused when it
  1. is not an absolute path, or has a `.` or `..` element;
  2. is a network path: a UNC path (`\\server\share`, `\\?\UNC\…`) or a mapped network drive
     (Windows);
  3. exists and is not a writable directory, or (missing) its nearest existing ancestor is not a
     writable directory;
  4. is or lies inside one of the server's directories: `~/.inubit-mcp`, the configured
     `workspace`, the configured `auditDirectory`, the **configuration directory**
     (`~/.config/inubit-mcp`, on Windows `%APPDATA%\inubit-mcp`) or the **folder of the
     configuration file in use**;
  5. is or lies inside a git working tree (a `.git` entry in the folder or an ancestor).

  Rules 4 and 5 are checked on the path as given and with symbolic links resolved (for a missing
  folder: its nearest existing ancestor). Known limits, not vulnerabilities: rule 2 checks the
  path as given, so a symbolic link or junction to a network share, or a local folder resolving
  onto a mapped drive, is not detected, and on macOS and Linux a mounted network share is not
  recognized as one. A target folder that a **cloud-sync client** uploads sends the clear-text
  secrets to the cloud: a folder below a known sync root (macOS `~/Library/CloudStorage`,
  `~/Library/Mobile Documents`; on Windows the folders named by `OneDrive`, `OneDriveCommercial`,
  `OneDriveConsumer`) is accepted with a warning, but only the listed sync roots are detected —
  not, e.g., iCloud "Desktop & Documents" (`~/Desktop`, `~/Documents`) or sync folders outside
  those roots. On POSIX the archives are `rw-------` and the folders the call creates
  `rwx------`; **on Windows the archives and folders inherit the target folder's ACLs** (they are
  not tightened), so choose a folder only you can read. Delete the archives when they are no
  longer needed.
- **Local knowledge** (`read_knowledge`, `record_knowledge`): the notes are plain Markdown files
  on the local disk, **unencrypted**; what the server creates there (folder, notes, history
  versions, lock file) is owner-only, existing folders keep their permissions (the server warns
  when group or others may access the knowledge folder). Notes are meant for operating knowledge,
  never for passwords, keys or other secrets. Recognized secrets — the profile's configured
  credentials, key material, INUBIT encrypted values and assignments to keys such as `password`
  or `token` — are shown as `***` in every result, error, log and audit record, and text holding
  one is refused for writing. The recognition is **pattern-based**: a secret in plain prose (e.g.
  "the password is tiger") is not recognized; this residual risk is known. To mask the configured
  credentials, the knowledge tools read the credentials of every node of the profile first and
  refuse to work (`PRECONDITION_FAILED`) while one cannot be read. Reports are welcome for a
  recognized secret reaching a tool result, the instructions, an error, a log or an audit record
  unmasked, a write that holds one, a note written outside the knowledge folder or through a
  symbolic link, a note deleted or renamed by a tool, a write without audit record, or notes
  shown before the credentials were read.
- **TLS and certificate pinning**: connections that succeed although the chain, the pin or the host
  name check should fail, or a pin that can be bypassed.
- **Partner Management** (`find_partners`; the partner usage of `get_inventory_item` shows names
  only): the tool reads the partners of the owner's system diagrams in memory (nothing is written to
  disk) and returns their shown values, so **partner host names, logins, ports, paths and other
  non-secret settings reach the conversation** and with it the AI provider of the MCP client — by
  design, as any other tool result. **Secrets never do**: passwords, partner fields whose
  `fieldType` is not `String` (e.g. `Password`), encrypted values, key stores, certificates' private
  keys and other key material, and values whose names mark them as secret are shown only as set or
  not set, are not searched and are not suggested as similar values. Equal secrets on several nodes
  are recognized only through a keyed fingerprint (HMAC-SHA256 with a random key of the server
  process) that stays in memory and in the in-memory cache; neither the fingerprint nor the key is
  returned, logged, audited or written. Every call is audited (request inputs and counts only, never
  a partner value); without its audit record no partner data is returned. An error of a Partner
  Management export carries no excerpt of INUBIT's answer, since it could quote partner data.
  Reports are welcome for a secret value, or anything derived from it, reaching a tool result, an
  error, a log line, an audit record or a file, and for partner data returned without an audit
  record.
- Isolation between profiles (credentials, audit directories, workspaces, knowledge folders,
  backups, packages, confirmation codes, temporary data).

Out of scope: vulnerabilities of INUBIT itself or of the INUBIT client (StartCLI) — report those to
the vendor — and issues that require an attacker who already controls the user's account or
configuration files.
