# Research: Credentials Only from a Credential Store (feature 011)

Decisions R-1 … R-17. Each: decision, rationale, alternatives considered. Feature 010's decisions
(`specs/010-secret-sources/research.md`) stay in force unless a decision here replaces one.

## R-1 How the server reads each store

- **Decision**: one adapter per operating system behind a new `config` port `CredentialStore`
  (R-2); the server picks the adapter by `os.name` once at startup.

  | System | Adapter | Mechanism | Requesting program |
  |---|---|---|---|
  | macOS | `KeychainStore` | subprocess `/usr/bin/security find-generic-password -s inubit-mcp -a <ACCOUNT> -g` (R-3) | `/usr/bin/security` |
  | Windows | `WindowsCredentialStore` | `CredReadW(L"inubit-mcp:<ACCOUNT>", CRED_TYPE_GENERIC)` / `CredFree` through JNA (R-4) | — (no per-program ACL) |
  | Linux | `SecretServiceStore` | subprocess `secret-tool lookup service inubit-mcp account <ACCOUNT>` (R-5) | `secret-tool` |
  | other | `UnavailableStore` | every read is "store not available" | — |

- **Rationale**:
  - macOS: entries created with `security add-generic-password` (today's how-to) list
    `/usr/bin/security` as trusted application, and people who answered "Always Allow" for the start
    script granted it to `/usr/bin/security`. Reading through the same program keeps that grant, so
    SC-001 needs no new dialog for such entries. A native call (Security framework through JNA)
    would make `java` the requesting program: a dialog per entry, again after every JDK update, and
    "Always Allow" would grant every Java program of the person — a weaker boundary.
  - Windows has no read tool for generic credentials (`cmdkey` only lists, adds and deletes;
    PowerShell needs a third-party module or compiling C# with `Add-Type` on every start, about a
    second). The Credential Manager has no per-program access control, so a native call has no
    security downside.
  - Linux: `secret-tool` is the reference client of libsecret, speaks D-Bus to whatever implements
    the Secret Service (GNOME Keyring, KeePassXC, KWallet ≥ 5.97), handles unlock prompts, and needs
    no Java D-Bus stack (which would add `dbus-java`, a socket library and a crypto provider for the
    session encryption).
  - Values never appear on a command line: only service and account names are arguments; the value
    comes back on the child's stdout/stderr pipe and is registered with the scrubber at once
    (FR-012).
- **Alternatives**: `java-keyring` (JNA + `secret-service` + `dbus-java`) — rejected: three
  dependencies, `java` as requesting program on macOS, and its Windows target-name scheme is not the
  one FR-004 fixes; one PowerShell script for Windows — rejected (slow, execution-policy issues);
  the Java 22 Foreign Function API instead of JNA — rejected while the baseline is Java 21 (the API is
  a preview feature there); libsecret through JNA on Linux — kept as fallback if `secret-tool` turns
  out to be missing too often (it is a separate package on Debian/Ubuntu: `libsecret-tools`).

## R-2 Port and layers

- **Decision**:
  - `config`: port `CredentialStore` with `StoreKind kind()` (`KEYCHAIN`, `CREDENTIAL_MANAGER`,
    `SECRET_SERVICE`, `NONE`, for messages) and `Lookup read(String account, Duration timeout)`;
    `Lookup` is sealed: `Found(Secret value)` (an `infra.Secret`, so no `toString` shows it), `NotFound`,
    `Failed(FailureKind kind, String detail)`; `detail` never contains output that may carry the value
    (R-3, R-5).
    The service `inubit-mcp` is a constant of the port (Out of Scope: not configurable).
    `FailureKind` gains `STORE_NOT_AVAILABLE` and `TIMEOUT`; `UNKNOWN_PROFILE` and `NO_CREDENTIALS`
    stay for Parameter Store.
  - `adapter/os` (new, `package-info.java`): the four adapters and `CredentialStores.forOs(String
    osName, ProcessLauncher)`. Only this package may use `com.sun.jna` (`PackageBoundaryTest`); it
    starts processes only through the existing `ProcessLauncher` (no `ProcessBuilder`, no
    `ProcessHandle`, as `onlyTheCliAdapterStartsOrInspectsProcesses` requires — the rule is widened to
    allow `ProcessLauncher` use in `adapter/os`).
  - `CredentialResolver.E2eCredentials` moves to `config.E2eCredentials` (used by `NodeSecrets`,
    `Wiring`, `SoapE2eClient`).
  - `adapter` (root): `NodeSecrets` gains the credential store variant (R-7); `CredentialGuard`
    always has a `Rereadable` (R-9).
- **Rationale**: the same shape as 010's `ParameterStore` keeps `NodeSecrets`, the guard and the
  check code source-neutral; tests above the adapter use an in-memory `FakeCredentialStore`.
- **Alternatives**: one adapter per store type selectable in the profile — rejected (FR-001: one
  type, the store of the running system; SC-007).

## R-3 macOS: `security` output and outcomes

- **Decision**: run `/usr/bin/security find-generic-password -s inubit-mcp -a <ACCOUNT> -g` through
  `ProcessLauncher` with an absolute path, an environment of `HOME`, `USER`, `LANG` only, stdin
  closed, stdout discarded, stderr read (`-g` prints the password to stderr). The stderr text is split
  into the line starting with `password:` (parsed, then dropped) and the other lines; only the other
  lines may ever reach a log or `Failed.detail` (Constitution II: the value is not yet registered with
  the scrubber when the outcome is unexpected).
  - `password: "<text>"` → the text; `password: 0x<HEX>  "<escaped>"` → `<HEX>` decoded to bytes,
    then strict UTF-8 (malformed → `INVALID_VALUE` "cannot be decoded"); `password: ` with nothing
    after it → empty → not found.
  - Outcome by **exit code first** (the low byte of the OSStatus): 0 → `Found`; 44
    (`errSecItemNotFound`) → `NotFound`; 128 (`errSecUserCanceled`) and 51 (`errSecAuthFailed`) →
    `ACCESS_DENIED`; 36 (`errSecInteractionNotAllowed`: no GUI session, e.g. SSH, or locked without a
    possible prompt) and 50 (`errSecNoSuchKeychain`) → `STORE_NOT_AVAILABLE`; timeout → process tree
    destroyed → `TIMEOUT`; any other code → `ACCESS_DENIED`, the non-password stderr lines in the log.
    Message texts are only a second signal when the code is ambiguous.
- **Rationale**: `-w` prints printable values raw but non-printable ones as hex without a marker, so
  a password of hex digits would be ambiguous; `-g` marks the hex form with `0x`. Exit codes and
  texts are recorded by the macOS live check before the release (risk until then); the parser is
  unit-tested against recorded outputs, including a `password:` line with an unexpected exit code
  whose value must not appear in the log.
- **Alternatives**: `-w` with a hex heuristic — rejected (ambiguous); Security framework through
  JNA — rejected (R-1); detecting an SSH session — rejected (exit code 36 says it directly).

## R-4 Windows: `CredReadW` through JNA

- **Decision**: dependency `net.java.dev.jna:jna` (core only, pinned 5.17.0, Apache-2.0 / LGPL-2.1 dual
  license; Apache-2.0 chosen). `jna-platform` has no `CREDENTIAL` mapping, so `adapter/os` maps
  `CREDENTIALW` (fields up to `CredentialBlobSize`, `CredentialBlob`), `CredReadW` and `CredFree` of
  `Advapi32` itself (about 40 lines, `W` functions only). Target name `inubit-mcp:<ACCOUNT>`, type
  `CRED_TYPE_GENERIC` (1). Outcomes: success → copy `CredentialBlobSize` bytes, `CredFree`, decode
  strict UTF-16LE (odd size or unpaired surrogate → `INVALID_VALUE`), wipe the byte copy;
  `ERROR_NOT_FOUND` (1168) → `NotFound`; `ERROR_NO_SUCH_LOGON_SESSION` (1312) →
  `STORE_NOT_AVAILABLE`; other errors → `ACCESS_DENIED` with the error number in the log. The read
  runs on its own thread and is abandoned after the timeout (`TIMEOUT`); it never prompts. A failed
  native load (`LinkageError`: `UnsatisfiedLinkError`, `NoClassDefFoundError`, e.g. when
  `jnidispatch.dll` cannot be extracted to the temp directory) is caught in the adapter and mapped to
  `STORE_NOT_AVAILABLE` (the `NodeSecrets` worker catches only `RuntimeException`); tested through
  the `CredApi` seam.
- **Rationale**: `cmdkey /generic:inubit-mcp:<ACCOUNT> /user:<any> /pass` (without a value it
  prompts) and the Credential Manager UI both store the password as UTF-16LE, which is what the
  documentation prescribes (FR-004 "read exactly as typed"). JNA loads its native stub only on
  first use, so macOS and Linux never load it; the shaded JAR grows by about 2 MB (natives of all
  platforms). JNA is not relocated in the shaded JAR (its native lookup needs its package name); it
  is listed in `THIRD-PARTY-NOTICES.md`.
- **Alternatives**: `jna-platform` (1.4 MB, no `CredRead` mapping); PowerShell (R-1).

## R-5 Linux: `secret-tool` output and outcomes

- **Decision**: run `secret-tool lookup service inubit-mcp account <ACCOUNT>` through
  `ProcessLauncher`; the executable is the first existing of `/usr/bin/secret-tool`,
  `/usr/local/bin/secret-tool`, then a `PATH` lookup (absent → `STORE_NOT_AVAILABLE` "secret-tool is
  not installed (package libsecret-tools or libsecret)"). Environment: an allowlist of
  `DBUS_SESSION_BUS_ADDRESS`, `XDG_RUNTIME_DIR` (libsecret finds the session bus through it when the
  address is missing), `DISPLAY`, `WAYLAND_DISPLAY`, `HOME`, `USER`, `LANG`, `PATH` — never the
  leftover `INUBIT_*` variables (as `CliRunner` never passes credential variables). stdin closed;
  stdout is the value as stored (no line break is added when stdout is a pipe), decoded strict UTF-8.
  stdout is never logged; only stderr is (it carries no value). It searches all collections and
  unlocks a locked one through the session's prompter.
  - Exit 0 with output → `Found`; exit 1 without output and without stderr → `NotFound`; stderr with
    "Cannot autolaunch D-Bus" / "org.freedesktop.secrets was not provided" / no bus → 
    `STORE_NOT_AVAILABLE`; timeout → `TIMEOUT`; other stderr → `ACCESS_DENIED`.
  - **Risk**: a dismissed unlock prompt most likely also ends with exit 1, no output and no stderr,
    i.e. like not found ("Cannot create an item in a locked collection" is an error of
    `secret-tool store`, not of `lookup`). To be verified in the Linux live check; if confirmed, the
    Linux not-found text says "not found (or the unlock prompt was dismissed)", the docs say so, and
    the manual access-denied step of FR-017 checks that text.
- **Rationale**: `secret-tool lookup` behaves like the Assumption "store per system". The
  documentation tells to create entries with `secret-tool store --label='inubit-mcp <ACCOUNT>'
  service inubit-mcp account <ACCOUNT>`, which reads the value from a prompt (no line break stored).
- **Alternatives**: libsecret through JNA (R-1).

## R-6 Entry names, prefix and configuration

- **Decision**: `CredentialVariables` is renamed `CredentialNames` (same derivation, kinds and
  `candidates(kind)`, node before group). `SecretSourceConfig.Environment` becomes
  `SecretSourceConfig.CredentialStore(Optional<String> prefix)`, YAML `type: credentialStore`. The
  loader's `checkSource` refuses `type: environment` with "credentials.source.type environment was
  removed in 0.7.0; credentials come from the credential store of this system (type credentialStore,
  the default) or AWS Parameter Store (type awsParameterStore); see docs/migration-environment-
  variables.md" (key path included). `CredentialsConfig` gains `prefix`; the top-level keys are
  `prefix`, `envPrefix` (deprecated alias) and `source`. `ProfileConfig.credentialPrefix()` returns
  `prefix`, else `envPrefix` (both set and different → error), else the default; a top-level
  `source.prefix` keeps 010's rule (it applies to inheriting nodes and must equal the profile-wide
  prefix if one is set), and overrides without their own `prefix` use `credentialPrefix()`. `SecretPlan` keeps its shape: a credential store plan holds
  `CredentialNames`, an AWS plan `ParameterNames`.
- **Rationale**: the derivation code and its tests (collisions, other profiles, `e2e` node name) are
  reused as they are; only the meaning of the names changes.
- **Alternatives**: keep the class name `CredentialVariables` — rejected (misleading after the
  removal).

## R-7 Lazy reads for every node (`NodeSecrets`)

- **Decision**: every node gets a `NodeSecrets`; `NodeSecrets.credentialStore(EffectiveNodeConfig,
  CredentialStore, SecretScrubber, Clock)` next to `NodeSecrets.aws(...)` (the node configuration
  gives `tls.trustStore`, the CLI configuration, `e2e.soap.baseUrl` and `e2eTests`, which R-11
  needs). `NodeSecrets` is split into the source-neutral part (cells: single flight, 30 s back-off,
  settle grace, validation through `CredentialRules`, scrubber registration, `secret.fetch` record)
  and a per-source `SecretReader` with its own texts: `ParameterReader` (today's code and texts,
  unchanged: `unavailable()`, `pending()`, interrupted message, `callError` with location, log keys
  `region`/`parameters`) and `EntryReader` (per-entry reads with node-to-group fallback; log keys
  `store`/`entries`). The environment constructor is removed.
- **Resolved credentials**: `NodeCredentials` keeps its resolved form (built only inside
  `NodeSecrets` from read values; used by `PortSetRegistry.NodeResources`, the CLI runners,
  `V81ProcessControlAdapter`, `InubitHttpClient.create`, `CredentialGuard.bind`, the gateway slot);
  the deferred placeholder (`deferred` flag, `NodeCredentials.deferred`) is removed, since every node
  now has a lazy `NodeSecrets`.
- **Main read** (credential store): per kind `USERNAME`, `PASSWORD`, and `TRUSTSTORE_PASSWORD` only
  when `tls.trustStore` is set — the node entry and, if it is `NotFound` or empty, the group entry; a
  `Failed` or invalid node entry does not fall back. **E2E cell**: always present for credential store
  nodes; it yields `Optional<E2eCredentials>`: none found → empty (no basic authentication), exactly
  one → `AUTH_FAILED` naming the missing entry, both → the credentials (R-11 checks first).
- **Waiting**: `NodeSecrets.timeout()` returns the source's timeout (60 s credential store, 10 s
  Parameter Store); `AdapterGatewayFactory.awaitCredentials()` (today `SECRETS_WAIT` =
  `STORE_TIMEOUT`) and the e2e function in `Wiring` use it, so a single-node call waits up to 60 s for
  an open dialog instead of giving up after 10 s.
- **Rationale**: one code path for both sources keeps FR-006/FR-008/FR-013 identical to 010 while
  the Parameter Store texts stay byte-for-byte unchanged.
- **Alternatives**: read all kinds of all nodes at startup — rejected (FR-006, dialogs for unused
  production nodes).

## R-8 Timeout, serialisation and fan-out allowance

- **Decision**: `NodeSecrets.STORE_TIMEOUT` stays 10 s for Parameter Store; the credential store
  uses `CREDENTIAL_STORE_TIMEOUT` = 60 s, not configurable (Out of Scope keeps the profile small; can
  be added later without breaking). The adapters share one fair lock (`StoreGate`), so only one
  entry is read at a time process-wide; the 60 s deadline of a node's read is fixed when the read
  starts, includes the waiting for the lock, and ends the read (`TIMEOUT`, the child process
  destroyed) when it passes. `SecretTiming` generalises from `awsNodes` to a per-node `fetchWait`
  (10 s for AWS nodes, 60 s for credential store nodes), plus `GRACE`, added only while the node's
  credentials are not held (`NodeSecrets.held()`, which never reads; FR-008), so fan-out deadlines
  stay as today once the credentials are read; `get_health` keeps its budget
  and reports a running read as "credentials not yet available (still reading from the <store>)".
  A rejected login inside a fan-out on a node whose credentials are held starts a re-read (up to
  60 s) that no allowance covers: that branch ends in `TIMEOUT`, the re-read runs to its end, and the
  next call on the node uses its result (or waits for it, as the guard makes new attempts wait).
  Tested in `CredentialGuardTest` and `InventoryServiceTest`.
- **Rationale**: per-entry dialogs on macOS (FR-008, Edge Cases); a fan-out over N new nodes then
  opens the dialogs one after the other, and a node whose deadline passes fails its part only.
- **Alternatives**: a configurable `defaults.credentialStoreTimeout` — deferred; parallel reads —
  rejected (stacked dialogs).

## R-9 Rotation for every node

- **Decision**: `CredentialGuard` is built with the node's `NodeSecrets` as `Rereadable` for every
  authenticated client. The constructor without `Rereadable` stays for clients that never
  authenticate (`InubitHttpClient` constructors reached by `V81MaintenanceProbe`, and tests); it can
  no longer be used for a node's authenticated client (`AdapterGatewayFactory` always passes the
  node's `NodeSecrets`). The re-read uses the node's own source timeout (10 s or 60 s).
- **Rationale**: FR-009, SC-005; the guard logic of 010 R-10 is unchanged.

## R-10 What remains of `CredentialResolver`

- **Decision**: it becomes `LeftoverVariables` (FR-003) plus the name checks: from the server's
  environment it reports the names of `ProfileConfig.credentialNameSet()` — every kind of every
  node, under the prefix the node would use as a credential store node, also for AWS nodes — that
  are set, in one warning. The collision checks move to `ConfigValidator` on `CredentialNames` of
  credential store nodes (unchanged rules). The "matches no configured group or node (typo?)"
  warning and the e2e variable lookup are deleted. `Launcher` and `LiveTarget` pass
  `System.getenv()` only to `LeftoverVariables`. Further callers that change:
  `EffectiveNodeConfig.credentialVariables()` → `credentialNames()`; `SslContexts`
  (`CredentialNames.TRUSTSTORE_PASSWORD`, texts via `SecretLabels`); `TestWiring` (its `of(...)`
  overloads take no `CredentialResolution` any more, R-14); `ConfigValidator` texts that name
  environment variables (credential key found in the file; "come from environment variables" for
  ignored `parameters`; `envPrefix` hints in the collision messages) name entries and
  `credentials.prefix` instead; `CredentialResolverTest` is replaced by `LeftoverVariablesTest` and
  the collision cases in `ConfigValidatorTest`.
- **Rationale**: SC-002 (no code path reads a credential value from the environment) is then
  checkable by search: `System.getenv` appears only in `Launcher`/`Wiring` for configuration
  location, the AWS SDK and `LeftoverVariables` (names only).

## R-11 Startup checks that read variable values today

- **Decision** (Edge Cases):
  - short `_PASSWORD` / `_E2E_PASSWORD`: moves into `CredentialRules.checkValue` at read time (as for
    Parameter Store);
  - trust store password on a node with CLI: `ConfigValidator` cannot know whether the entry exists;
    `NodeSecrets` refuses the main read with `AUTH_FAILED` ("… has a CLI configured and needs a
    password-less trust store; delete the entry <name>") before any request, and `--check-config`
    reports it as an error;
  - trust store password entry without `tls.trustStore`: not read at run time; `--check-config` looks
    the names up and warns ("ignored");
  - e2e entries with an `http://` `e2e.soap.baseUrl` while e2e tests are allowed: `NodeSecrets.e2e`
    refuses before anything is sent; `--check-config` reports an error when an entry exists.
- **Rationale**: keeps every safety rule, only the moment changes.

## R-12 Error texts and codes

- **Decision**: `SecretLabels` gets a credential store variant: label "credential store entry
  <ACCOUNT> (<store>)", `fixCredentials()` "Create or correct the credential store entry
  inubit-mcp / <node name> (or <group name>) in the <store>; no restart is needed", and per kind the
  next step with the system command (e.g. macOS `security add-generic-password -U -s inubit-mcp -a
  <ACCOUNT> -w`). Codes: `NOT_FOUND`, `ACCESS_DENIED`, `INVALID_VALUE`, `STORE_NOT_AVAILABLE` →
  `AUTH_FAILED`; `TIMEOUT` → `UNREACHABLE`. The environment variant and its "restart the MCP client
  from a shell" text are deleted; `CliOutputClassifier`'s login-failure text uses `SecretLabels`.
- **Rationale**: FR-010.

## R-13 `--check-config`

- **Decision**: `EntryCheck` (next to `ParameterCheck`) reads, for every credential store node, the
  required kinds (node then group name), the optional kinds when present, and validates them as at
  run time, through the same `StoreGate` and 60 s timeout per node; outcome per name as in FR-011.
  Each distinct account is read at most once per run (a group entry shared by many nodes opens at most
  one dialog). Entries checked only for existence (a trust store password without `tls.trustStore`)
  are looked up without reading the value where the store allows it (macOS:
  `find-generic-password` without `-g` reads attributes only and presumably opens no dialog — to
  verify in the live check; otherwise the value is read and discarded).
  The first `STORE_NOT_AVAILABLE` or `TIMEOUT` stops the check of the store (one warning, the rest
  `not checked`). `ConfigSummary` replaces the `Credential variables:` line with `Credential store
  entries: service inubit-mcp, account <PREFIX>_<GROUP>[_<NODE>]_<KIND> (<store>)` and shows
  `secrets: credentialStore (<store>)` on every node line (always, since the source is never
  implicit-environment any more).
- **Rationale**: US4; the dialogs appear in `--check-config` too, which is acceptable (the person runs
  it interactively) and documented.

## R-14 Live tests

- **Decision**: `Wiring` gets a `CredentialStore` seam next to its `Supplier<ParameterStore>`:
  production passes `CredentialStores.forOs(System.getProperty("os.name"), launcher)`, every wiring
  test a `FakeCredentialStore`, `TestWiring`'s `of(...)` overloads take a store instead of a
  `CredentialResolution`. `LiveTarget` keeps running the same `ConfigLoader`/`ConfigValidator` as the
  server and hands the real store (and `SsmParameterStore`) to `TestWiring`, so it resolves exactly as
  the server; `LiveTargetTest` checks the wiring instead of reading `credentials().credentials(...)`.
  New opt-in class `CredentialStoreLiveTest` (`-Plive-store`, enabled only with
  `INUBIT_LIVE_STORE=1`): on the current system it expects entries `INUBIT_LIVETEST_DEV_USERNAME`,
  `INUBIT_LIVETEST_DEV_PASSWORD` (a value with `ä€` to cover encoding) created by the person as
  documented, and checks found, not found (`INUBIT_LIVETEST_DEV_NODE9_PASSWORD`), the value, and
  records exit codes and texts (R-3, R-5 risks); access denied is a documented manual step on macOS
  and Linux and does not exist on Windows. It never contacts INUBIT.
- **Rationale**: FR-014, FR-017; creating entries is left to the person (Out of Scope).

## R-15 Tests without environment credentials

- **Decision**: test fixtures that put credentials into environment maps (`ConfigValidatorTest`,
  `LauncherTest`/`MainTest`, `ConfigSummaryTest`, `WiringE2eTest`, …)
  switch to `FakeCredentialStore`. Kept on purpose: `NoEnvironmentCredentialsTest` (SC-002: every
  derived variable set to a distinct value, a full tool run, none of the values ever reaches an HTTP
  request, a StartCLI stdin or a result) and the FR-003 warning tests. `NoSecretLeakTest` is
  extended to the credential store.

## R-16 Documentation and migration

- **Decision**: new `docs/migration-environment-variables.md` (from shell exports: create entries per
  system; from the macOS start script: register the JAR directly, entries stay; changed e2e
  behaviour; `envPrefix` → `prefix`); `docs/setup.md` section 4 rewritten ("Credentials: sources",
  one general part for reading, checking and changing, then "Credential store" with a table per
  system and "AWS Systems Manager Parameter Store" unchanged); README steps 4 and 7 and the safety
  summary; `docs/tools.md` error tables and `get_health`; `SECURITY.md`; `docs/live-tests.md`;
  `docs/migration-001-to-002.md` notes that variables were replaced in 0.7.0; `Format001Detector`
  hint uses `credentials.prefix`; `Launcher` `--help`; CHANGELOG "0.7.0 — breaking". `docs/setup.md`
  also says per system what an access permission grants (macOS "Always Allow" for
  `/usr/bin/security`, which every process of the user may run; Linux an unlocked collection for every
  client of the session; Windows no per-program control) and how to find duplicate entries (macOS
  `security dump-keychain | grep inubit-mcp`, Linux `secret-tool search --all service inubit-mcp`,
  Windows `cmdkey /list:inubit-mcp:*`). The release notes name the systems on which the live check was
  executed (SC-007).

## R-17 Audit account before the first read

- **Decision**: `Wiring.policy(...)` falls back, for a credential store node whose credentials are not
  read yet, to "(credential store <node USERNAME account> or <group USERNAME account>)" (names only,
  nothing is read for it), as it does "(Parameter Store <name>)" for AWS nodes; after the first read
  it is the read username.
- **Rationale**: today environment nodes had their username at startup; without the fallback audit
  records before the first read would say "unknown" (Constitution VII). Tested on the audit record.
