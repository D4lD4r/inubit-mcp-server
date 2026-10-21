# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Repository files on a development stage (feature 008): schemas, stylesheets, WSDLs and other XML
  files below `/Root/<owner>/` can be brought from the workspace into a development node, not only
  read:
  - Setting `development.repository` (`READ_ONLY` by default, or `WRITE`; defaults, group, node),
    effective only on a development stage. An explicit `WRITE` on a node that is not a development
    stage, or on a production group or its nodes, is a startup error; on a non-production group
    without a development node it is a warning. `--check-config` adds
    `, repository: write | read-only` to the development part of a node's line, and `list_nodes`
    reports `repositoryWrite`.
  - `import_artifacts` takes the new scope `repositoryPaths` (1–50 paths relative to the owner's
    repository root). A diagram-group or module import also carries the new and changed
    repository files that the modules reach, also through other repository files; a diagram group
    with only repository changes is a valid import. Every call that would send a repository file
    returns a preview with a server code first, also on a node with
    `development.confirmation: CLIENT`. The preview lists the files in `create`, `modify`,
    `existing` and `identical`, the workspace artifacts outside the scope that use them
    (`repositoryReferences`), a diff (`repositoryDiff`) and the shared-use warnings. The
    repository files are imported before the workflows and modules, verified byte for byte and
    written back to the workspace; on a failure they are rolled back from the backup first, and a
    failed repository rollback names the files and points to `restore_backup` or the Workbench
    (only to the Workbench and the backup's repository exports if the last re-export of the
    repository files failed).
    Created files are never removed (`createdNotRemoved`). The result's `tag` lists the imported
    repository files the group tag covers (`tag.repositoryFiles`); audit records name every file
    with its operation and hash, never its content.
  - `export_artifacts` takes the new scope `repositoryPaths` (1–20 files or folders, folders
    recursively, at most 500 files and 100 MiB); if any path is missing on the node, the whole
    call is `NOT_FOUND` and nothing is written. Two paths that differ only in letter case are
    refused with `INVALID_INPUT`.
  - `restore_backup` also restores the previous content of the repository files the backed-up
    development call changed, repository files first, with conflict check, verification and
    rollback, and always with a server code. Files that call created stay
    (`createdNotRemoved`); a file changed on the node since is a `CONFLICT`. Backups of
    deployments are restored as before, whatever the switch says.
  - Error code `REPOSITORY_WRITE_DENIED`: on a node whose `development.repository` is
    `READ_ONLY`, an import or restore that would create or change a repository file is refused as
    a whole, naming the paths, before anything is sent.

  See `docs/setup.md` ("Development settings", "Artifact workspace") and `docs/tools.md`
  ("Repository files (feature 008)", `restore_backup`).
- INUBIT version detection and version-dependent capabilities (feature 009): every tool needs a
  capability (`monitoring`, `diagnosis`, `inventory`, `workspace_export`, `development_writes`,
  `deployment`, `e2e_test`, `process_control`), and the server declares per INUBIT version which
  of them are supported. INUBIT 8.1.x supports all of them; any version other than 8.1.x (9.0,
  later or earlier versions), `V9_X` and an unknown version run reads unverified (with a warning)
  and refuse writes. No 9.0-specific access is part of this version. What changes for existing
  profiles is listed under "Changed".
  - `versionLine` also accepts a concrete version such as `"8.1.17"` or `"9.0.7-HF1"` (major,
    minor and patch; a suffix is kept and ignored for matching; quote it). Any other value is a
    configuration error that names the key.
  - StartCLI client check: for each node with a StartCLI installation the server runs
    `startcli.sh -v` locally (no credentials, no INUBIT contact) in `get_health`, before the first
    StartCLI-backed call on the node and in `--check-config`, and compares the client version and
    the INUBIT range it declares with the node's version. A different version gives a warning; a
    failed or timed-out check is retried at most once per 60 s.
  - `list_nodes` reports a `version` block per node: the setting, the last known server version
    with its source and time (or `not_yet_detected` / `unknown` with the reason), the StartCLI
    client, a support status (`supported`, `partially_supported`, `unsupported`, `unknown`) and
    the usable and not usable capabilities. It still contacts no node.
  - `get_health` reports the StartCLI client per node (`client`: version, declared range and
    comparison with the server version, or `not_available`); a check that fails or does not
    finish within the health time budget is the new part `client` in `unavailable`.
  - Optional `warnings` in the results of `find_processes`, `query_logs`, `list_inventory`,
    `get_inventory_item`, `restart_process` and `kill_process`, and at the top level of the
    `deploy_release` preview: version and client warnings of the node. They also go into the
    existing `warnings` of `export_artifacts` and of the `deploy_release` result. A version
    warning of the `check_artifacts` module lookup becomes the new finding
    `MODULE_LOOKUP_WARNING`; a refused lookup turns the modules into `MODULE_UNVERIFIED` with the
    reason.
  - `--check-config` adds `inubit: <versionLine>` to each node's line and, after a successful
    client check, `client: <version> (<from>–<to>)` with `client vs pin: …` for a concrete pin or
    `server version: determined at runtime` otherwise. The existing warning per `V9_X` node now
    reads "versionLine V9_X: write tools are refused on this node (…); read tools run unverified"
    (before: "… the 8.1 adapters are used"); new is one warning per failed client check (not an
    error).

  See `docs/setup.md` ("INUBIT version (`versionLine`)", "StartCLI client check") and
  `docs/tools.md` ("INUBIT versions and capabilities (feature 009)", `UNSUPPORTED_VERSION`).
- Configurable secret sources (feature 010): a node's INUBIT credentials come from environment
  variables (the default, as before) or from AWS Systems Manager Parameter Store. A profile
  without the new settings behaves as before. No tool, parameter, result field or error code is
  new. What changes for existing profiles is listed under "Changed".
  - `credentials.source` at the top level, on a group or on a node (node before group before
    profile): `type: environment` (optional `prefix`) or `type: awsParameterStore` with `region`
    and an optional `awsProfile` of the local AWS configuration. `credentials.parameters` on a
    group or node names the full Parameter Store name per kind (`username`, `password`,
    `trustStorePassword`, `e2eUsername`, `e2ePassword`); a node's own name wins over its group's,
    and the group's names apply only to nodes without their own `source`. The profile holds names
    only, never a value or an AWS key; a value that is not a valid parameter name is refused
    without being echoed.
  - The server starts without contacting AWS. A node's username, password and (if named) trust
    store password are fetched together on the node's first use (one `GetParameters` request with
    decryption, bounded by 10 s), shared by concurrent calls and kept in memory for the life of
    the process; its end-to-end credentials on its first `run_e2e_test`. A failed fetch is not
    repeated for 30 s. AWS access uses the standard AWS credential chain, or the named AWS
    profile (SSO and assume-role profiles included). Fetched values pass the same checks as
    environment variables; a `StringList` is refused, a plain `String` is accepted with a
    warning in the log.
  - Rotation: when INUBIT rejects the login of a Parameter Store node, its credentials are read
    once more; changed values are used from the next call on, without a restart, with exactly one
    further login, otherwise the node pauses for 60 s as before.
  - A failed fetch is `AUTH_FAILED` (parameter missing or empty, access denied, invalid value,
    unknown AWS profile, no AWS credentials) or `UNREACHABLE` (store unreachable, throttled or
    not answering within 10 s), naming node, region, AWS profile, parameter and failure, never a
    value or AWS's own message (that goes to the scrubbed log; one `secret.fetch` record per
    fetch). `AUTH_FAILED` after a rejected login names the parameters ("… in Parameter Store
    (…); no restart is needed"), and `TLS_ERROR` about the trust store password names its
    parameter.
  - `get_health` keeps its time budget: a node whose first fetch does not finish within it is
    reported as `TIMEOUT` "<node>: credentials not yet available (still fetching from AWS
    Parameter Store); call get_health again shortly", without any request to that node. The
    per-node deadlines of `find_processes`, `query_logs`, `list_inventory` and
    `get_inventory_item` are 11 s longer for Parameter Store nodes, so a slow store fails only
    that node.
  - The audited `account` of a Parameter Store node is the fetched username (after a rotation the
    current one), or `(Parameter Store <username parameter>)` on records written before the first
    fetch.
  - `--check-config` reads and validates every configured parameter, never printing a value, and,
    once the profile uses `credentials.source`, shows per node `secrets: environment` or
    `secrets: awsParameterStore (<region>[, profile <awsProfile>])` with each name and its outcome
    (`ok`, `not encrypted`, `not found`, `access denied`, `invalid value`, `not checked`).

  See `docs/setup.md` ("4. Credentials: sources", "The `secrets:` part") and `docs/tools.md`
  ("Credentials from AWS Parameter Store (feature 010)").

### Changed

- Exports write the repository files the exported workflows and modules reach **transitively**:
  also files that only another repository file includes or imports, and the message-type schemas
  of the module index (`InputMessageTypeSchema`, `OutputMessageTypeSchema`). An export can
  therefore write more files than before. A URI in a string literal (e.g. in an `xsl:variable`)
  no longer counts as a reference. Repository files below another owner's root are no longer
  written; a warning counts them.
- `check_artifacts` also checks these references, including inside repository files (and still
  reports every `inubitrepository:` URI): a missing included schema or stylesheet, or a missing
  message-type schema, is `REPOSITORY_REF_MISSING`; one in another owner's root is
  `REPOSITORY_REF_UNVERIFIED`.
- The workspace history entry that records the verified state after `import_artifacts`,
  `restore_backup` or `set_active` carries the call's reason as a `Reason: <reason>` trailer next
  to `Server-State`.
- Backup manifests of development calls name the repository files the backup holds
  (`repositoryPaths`) and keep them, and their `kind`, when the call's outcome is recorded.
  Manifests of earlier versions stay readable.
- Deliberate behaviour changes of feature 009. Before, every tool ran with the 8.1 adapters and
  at most a warning, whatever the version; now the error code `UNSUPPORTED_VERSION` (reserved
  until now) is returned, before anything but the version detection and the local client check
  is sent, and refusals of write tools are audited:
  - Write tools (`restart_process`, `kill_process`, the development writes, `run_e2e_test`,
    `deploy_release`, `restore_backup`) are refused on an unknown version, also after a failed
    `AUTO` detection, which used to fall back to 8.1 with the warning "INUBIT version could not
    be detected (…); assuming 8.1". The remedy is to fix the cause or to pin the version the node
    runs (`V8_1` or a concrete version).
  - Write tools are refused on `V9_X` nodes and on nodes detected as INUBIT 9.x (or any version
    other than 8.1), which used to run them with an "unsupported" warning.
  - A pin that `get_health` contradicts (a different major.minor) refuses writes on that node
    until a later `get_health` reads a matching version or the server restarts with a corrected
    pin; a different patch only gives a warning.
  - StartCLI-backed tools, reads included, are refused on a node whose StartCLI client declares an
    INUBIT range that excludes the node's major.minor (e.g. an 8.1 client at a 9.0 node);
    `get_inventory_item` for a diagram then returns its REST data with the version history in
    `unavailable`.
  - `deploy_release` checks every source and target node in the preview and again before the
    execution, and is refused as a whole if any node is refused or unknown or the nodes run
    different minor versions (8.1.x → 8.1.y is allowed). `restore_backup` of a deployment backup
    follows the deployment rules.
  - A write tool is no longer announced when every node that could run it by configuration is
    pinned to a version that does not support it (e.g. only `V9_X` nodes have `write.enabled`);
    `deploy_release` (and `restore_backup` for deployment backups) only when a node of a target
    group may deploy. `AUTO` nodes count as capable.
- Before a write, the version of an `AUTO` node is detected again (one `/system/info` request)
  if it is older than `inventory.cacheTtl` or was marked stale. A response the 8.1 adapters did
  not expect marks it stale.
- An HTTP 404 from a fixed endpoint (`/system/info`, the diagram list, the log query) is
  `UNEXPECTED_RESPONSE` ("the node may run another INUBIT version") instead of `NOT_FOUND`; a 404
  for a requested process or diagram stays `NOT_FOUND`.
- `list_nodes` reports `versionLine` as the configured string (also a concrete version) instead
  of an enum value.
- The version warnings of `get_health` and the read tools read "INUBIT 9.0.7 on <node> is not
  verified for <capability>; results may be incomplete" and "the INUBIT version of <node> is
  unknown (<code> at <time>); results are unverified", instead of "… is unsupported in this
  version; the 8.1 adapters are used" and "… assuming 8.1".
- Deliberate behaviour changes of feature 010:
  - A `credentials` section on a group or node, refused before as a credential key, now sets
    `source` and `parameters`. Inside every `credentials` section only the documented keys are
    accepted (top level `envPrefix` and `source`; group and node `source` and `parameters`); any
    other key, an AWS access key included, is a startup error naming its path. The error for a
    credential key in the file now names both sources ("… set the value in the environment
    variable (…) or store it in AWS Parameter Store and name it in credentials.parameters").
  - `--check-config` exits 1 also for a configured Parameter Store parameter that is missing,
    empty, denied or invalid, and for an unknown AWS profile; missing AWS credentials and an
    unreachable store are warnings ("resolvability not checked"). It may now access the network,
    but only to read these parameters (and what the AWS credential chain contacts for a login).
  - The checks of the environment variables (collisions, variables shared with another profile,
    `<PREFIX>_*_PASSWORD` variables that match no group or node) consider only nodes that read
    environment variables. A password variable left over for a node that now reads Parameter
    Store is therefore warned about as matching no configured group or node.
  - `find_processes` and `query_logs`: for a Parameter Store node whose credentials are not
    fetched yet, an `INVALID_INPUT` of the node's adapter checks (an unsupported log filter, an
    unsendable character) is that node's `error`; before, it failed the whole call. Checks that
    need no node (time format and range, paging, the `processId` pattern) still fail the whole
    call.
  - A Parameter Store node allows up to two failed logins per 60 s (the re-read after a rejection
    allows one further login with changed values); a node with environment variables keeps one.

### Fixed

- `check_artifacts` decodes percent-escaped repository paths before the existence check, so a
  reference to e.g. `a%20b.xsd` no longer gives a false `REPOSITORY_REF_MISSING`; its messages
  show the decoded path.

### Dependencies

- AWS SDK for Java v2 `2.54.3` (Apache-2.0, pinned by its BOM) for Parameter Store: `ssm`, `sso`,
  `ssooidc`, `sts` and `url-connection-client` as the only HTTP client (the Netty and Apache
  HTTP clients are excluded), used only in `adapter/aws`; the modules are listed in
  `THIRD-PARTY-NOTICES.md`. The executable JAR grows from about 14 MB to about 26 MB.

## [0.5.3] - 2026-10-17

### Fixed

- A repository import that INUBIT carried out is no longer taken for a failure. StartCLI prints
  the import's progress text `Completed = … MB / … MB` without a line break, so its `1-OK`
  message follows on the same line; the output parser now reads it there. Before, every
  repository import of `deploy_release` (the new and changed repository files a release brings
  along) ended as a failure although the node had taken the files.

## [0.5.2] - 2026-10-09

### Fixed

- The automatic git maintenance (`gc --auto` or geometric repack) that a commit in the workspace
  triggers now runs in the foreground of that commit instead of detaching. A detached
  maintenance outlived the call and kept writing packs while the next git call or the cleanup of
  the workspace ran (seen as a sporadic test failure "Failed to delete temp directory …
  .git/objects/pack"). Such a commit now takes a few seconds longer roughly every 6700 new
  objects.

## [0.5.1] - 2026-10-09

### Fixed

- INUBIT writes the outgoing connections of a workflow module in a non-deterministic order. Every
  comparison now ignores that order: `import_artifacts`, `set_active` and `restore_backup` no
  longer end in a false `VERIFY_MISMATCH`, a needless rollback, a false `rollback: FAILED` or a
  false `CONFLICT`; `deploy_release` classifies such workflows as `UNCHANGED` (or `LAYOUT_ONLY`
  if their layout differs as well), finds the source nodes consistent and verifies and rolls back
  without a false mismatch. Fingerprints of previews, backups and the deployment ledger are
  computed with the connections in one order.
- Real differences are still found: a connection added, removed or changed (target, id, label
  position), any other content change and a different order of the workflow modules themselves.
  What is imported, deployed, restored or stored is unchanged, and difference reports still show
  the renderings as the server wrote them.
- Fingerprints that 0.5.0 or earlier recorded in backups and in the deployment ledger are still
  recognised, so restoring such a backup or the next deployment preview is not refused or warned
  about for content that is unchanged. Known limitation: a fingerprint recorded by 0.5.0 or
  earlier while the server showed one connection order is not recognised after the order
  flipped, so restoring such an old backup is refused with `CONFLICT` (nothing is changed) and
  the next deployment preview may warn `OUTSIDE_CHAIN` for that workflow.
- `export_artifacts`, and the write-back after an import or a deployment, keep a workspace
  workflow file that differs from the server's new rendering only in that order: no rewritten
  file, no commit and no `MODIFIED` entry for it. The workspace still holds only renderings as
  the server wrote them.

## [0.5.0] - 2026-10-09

### Added

- `tools/inubit-cert-check.py` (feature 006): an operator tool for changed server certificates of
  a stage. `--check` compares the certificate every server presents with the stage's pin (text or
  `--json`; exit 0, 10 changed, 20 unreachable). `--accept STAGE FINGERPRINT` adopts a certificate
  only after re-verifying it on every server of the stage: backups, trust-store import, single-line
  pin change, validation with the server's `--check-config`, rollback on any failure or interrupt,
  one audit line. `--interactive` (for the launcher) asks in a desktop dialog when the server
  starts and never writes to stdout; `--prune` removes superseded trust-store entries and
  `--forget` drops a remembered rejection. Python 3.9 standard library only, tests in `tools/tests`
  (also run in CI). See `docs/setup.md`, section 5 "Certificate changes".

## [0.4.3] - 2026-10-08

### Fixed

- `import_artifacts`: the preview now lists, under `identical`, an unchanged workspace module that a
  changed workflow references but that the diagram group's export lacks (e.g. a module exported on
  its own, or left by a rolled-back import). Before, the import bound the workflow to that module
  without the preview saying so. Such a module is exported on its own and compared with its
  exported base. If it changed on the node since that export, the import is a `CONFLICT`.
  Unchanged modules inside the group's export are not listed.

### Changed

- The result of `import_artifacts` carries `identical` as well. The tool description, README and
  `docs/tools.md` explain `existing` and `identical`.

## [0.4.2] - 2026-10-08

Not tagged or released on its own; its changes are part of the 0.4.3 release.

### Fixed

- `import_artifacts`: an embedded stylesheet or WSDL that ends with a line break (as an editor
  saves it) no longer fails the verification with `VERIFY_MISMATCH`. INUBIT stores embedded XML
  documents without trailing whitespace: the archive is built that way, and the change set, the
  conflict check and the verification compare `.xsl` and `.wsdl` files without trailing
  whitespace and with either line end.
- `import_artifacts`: after a rolled-back import, the next import of the same diagram group is no
  longer a `CONFLICT`. The conflict check compares reviewed content, so the check-in comment and
  last update a rollback wrote are no change; a new artifact the node has already (e.g. a module
  the rollback left, `createdNotRemoved`) is not sent if identical, otherwise updated as a new
  version and shown as such in the preview (`existing`, `identical`). A module of the change set
  that the diagram group's export lacks is exported on its own, and a module file that an export
  wrote unchanged no longer counts as a local change. Content changes on the node and workflows
  open in the Workbench are still conflicts.
- The conflict and verification difference reports note differences the lines do not show
  (missing trailing newline, line ends, trailing whitespace, a missing file).

### Changed

- `list_nodes` reports `developmentEnabled` (`development.enabled && !production`); the
  description of `writeEnabled` says that it governs restart and kill only.

## [0.4.1] - 2026-10-08

### Changed

- Documentation brought up to date for 0.4.0: README, SECURITY.md, CONTRIBUTING.md,
  `docs/setup.md`, `docs/tools.md` and `docs/live-tests.md` (offered tools, requirements, audit,
  workspace lock, `--check-config` output, error codes and the `deploy_release` challenge).
- The titles of `restore_backup` ("Restore a development or deployment backup on an INUBIT node")
  and `run_e2e_test` ("Send a SOAP test message to an INUBIT node") cover deployment targets too.

## [0.4.0] - 2026-10-07

### Added

- Stage chain and deployments (feature 005): a group with a `deploy` record (`from`, `mode`
  `EXECUTE | PACKAGE_ONLY`, `exclude`) receives releases from exactly one source group;
  `--check-config` shows the chains and each node's `deploy` line.
- `deploy_release`: deploys every diagram group that carries a tag on the source into ONE target
  group, node by node. The first call is always a preview per node (new, changed, layout-only,
  unchanged, excluded, active flags, warnings such as `OUTSIDE_CHAIN`, `SHARED_MODULE`,
  `STAGE_SPECIFIC_VALUE`) with a server code valid `deployConfirmationTtl`; the call with the code
  re-checks, backs up, imports only what changed (repository files, modules, workflows per active
  flag) with each node's own secret values, verifies, rolls back a failing node and stops there,
  then tags the deployed groups, records the ledger and commits the verified state. Package-only
  groups (e.g. production) get owner-only import packages (archives, `diff.txt`,
  `warnings.txt`, `README.md` with the StartCLI commands) and never an import or a tag.
- Error codes `CHAIN_VIOLATION`, `SOURCE_INCONSISTENT` and `DEPLOY_LOCKED`; audit outcome
  `PACKAGED`.
- Deployment live test (`live/DeploymentLiveTest`, opt-in, never production or package-only).

### Changed

- `restore_backup` also restores a deployment backup on the node of the target group it was taken
  on (always with preview and server code); it is offered with an `EXECUTE` target even without a
  development node.
- `run_e2e_test` also runs on non-production nodes of groups that receive deployments, as their
  `e2eTests` allows; it is offered where such a node allows it.

### Fixed

- `import_artifacts` (and deployments): a newly created module was reported as `VERIFY_MISMATCH`
  and rolled back, because INUBIT puts one more `DefaultCommitCommentImport###` in front of the
  check-in comment of a created module; the reason check accepts that form.

## [0.3.0] - 2026-10-07

### Added

- Development on a development stage (feature 004), offered only if a node has
  `development.enabled: true` (never on production groups) and refusing every other node with
  `NOT_DEVELOPMENT`:
  - `import_artifacts`: imports the changed workflows of one diagram group (with their changed or
    new modules), or changed modules, from the workspace. The server enforces the sequence: checks
    of `check_artifacts`, conflict detection against the last verified server state (changed on
    the server, Workbench edit mode), server-side confirmation by default (preview and one-time
    code bound to inputs, workspace and server state, re-checked before sending), a backup,
    an archive with only the changed artifacts and the target's current secret values (in
    memory), protocol matching, verification by re-export with the reason in the check-in
    comment, then the commit with a `Server-State` trailer — or a rollback from the backup.
    Created artifacts are never removed (listed instead).
  - `restore_backup`: re-imports the backup of an earlier development call, limited to the
    artifacts that call changed, with its own backup, conflict check against the state the call
    left, verification and rollback.
  - `set_active`: activates or deactivates one workflow, built from the server's current state.
  - `tag_artifacts`: tags the head versions of whole named diagram groups and their modules
    (INUBIT tags only whole groups). Pre-check and verification export only the requested groups
    (never owner-wide); an existing tag name is reused — it moves to the current versions within
    the requested groups and stays elsewhere; a mismatch or a failing tag command is reported as
    `FAILED` and nothing is removed (a tag removal would act owner-wide).
  - `import_artifacts` takes an optional `tag` for a diagram-group import: the group is tagged
    after the verified import in the same audited call; a tag failure keeps the import
    (`EXECUTED`, `tag.applied: false`, warning to retry `tag_artifacts`); a module import with a
    tag is refused before anything is sent.
  - `run_e2e_test`: sends a SOAP envelope from the workspace (`e2eTests: FREE | CONFIRM`) with a
    test-id header and reports the response, process instances, errors and log entries, found by
    the test id or, marked uncertain, by workflow and time window.
- Settings `development.enabled`, `development.confirmation`, `e2eTests` and `e2e.soap.baseUrl`;
  optional end-to-end basic authentication from `<PREFIX>_<GROUP>[_<NODE>]_E2E_USERNAME` /
  `_E2E_PASSWORD`; `--check-config` shows the development and end-to-end settings per node.
- Backups in `~/.inubit-mcp/<profile>/backups`, kept 30 days (the newest per scope always);
  removals are audited. Every call of the development tools is audited.
- Writes work for user and user-group owners alike: every import names the owner with
  `--importUser` (INUBIT 8.1 refuses `--importUserGroup`). There is no owner-kind lookup and no
  `owners` setting; such a section in a profile is a startup error (unknown key).

## [0.2.0] - 2026-10-06

### Added

- `export_artifacts` (feature 003): exports technical workflows by diagram group, or single
  modules, through StartCLI into a local **workspace** as readable files — one file per workflow,
  per module the module and index files plus every embedded document (stylesheets, WSDLs,
  schemas), the referenced repository files, volatile values under `.meta/` — and records the
  export in the workspace's own git history (never pushed). Uncommitted changes are recorded
  first as `local changes`; an unchanged re-export records nothing. Every secret is replaced by
  `${secret:<property path>}` in memory before anything is written. Offered only if a node has a
  StartCLI installation.
- `check_artifacts`: offline checks of workspace files — workflow structure (edges, ids,
  Demultiplexer keys, parent references, referenced modules with a module-list lookup,
  variables, repository references, derived checksums), stylesheet runs on Saxon-HE 10 with
  deterministic stand-ins for INUBIT's XSLT functions (fixed GUIDs and time, 60 s deadline, no
  access to the server's environment or files outside the workspace), well-formedness and XSD
  validation; findings bounded by `resultLimits` with a full report under `.reports/`.
- Setting `workspace` (default `~/.inubit-mcp/<profile>/workspace`, created `rwx------`);
  `--check-config` shows `Workspace: <path> (ok | created | <problem>)`; two profiles may not
  share or nest their workspaces.
- Requirement for the workspace: `git` 2.32 or newer.

### Dependencies

- `net.sf.saxon:Saxon-HE:10.9` (MPL-2.0) for local stylesheet runs; listed in
  `THIRD-PARTY-NOTICES.md`.

## [0.1.1] - 2026-10-05

### Added

- `tools/check-identifiers.py`: checks the staged changes (`--staged`) or the whole working tree
  (`--all`) against the local identifier lists and reports findings as
  `file:line: <list> rule <n>: <masked match>`, never the full value.
- Git hooks in `.githooks/` that run this check; install them with
  `git config core.hooksPath .githooks`. `pre-commit` checks the staged changes; `pre-push` checks
  what the push publishes (`--push`: diffs, file names, messages and author/committer of the
  commits the push target does not have yet, annotated tags and the names of the pushed refs)
  and the working tree (`--all`).
- An optional local allowlist of exact values (generic words) that the check does not report.
- Setup guide: how to run the server from the Claude desktop app and other GUI clients on macOS,
  which do not read `~/.zshrc`, with a start script that loads the credentials from the Keychain.

### Changed

- The guard test `NoCustomerIdentifiersTest` also uses the regular expressions of the neutralize
  map and the synthetic map as forbidden patterns, not only the denylist.
- The identifier lists are also found in the default directory `~/.config/inubit-mcp/`
  (`%APPDATA%\inubit-mcp\` on Windows), so a local `mvn verify` runs the guard test; new variable
  `INUBIT_MCP_SYNTHETIC_MAP`. Without any list the test is still skipped, and the skip message
  names the lists it looked for.
- Findings of the guard test name the list and the rule number and show the match masked.
- Dependabot proposes Jackson 3 patch releases (security fixes); minor and major updates are still
  aligned by hand together with an MCP SDK update.

### Security

- Jackson 3 (`jackson-core`, `jackson-databind`, `jackson-dataformat-yaml`) 3.1.4 → 3.1.7, which
  fixes GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89, GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54,
  GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf, GHSA-vvgp-rfg2-7rr6 and
  GHSA-5gvw-p9qm-jgwh.

## [0.1.0] - 2026-10-05

First public release.

### Added

- Local MCP server over stdio for INUBIT 8.1, packaged as one executable JAR (Java 21).
- Read-only tools: `list_nodes`, `get_health`, `find_processes`, `query_logs`, `list_inventory`
  and `get_inventory_item` (health and readiness, failed, waiting or hanging process instances,
  log queries, diagram and module inventory with version histories and module usage).
- Write tools `restart_process` and `kill_process` for exactly one process instance on one node,
  off by default, with a production lock, server-side two-step confirmation bound to the instance
  state, and an append-only audit log per profile.
- Profiles: one YAML file per customer or project (`--profile <name>`), configurable terminology
  for the two levels (groups and nodes), per-profile credential prefixes, audit directories and
  temporary data; several profiles run side by side.
- Credentials only from environment variables, secret scrubbing of all output, a 60-second login
  back-off against account lockout, TLS with a dedicated trust store and certificate pinning.
- `--check-config` to validate a profile without starting the server, and `--version`.
- Version-specific REST and StartCLI adapters; an offline test suite with recorded fixtures.
- Migration guide for configuration files of the earlier `stages`/`servers` format.

[Unreleased]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.5.3...HEAD
[0.5.3]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.5.2...v0.5.3
[0.5.2]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.4.3...v0.5.0
[0.4.3]: https://github.com/D4lD4r/inubit-mcp-server/compare/983fa42dc6ebcdd8fe32e6a2b5b5aefeef1296fc...v0.4.3
[0.4.2]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.4.1...983fa42dc6ebcdd8fe32e6a2b5b5aefeef1296fc
[0.4.1]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.1.0
