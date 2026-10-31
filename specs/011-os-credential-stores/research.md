# Research: Credentials Only from a Credential Store (feature 011)

Decisions R-1 … R-16. Each: decision, rationale, alternatives considered. Feature 010's decisions
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
    `Lookup` is sealed: `Found(String value)`, `NotFound`, `Failed(FailureKind kind, String detail)`.
    The service `inubit-mcp` is a constant of the port (Out of Scope: not configurable).
    `FailureKind` gains `STORE_NOT_AVAILABLE` and `TIMEOUT`; `UNKNOWN_PROFILE` and `NO_CREDENTIALS`
    stay for Parameter Store.
  - `adapter/os` (new, `package-info.java`): the four adapters and `CredentialStores.forOs(String
    osName, ProcessLauncher)`. Only this package may use `com.sun.jna` (`PackageBoundaryTest`).
  - `adapter` (root): `NodeSecrets` gains the credential store variant (R-7); `CredentialGuard`
    always has a `Rereadable` (R-9).
- **Rationale**: the same shape as 010's `ParameterStore` keeps `NodeSecrets`, the guard and the
  check code source-neutral; tests above the adapter use an in-memory `FakeCredentialStore`.
- **Alternatives**: one adapter per store type selectable in the profile — rejected (FR-001: one
  type, the store of the running system; SC-007).

## R-3 macOS: `security` output and outcomes

- **Decision**: run `/usr/bin/security find-generic-password -s inubit-mcp -a <ACCOUNT> -g` with an
  absolute path, an empty environment except `HOME` and `USER`, stdin closed, stdout discarded and
  stderr read (`-g` prints the password to stderr). Parse the single line that starts with
  `password: `:
  - `password: "<text>"` → the text (printable ASCII as stored);
  - `password: 0x<HEX>  "<escaped>"` → decode `<HEX>` to bytes, then strict UTF-8 (malformed →
    `INVALID_VALUE` "cannot be decoded");
  - `password: ` with nothing after it → empty → not found (Edge Cases).
  Exit code 0 → `Found`; exit code 44 (`errSecItemNotFound`, "could not be found in the keychain")
  → `NotFound`; "User canceled the operation" / `errSecAuthFailed` / "interaction is not allowed" →
  `ACCESS_DENIED`, except "interaction is not allowed" without a GUI session (`SSH_CONNECTION` set or
  no `launchd` session) → `STORE_NOT_AVAILABLE`; "keychain cannot be found" / locked without prompt
  → `STORE_NOT_AVAILABLE`; timeout → process destroyed (whole tree) → `TIMEOUT`. Unknown stderr →
  `ACCESS_DENIED` with the scrubbed text in the log only.
- **Rationale**: `-w` prints printable values raw but non-printable ones as hex without a marker, so
  a password that consists of hex digits would be ambiguous; `-g` marks the hex form with `0x`. The
  exit code and message texts are verified by the macOS live check (FR-017) before the release;
  the parser is unit-tested against recorded outputs.
- **Alternatives**: `-w` with a hex heuristic — rejected (ambiguous); Security framework through
  JNA — rejected (R-1).

## R-4 Windows: `CredReadW` through JNA

- **Decision**: dependency `net.java.dev.jna:jna` (core only, 5.x, Apache-2.0 / LGPL-2.1 dual
  license; Apache-2.0 chosen). `jna-platform` has no `CREDENTIAL` mapping, so `adapter/os` maps
  `CREDENTIALW` (fields up to `CredentialBlobSize`, `CredentialBlob`), `CredReadW` and `CredFree` of
  `Advapi32` itself (about 40 lines, `W` functions only). Target name `inubit-mcp:<ACCOUNT>`, type
  `CRED_TYPE_GENERIC` (1). Outcomes: success → copy `CredentialBlobSize` bytes, `CredFree`, decode
  strict UTF-16LE (odd size or unpaired surrogate → `INVALID_VALUE`), wipe the byte copy;
  `ERROR_NOT_FOUND` (1168) → `NotFound`; `ERROR_NO_SUCH_LOGON_SESSION` (1312) →
  `STORE_NOT_AVAILABLE`; other errors → `ACCESS_DENIED` with the error number in the log. The read
  runs on its own thread and is abandoned after the timeout (`TIMEOUT`); it never prompts.
- **Rationale**: `cmdkey /generic:inubit-mcp:<ACCOUNT> /user:<any> /pass` (without a value it
  prompts) and the Credential Manager UI both store the password as UTF-16LE, which is what the
  documentation prescribes (FR-004 "read exactly as typed"). JNA loads its native stub only on
  first use, so macOS and Linux never load it; the shaded JAR grows by about 2 MB (natives of all
  platforms). JNA is not relocated in the shaded JAR (its native lookup needs its package name); it
  is listed in `THIRD-PARTY-NOTICES.md`.
- **Alternatives**: `jna-platform` (1.4 MB, no `CredRead` mapping); PowerShell (R-1).

## R-5 Linux: `secret-tool` output and outcomes

- **Decision**: run `secret-tool lookup service inubit-mcp account <ACCOUNT>` (found on `PATH`;
  absent → `STORE_NOT_AVAILABLE` "secret-tool is not installed (package libsecret-tools or
  libsecret)") with the server's environment (it needs `DBUS_SESSION_BUS_ADDRESS`), stdin closed;
  stdout is the value as stored (no line break is added when stdout is a pipe), decoded strict UTF-8.
  It searches all collections and unlocks a locked one through the session's prompter.
  Exit 0 with output → `Found`; exit 1 without output and without stderr → `NotFound`; stderr with
  "Cannot autolaunch D-Bus" / "org.freedesktop.secrets was not provided" → `STORE_NOT_AVAILABLE`;
  "Cannot create an item in a locked collection" / dismissed prompt → `ACCESS_DENIED`; timeout →
  `TIMEOUT`.
- **Rationale**: `secret-tool lookup` searches all unlocked collections and prompts for unlocking
  where a prompter exists, which is the Assumption "store per system". The documentation tells to
  create entries with `secret-tool store --label='inubit-mcp <ACCOUNT>' service inubit-mcp account
  <ACCOUNT>`, which reads the value from a prompt (no line break stored).
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

- **Decision**: every node gets a `NodeSecrets`; `NodeSecrets.credentialStore(plan, store, scrubber,
  clock)` next to `NodeSecrets.aws(...)`. The environment constructor (`NodeSecrets(NodeCredentials)`)
  and `NodeCredentials`' non-deferred form are removed; `CredentialResolver` no longer resolves
  values (R-10). One read of the main cell reads, per kind (`USERNAME`, `PASSWORD`, and
  `TRUSTSTORE_PASSWORD` only when `tls.trustStore` is set), the node entry and, if it is `NotFound`
  or empty, the group entry; a `Failed` or invalid node entry does not fall back. The e2e cell reads
  `E2E_USERNAME` and `E2E_PASSWORD` the same way on the first `run_e2e_test`; exactly one found →
  `AUTH_FAILED` naming the missing entry. The existing single-flight, back-off (30 s), settle grace,
  validation (`CredentialRules`), scrubber registration and `secret.fetch` log record are reused;
  the log record gets `source=credentialStore`, `store=<kind>`, `entries=<names read>`.
- **Rationale**: one code path for both sources keeps FR-006/FR-008/FR-013 identical to 010.
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
- **Rationale**: per-entry dialogs on macOS (FR-008, Edge Cases); a fan-out over N new nodes then
  opens the dialogs one after the other, and a node whose deadline passes fails its part only.
- **Alternatives**: a configurable `defaults.credentialStoreTimeout` — deferred; parallel reads —
  rejected (stacked dialogs).

## R-9 Rotation for every node

- **Decision**: `CredentialGuard` is always built with the node's `NodeSecrets` as `Rereadable`;
  the guard's "no re-read" path (environment nodes) is removed. The re-read uses the node's own
  source timeout (10 s or 60 s).
- **Rationale**: FR-009, SC-005; the guard logic of 010 R-10 is unchanged.

## R-10 What remains of `CredentialResolver`

- **Decision**: it becomes `LeftoverVariables` (FR-003) plus the name checks: from the server's
  environment it reports the names of `ProfileConfig.credentialNameSet()` — every kind of every
  node, under the prefix the node would use as a credential store node, also for AWS nodes — that
  are set, in one warning. The collision checks move to `ConfigValidator` on `CredentialNames` of
  credential store nodes (unchanged rules). The "matches no configured group or node (typo?)"
  warning and the e2e variable lookup are deleted. `Launcher` and `LiveTarget` pass
  `System.getenv()` only to `LeftoverVariables`.
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
  The first `STORE_NOT_AVAILABLE` or `TIMEOUT` stops the check of the store (one warning, the rest
  `not checked`). `ConfigSummary` replaces the `Credential variables:` line with `Credential store
  entries: service inubit-mcp, account <PREFIX>_<GROUP>[_<NODE>]_<KIND> (<store>)` and shows
  `secrets: credentialStore (<store>)` on every node line (always, since the source is never
  implicit-environment any more).
- **Rationale**: US4; the dialogs appear in `--check-config` too, which is acceptable (the person runs
  it interactively) and documented.

## R-14 Live tests

- **Decision**: `LiveTarget` builds the node's `NodeSecrets` through the same factory as `Wiring`
  (extracted to `SecretsFactory`) and the real `CredentialStores.forOs(...)` / `SsmParameterStore`.
  New opt-in class `CredentialStoreLiveTest` (`-Plive-store`, enabled only with
  `INUBIT_LIVE_STORE=1`): on the current system it expects entries `INUBIT_LIVETEST_DEV_USERNAME`,
  `INUBIT_LIVETEST_DEV_PASSWORD` (a value with `ä€` to cover encoding) created by the person as
  documented, and checks found, not found (`INUBIT_LIVETEST_DEV_NODE9_PASSWORD`) and the value;
  access denied is a documented manual step on macOS and Linux and does not exist on Windows. It
  never contacts INUBIT.
- **Rationale**: FR-014, FR-017; creating entries is left to the person (Out of Scope: the server
  never writes entries).

## R-15 Tests without environment credentials

- **Decision**: test fixtures that put credentials into environment maps (`ConfigValidatorTest`,
  `CredentialResolverTest`, `LauncherTest`/`MainTest`, `ConfigSummaryTest`, `WiringE2eTest`, …)
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
  hint uses `credentials.prefix`; `Launcher` `--help`; CHANGELOG "0.7.0 — breaking".
