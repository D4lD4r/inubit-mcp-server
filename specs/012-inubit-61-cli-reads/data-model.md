# Data Model: Read-Only INUBIT 6.1 Support via StartCLI Only

Changes to the domain and adapter model for [spec.md](spec.md); decisions in
[research.md](research.md). "New" = new type; "changed" = existing type extended. All new
output fields are optional and additive unless stated.

## Domain (`domain/model`)

| Type | Change | Fields / values | Rules |
|---|---|---|---|
| `VersionSetting.Line` | changed | new constant `V6_1` = `Line(6, 1)` | parsed from `"V6_1"`; text `V6_1` (D-1) |
| `Capability` | changed | `DIAGNOSIS` → `PROCESS_DIAGNOSIS`, `LOG_DIAGNOSIS` (both `READ`, default channel `REST`) | every existing matrix entry declares both with the former `DIAGNOSIS` state (D-2) |
| `CapabilityMatrix.Entry` | changed | `+ Map<Capability, Set<Channel>> channels` (default: the capability's channels), `+ boolean cliOnly` (default `false`) | on a `cliOnly` entry nothing but `SUPPORTED` runs; no 8.1 fallback (D-3) |
| `CapabilityMatrix.production()` | changed | `+ Entry(6.1–6.1, {MONITORING, PROCESS_DIAGNOSIS: UNSUPPORTED → SUPPORTED per recordings}, channels {MONITORING: CLI, PROCESS_DIAGNOSIS: CLI}, cliOnly)` | a 6.1 capability becomes `SUPPORTED` only together with its recorded fixtures (FR-005, D-15) |
| `NodeVersionState` | changed | detection failure keeps `endpointMissing` (HTTP 404 on the detection request) | used only for the `AUTO` hint (FR-003, D-11) |
| `HealthReport` | changed | `+ Optional<ProcessCounts> processCounts` | present for 6.1 reports (FR-008) |
| `ProcessCounts` | new | `long error, processing, waiting, queued` | from the health session (D-6) |
| `LoadFigures` | changed | `threadsInUse`, `licensedThreads`, `maxThreads`, `blockingQueueEntries`, `maxBlockingQueueSize`, `blockingQueuePercent` become optional | absent on 6.1; always present on 8.1 as today |
| `UnavailablePart` | changed | parts `+ threads`, `+ blockingQueue`; reason code `NOT_AVAILABLE_ON_6_1` | 6.1: `ready`, `threads`, `blockingQueue` with this reason |
| `CliHealthSnapshot` | new | `boolean alive`, `Optional<String> versionText`, `boolean versionIsServer`, `Optional<Boolean> maintenance`, `Optional<MemoryFigures> memory`, `Optional<ProcessCounts> counts`, `List<UnavailablePart> missing` | built by `V61HealthAdapter`; parts the session could not read are `missing` |
| `MemoryFigures` | new | `long usedMb, freeMb, maxMb`; derived `usedPercent` (one decimal) | from `free -m` |
| `CliClientOptions` | new | `boolean trustStoreFilePath, disableHostNameVerification, execCommand`; `Optional<Integer> javaFeatureVersion` | from the local probe (`-h`, `cli.javaHome/release`); unknown = absent (fail closed, D-8) |
| `NodeAccess` | new (enum) | `REST_AND_CLI`, `CLI_ONLY` | from the matrix entry; shown in `list_nodes` as `rest_and_cli`/`cli_only` (D-13) |
| `ProcessInstance` | unchanged (REC) | — | if the recordings show `DATE` is not the state-entry time, `since`/`timeInState` become optional in a follow-up task (D-10) |

## Ports (`domain/port`)

| Port | Change | Methods |
|---|---|---|
| `CliHealthPort` | new | `CliHealthSnapshot snapshot(Duration budget)` |
| `ClientVersionPort` | unchanged | still the `-v` probe only; the client options are not part of it (D-8) |
| `GatewayFactory` | changed | `Optional<CliHealthPort> cliHealth(NodeId)`; `NodeAccess access(NodeId)` |
| `ProcessQueryPort` | unchanged | implemented by `V61ProcessQueryAdapter` |

## Adapters

| Type | Package | Role |
|---|---|---|
| `V61PortSet` | `adapter/cli/v61` | registered for 6.1 in `PortSetRegistry` (D-4) |
| `V61HealthAdapter` | `adapter/cli/v61` | `CliHealthPort` via one session (D-5, D-6) |
| `V61ProcessQueryAdapter` | `adapter/cli/v61` | `ProcessQueryPort` via `ps` listings (D-10) |
| `V61SessionParser`, `V61PsParser`, `V61HealthParser` | `adapter/cli/v61` | parse session blocks, listings, health commands; German and English; unknown → `UNEXPECTED_RESPONSE` |
| `RefusingPorts` | `adapter` | stubs for ports 6.1 does not offer; throw `UNSUPPORTED_VERSION` |
| `CliRunner.runSession` | `adapter/cli` | interactive run: password and commands on stdin; arguments from the given `CliClientOptions`; the 8.1 argument array is unchanged (D-5, D-8) |
| `CliSessionCommand` | `adapter/cli` | allowlisted command text (`uname`, `uptime`, `maintenance`, `free -m`, `ps …`) — no free text |
| `CliClientProbe` | `adapter/cli` | new class beside `CliVersionProbe` (which stays the `-v` probe behind `ClientVersionPort`); `optionsFor(node, timeout)` runs `-h` and reads the Java version for `cliOnly` nodes only, with an adapter-side per-node cache; used by `V61Connection` and by `Launcher` for `--check-config` (D-8) |
| `CliConcurrencyLimiter` | `adapter/cli` | per-node `Semaphore(2)` for `cliOnly` nodes (D-9) |
| `CliDates` | `adapter/cli` | shared zone-less date parsing (Europe/Berlin), used by `v81` and `v61` |
| `TlsHandshakeCheck` | `adapter/rest` | handshake-only certificate check to host:port (D-7) |
| `V61Connection` | `adapter/cli/v61` | the 6.1 connection sequence: StartCLI available → option/format refusals → permit → handshake → session; owns every refusal of D-8 |

## Configuration

| Key | Change | Rule |
|---|---|---|
| `versionLine` | new value `V6_1`; concrete 6.1 pins with three or four numeric parts and suffix | error text names `V6_1` (D-1) |
| (validation) | 6.1 node without `cliHome` → new warning; `cliHome` without `startcli.sh` → existing warning; client options / format → warnings (FR-012a); trust-store password with CLI → error (existing) | no INUBIT contact |

## State transitions

- **6.1 node version**: `pinned 6.1` → (`get_health` observes another major.minor, server version
  confirmed) → `contradicted` (everything but `get_health` refused) → (`get_health` observes 6.1)
  → `pinned 6.1`.
- **6.1 capability in production**: `UNSUPPORTED` (reconstructed fixtures) → `SUPPORTED`
  (recorded fixtures replace them; guarded by `V61RecordedFixturesTest`).
