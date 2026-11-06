# Research: INUBIT 9 Support

**Feature**: 017-inubit-9-support | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

Base: the test branch `0.8.0` (012 INUBIT 6.1 CLI reads, 013 StartCLI on Windows, 016 update
check), merged into this branch before planning. Baseline of the merged state:
`mvn -o clean verify` green, 4998 tests, 0 failures, 51 skipped.

Sources: `docs/research/inubit-interfaces.md` §4, the 9.0 breaking-changes and StartCLI pages of
the vendor documentation (summarised in spec "Context"), the code of `0.8.0`.

---

## D-1 Configuration shape of the clients per version

**Decision**: `defaults.cliClients`, a YAML mapping from `"<major>.<minor>"` to
`{home, javaHome?}`:

```yaml
defaults:
  cliHome: /opt/inubit-8.1/client          # version-less client (unchanged meaning)
  cliJavaHome: /opt/jdk-17
  cliClients:
    "8.1": { home: /opt/inubit-8.1/client, javaHome: /opt/jdk-17 }
    "9.0": { home: /opt/inubit-9.0/client, javaHome: /opt/jdk-21 }
```

- Keys must match `^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$`. Jackson hands map keys over as the raw
  scalar text, so unquoted `8.1:` and `9.0:` work as well; `9.00`, `9`, `9.x`, `V9_0` are
  rejected with the path `defaults.cliClients.<key>`.
- Duplicate keys: a dedicated deserializer for `cliClients` reads the mapping token by token and
  reports a duplicate key as a load error. Global `STRICT_DUPLICATE_DETECTION` is **not** switched
  on: it would change the behaviour of every other mapping of 0.8.0 profiles (FR-009).
- `home` is required; `javaHome` optional; both use the `Path` binding of `cliHome`/`cliJavaHome`
  (same path syntax and canonicalisation). Unknown fields in an entry fail like everywhere else
  (`FAIL_ON_UNKNOWN_PROPERTIES`).
- A key whose version the capability matrix does not declare (e.g. `"9.1"`, `"7.0"`) loads and is a
  `--check-config` warning (FR-003). It is still chosen for a node whose effective version has
  that major.minor (the entry is the operator's explicit wish); the warning only says that the
  version is not declared supported, so its tools run with the matrix's unknown-version rules.
- Only in `defaults`: groups and nodes keep `cli.home`/`cli.javaHome`, which already win.

**Alternatives rejected**: version keys under `cli` of a group (would multiply the entries per
group, the request is "per version"); a list of `{versions: range, home}` (ranges overlap and need
ordering rules; the matrix declares major.minor anyway); keying by `versionLine` names (`V9_0`)
(an `AUTO` node has no line).

## D-2 Where the choice is computed: configuration, not adapter

**Decision**: `EffectiveNodeConfig` keeps the layers of the StartCLI settings (`CliSources`: the
explicit `cli.url`, node and group `cli.home`/`cli.javaHome`, `defaults.cliClients`,
`defaults.cliHome`/`cliJavaHome`) and offers `cliFor(Optional<InubitVersion>)` → `CliChoice`
(the effective `Cli` plus where home and address came from). `cli()` becomes the choice for the
configured pin (`cliFor(<pinned version>)`, `cliFor(empty)` for `AUTO`): for `AUTO`, 6.1 and 8.1
pins without `cliClients` this is exactly the 0.8.0 value (FR-009); `ConfigValidator` keeps using
`cli()` for its static checks.

**Rationale**:
- `PackageBoundaryTest` (013 T026) forbids `config` → `adapter`; `--check-config`
  (`ConfigValidator`, `ConfigSummary`) and `list_nodes` must show the choice for a pinned node
  without running anything.
- The default StartCLI address is already a configuration default (`DEFAULT_CLI_PATH` in
  `EffectiveNodeConfig`); making it depend on the major version is the same kind of default.
- FR-014's "addresses" is met in this sense: the rule is one table keyed by the major version in
  the configuration resolution (`AddressKind`), not a branch in tool logic. The spec is clarified
  accordingly (plan review round, see `reviews.md`).

## D-3 Which version selects the client and the address

**Decision**: the version passed to `cliFor` is the node's effective version
(`VersionObserver.effectiveVersion`, feature 009: pin, detection, `get_health` observation) at the
moment a StartCLI process is about to be launched. Rules of `cliFor(version)` with the node's
`versionLine`:

| Situation | Client directory | JDK | Default address |
|---|---|---|---|
| node or group `cli.home` set | that one | node → group `cli.javaHome` → `defaults.cliJavaHome` | by major (below) |
| effective version with minor `M.m`, entry `"M.m"` exists, line is not `V9_X`-like | entry `home` | node → group `cli.javaHome` → entry `javaHome` → `defaults.cliJavaHome` | by major |
| otherwise | `defaults.cliHome` (may be empty) | node → group `cli.javaHome` → `defaults.cliJavaHome` | by major |

Address: explicit `cli.url` (node → group) wins; otherwise `<baseUrl>/ibis/servlet/IBISSoapServlet`
if the major is < 9 or unknown, `<baseUrl>` if the major is ≥ 9. The major comes from the
effective version, else from a line pin (`V9_X` → 9).

- A line without minor (`V9_X`) never chooses an entry, even after an observation (FR-011: "no
  `defaults.cliClients` entry is chosen for it").
- Unknown version (failed detection, `AUTO` not yet detected) → version-less client, servlet
  address: identical to 0.8.0 (FR-009, spec edge case).
- `baseUrl` is stored without trailing slash (`Urls.withoutTrailingSlash`), so `<baseUrl>` never
  ends with `/`; the 9.x address is `https://host:port` or `https://host:port/<context>` as
  configured.

## D-4 Applying the choice: once per StartCLI call, at the CLI entry points

**Decision**: `CliRunner` gets the version source (`VersionObserver::effectiveVersion`) and a
method `Selection select(EffectiveNodeConfig server, Optional<InubitVersion> version)` that returns
`server.withCli(server.cliFor(version).cli())` together with the `CliChoice`, the `ClientKey` and
the address warning (D-19). Every entry point that launches StartCLI (`run`, `runSession`,
and the package-level `execute` used by the `-v`/`-h` probes) reads the version once and selects
once; `checkAvailable` (no launch) selects with the same single read. The probes select by the
version their caller passes (`ClientVersionPort.probe(node, version, timeout)`, D-5). `launchTarget`, the Windows refusals (013), `javaHome`, the
trust-store handling and `HandshakeStartCliPinProbe` keep reading `server.cli()` and therefore see
the chosen values without change. `V61Connection` selects at its start (it reads `cli().url()`
before calling the runner). `select` is idempotent (`cliFor` reads the unchanged `CliSources`).
`ClientSelectionPort` (D-5) is implemented on the same `select` with the caller's version.

**Launch guard (plan reviews 1 and 2, finding 1/2)**: the gate admits with a version snapshot,
the runner launches later; a concurrent `get_health` can change an `AUTO` node's effective
version in between, so the runner could launch a client nobody checked. The guard closes exactly
that gap without launching anything itself (no recursion through `CliVersionProbe`, no extra
budget):
- `CliRunner` gets a read-only view `ClientCheckLookup` (new domain port, implemented by
  `NodeVersions`, wired as for the version source): `Optional<ClientCheck> check(NodeId,
  ClientKey)` (a recorded result or a recorded failure) and `boolean anyCheck(NodeId)`.
- Before a launch through `run` or `runSession` (the tool launches; not `execute`, the
  `-v`/`-h` probe path, and not `--check-config`):
  - a result for the chosen key whose range excludes the version selected for → refused before
    the launch with the 009 `UNSUPPORTED_VERSION` text;
  - a result whose range cannot be evaluated, or a recorded failure for the key → launched, as the
    gate allows it today;
  - no check for the chosen key, but a check for another key of the node (the key changed since
    the last admission) → `CLI_UNAVAILABLE` before the launch: "The StartCLI client for <node>
    changed to the client for INUBIT <M.m> during this call; the new client has not been checked
    yet" / next step "Call the tool again"; nothing was started;
  - no check for the node at all (first use; e.g. a 6.1 health session that starts in parallel
    with the probe) → launched, as in 0.8.0.

The gate always probes the current key before it admits a CLI capability, so the refusal case is
reachable only in the race; outside it every path behaves as in 0.8.0 (FR-009).

**Write lock (D-24)**: the gate's capability decision for a write is bound to the version it was
made on; a write is handed off only while the node's known version is the admitted one, under a
per-node lock held until the hand-off (D-24). The launch guard above runs inside that locked
section for writes.

**Rationale**: smallest change with one place of truth; the 013 Windows checks (UNC home, refused
characters in `cli.home`, `cli.javaHome`, `cli.url`) automatically apply to a version entry's paths
(FR-003 "on Windows"); `tls.pinPerServer` (014) checks the chosen address (spec edge case).

**Alternatives rejected**: rebuilding the port sets when the version changes (the slot already
rebuilds per registry range, but 8.1 and an unknown version share the default range, and the
`-v` probe and `--check-config` do not go through port sets); resolving in every adapter (many
call sites, easy to miss one).

## D-5 A client check belongs to the client it checked (FR-017)

**Decision**:
- Domain value `ClientKey(String home, String javaHome)`: the chosen client directory and the
  configured JDK (empty string for "none"/server `JAVA_HOME`); no credentials, no URL.
- `ClientVersionPort.probe` returns `ProbedClient(ClientKey key, ClientVersion version)` — the key
  of the client that was actually launched, chosen at launch time.
- `ClientVersionPort.probe(NodeId, Optional<InubitVersion> version, Duration)`: probes the client
  chosen for the given version (the caller's own snapshot, never a fresh read of the shared state).
- New domain port `ClientSelectionPort` (implemented in `adapter/cli`, backed by `CliRunner.select`
  and the file-existence predicate), every method taking the caller's version snapshot:
  `Optional<ClientKey> current(NodeId, Optional<InubitVersion>)` (empty: no client),
  `boolean available(NodeId, Optional<InubitVersion>)` (chosen `startcli` exists),
  `boolean anyCandidate(NodeId)` (D-7) and `Optional<String> warning(NodeId, Optional<InubitVersion>)`
  (D-19).
- `NodeVersionState` keeps client results and failures in maps keyed by `ClientKey` (bounded by the
  number of distinct clients of the node, at most node/group home + entries + default).
  `needsClientProbe(now, key)` is true if no result for `key` exists and no failure for `key` is in
  its 60 s back-off. A result or failure of one key never touches another key's, so A→B→A reuses
  A's result and keeps A's back-off (plan review 1, finding 5).
- `VersionGate` asks `ClientSelectionPort.current(node, version)` with the version of its own
  snapshot before deciding to probe, probes with that version and judges only the result of that
  key.
- `CliClientProbe` (012, `-h` options) caches per `(NodeId, ClientKey)`.

## D-6 `get_health` and the client chosen for the observed version

**Decision**: `HealthService` starts the client probe in parallel as today, for the version it
knows at the start. After reading `/system/info` it computes `ClientSelectionPort.current(node,
observed)` with the version *this call* observed (not the shared state, which an overlapping call
may change) and compares it with the probed key: equal → report as today; different → start one
more probe for `observed` within the remaining single-node budget and report that; if the budget is
exhausted the `client` part is `unavailable` with "the StartCLI client for INUBIT <version> on
<node> has not been checked yet; call get_health again" (FR-017).

## D-7 `withClient` becomes dynamic

**Decision**: the static `Set<NodeId> withClient` of `Wiring` (startcli of the version-less client
exists) is replaced by `ClientSelectionPort.available(node, versionSnapshot)` for `VersionGate`,
`HealthService` and `list_nodes` (`cliAvailable`, the allowed capabilities). Tool
announcement at startup (`export_artifacts`, `export_archives`) uses `anyCandidate(node)`: a node
pinned to a version with minor uses only the choice for that version; `V9_X` and other pins
without minor use only the version-less sources; only an `AUTO` node considers every entry, because
its version is not known yet (plan review 1, finding 6).

## D-8 Missing or mismatching client: messages (FR-005)

**Decision**:
- No client from any source: `CLI_UNAVAILABLE` "No CLI home is configured for <node>"; when the
  version is known with minor *and* (`defaults.cliClients` is configured or the major is ≥ 9) the
  message names the version and the next step reads "Add `defaults.cliClients."<M.m>".home` (the
  INUBIT <M.m> client directory) or set cliHome". Otherwise the 0.8.0 text is kept unchanged
  (FR-009).
- Client range excludes the version: the existing `UNSUPPORTED_VERSION` refusal of 009; its next
  step gains "or add `defaults.cliClients."<M.m>"`" under the same condition.
- JDK hint in the "no JDK" message: "(8.1: JDK 17, 9.0: JDK 21)".

## D-9 Version values and the 9.0 matrix entry (FR-001, FR-002, FR-011)

**Decision**:
- `VersionSetting.V9_0 = new Line(9, OptionalInt.of(0))`; `parse("V9_0")`; the load error texts
  list `AUTO, V6_1, V8_1, V9_0, V9_X or a full version`.
- `CapabilityMatrix.production()`: the 9.0 entry becomes `ALL_SUPPORTED` (the same states and
  channels as 8.1, REST and CLI). `V9_X` keeps the line-without-minor rule (`stateOfLine` → unknown
  → reads unverified, writes refused).
- `ConfigValidator`: `V9_X` gets a warning "versionLine V9_X of <node> does not select 9.0 support;
  use V9_0 (INUBIT 9.0.x) or AUTO".
- `DeployGuard` keeps "same major.minor"; 9.0 ↔ 9.0 is now admitted because writes are supported.

## D-10 Fixtures for 9.0 and the release guard (Constitution V)

**Decision**:
- `src/test/resources/fixtures/v9_0/` gets documentation-derived fixtures for every endpoint and
  StartCLI output 9.0 uses: `rest/` (healthcheck, ready, system_info, metrics, log GET/POST,
  model_models, modelByName, model_export, the error bodies 401/403/404/500/503) and `cli/` (`-v`,
  `-h`, processErrorStart, kill, unknown_command, login_failed, unreachable, ps, export, import,
  tag). Windows invocation is not a 9.0 fixture case: 013's `record-fixtures.ps1` has no recorded
  fixtures and no test consumer yet (`fixtures/windows` does not exist); 017 leaves the `.ps1`
  unchanged and covers 9.0 on Windows by the live check (quickstart step 11; plan review 3).
- Contract tests run the 8.1 adapters against these fixtures (FR-002).
- **Provenance per case, positive (plan review 2, finding 3)**: every case has a mandatory
  `<case>.origin` file next to its fixture files: `reconstructed <source>` (documentation section)
  or `recorded <date> <INUBIT version>`. Only the record scripts write `recorded …`; a
  documentation-derived fixture is created with `reconstructed`. A missing origin file fails the
  build, so an omitted entry can no longer pass for "recorded".
- **Required cases (closed world)**: the 9.0 catalogue is the list
  `tools/record-fixtures.sh --list` prints for `RECORD_INUBIT_LINE=9.0` (REST and POSIX StartCLI,
  all read-only; plan review 2, finding 4). `V90FixtureCatalogueTest` (always) asserts in both
  directions (plan review 3, finding 3): every catalogue and every non-recordable case has its
  fixture and origin files, and every fixture file belongs to one of them; the set of cases the
  9.0 contract tests load (one registry `V90Cases`, the only way those tests open a fixture)
  equals catalogue ∪ non-recordable.
- **Non-recordable cases** (`fixtures/v9_0/NOT_RECORDABLE.txt`, each with a reason; they stay
  `reconstructed`; plan review 3, finding 2):
  - write successes (import, tag, processErrorStart, kill): the read-only recorder cannot produce
    them; confirmed by the live check (quickstart steps 8–9, the result texts are compared with the
    fixtures);
  - REST 403, 500, 503: no reproducible way to provoke them on a test node; they stay
    documentation-derived (SC-006 names the exemptions; the set in
    `NOT_RECORDABLE.txt` is asserted exactly, so no exemption can be added silently). What 017 relies on is status-driven: the status → `ErrorCode` mapping is
    shared with 8.1 and contract-tested, and the body is used only as a scrubbed excerpt (D-12);
    a deviating body changes only that excerpt.
- The sh catalogue gets the 9.0 additions that are read-only: `help` (`-h`), `rest_auth_failed`
  (GET `/system/info` with the dummy password → 401 body), `rest_not_found` (modelByName of a name
  that does not exist → 404 body).
- `V90RecordedFixturesTest`: fails when the build version (`BuildInfo`, from `pom.xml`) is a
  release (`SemVer` without pre-release) while a catalogue case's origin is not `recorded`.
  Pre-release builds (`0.8.0-test.1`, `0.8.1-test.N`) pass with a skipped-test message naming the
  open cases.
- Recording: `tools/record-fixtures.sh` gets `RECORD_INUBIT_LINE=9.0` (fixtures to
  `fixtures/v9_0/rest|cli`, CLI URL `<baseUrl>`, each REST case with the server's `Accept`, D-11)
  and writes the `.origin` file; the 8.1 default stays. `RecordFixturesCatalogueTest` covers the
  new option.

**Deviation**: Constitution V asks for recorded responses before a version counts as supported.
The person decided (clarify Q2/Q3) that 9.0 is declared supported now and the live recording is a
release blocker. The release guard enforces recordings for every recordable case; the write
successes are confirmed by the live check (the release blocker the person set); REST 403/500/503
stay documentation-derived with a status-driven contract; see plan "Complexity Tracking".

## D-11 `Accept` per request (FR-007)

**Decision**: `InubitHttpClient` sends the `Accept` header of the request instead of the fixed
`application/json, application/xml;q=0.9, */*;q=0.8`: `get`/`post` get an `Accept` parameter
(enum `MediaRange`: `XML` = `application/xml, */*;q=0.1`, `JSON` = `application/json, */*;q=0.1`,
`ANY` = `*/*`). Per endpoint:

| Endpoint | Parser | Accept |
|---|---|---|
| `/healthcheck`, `/ready` | JSON | JSON |
| `/system/info` (detector, monitoring, inventory) | XML | XML |
| `/metrics?format=json` | JSON | JSON |
| `/log/{name}` (POST) | JSON | JSON |
| `/model/models`, `/model/modelByName/{name}` | XML | XML |
| `/model/export/{name}` | ZIP | ANY |

**Rationale**:
- 9.0 makes `Accept` mandatory and chooses JSON or XML from it; the 0.8.0 header prefers JSON, so a
  9.0 `/system/info` that offers JSON would break the XML parser. An XML-only 9.0 endpoint answers
  406 to a JSON-only `Accept`; the `*/*;q=0.1` fallback avoids that.
- On 8.1 each endpoint produces a single format per request (or the one selected by `format=`),
  and `*/*` stays acceptable, so 8.1 receives what it receives today (FR-007 "8.1 unchanged";
  verified by the 8.1 contract tests, which additionally assert the header).
- The header is version-neutral; it is part of the shared adapters, no branch on the version.

## D-12 JSON error bodies (FR-008)

**Decision**: the status mapping of `InubitHttpClient` is status-based and stays; a 9.0 JSON
error body changes only the excerpt. When the body is JSON with a string `message`, the excerpt
is that message (scrubbed, length-capped as today) instead of the raw body; otherwise unchanged.
401 keeps ignoring the body. Contract tests with the 9.0 error fixtures assert codes and scrubbed
excerpts for 401, 403, 404 (resource and endpoint), 500 and 503 (maintenance via the healthcheck
probe as on 8.1). On 8.1 (HTML/plain bodies) nothing changes.

## D-13 URL strictness of 9.0

**Decision**: no change. `baseUrl` is stored without trailing slash, every path is a constant with
a single leading slash, path segments are percent-encoded (`/` as `%2F`, as on 8.1). A contract
test asserts that no request path contains `//`, `/./` or `/../` for the 9.0 fixtures.

## D-14 Port set for 9.0

**Decision**: register `VersionRange(9, 0, 9, 0)` in `PortSetRegistry` explicitly with
`V81PortSet::create` (no CLI health factory). Behaviour equals the registry default today, but the
registration documents that 9.0 is served by the 8.1 adapters, verified against 9.0 fixtures, and
gives a later 9.0 deviation its place. An `AUTO` node moving 8.1 → 9.0 rebuilds its ports
(existing slot behaviour, no network access).

## D-15 StartCLI version output (FR-012)

**Decision**: the range pattern of `CliVersionProbe` becomes case-insensitive
(`Pattern.CASE_INSENSITIVE` on "Supported inubit Process Engine versions from … to …"); the
`CLI <version>` line stays. The existing 9.0 fixture (`fixtures/v9_0/cli/version.*`, lower-case
"inubit", documentation-derived) is used; an unrecognised output stays "not evaluable" (009).

## D-16 Login on 9.0 (FR-013)

**Decision**: unchanged mechanics (password on stdin, Basic for REST). `CliOutputClassifier`'s
`AUTH_FAILED` next step gets one sentence when the major of the version the client was chosen for
(`CliChoice.major`) is ≥ 9, whatever the address kind (an explicit `cli.url` is `CONFIGURED`): "On
INUBIT 9 the user must exist in the internal user management; users that log in only through OAuth
cannot use StartCLI". `CliRunner` passes the choice to the classifier; 8.1 texts unchanged (plan
review 1, finding 2).

## D-17 `--check-config` (FR-006, FR-010, FR-011, FR-015)

**Decision**:
- Distinct clients = the set of `(home, javaHome)` of all version entries, the version-less client
  and node/group homes; each is probed once with `startcli -v` (local, no INUBIT contact).
- Warnings: an entry whose client range excludes its key's version; a missing `home` or `javaHome`
  directory; for an 8.1/9.0 entry or a node pinned to 8.1/9.0, an effective JDK whose major
  (`JAVA_VERSION` in `<javaHome>/release`) is not 17/21; a key the matrix does not declare;
  `V9_X` (D-9); a pinned ≥ 9 node whose explicit `cli.url` contains the servlet path (FR-010).
- The JDK major reader is a small config helper with an injected file reader (no adapter); an
  unreadable `release` file is "JDK version unknown" (no warning).
- The summary line of each node shows `client: <dir> (<source>), address: <kind>` for its pinned
  version; for `AUTO` the version-less choice with "chosen at runtime".

## D-18 `list_nodes` field (FR-015)

**Decision**: additive object `startCli` per node:
`{ clientDirectory?: string, source: "node"|"group"|"version_entry"|"defaults"|"none",
versionEntry?: "9.0", address: "servlet_path"|"base_url"|"configured", final: boolean }`,
computed from the currently known effective version without node contact. `final` is false while
the version is unknown (`AUTO` not yet detected or failed). The directory is a local path, no
credentials and no URL (the `list_nodes` rule "no URLs" holds: only the address kind is shown).

## D-19 Servlet-address warning on tools (FR-010)

**Decision**: `CliRunner.select` also yields `Optional<String> addressWarning` when the explicit
`cli.url` contains `/ibis/servlet/IBISSoapServlet` and the major used for the choice is ≥ 9. The
CLI-backed services add it to their result warnings through the existing warning channel of the
gate admission (`VersionGate` asks `ClientSelectionPort.warning(node, versionSnapshot)` when the
channel includes CLI), so no tool code branches on it.

## D-20 Tests that used 9.0 as "an undeclared version" (SC-003)

**Decision**: about 25 test classes use `9.0.7` (or the 9.0 entry) as the example of an
unverified/unsupported version. Those whose subject is the behaviour of an undeclared version move
their example to `9.1.0` (still undeclared after 017), assertions unchanged. Tests whose subject is
9.0 itself (`CapabilityMatrixTest.readsAreUnverifiedAndWritesUnsupportedOn90`) are replaced by the
new 9.0 expectations. SC-003 is clarified to permit exactly these two kinds of change besides the
additive `list_nodes` field (see `reviews.md`).

## D-21 Windows (013)

**Decision**: version entries go through the same rules as `cli.home`/`cli.javaHome` on Windows:
`startcli.bat`, no UNC home, refused characters (D-4 makes `CliRunner` check the chosen paths);
`ConfigValidator`'s Windows checks (013) iterate the entries too. The JDK `release` file is read
the same way on Windows. No Windows-specific code in 017 beyond that.

## D-22 Touch points with 012, 013, 016

017 builds directly on the merged state:
- 012: `CapabilityMatrix` entries/channels/`cliOnly`, `PortSetRegistry` ranges, `NodeVersionState`
  (`endpointMissing`), `CliClientProbe`; 017 changes the 9.0 entry, adds a range, keys client
  results. The 6.1 behaviour is unchanged (6.1 nodes are pinned; a `"6.1"` entry would be chosen
  for them like any other entry).
- 013: `startcli.bat`, Windows refusals, `ProcessContainment`; reused through D-4/D-21.
- 016: no touch point.

## D-23 Release notes and version

**Decision**: `CHANGELOG.md` gets an `0.8.1 (unreleased)` section; `pom.xml`'s version is not
changed by 017 (the integration branch owns the version). Docs: `docs/setup.md` (cliClients,
default address, JDKs, migration 8.1 → 9.0), `docs/tools.md` (`list_nodes.startCli`, 9.0
support), `README.md` (supported versions), `docs/research/inubit-interfaces.md` §4 (what 017
relies on), a live-check list for 9.0 in `quickstart.md`.

## D-24 A write is sent only on the version it was admitted on (FR-018)

**Context**: the gate admits a write on the version known at admission; the write is handed off
later (each `import`/`tag`/`processErrorStart`/`kill` launch, the E2E request). A concurrent
`get_health` or detection can change the node's known version in between, so a write admitted on
8.1 could be sent to a node now known as 9.0 (0.8.0 has this gap for 8.1 → 9.0, where 9.0 refused
writes) or one admitted on 9.0 to a node known as 9.1. The person decided (plan review 5) to close
the gap in 017, for every transition including 8.1 ↔ 9.0.

**Decision**:
- `VersionGate.Admission` gains the version the call was judged on (`Optional<InubitVersion>`;
  present for every admitted write, because writes need a known version). The write guards
  (`DevelopmentGuard` → `DevelopmentPolicy`, `WriteGuard` → `WritePolicy`, `DeployGuard` →
  `Admitted`, per target node) hand out a `WritePermit(NodeId node, Capability capability,
  InubitVersion admitted)`; the capability is the write purpose the call was admitted for (any
  capability of kind `WRITE`: `DEVELOPMENT_WRITE`, `DEPLOYMENT`, `E2E_TEST`, `PROCESS_CONTROL`;
  `DevelopmentGuard` takes it from the operation, `E2E_TEST` for `run_e2e_test`, `DEPLOYMENT` for
  the restore of a deployment backup), so a port serving several purposes (`ImportPort`,
  `TagPort`) learns it from its caller.
- Every state-changing port method takes the permit: `ImportPort.importArchive(…, WritePermit)`,
  `ImportPort.importRepository(…, WritePermit)`, `TagPort.tag(…, WritePermit)`,
  `ProcessControlPort.restart/kill(…, WritePermit)`, `E2ePort.post(Message, WritePermit)`. All
  callers pass the permit of their admission (`ImportService` incl. `DiagramGroupTagger`,
  `RepositoryStep`, restore and set-active; `TagService`; `NodeDeployer` incl. rollback;
  `ProcessControlService`; `E2eTestService`); decorators (`StaleReportingPorts`) pass it through.
  A permit for another node is refused (`IllegalArgumentException`, programming error).
- New domain port `WriteLock` (implemented by `NodeVersions`): `<T> T handOff(WritePermit permit,
  Function<Optional<InubitVersion>, T> action)` takes the node's lock, reads the current
  effective version and the `stale` flag, refuses unless the state is not stale and the effective
  version has the permit's major.minor, and otherwise runs `action` with that version while
  holding the lock. Every `NodeVersions` state change of that node (`update`) takes the same lock,
  so the node's known version cannot change between the check and the end of `action`. One fair
  `ReentrantLock` per node (`new ReentrantLock(true)`, always taken with blocking `lock()`, never
  `tryLock`), so a version change queued during a hand-off is applied before any hand-off queued
  after it (no barging, no starvation of updates); writes on one node hand off one after the
  other (a hand-off takes milliseconds). `action` must neither change the node's version state
  nor hand off again: `update` or `handOff` from inside a hand-off on the same thread throws
  `IllegalStateException` (a nested hand-off would keep the lock across the inner wait).
- Refusal: `UNSUPPORTED_VERSION` "INUBIT on <node> changed from <admitted> to <current> during this
  call; the write was admitted for INUBIT <M.m>; nothing was sent" (stale or unknown current
  version: "INUBIT on <node> may run another version than <admitted> since this call was
  admitted; …"), next step "Call the tool again; it is judged on the version INUBIT runs now".
  Built from node id and versions only (SC-005).
- CLI writes: `CliRunner.run(…, WritePermit)` (new overload; the one without permit stays for
  reads). Before the lock: one unlocked version read, selection, Windows checks, `tls.pinPerServer`
  probe (network, so outside the lock). Inside `handOff`: the version read under the lock must
  select the same `ClientKey` and address as before the lock, else `CLI_UNAVAILABLE` "changed
  during this call" (D-4 text); then the launch guard (D-4) with that version; then
  `ProcessLauncher.launch`. The lock is released when the process has started, before waiting for
  its output; the credential guard (`guarded`) wraps the whole as today.
- E2E: `SoapE2eClient.post(message, permit)` builds the request, then inside `handOff` hands it to
  the HTTP client (`sendAsync`) and leaves the lock when the request was handed over; it waits for
  the response outside the lock (bounded by the message timeout as today).
- Multi-step writes (`deploy_release`, `import_artifacts` with tag, restore): every hand-off
  checks the same permit. A refused step is a failed step of the existing failure handling (as a
  StartCLI error); a rollback is a write with the same permit and is refused as well after a
  version change (`ROLLBACK_FAILED` as today; importing the pre-deploy exports of the admitted
  version into another version is what `deploy_release` refuses in general). Its warning names
  both versions and says to restore the backup only on the admitted version (contracts). The
  backup manifest records no INUBIT version, so `restore_backup` itself cannot check this; that
  holds in 0.8.0 for any upgrade between deploy and restore and is outside 017 (design review 1
  #4).
- Reads take no lock and no permit (not destructive; D-4 keeps the single read per launch).

**Rationale**: the decision and the hand-off see the same version; the lock covers exactly the
check and the irreversible hand-off and never a network wait. Comparing major.minor matches the
matrix (keyed by major.minor), the client choice (D-3) and the deploy rule; a patch change keeps
the decision valid, and the client's range for the full version is re-checked by the launch guard
inside the lock. Covers 8.1 → 9.0 (0.8.0's gap) and every other transition the same way. The
lock orders the write against what the server knows about the node; an upgrade the server has not
noticed yet is caught, as in 0.8.0, by the `stale` marking of D-10 and the next detection, not by
the lock.

**Alternatives rejected**: re-checking without a lock (round 3, leaves a window, round 4);
holding the lock for the whole tool call (blocks version updates for minutes during deploys);
re-judging the capability instead of comparing major.minor (the matrix is keyed by major.minor,
so equal major.minor means equal decision; a different major.minor must be re-admitted anyway
for the deploy rule and the client); a permit without capability (`ImportPort`/`TagPort` serve
two purposes; the refusal and audit need it).

