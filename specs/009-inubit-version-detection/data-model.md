# Data Model: INUBIT Version Detection (feature 009)

All types are in memory; nothing is persisted except audit records (unchanged format, new reason
texts). Domain types live in `domain/model` (pure Java, PackageBoundaryTest).

## InubitVersion (domain, new)

| Field | Type | Rule |
|---|---|---|
| `major` | int | ≥ 0 |
| `minor` | int | ≥ 0 |
| `patch` | OptionalInt | absent if the raw value has none |
| `raw` | String | the value as reported or configured, suffix kept |

- `parse(String)` → `Optional<InubitVersion>`: leading `major.minor[.patch]`; no major.minor →
  empty (= unknown). Suffixes are ignored for matching (D-1).
- `sameMinor(other)`: equal major and minor. Ordering: major, minor, patch (absent = 0).

## VersionSetting (domain value in `domain/model`, replaces `config/VersionLine`; only the Jackson deserializer lives in `config`)

Sealed: `Auto` | `Line(int major, OptionalInt minor)` (`V8_1` = 8.1, `V9_X` = 9) |
`Pinned(InubitVersion)` (needs a patch). `text()` returns the configured spelling. Resolution node →
group → built-in `Auto` (unchanged). Invalid: a number (`8.1`), a version without patch, any other
string → configuration error (D-2).

## Capability (domain enum, new)

`MONITORING`, `DIAGNOSIS`, `INVENTORY`, `WORKSPACE_EXPORT` (kind READ);
`DEVELOPMENT_WRITE`, `DEPLOYMENT`, `E2E_TEST`, `PROCESS_CONTROL` (kind WRITE). Each has its
channels (REST, CLI, SOAP) per the spec's tool classification.

## SupportState (domain enum, new)

`SUPPORTED`, `UNVERIFIED` (only meaningful for READ capabilities), `UNSUPPORTED`.

## CapabilityMatrix (domain, new)

Ordered list of `(VersionRange, Map<Capability, SupportState>)` plus the state for unknown
versions. `state(Optional<InubitVersion>, Capability)`; a major-only line (`V9_X`) is looked up as
unknown (consistent with FR-014a), which gives the same states as 9.0 in production. The production declaration is the table in
research D-3; tests may construct others (US3).

`VersionRange`: inclusive `from`/`to` on major.minor (patch ignored), e.g. 8.1–8.1.

## ClientVersion (domain, new)

| Field | Type |
|---|---|
| `version` | InubitVersion (raw kept) |
| `supportedFrom`, `supportedTo` | Optional<InubitVersion> |

- `compatibleWith(server)`: range on major.minor, inclusive; a line with major only is
  compatible if any minor of that major lies in the range (D-8); empty range → not evaluable.
- `compare(server)` → `MATCH` | `MISMATCH` | `NOT_COMPARABLE` (FR-011).

## NodeVersionState (domain value, new) and NodeVersions (application, new)

Per node, held by `NodeVersions`:

| Field | Type | Set by |
|---|---|---|
| `setting` | VersionSetting | configuration |
| `detected` | Optional<(InubitVersion, Instant)> | detection (gate, gateway slot) |
| `detectionFailure` | Optional<(ErrorCode, Instant)> | detection |
| `observed` | Optional<(InubitVersion, Instant)> | `get_health` |
| `stale` | boolean | stale triggers (D-10); cleared by a detection |
| `pinContradiction` | boolean | `get_health` observation vs pin (FR-012) |
| `client` | Optional<(ClientVersion, Instant)> | client probe (kept for the process lifetime) |
| `clientFailure` | Optional<(ErrorCode, Instant)> | failed or timed-out client probe (retried ≥ 60 s later); a version without a range is a success with an empty range |

Derived:
- `effectiveVersion()`: the most recent of `detected`/`observed`; for `Pinned`/`Line` without an
  observation, the pin. On `Auto`, an observation replaces the detection (D-4).
- `needsDetection(purpose, now, bound)`: `Auto` and (no version) or (purpose WRITE and (stale or
  older than bound)); never within 60 s after a failure.
- `writableVersion(now, bound)`: the effective version for a WRITE. On `Auto`, a version that is
  stale or older than the bound and whose re-detection failed (or is suppressed by the 60 s
  back-off) counts as unknown: no writable version, so writes are refused with the
  detection-failure text; reads keep using `effectiveVersion()`.
- `supportStatus(matrix, config)`: `SUPPORTED` / `PARTIALLY_SUPPORTED` / `UNSUPPORTED` /
  `UNKNOWN` (FR-004; "usable" per D-9).

## NodeSummary (domain, extended)

Existing configuration fields unchanged (`versionLine` keeps the configured text). New `version`
block (filled at call time by `ListNodesTool`'s supplier):

| Field | Content |
|---|---|
| `setting` | configured text (`AUTO`, `V8_1`, `V9_X`, `8.1.17`) |
| `server` | `{version, source: detected|observed|pinned, at}` or `{state: not_yet_detected}` or `{state: unknown, reason, at}` |
| `client` | `{version, supportedFrom?, supportedTo?, comparison}` or `{state: not_yet_detected|not_available|unknown, reason?, at?}` |
| `status` | `supported` / `partially_supported` / `unsupported` / `unknown` |
| `capabilities` | `{usable: [...], notUsable: [{capability, reason}]}`; a mixed-channel capability whose StartCLI part is refused by the client check is listed as usable and additionally as `{capability: "inventory (StartCLI parts)", reason}` in `notUsable` |
| `warnings` | e.g. the pin contradiction |

## Admission (application value, new)

Result of `VersionGate.admit(node, capability, purpose, channels)`: `warnings: List<String>`; a refusal is a
`ToolErrorException` with `UNSUPPORTED_VERSION`, `message` naming node, version, capability (and
client version/range when the client is the cause), `nextStep` per FR-005.
