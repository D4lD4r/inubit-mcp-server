# Research: Development on a Development Stage (feature 004)

Inputs: [spec.md](spec.md) (clarifications of 2026-10-06), the umbrella design
[docs/design/2026-10-05-development-and-deployment.md](../../docs/design/2026-10-05-development-and-deployment.md),
the spike findings [docs/research/spike-development-deployment.md](../../docs/research/spike-development-deployment.md)
and feature 003 (`specs/003-artifact-workspace/`). Decisions D-1…D-23.

## D-1 Development settings and guard

**Decision**: new configuration, resolved node-wins like `write.*`:

```yaml
owners:                      # profile level, optional: kind override (D-21)
  OWNERS: USER_GROUP
groups:
  - name: dev
    development:
      enabled: true          # default false
      confirmation: SERVER   # SERVER (default) | CLIENT
    e2eTests: FREE           # FREE | CONFIRM | FORBIDDEN (default FORBIDDEN)
    e2e:
      soap:
        baseUrl: https://inubit-dev.example.test:8443   # per group or node
```

`DevelopmentGuard` (application) admits a node for a capability: unknown/group id → existing errors;
not a development node → `NOT_DEVELOPMENT`; production → `PRODUCTION_PROTECTED` (never reachable
after startup validation, kept as defence in depth); CLI unavailable → `CLI_UNAVAILABLE`. The existing
`WriteGuard` for restart/kill stays unchanged (its texts are specific to those tools).

Startup rules (ConfigValidator): `development.enabled: true` or `e2eTests` ≠ `FORBIDDEN` on a
`production: true` group or node → error; `e2eTests` ≠ `FORBIDDEN` without `e2e.soap.baseUrl` →
error; `baseUrl` must be `https`, or `http` only with a warning (development stages may run plain
HTTP); owner kinds must be `USER` or `USER_GROUP`.

**Alternatives**: extending `write.*` — mixes two capabilities with different risk; rejected.

## D-2 Two-step confirmation for new capabilities

**Decision**: a new `WriteChallengeRegistry` with the same code format and rules as the existing
`ConfirmationRegistry` (22-char URL-safe code, single use, TTL `confirmationTtl`, in memory, max 1000
pending) but bound to `(capability, node, inputFingerprint, previewState)`:

- `inputFingerprint` = SHA-256 of the canonical tool inputs (sorted JSON without the code);
- `previewState` = what the preview saw: base commit, change-set hash and the server-state hash of the
  affected artifacts (import/restore/activate), the tag preconditions (tag), or the endpoint and
  payload hash (e2e).

Redeeming with other inputs or a changed state → `CONFIRMATION_INVALID` (inputs) or `CONFLICT`
(server state). The existing registry stays for restart/kill. Expired or unknown codes reuse
`CONFIRMATION_INVALID`.

## D-3 Base export and server-state commits

**Decision**: every commit that records a verified server state carries a git trailer
`Server-State: <node>`; feature 003's export commits get it too (small change in `WorkspaceService`).
The base of an artifact = the last commit with that trailer for the node that touched the artifact's
files. New `VersionHistoryPort` methods (all read-only except `commitAll`): `lastServerState(node,
path)`, `show(rev, path)`, `changedPaths(fromRev, subtree)`. The server never rewrites history.

## D-4 Change set

**Decision**: before an import, uncommitted edits are committed as `local changes` (feature 003
rule). The change set = files changed between the base commit and HEAD below
`<group>/<owner>/workflows/<diagram group>/` (workflows) and the module directories referenced by
those workflows or named explicitly (module import). New files are new artifacts. A **deleted**
workflow or module file aborts with `INVALID_INPUT` ("deleting artifacts is not supported; restore
the file or delete it in the Workbench"). Changes outside the scope are listed as "not imported".

## D-5 Conflict detection

**Decision**: export the scope fresh (`exportWorkflowGroup` or `exportModule`), render it in memory
through `ArchiveCodecPort.prepare` (new read-only `PreparedExport.files()` view) and compare the
reviewed content of every change-set artifact with its base revision (`show`). Any difference →
`CONFLICT`; any `<CheckoutUser>` on a change-set workflow → `CONFLICT` (edit mode). The difference is
written to `.reports/conflict-<auditId>.diff` (path returned). In confirmation mode `SERVER` this
runs in the preview and again in the execute call immediately before the import.

## D-6 Secrets for the import

**Decision**: the raw fresh export of D-5 (unredacted, in memory) is read with `ArchiveReader`, and a
new `SecretValues` index maps `(artifact, ${secret:<path>})` → value using the **same** path rules as
`SecretRedactor` (the path derivation is extracted into a shared `SecretPaths` so both walk
identically). A new `ImportAssembler` (adapter `archive/v81`) builds the import archive from the
workspace (like `ArchiveAssembler`, but without re-redaction) and replaces each placeholder with its
value; a placeholder without a value → `SECRET_UNRESOLVED` (names artifact and property path, never a
value). Withheld key-material repository files are taken from the target's raw repository. Secret
values exist only in memory and in the import ZIP inside the existing private temporary directory,
which is deleted on every path. Nothing secret reaches the workspace, history, backups index, audit,
results or logs.

## D-7 Guard

**Decision**: before the import, the assembled archive is read back and its workflow and module
names (and repository entries) must equal the change set exactly; otherwise `INTERNAL` (a bug), no
import.

## D-8 Import through StartCLI

**Decision**: `CliCommand` allowlist gains `import` with `--importFile` (path), `--importWorkflow`,
`--importWorkflowActive`, `--importWorkflowInactive`, `--importModule`, `--importUser`,
`--importUserGroup`, `--returnProtocol`; and `tag` with `--tagMove`, `--tagDelete`,
`--tagWorkflowGroup`, `--tagWorkflowType`, `--tagUser`. A new `CliImportRunner` writes the archive to
a private temporary directory (same rules as exports) and runs
`import --importFile '<zip>' --importWorkflow|--importModule --importUser|--importUserGroup '<owner>' --returnProtocol`
with the node's `cliExportTimeout` (no new setting). `ImportProtocolParser` parses the fixed-width
table (`TYPE DESCRIPTION DIAGRAM/MODULE GROUP/USER`, `Total: n`; spike §1); the protocol must name
exactly the change set (`was created` / `was modified`), else failure.

## D-9 Verification

**Decision**: export the scope again and render it; for every change-set artifact the reviewed content
must equal the workspace file, except the `CheckinComment` (rewritten by INUBIT, D-11) and values
that are placeholders in the workspace. A failing export (the spike's broken-group case) is a
failure. `VERIFY_MISMATCH` writes the difference to `.reports/verify-<auditId>.diff`.

## D-10 Rollback

**Decision**: on any failure after the import command started: assemble a rollback archive from the
**backup** (raw, with the original secrets) filtered to the change-set artifacts that existed before,
import it, verify against the backup's rendered state, and report `IMPORT_FAILED` or
`VERIFY_MISMATCH` with `rollback: SUCCEEDED | FAILED`. Artifacts that the failed call **created** cannot
be removed (no delete); they are listed as "created, not removed — delete in the Workbench if
needed". A failed rollback keeps the backup and names it.

## D-11 Reason as check-in comment

**Decision**: the person-written part of each change-set artifact's `CheckinComment` in the import
archive is set to the reason; INUBIT prefixes it (`DefaultCommitCommentImport###<reason>###…`, spike
§5). The verification checks that the reason appears.

## D-12 Workspace after success

**Decision**: the verified rendered state of the scope is written to the workspace (feature 003
writer) and committed as `import <node>: <diagram group> (<n> artifacts) [<auditId>]` with the
`Server-State` trailer. The same applies to `set_active` and `restore_backup`.

## D-13 Backups

**Decision**: `~/.inubit-mcp/<profile>/backups/<auditId>.zip` (the raw fresh export of the scope,
taken in step D-5 of the execute call) plus `<auditId>.json` (node, owner, scope, change-set names,
created artifacts, timestamp; no secrets); directory `rwx------`, files `rw-------`. Retention
(clarification 3): at the start of every writing call, backups older than 30 days are removed unless
they are the newest of their `(node, owner, scope)`; each removal is audited
(`capability: backup_retention`).

## D-14 `restore_backup`

**Decision**: input `backupRef` (an `auditId`); the index must exist, belong to this profile and node;
the conflict check compares the server with the state the referenced call left (its commit); the
rollback archive of D-10 is imported; verify; commit `restore <node>: … [<auditId>]`. Created
artifacts of the original call stay (no delete) and are reported.

## D-15 `set_active`

**Decision**: input one workflow; conflict check on that workflow; archive with only that workflow
(no modules, export record of the workflow alone) imported with `--importWorkflowActive` or
`--importWorkflowInactive`; verify `IsActive`; commit. INUBIT creates a new version (noted in the
result).

## D-16 `tag_artifacts`

**Decision**: inputs `diagramGroups` (1–20, non-blank, `CliCommand.VALUE`) and `tag` (`VALUE`).
Preconditions: export the owner's technical workflows **of all diagram groups** with
`--includeHistory` (read-only) and refuse if the tag already exists anywhere for the owner (no
moving). Then per diagram group `tag --tagMove '<tag>' --tagWorkflowGroup '<group>' --tagWorkflowType
'technical' --tagUser '<owner>'`. Verification: the history export again; the tag must be exactly on
the head versions of the requested groups and their modules; anything else → `tag --tagDelete '<tag>'
--tagUser '<owner>'` and `IMPORT_FAILED`-like failure (`VERIFY_MISMATCH`). Open (spike): tagging for a
**user-group** owner was not probed → first live run on a disposable diagram group with the user's
approval.

## D-17 `run_e2e_test` (SOAP)

**Decision**: inputs `node`, `envelope` (workspace path), `path` (relative to `e2e.soap.baseUrl`,
validated: no `..`, no scheme/host), optional `soapAction`, optional `workflow` (for correlation),
optional `timeoutSeconds` (≤ 120, default 60). JDK `HttpClient` with the node's TLS settings (trust
store, pinned certificate, hostname rule — shared builder with the REST client). Headers:
`Content-Type: text/xml; charset=utf-8`, `SOAPAction`, `X-Inubit-Mcp-Test-Id: <uuid>`; the envelope is
sent unchanged. Response saved to `.tests/e2e/<auditId>.response.xml`; result excerpt ≤ 2 KB.
Correlation: logs (`systemLog`, `auditLog`) searched by the test id in `[start-5 s, end+30 s]`; if
found → instances by their process ids; else `find_processes` + logs of `workflow` in the window,
marked `uncertain`. Whether INUBIT logs the header is unverified → the live run decides how often
the fallback applies.

## D-18 Audit

**Decision**: capabilities `import_artifacts`, `set_active`, `tag_artifacts`, `restore_backup`,
`run_e2e_test`, `backup_retention`; steps and outcomes as for restart/kill
(`CHALLENGE_ISSUED`, `PENDING`, `EXECUTED`, `FAILED`, `REFUSED`). Inputs: reason, scope (diagram group
or module names), owner, owner kind, tag, backupRef, endpoint path, change-set names (bounded list),
rollback outcome; codes hashed as today. Never content, payloads or secrets.

## D-19 Error codes

**Decision**: add `NOT_DEVELOPMENT`, `CONFLICT`, `SECRET_UNRESOLVED`, `IMPORT_FAILED`,
`VERIFY_MISMATCH`, `E2E_FORBIDDEN` to `ErrorCode`; reuse `CONFIRMATION_REQUIRED`,
`CONFIRMATION_INVALID`, `PRECONDITION_FAILED` (lock), `INVALID_INPUT`, `NOT_FOUND`.

## D-20 Locking

**Decision**: the workspace lock of feature 003 is held for the whole preview and for the whole
execute call (export, check, import, rollback, commit); concurrent workspace operations are refused
at once.

## D-21 Owner kind

**Decision** (probed read-only on the development node): REST `GET /user/users?type=processEngineUser`
lists **users only** (the personal owner was listed, the shared group owner was not); StartCLI
`finger <name>` succeeds for users and reports "not registered" for the group owner and for unknown
names alike. Rule: profile `owners.<name>` wins; else listed as user → `USER`; else, if the owner has
artifacts on the node (from the conflict export) → `USER_GROUP`; else → `PRECONDITION_FAILED` ("owner
kind cannot be determined; set owners.<name> in the profile"). The REST read goes through the existing
REST client and credential guard.

## D-22 Fixtures

**Decision**: import, tag and finger outputs recorded in the spike (local, `~/.inubit-mcp/<profile>/spike`)
are neutralized into `fixtures/v8_1/cli/import_*`, `tag_*`, `finger_*`; failure modes (NOK lines,
protocol mismatch, broken re-export) are synthetic and documented. A `ScriptedProcessLauncher` (test)
answers a sequence of StartCLI calls (export → import → export) by matching the command line.

## D-23 Live tests

**Decision**: opt-in (`INUBIT_LIVE_DEV_NODE`, `INUBIT_LIVE_DEV_OWNER`, `INUBIT_LIVE_DEV_DIAGRAM_GROUP`),
refused unless the node is a development node and the owner is a **user** (personal diagram group);
scenario: export → edit a layout value → import → verify → restore → set_active → tag with a unique
`LIVE-<timestamp>` tag → remove the tag. Never on shared owners or production.
