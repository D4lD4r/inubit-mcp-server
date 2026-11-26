# Data Model: INUBIT 7.4 Support (Mandate A, Mandate B)

Phase 1 output of `/speckit-plan`. Only what changes; everything else is as in features 009, 012 and
017. Mandate B entities were to be designed after the recordings (research D-15); *superseded
2026-11-26*: they are designed from the code and the reconstructed fixtures (research D-16) in the
section "Mandate B (2026-11-26)" below.

## VersionSetting (changed)

| Value | Meaning | New |
|---|---|---|
| `AUTO`, `V6_1`, `V8_1`, `V9_0`, `V9_X`, concrete version | as today | – |
| `V7_4` | `Line(7, 4)`: the node runs INUBIT 7.4.x | yes |

Concrete 7.4 pins (`"7.4.0"`, `"7.4.0.90"`, `"7.4.0.90-HF1"`) are `Pinned` as today.

## InubitVersion (changed)

| Field | Type | Rule |
|---|---|---|
| `major`, `minor` | int | as today |
| `patch` | OptionalInt | as today |
| `build` | OptionalInt | **new**: the fourth numeric part; present only if `patch` is present and the value has a fourth numeric part |
| `raw` | String | as today (suffix and all parts kept, used for display) |

- `compareTo`, `sameMinor`: unchanged (ignore `build`).
- Four-argument constructor kept (`build` empty).

## PatchDepth (new, domain)

`THREE` | `FOUR`: how many numeric parts the client/server patch comparison uses for a range.

## CapabilityMatrix.Entry (changed)

| Component | Change |
|---|---|
| `range`, `states`, `channels`, `cliOnly`, `notes` | as today |
| `patchDepth` | **new**, default `THREE` |

Production entries:

| Range | cliOnly | States | Channels | Notes | patchDepth |
|---|---|---|---|---|---|
| 6.1 | yes | all `UNSUPPORTED` | monitoring, process diagnosis: CLI | none | THREE |
| **7.4 (new)** | yes | all `UNSUPPORTED` | monitoring, process diagnosis: CLI | "not yet verified" on every capability | **FOUR** |
| 8.1 | no | unchanged | – | unchanged | THREE |
| 9.0 | no | unchanged | – | unchanged | THREE |
| 6.1 after Mandate B (T035) | yes | monitoring, process diagnosis `UNVERIFIED`; all others `UNSUPPORTED` | monitoring, process diagnosis: CLI | none | THREE |
| 7.4 after Mandate B (T035) | yes | monitoring, process diagnosis `UNVERIFIED`; all others `UNSUPPORTED` | monitoring, process diagnosis: CLI | "not yet verified" on every capability except monitoring and process diagnosis | FOUR |

New queries: `patchDepth(Optional<InubitVersion>)`, `comparePatch(ClientVersion,
Optional<InubitVersion>)` (the one entry point for client/server patch comparisons), `cliOnlyLines()` (major.minor of the CLI-only
entries in order), `allNotYetVerified(Optional<InubitVersion>)` (every capability carries a note).
`lines(capability)` ("supported once verified") ignores the notes of an entry for which
`allNotYetVerified` holds, so 7.4 never appears in "; supported: …". *Mandate B (2026-11-26,
FR-035):* `lines` ignores the notes of every CLI-only entry instead, because after the flip 7.4 is
no longer noted on every capability; `allNotYetVerified` stays and is false for every production
entry after the flip.

## ClientVersion.Comparison (rule extended)

`compare(server, depth)`:

| major.minor.patch | build (depth `FOUR` only) | Result |
|---|---|---|
| patch absent on either side | – | `NOT_COMPARABLE` |
| differ | – | `MISMATCH` |
| equal | equal, or absent on both | `MATCH` |
| equal | different | `MISMATCH` |
| equal | present on one side only | `NOT_COMPARABLE` |

With depth `THREE` the build is never looked at (today's rule).

## 7.4 fixture lists (test resources)

| File | Line format | Mandate A content |
|---|---|---|
| `fixtures/v7_4/cli/RECONSTRUCTED.txt` | `<case> <capability>` | every case of the spec's FR-014 table except `help` |
| `fixtures/v7_4/cli/DERIVED.txt` | `<case> <source-case> <capability> <edit…>` | comments only |

Guard rule (`V74RecordedFixturesTest`, spec FR-015, FR-030), Mandate A: a capability declared
`SUPPORTED` on 7.4 has no case in `RECONSTRUCTED.txt`, no derived case whose source is not in the
README's recorded list, and the README's recorded line for `help` names `v74_help` as its source.
*Replaced 2026-11-26 (Mandate B):* no 7.4 capability may be `SUPPORTED`; only monitoring and
process diagnosis may be `UNVERIFIED`, each with at least one case in `RECONSTRUCTED.txt` and `help`
in the README's recorded list. Mandate B adds the reconstructed case `version` (the client's `-v`)
and lists every case both capabilities use (`version`, `login_failed`, `unreachable`) once per
capability, one line per case and capability, as the 6.1 list does; `DERIVED.txt` stays comments only. The 6.1 guard
(`V61RecordedFixturesTest`, spec FR-037) gets the same rule for 6.1: no 6.1 capability
`SUPPORTED`; only monitoring and process diagnosis `UNVERIFIED`, each with a case in the 6.1
`RECONSTRUCTED.txt`.

## Recorder case (tools/record-fixtures.ps1)

| Field | Values |
|---|---|
| `Name` | `win_*` (feature 013) or `v74_*` |
| `Kind` | `local`, `exec`, `direct`, `session`, `failure` (plus the existing `export`, `export-backslash`, `argv`) |
| `Arguments` / `Commands` | argument list (local, exec, direct) or session commands incl. the end-of-session command |
| `Credentials` | whether the case asks for username and password |
| `Contacts` | whether the case contacts a server (then group and host allowlists apply) |
| `Folder`, `FixtureName` | `fixtures/windows/` or `fixtures/v7_4/cli/`; the file name the case writes |

The `v74_*` cases are an optional later verification path since 2026-11-26; this feature runs none
of them.

## Mandate B (2026-11-26)

### CapabilityMatrix.Entry (changed again)

| Component | Change |
|---|---|
| `ownClientOnly` | **new**, default false (query `ownClientOnly(Optional<InubitVersion>)`); set on the production 7.4 entry in T035 (T033 tests it with a test matrix): StartCLI never falls back to the version-less `defaults.cliHome`, `choosesCliByVersion` is true, the `CLI_UNAVAILABLE` text names the `"7.4"` entry (spec FR-029) |

### SupportState (meaning extended, no new value)

`UNVERIFIED`: a read runs with the adapters of the version's entry and the warning "<name> on
<node> is not verified for <capability>; results may be incomplete": the 8.1 adapters for versions
without own adapters (as today), the StartCLI adapters on a CLI-only version (7.4 and 6.1). The
warning is on every per-node entry after the version gate, with data or an error. Never for
writes.

### CliLineProfile (new, `adapter/cli/v61`) and V74Profile (new, `adapter/cli/v74`)

| Field | 6.1 | 7.4 |
|---|---|---|
| release (in every text that names the version) | `6.1` | `7.4` |
| reason of the unavailable parts (readiness, threads, blocking queue) | `NOT_AVAILABLE_ON_6_1` | `NOT_AVAILABLE_ON_7_4` (new in `UnavailablePart`) |
| `uname` reports the server's version | false | false (6.1 behaviour; known limitation: no server version, no pin contradiction from 7.4) |

Taken by `V61HealthAdapter`, `V61ProcessQueryAdapter`, `V61QueryRules` and `V61SessionParser`;
`V61Connection`, `V61HealthParser` and `V61PsParser` are reused unchanged.

### ContactedException (new, domain) and warnings on failures

| Element | Change |
|---|---|
| `ContactedException` | **new**, extends `ToolErrorException` with the same `ToolError` (the error's JSON is unchanged): the error arose after contact began, i.e. in the server's own TLS handshake, after the StartCLI process was started, or from classifying or parsing a returned session. Taking the limiter permit is not contact. Every other `ToolErrorException` of the StartCLI path is pre-contact: missing credentials, the Windows credential refusal, refused TLS options, no client, no free slot, the launch guard (`checkLaunch`), the `CredentialGuard` cooldown, StartCLI cannot be started. |
| `ToolErrorException` | **new** `warnings()` (default empty) and `withWarnings(List<String>)`, keeping the subtype |
| `NodeResult` | **new** component `warnings`: a failure may carry them (`failure(node, error)` keeps none) |
| `FanOut` | maps a thrown exception with warnings to a failure with these warnings; its deadline `TIMEOUT` carries the warnings the node's task registered right after admission (per-call sink), none if it registered none |
| `NodePages` | renders a failure's warnings beside `error` |
| `SecretTiming.explain` | keeps a result's warnings when it rebuilds a `TIMEOUT` |
| `HealthService.Outcome` | carries whether its error is a `ContactedException` |

Rule (spec FR-030 (b)): the admission warnings of a 7.4 or 6.1 node go onto data entries and onto
contacted errors and deadline `TIMEOUT`s of an admitted node (the `FanOut` deadline, the
`get_health` `await` budget, also while the node still waits for a StartCLI slot), never onto
pre-contact refusals (including the limiter's own no-slot `TIMEOUT`). The
`find_processes` output schema keeps its shape (`warnings` beside `error`); only its description
changes ("a failed {node} has an error and possibly warnings").
