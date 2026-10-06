# Design: development and deployment through the MCP server

Status: draft for review · Date: 2026-10-05

This document is the umbrella design for turning the INUBIT MCP server from a read-only
diagnostics tool into a tool that can **develop** INUBIT artifacts on a development stage and
**deploy** them along a chain of stages. It is the input for three Spec Kit features (003–005)
and a spike that precedes them. Each of those gets its own spec, plan and tasks under `specs/`;
this document fixes the decisions they share.

All names in this document are examples (profile `acme`, groups `dev`, `int`, `qa`, `acc`,
`prod`). The server knows only generic concepts — groups, nodes, roles and the relations between
them — and every installation describes its own stages in its profile.

## 1. Goal and scope

**Goal.** An MCP client (e.g. Claude Code) implements a change requested in natural language on an
INUBIT development stage — module content (XSLT, WSDL, configuration documents) **and** workflow
structure and logic (nodes, connections, branch conditions, variables, assignments) — tests it,
and promotes the result along the stage chain up to the last stage before production. For
production it prepares a reviewed package that a person imports.

**In scope**

- Exporting workflows, modules and repository files into an editable, diff-friendly file tree.
- A local git mirror that records every export, import and deployment.
- Local checks before every import: structure, XSLT execution, XML/XSD validation.
- Import, activation, tagging and restore on development stages.
- End-to-end tests on stages whose policy allows them (SOAP first, AS2 later).
- Deployment of tagged releases from a stage to its configured successor, node by node, with a
  server-enforced preview and confirmation; package-only mode for production.

**Out of scope**

- Deleting workflows or modules.
- Creating connectors that need credentials, or setting any credential value. A person does
  that in the Workbench.
- Skipping stages that the configuration does not chain.
- Bulk operations across several diagram groups in one call.
- Script modules (not observed in real exports; revisit only if the spike finds them).

## 2. Principles

1. **Universal server, installation-specific configuration.** Stage names, the chain, write
   rights and test policies are configuration. Nothing in code or documentation refers to a
   particular installation.
2. **The development stage is the source of truth.** People keep working in the Workbench in
   parallel. The git mirror records state for diffs and traceability; it is never the source of
   a deployment.
3. **Safety is enforced by the server, not hoped for in the client** (Constitution I). The
   client cannot bypass the chain, a confirmation, a test policy or the production rules.
4. **No secret reaches the conversation, the workspace or git** (Constitution II). Secret values
   are replaced by placeholders on export and restored from the target, in memory, on import.
5. **Round trip without loss.** Export → workspace → import package yields an archive that INUBIT
   treats as identical to the original. Everything else builds on that.
6. **Verify, don't assume.** Every import and deployment is followed by a re-export and a
   comparison with the intended state.

## 3. Configuration model

The new capabilities are settings of a **group** (and, where it makes sense, of a node), like the
existing `production` and `write` settings. Without them the server behaves exactly as today.

```yaml
defaults:
  workspace: ~/.inubit-mcp/acme/workspace   # default: ~/.inubit-mcp/<profile>/workspace

groups:
  - name: dev
    development:
      enabled: true          # import, activate, tag, restore allowed here
      confirmation: CLIENT   # SERVER (default: preview + one-time code) or CLIENT
    e2eTests: FREE           # FREE | CONFIRM | FORBIDDEN (default FORBIDDEN)
    e2e:
      soap:
        baseUrl: https://inubit-dev.example.test:8080   # per group or per node

  - name: int
    deploy:
      from: dev              # the only allowed source; no "from" = no deployments into this group
    e2eTests: FREE

  - name: qa
    deploy: { from: int }
    e2eTests: CONFIRM

  - name: acc
    deploy: { from: qa }
    e2eTests: CONFIRM

  - name: prod
    production: true
    deploy:
      from: acc
      mode: PACKAGE_ONLY     # EXECUTE (default) | PACKAGE_ONLY
      exclude:               # in addition to the default exclusions (section 7)
        - diagramGroup: GRP-SYS
```

**Rules enforced at startup** (a violation is a configuration error; the server does not start and
`--check-config` exits 1):

- `deploy.from` names an existing group; the chain has no cycles.
- On a group with `production: true`: `development.enabled` is not allowed; `e2eTests` must be
  `FORBIDDEN`; `deploy.mode: EXECUTE` additionally requires the existing `write.productionOptIn`.
- Deployments always use the server-side two-step confirmation; there is no option to turn it off.

`--check-config` prints the chain (`dev → int → qa → acc → prod (package only)`) and, per node, the
development, deployment and test rights. The existing `write.*` settings for `restart_process` and
`kill_process` are unchanged and independent.

## 4. Export format (known facts)

From an earlier analysis of INUBIT 8.x diagram exports (format version 8.0.28), confirmed and
extended on 8.1.17 by the spike ([findings](../research/spike-development-deployment.md)).

- One ZIP per export, entries without directory entries (a module-only export adds one empty
  `workflow/` entry):
  - `archive.properties` — Java properties: a date comment, `sourceVersion`, `operationId`.
  - `workflow/workflow.xml` — **all** exported workflows in one file:
    `IBISWorkflow/Workflows/WorkflowGroup(WorkflowGroupName)/Workflow`.
  - `module/module.xml` — index of all modules, grouped by plugin type.
  - `module/<lower-case module name>.xml` — configuration of each module.
  - `Repository.zip` — nested ZIP with shared repository files: `<file>.dat` (content) plus
    `<file>.xml` (metadata), referenced from stylesheets as `inubitrepository:/<path>`.
- Module content (stylesheets, WSDL, configuration documents) is **entity-escaped XML inside a
  `<Property>`**, never CDATA. `InternalDocument` properties are gzip + base64. The
  `xslt.base64Zipped` flag does not reflect the actual encoding; decode by content.
- A workflow refers to modules **by name**. Node ids (`ModuleId`) are unique only within one
  workflow. Edges are `Connection@moduleOutId`. Branch conditions of a Demultiplexer are instance
  properties keyed by `<Name>(<ModuleId>)`, so renaming a node or changing its id must update them.
- Secrets: **every** property with `type="Password"`, whether or not it carries
  `encrypted="true"` and whatever the value format (`AES-…`, `AESG…`, older non-AES encodings,
  plain text); embedded keystores and certificates (base64); `isPassword` literals and
  `is:password` variables in workflows; saved XSLT test values (`xslt.sourceVariables`), some of
  them unencrypted. Encrypted values count as secrets: the encryption is installation-independent,
  so the ciphertext is as good as the password.
- Volatile values: `archive.properties` (date comment, `operationId`), ZIP entry times, the suffix
  of `CheckinComment` that the export writes (exporting user, server, version, export time), and
  after an import `WorkflowUId` / `ModuleUId`. `LastUpdate`, `ExportUser`, module files and
  repository contents are stable. `CheckoutUser` is **not** volatile: it marks a workflow in edit
  mode and is the conflict signal of section 6.
- Stylesheets are XSLT 1.0, 2.0 and 3.0; INUBIT runs **Saxon 10**. Stylesheets may call INUBIT
  extension functions through `java:` namespaces.
- A diagram-group export contains every module used by its workflows; a module-only export has
  the same layout with an empty `workflow/` entry.
- `ModuleId` and `ConnectionId` share one id space per workflow.
- Artifacts are identified **by name**. `WorkflowUId` and `ModuleUId` are reassigned by every
  modifying import; they are volatile.
- Encrypted values are stable across exports and installation-independent: most are identical on
  two stages, stage-specific ones differ.
- INUBIT does not validate an imported workflow: dangling edges and references to missing modules
  are stored, and the latter makes every export of the diagram group fail.
- Of one owner's 656 stylesheets, 88 % compile on Saxon-HE 10 as they are; the rest need about 20
  INUBIT extension functions (`com.inubit.ibis.xsltext.Formatter`, `Misc`, `ISFunctions`).

## 5. Workspace and git mirror (feature 003)

One local git repository per profile at `workspace` (directory `rwx------`). The server never adds a
remote and never pushes. One directory per group, so that a stage comparison is a file diff:

```
workspace/<group>/
  workflows/<diagram group>/<workflow>.xml      one file per workflow (split from workflow.xml)
  modules/<plugin type>/<module>.xml            module configuration, content properties replaced by references
  modules/<plugin type>/<module>.<ext>          embedded content as real files (.xsl, .wsdl, .xsd, .xml)
  repository/<path>/<file>                      repository files
  .meta/…                                       volatile values (committed, excluded from review diffs)
  .tests/…                                      outputs of local checks
```

Backups are raw INUBIT exports and contain secrets (including ciphertext, which is portable).
They are kept **outside** the git repository, in `<workspace>/../backups/<auditId>.zip`
(directory `rwx------`, files `rw-------`), are never committed and never returned in a tool
result; the audit log references them by `auditId` only.

**Normalization.** XML is written with one canonical indentation and attribute order; volatile
values (the export suffix of `CheckinComment`, `archive.properties`, UIDs) move to `.meta/`, so
exporting an unchanged artifact twice yields an empty diff. `CheckoutUser` stays in the reviewed
content. Embedded XML is escaped the way INUBIT writes it (only `<` and `&`). Layout
values (`StyleSheet` coordinates, juncture points) stay in place; diffs report layout-only changes
separately.

**Secrets.** Every secret value listed in section 4 is replaced by a placeholder
`${secret:<artifact>/<property path>}`. Embedded keystores with private keys are replaced; plain
certificates without a private key may stay. Logins, host names and URLs are not secret and stay.

### Tools

| Tool | Purpose | INUBIT access |
|---|---|---|
| `export_artifacts` | export one or more diagram groups (StartCLI exports per owner and diagram group, always with every used module and the owner's whole repository) or single modules from one group or node; the server writes the selected workflows, their modules and the repository files they reference into the workspace and commits | read (StartCLI export) |
| `check_artifacts` | local checks of workspace files; mandatory before every import (the import runs it itself) | none |

`export_artifacts` exports from the first node of a group unless a node is given. Its result lists
the changed paths and the commit; the client edits the files with its own tools.

`check_artifacts` reports per artifact:

- **Structure** (INUBIT itself checks none of this on import): every edge targets an existing
  `ModuleId`; Demultiplexer condition keys match the outgoing edges; every referenced module exists
  in the workspace or on the server (including published library modules); `ModuleId` and
  `ConnectionId` are unique together per workflow; variable references resolve;
  `inubitrepository:` references resolve.
- **XSLT**: transformation with Saxon-HE 10 of a given input file; output written to `.tests/`.
  Stylesheets that need Saxon-EE features (schema awareness, reflexive extension calls without a
  stub) are reported as *not testable locally* — never as passed. Stubs for the INUBIT extension
  functions found by the spike are part of 003; without them about one stylesheet in nine could
  not be checked.
- **XML/XSD**: well-formedness; validation against embedded or repository schemas.

## 6. Development on a development stage (feature 004)

All tools in this section are available only on nodes with `development.enabled` (exception:
`restore_backup` after a deployment, section 7). With
`development.confirmation: SERVER` each call first returns a preview and a one-time code. Every
call is audited.

### `import_artifacts`

The server enforces these steps in this order:

1. **Check** — run `check_artifacts`; errors abort, warnings are passed through.
2. **Detect conflicts** — export the affected artifacts from the node and compare them with the
   export commit the workspace is based on. A change made in the meantime (e.g. in the Workbench)
   or an artifact in edit mode (`<CheckoutUser>` set; Workbench "Edit", not yet "Publish") aborts
   with `CONFLICT` and returns the diff. INUBIT itself does not protect an artifact in edit mode:
   an import overwrites it and a later publish overwrites the import, both silently. With
   `development.confirmation: SERVER` the check runs in the preview **and again** in the execute
   call, immediately before the import. Residual risk, documented in the tool description: after
   an import INUBIT drops the `CheckoutUser` marker although the Workbench is still editing, so a
   person who edited before the import can still publish over it.
3. **Back up** — keep that fresh export as a backup (section 5, outside git).
4. **Assemble** — build the import archive from the workspace; restore secret placeholders from
   the fresh export, in memory only. A placeholder without a counterpart aborts with
   `SECRET_UNRESOLVED`. The `reason` of the call becomes the check-in comment. Only changed
   artifacts go into the archive (every import creates a new version, even an unchanged one):
   changed workflows as a workflow archive with only their **changed** modules, changed modules
   alone as a module archive; `Repository.zip` is empty unless repository files changed. Before
   importing, the server lists the archive's workflows and modules and aborts if anything is not
   part of the intended change (guard).
5. **Import** — `import --importFile <zip> --importWorkflow --importUser|--importUserGroup <owner>
   --returnProtocol` (modules: `--importModule`); the protocol lists every created or modified
   artifact and must match the archive.
6. **Verify** — export again and compare with the intended state; on success commit
   `import <group>: …`.

**Rollback.** Any failure after the first archive was sent — import exit code ≠ 0, a protocol
that does not match the archive, a failing re-export, or a verify mismatch — makes the server
re-import the backup at once (only the artifacts of this call) and verify that, too; the result is
`IMPORT_FAILED` or `VERIFY_MISMATCH` with what happened and whether the rollback succeeded. When a
change needs a module archive and a workflow archive, modules are imported first; a failure in the
second step rolls back both.

### Further tools

| Tool | Purpose |
|---|---|
| `set_active` | activate or deactivate one workflow (re-import of an archive that contains **only** that workflow and none of its modules, with `--importWorkflowActive` / `--importWorkflowInactive`; the flag applies to every workflow in an archive; creates a new version) |
| `tag_artifacts` | tag the current version of one or more **diagram groups** (with their modules) — the smallest unit INUBIT can tag; always scoped by owner and diagram group, never owner-wide (an empty or blank group is rejected before StartCLI runs, and the server checks afterwards that only the intended artifacts carry the tag); refuses to move an existing tag; the tag is the release marker for deployments |
| `restore_backup` | re-import a backup by `auditId`, limited to the artifacts the backed-up call changed (never the whole group backup); same steps as `import_artifacts`, including the conflict check against the state the call left behind. Also available on a deployment target for the backups its deployments took; there it always requires preview and code |
| `run_e2e_test` | send a test message and collect the outcome (below) |

### End-to-end tests

`run_e2e_test` is governed by `e2eTests` of the node: `FREE` runs at once, `CONFIRM` requires
preview and code (the preview names endpoint and payload), `FORBIDDEN` refuses with
`E2E_FORBIDDEN`.

- **SOAP** (first increment): input is the workflow, the operation and an envelope file from the
  workspace. The server posts the envelope to `e2e.soap.baseUrl`, records the response, then
  correlates process instances, errors and log entries in the time window of the test (the logic
  of `find_processes` and `query_logs`) and returns them together.
- **AS2** (second increment): needs a sender with certificates, signing, encryption and MDN
  handling; designed in its own spec section once SOAP works.

## 7. Stage chain and deployment (feature 005)

**What is deployed.** A **release**: one or more diagram groups with their modules and repository
files, identified by a tag on the source group and exported with `--exportTag`. Deployments always come from the target's `deploy.from`
group, so what moves on is exactly what was deployed and tested there; content hashes prove it.
After a successful deployment the same tag is set on the target.

**Stage-specific values.** Artifacts are expected to be stage-independent: stage-specific
settings live in system diagrams or are set at runtime. Therefore:

- A release is imported **unchanged**.
- Artifacts that configure a stage are **never deployed**. System diagrams are excluded by
  default; `deploy.exclude` adds rules by diagram type, diagram group or name pattern. The preview
  lists every excluded artifact of the release.
- Safety net: if a release contains secrets, the target keeps its own values (restored as in
  section 6); a secret without a counterpart on the target aborts with `SECRET_UNRESOLVED`.
- Differences in host names, URLs or logins between release and target are reported as warnings
  ("stage-specific value?"); the server does not change them.

### `deploy`

**Step 1: preview** (`target` = a group, `release` = a tag):

1. Check the chain: the source is `deploy.from` of the target, else `CHAIN_VIOLATION`.
2. Export the release from the source. With several source nodes, all must hold the same content,
   else `SOURCE_INCONSISTENT`.
3. Export the same artifacts from **every target node**.
4. Diff per node: new, changed, layout-only, excluded, warnings.
5. Freeze the package and return the summary, the paths of the diff files in the workspace and a
   one-time code.

**Step 2: execute** (with the code; mode `EXECUTE`), node by node: back up, import only the new
and changed artifacts of the release (unchanged ones would get a new version for nothing), verify
by re-export; the rollback rule of section 6 applies per node. The active flag of each workflow is
taken from the release (as it is on the source stage). If a node fails, the remaining nodes are not touched; the result lists the state of
every node and offers `restore_backup` per finished node (on these stages restore also needs a
preview and code). One deployment per target group at a time (`DEPLOY_LOCKED`). The mirror of the
target group gets the commit `deploy <target> ← <source>: <tag>`.

**Mode `PACKAGE_ONLY`**: the same preview; instead of executing, the result is the archive, a diff
report and the list of warnings for the person who imports it.

## 8. Errors and audit

New `ToolError` codes, each with likely cause and next step: `NOT_DEVELOPMENT`,
`VALIDATION_FAILED`, `CONFLICT`, `SECRET_UNRESOLVED`, `IMPORT_FAILED`, `VERIFY_MISMATCH`,
`CHAIN_VIOLATION`, `SOURCE_INCONSISTENT`, `DEPLOY_LOCKED`, `E2E_FORBIDDEN`.

The audit log keeps its format and gains: the release tag, per-node results, the backup reference
and the list of affected artifacts (names only — never content or secrets). Every tool of sections
6 and 7 is audited, including refusals and previews.

## 9. Testing

- **Offline** (Constitution III): round-trip tests on real development-stage exports that were
  anonymized with `tools/neutralize.py` (the identifier guard covers the fixtures); import and
  deployment against a fake StartCLI; configuration tests for every forbidden combination of
  section 3; secret-placeholder tests proving that no secret value reaches workspace, git, tool
  results or logs.
- **Live** (opt-in): read-only as today; write tests only against nodes of groups with
  `development.enabled`, never against `production`.

## 10. Spike (before feature 003)

Done on 2026-10-06; findings in
[docs/research/spike-development-deployment.md](../research/spike-development-deployment.md).
The questions were:

1. StartCLI import: command, options, behaviour for existing artifacts, check-in and versioning,
   checked-out artifacts, error output.
2. Round trip: export → change `workflow.xml` (add a node, change an edge and a branch condition)
   → import → export: is the change applied exactly, and are ids stable?
3. Export of a tagged version; setting tags and the active flag (REST or StartCLI).
4. Layout of a module-only export.
5. `AES-…` secret values: stable across exports? Accepted when imported on another server of the
   chain?
6. Which stylesheets of a real export run on Saxon-HE 10 without stubs.

The spike's findings go into `docs/research/` and decide the open points of features 003–005.

**Still open after the spike** (to be settled in the specs of 003–005):

- Question 5, second half: whether imported secret values are accepted on another server was not
  probed (no write outside the development stage); the design restores secrets from the target
  anyway.
- Import with a UID that exists under another name (all probes used fresh UIDs for new names).
- Edit-mode markers for modules (only workflows showed `CheckoutUser`).
- Writes for a shared owner (`--importUserGroup`, `tag --tagUser <group>`): all probes wrote to a
  personal diagram group.
- Renames: with identity by name, a renamed artifact is a new one; the old one stays unless a
  person deletes it.
- Export durations for large groups and a suitable timeout (the existing `cliExportTimeout`
  applies until measured).
- Script modules: none were found; nothing is designed for them.

## 11. Delivery order

1. Spike (section 10).
2. Feature 003: workspace, export, git mirror, `check_artifacts`.
3. Feature 004: `import_artifacts`, `set_active`, `tag_artifacts`, `restore_backup`,
   `run_e2e_test` (SOAP, then AS2).
4. Feature 005: chain configuration and `deploy`.

New tools in total: eight (`export_artifacts`, `check_artifacts`, `import_artifacts`,
`set_active`, `tag_artifacts`, `restore_backup`, `run_e2e_test`, `deploy`), each tied to a use case
as Constitution IV requires.
