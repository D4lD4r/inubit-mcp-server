# Data Model: INUBIT 7.4 Support (Mandate A)

Phase 1 output of `/speckit-plan`. Only what changes; everything else is as in features 009, 012 and
017. Mandate B entities are designed after the recordings (research D-15).

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

New queries: `patchDepth(Optional<InubitVersion>)`, `comparePatch(ClientVersion,
Optional<InubitVersion>)` (the one entry point for client/server patch comparisons), `cliOnlyLines()` (major.minor of the CLI-only
entries in order), `allNotYetVerified(Optional<InubitVersion>)` (every capability carries a note).
`lines(capability)` ("supported once verified") ignores the notes of an entry for which
`allNotYetVerified` holds, so 7.4 never appears in "; supported: …".

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

Guard rule (`V74RecordedFixturesTest`, spec FR-015, FR-030): a capability declared `SUPPORTED` on
7.4 has no case in `RECONSTRUCTED.txt`, no derived case whose source is not in the README's recorded
list, and the README's recorded line for `help` names `v74_help` as its source.

## Recorder case (tools/record-fixtures.ps1)

| Field | Values |
|---|---|
| `Name` | `win_*` (feature 013) or `v74_*` |
| `Kind` | `local`, `exec`, `direct`, `session`, `failure` (plus the existing `export`, `export-backslash`, `argv`) |
| `Arguments` / `Commands` | argument list (local, exec, direct) or session commands incl. the end-of-session command |
| `Credentials` | whether the case asks for username and password |
| `Contacts` | whether the case contacts a server (then group and host allowlists apply) |
| `Folder`, `FixtureName` | `fixtures/windows/` or `fixtures/v7_4/cli/`; the file name the case writes |
