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

**Decision** (probed live on the personal test workflow, 2026-10-06): INUBIT takes the check-in
comment of an **update** from the archive only if it has the export shape
`DefaultCommitCommentImport###<reason>###@@@Deploying User: <user>@@@Server: <host>@@@Version: <n>@@@Export/Deployment: <dd.MM.yyyy HH:mm:ss>@@@`
(a free text or a `###`-segment without the `@@@` suffix is replaced by `DefaultCommitCommentImport###`).
The assembler writes exactly that shape (user = the node's username, server = node host, version =
the server's current version + 1 as seen in the conflict export, time = now). The reason must match
`[^#@\p{Cntrl}]{1,500}` (no `###`, no `@@@`, no control characters) — `INVALID_INPUT` otherwise.
Verification checks that the head comment's person-written segment equals the reason.

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

## D-24 Live probes for archive shapes (2026-10-06, personal test workflow only)

- A workflow archive with **only** `workflow/workflow.xml` and an **empty** module index (no module
  files, no `Repository.zip`) is accepted: only the diagram gets a new version; nodes, edges and
  Demultiplexer conditions stay; referenced modules on the server are untouched (no new versions).
  → Import archives contain only the changed workflows and the changed/new modules; `Repository.zip`
  is omitted (repository changes are out of scope of this feature; a change below `repository/`
  aborts with `INVALID_INPUT`).
- The same workflow-only archive with `--importWorkflowActive` / `--importWorkflowInactive` sets the
  active flag → `set_active` is built from the **fresh server export** of that workflow (not from the
  workspace), so unimported workspace edits are never shipped; it refuses if the workspace file of
  that workflow differs from its base (unimported edits).
- Recorded outputs of these probes go into the fixtures (T001).

## D-25 Resolutions of the pre-implementation analysis

- **Owner kinds (C1, M5)**: positive evidence only — profile `owners.<name>` or presence in the REST
  user list → `USER`; profile `USER_GROUP` → `USER_GROUP`. Writes for `USER_GROUP` owners are
  **refused** (`PRECONDITION_FAILED`, "imports for user-group owners are not yet verified") until a
  live probe on a disposable diagram group of a user group has been approved by the user and recorded;
  the refusal is a single guard to lift then. The REST fixture is a neutralized **recording** of
  `/user/users` (read-only GET), not synthetic. `finger` is not used.
- **Rollback builder (H2, M7)**: a backup is a manifest `<auditId>.json` plus one raw ZIP per StartCLI
  export of the scope (`<auditId>-<n>.zip`). The rollback is built like an import: the backup is
  rendered in memory (feature-003 codec), `ImportAssembler` assembles the change-set artifacts that
  existed before, and `SecretValues` is taken from the **target's current export** (not the backup),
  so a restore never brings back old passwords (M4). Backups keep only the scope exports (no other
  owner data); key material stays withheld from any file except the raw backup ZIP itself.
- **Base without trailer (H3)**: `lastServerState` falls back to the last commit whose subject starts
  with `export <node>:` (feature 003 subject) when no trailer exists; no base at all → `PRECONDITION_FAILED`
  "export the scope first".
- **Per-artifact base (H4)**: each change-set candidate is diffed against its **own**
  `lastServerState(node, path)`; module directories shared by several diagram groups are compared per
  module.
- **Write-back (H5)**: after success only the change-set files and their `.meta` are replaced with
  the verified server state; other workspace files are untouched.
- **Referenced modules (H6)**: an additional import rule (not a `check_artifacts` change): every
  module referenced by an imported workflow must be in the archive or in the **target node's** module
  list for that owner (the conflict export shows it) — otherwise `PRECONDITION_FAILED` with the names
  (prevents the spike's broken-group case). Unchanged referenced modules are not imported.
- **Restore base and backup (H7)**: restore compares the server with the **intended state** recorded in
  the referenced call's manifest (rendered hashes of the change set after the call, or, for a failed
  call, the state the verification saw); restore takes its own backup and has its own rollback.
- **SOAP envelope (H8)**: the envelope path is confined like `check_artifacts` paths (real path inside
  the workspace, not below `.git`, `.meta`, `.reports`, backups); redirects are never followed; an
  optional e2e basic-auth comes only from configuration variables (`<PREFIX>_<GROUP>[_<NODE>]_E2E_USERNAME`/`_PASSWORD`,
  same rules as credentials); envelopes containing a `wsse:Password` with a non-placeholder value are
  refused.
- **New artifacts (H9)**: a new workflow or module needs a workspace file only; the assembler writes
  default context (`WorkflowGroup` = scope, `IsActive=false`, fresh UID placeholders omitted — INUBIT
  assigns them) and a module-index entry from the module's `index.xml`; a name that exists on the
  target for another owner or kind → `PRECONDITION_FAILED`.
- **All-groups history export (M1)**: new `CliExportRunner.exportHistoryAllGroups(owner)` with
  `--exportWorkflowGroup ''` (emptyQuoted) and `--exportWorkflowType 'all'`, tested; the tag
  pre-check covers workflows and modules of all diagram types; note: every export appends to
  check-in histories (spike §3).
- **Failure model (M2)**: refusals before anything is sent are tool errors (`NOT_DEVELOPMENT`,
  `INVALID_INPUT`, `CONFLICT`, `SECRET_UNRESOLVED`, `PRECONDITION_FAILED` incl. check errors with a
  findings report path, `CONFIRMATION_INVALID`); once anything was sent, the call returns a result
  with `outcome: FAILED`, `failure: {code: IMPORT_FAILED|VERIFY_MISMATCH, step, message}` and the
  rollback state. Tag verification failures use the same result shape.
- **Timeout race (M6)**: after a StartCLI `TIMEOUT` the server re-exports the scope to determine the
  state before deciding on rollback.
- **Multiple development nodes (M8)**: the `Server-State` trailer names the **group**; the validator
  allows at most one development-enabled node per group (startup error otherwise).
- **E2E data (M9)**: the response excerpt is returned only with `includeExcerpt: true` (default
  false); `.tests/e2e` files older than 30 days are removed at the next e2e run.
- **Small items (L1–L3)**: workspace changed between preview and execute → `CONFIRMATION_INVALID`
  ("preview again"); `owner` defaults to `inventory.owner` for all tools; the import calls
  `checkPaths` (lock already held); `<CheckoutUser>` is stripped from import archives; artifacts in
  the scope but outside the change set are compared in the conflict check.

### D-25 addendum: review of the import core (T010–T018, 2026-10-07)

- **Identity from the target (I1)**: the UIDs, the module file name and the diagram-group context
  of a **modified** artifact come from the target's fresh raw export of the call, never from the
  workspace's `.meta/` records (anyone can edit them); a module file name is accepted only as
  `module/<name>.xml`. Every workflow is sent with `UserOrUserGroupName` = the owner of the
  request; a workflow file that names another owner (e.g. copied from another owner's diagram
  group) is `INVALID_INPUT`. A modified artifact that is missing on the target is
  `PRECONDITION_FAILED`.
- **New workflows (I2)**: INUBIT creates a new workflow inactive, so its workspace file must say
  `IsActive=false`; otherwise `INVALID_INPUT` before anything is sent, pointing to `set_active`.
- **New modules in a module import (I3)**: StartCLI answers `NOT_FOUND` when exporting a module
  that does not exist. A module import therefore exports only the **modified** modules for the
  conflict check and the backup; whether a **new** module exists already is decided by the
  owner's module list (`CONFLICT` "exists already"). After the import a new module that was not
  created counts as absent (not as an export failure).
- **Name collisions (H9, m5)**: a new workflow or module is checked against the names of the
  **owner's own** workflows and modules on the target (inventory lists of that owner); names of
  other owners are not checked — an import addresses one owner (`--importUser`) and the inventory
  lists one owner's artifacts.
- **Reason (D-11, m2)**: the verification requires the person-written segment to be exactly
  `DefaultCommitCommentImport###<reason>###` (copies of the empty last segment appended by later
  exports counted once), for workflows and module index entries.
- **Unexpected failures after sending (M2, m6)**: they are `FAILED` results like any other
  (`failure{code: IMPORT_FAILED, step}`, state re-exported, rollback from the backup, backup
  named); an `INTERNAL` tool error remains only if not even that result can be produced.
