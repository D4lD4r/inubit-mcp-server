# Data Model: INUBIT 9 Support

Changes to the configuration, domain and adapter model for [spec.md](spec.md); decisions in
[research.md](research.md). "New" = new type; "changed" = existing type extended. Every new output
field is optional and additive.

## Configuration (`config`)

| Type | Change | Fields / values | Rules |
|---|---|---|---|
| `Defaults` | changed | `+ Map<MajorMinor, CliClient> cliClients` (empty by default) | only in `defaults`; bound by `CliClientsDeserializer` (D-1) |
| `CliClient` | new | `Path home`, `Optional<Path> javaHome` | `home` required; path binding as `cliHome` |
| `CliClientsDeserializer` | new | — | key pattern `^(0\|[1-9][0-9]*)\.(0\|[1-9][0-9]*)$`; duplicate key → load error with the path `defaults.cliClients.<key>`; no echo of values other than the key |
| `EffectiveNodeConfig.CliSources` | new | `Optional<URI> url` (explicit node → group), `Optional<Layered<Path>> home` (node or group, with the level), `Optional<Layered<Path>> javaHome`, `Map<MajorMinor, CliClient> clients`, `Optional<Path> defaultHome`, `Optional<Path> defaultJavaHome` | built in `resolve`; immutable |
| `EffectiveNodeConfig` | changed | `+ CliSources cliSources`; `+ CliChoice cliFor(Optional<InubitVersion>)`; `+ EffectiveNodeConfig withCli(Cli)`; `cli()` = `cliFor(<pinned version>)` (`AUTO`: version-less; equals 0.8.0 for `AUTO`, 6.1 and 8.1 pins without `cliClients`) | `cliFor` per D-3; `withCli` keeps `cliSources`, so `select` is idempotent (D-4) |
| `CliChoice` | new | `Cli cli`, `HomeSource source` (`NODE`, `GROUP`, `VERSION_ENTRY`, `DEFAULTS`, `NONE`), `Optional<MajorMinor> entry`, `AddressKind address` (`SERVLET_PATH`, `BASE_URL`, `CONFIGURED`), `OptionalInt major` (used for the choice), `boolean versionKnown` | no credentials; `entry` present iff `source == VERSION_ENTRY` |
| `JdkRelease` | new | `OptionalInt major(Path javaHome, Function<Path, Optional<String>> reader)` | reads `JAVA_VERSION="<n>…"` of `<javaHome>/release`; `"1.8…"` → 8; unreadable → empty (D-17) |
| `ConfigValidator` | changed | entry checks, JDK major, `V9_X` suggestion, servlet warning for pinned ≥ 9, Windows checks of entry paths | warnings vs. errors per contracts/configuration-delta.md |
| `ConfigSummary` | changed | per node `client: <dir> (<source>), address: <kind>` (+ `chosen at runtime` for unknown versions); one line per distinct client with its `-v` result and JDK major | no URL, no credentials |
| `ConfigLoader` | changed | `V9_0` in the error text; `cliClients` binding | — |

## Domain (`domain/model`, `domain/port`)

| Type | Change | Fields / values | Rules |
|---|---|---|---|
| `MajorMinor` | new | `int major, int minor`; `parse(String)`; `of(InubitVersion)` (empty without minor); `text()` = `"M.m"` | used as `cliClients` key and in messages |
| `VersionSetting` | changed | `+ V9_0 = Line(9, OptionalInt.of(0))` | `parse("V9_0")`; `V9_X` unchanged |
| `CapabilityMatrix.production()` | changed | 9.0 entry `READS_UNVERIFIED` → `ALL_SUPPORTED` (REST and CLI channels as 8.1); `+ boolean declares(MajorMinor)` | `V9_X`/unknown unchanged (D-9) |
| `ClientKey` | new | `String home`, `String javaHome` (`""` = none) | value equality; no URL, no credentials (D-5) |
| `ProbedClient` | new | `ClientKey key`, `ClientVersion version` | result of `ClientVersionPort.probe` |
| `NodeVersionState` | changed | `clients: Map<ClientKey, Timed<ClientVersion>>`; `clientFailures: Map<ClientKey, Timed<ErrorCode>>`; `withClient(ProbedClient, Instant)`; `withClientFailure(ClientKey, ErrorCode, Instant)`; `needsClientProbe(Instant, ClientKey)`; `clientFor(ClientKey)` | a result or failure of one key never touches another key's; the 60 s back-off applies per key; A→B→A reuses A |
| `ClientVersionPort` | changed | `ProbedClient probe(NodeId, Optional<InubitVersion> version, Duration)` | probes the client chosen for the caller's version snapshot; returns its key |
| `ClientCheckLookup` | new (port) | `Optional<ClientCheck> check(NodeId, ClientKey)` (`ClientCheck` = a recorded `ClientVersion` or a recorded failure code), `boolean anyCheck(NodeId)` | implemented by `NodeVersions`; read-only; used by the `CliRunner` launch guard (D-4) |
| `ClientSelectionPort` | new | `Optional<ClientKey> current(NodeId, Optional<InubitVersion>)`, `boolean available(NodeId, Optional<InubitVersion>)`, `boolean anyCandidate(NodeId)` (D-7), `Optional<String> warning(NodeId, Optional<InubitVersion>)` (servlet address on ≥ 9, D-19) | no node contact, no process launch; the version is the caller's snapshot, never re-read |
| `NodeSummary` | changed | `+ StartCli startCli` (`Optional<String> clientDirectory`, `String source`, `Optional<String> versionEntry`, `String address`, `boolean finalChoice`) | rendered as `startCli` (contracts/mcp-tools-delta.md) |

## Adapter

| Type | Change | Rules |
|---|---|---|
| `CliRunner` | changed | `+ Function<NodeId, Optional<InubitVersion>> versions`, `+ ClientCheckLookup checks` (constructor); `+ Selection select(EffectiveNodeConfig, Optional<InubitVersion>)` (`server` with the chosen `Cli`, the `CliChoice`, the address warning); every launching entry point selects once with one version read (D-4); launch guard on `run`/`runSession` (D-4): result for the key excludes the version → `UNSUPPORTED_VERSION`; no check for the key but one for another key → `CLI_UNAVAILABLE` "changed during this call"; otherwise launch; never probes itself; `AUTH_FAILED` next step by `CliChoice.major` ≥ 9 (D-16); FR-005 texts (D-8) |
| `CliClientSelector` | new (`adapter/cli`) | implements `ClientSelectionPort` from `CliRunner.select`, the existence predicate and the Windows flag |
| `CliVersionProbe` | changed | `probe(node, version, timeout)` selects by the given version; returns `ProbedClient`; range pattern case-insensitive (D-15) |
| `CliClientProbe` | changed | cache key `(NodeId, ClientKey)` |
| `V61Connection` | changed | selects at its start; uses the chosen URL |
| `InubitHttpClient` | changed | `+ enum MediaRange {XML, JSON, ANY}`; `get`/`post`/`getWithoutCredentials` take the range (overloads keep `RESOURCE`/`ENDPOINT`); JSON error message as excerpt (D-12) |
| `V81VersionDetector`, `V81MonitoringAdapter`, `V81MaintenanceProbe`, `V81InventoryAdapter`, `LogEndpoint` | changed | pass the `MediaRange` of their parser (D-11) |
| `PortSetRegistry` | changed | `+ VersionRange(9, 0, 9, 0) → V81PortSet::create` (D-14) |

## Application

| Type | Change | Rules |
|---|---|---|
| `NodeVersions` | changed | implements `ClientCheckLookup`; `clientProbed(NodeId, ProbedClient)`, `clientProbeFailed(NodeId, ClientKey, ErrorCode)`, `needsClientProbe(NodeId, ClientKey)` |
| `VersionGate` | changed | `withClient` predicate → `ClientSelectionPort` with the gate's version snapshot; probe and judge only that key; adds `ClientSelectionPort.warning` when the channel includes CLI |
| `HealthService` | changed | selects with the version this call observed; re-probe when that key differs from the probed one, within the remaining budget; otherwise `client` unavailable "not checked yet" (D-6) |
| `NodeOverview` | changed | `startCli` per node; allowed capabilities with the current `available` (D-7) |

## State transitions: client check of one node (FR-017)

```text
no result ──probe(key K)──▶ result(K) ──version changes, current key K'≠K──▶ needs probe(K')
    │                          ▲                                               │
    └─fail(K)─▶ back-off(K) ───┘ (after 60 s)                       probe(K') ─┴─▶ result(K')
```

A result or failure of key K is judged only while the current key is K.
