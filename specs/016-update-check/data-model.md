# Data Model: Update Check and Manifest Version

## SemVer (domain.model)

| Field | Type | Rule |
|---|---|---|
| major, minor, patch | int ≥ 0 | no leading zeros |
| preRelease | List<String> | dot-separated identifiers after `-`; empty for a release |

`parse(String)` accepts `MAJOR.MINOR.PATCH[-PRE][+BUILD]` (build ignored), else `Optional.empty()`.
`compareTo` follows SemVer 2.0.0 precedence. `toString()` gives the normalized text without build.

## ServerUpdate (domain.model) — `get_health.serverUpdate`

| Field | Type | Meaning |
|---|---|---|
| currentVersion | String | running version (`BuildInfo.version()`) |
| latestVersion | String | version of the latest release |
| updateAvailable | boolean | `latest > current` (SemVer) |
| releaseUrl | String | `https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v<latest>` |
| checkedAt | Instant | when the successful check ran |
| notice | Optional<String> | only if `updateAvailable`: "inubit-mcp-server 0.8.0 is available (running 0.7.1): <url>. Update manually: download the JAR and its .sha256 file, check the checksum, replace the JAR the MCP registration runs, restart the client." |

Created only from a successful check. A later failed check keeps the previous value.

## LatestRelease (application port value)

`record LatestRelease(SemVer version)` — what `ReleaseSource.latest()` returns.

## ReleaseSource (application port)

`LatestRelease latest() throws ReleaseCheckException` — one request; any failure is a
`ReleaseCheckException` with a short reason (no response body, no stack trace needed by callers).

## UpdateStatus (application)

Thread-safe holder `AtomicReference<ServerUpdate>`; `current(): Optional<ServerUpdate>`,
`record(ServerUpdate)`.

## UpdateCheck (application)

State: `ReleaseSource`, running `SemVer`/text, `UpdateStatus`, `Clock`, `Set<String> announced`.
`checkOnce()`: query → compare → record → WARN once per newly available version; on
`ReleaseCheckException`/`RuntimeException`: DEBUG line, status unchanged. `start(executor)` schedules
`checkOnce` with fixed delay 24 h and initial delay 0; `close()` shuts the executor down.

## UpdateCheckConfig (config) — profile key `updateCheck`

| Key | Type | Default |
|---|---|---|
| `updateCheck.enabled` | strict YAML boolean | `true` (absent) |

`UpdateCheckSetting.resolve(config, environment)` → `enabled` + optional warning for an
unrecognised `INUBIT_MCP_UPDATE_CHECK` value.

## UpdateCache (application port, file adapter in infra)

`Optional<CachedRelease> read()`, `void write(CachedRelease)`; `record CachedRelease(SemVer
latestVersion, Instant checkedAt)`. File `~/.inubit-mcp/update-check.json`:
`{"latestVersion": "<SemVer>", "checkedAt": "<ISO-8601 instant>"}`; unknown fields ignored; an
invalid version or time makes the whole file ignored. Written via temp file + atomic move.
`UpdateCheck` writes after every successful check; `Launcher` reads once at start and, if newer than
the running version, records a `ServerUpdate` built from it (its `checkedAt` is the cached time).

## UpdateNotice (mcp)

State: `Supplier<Optional<ServerUpdate>>`, last announced latest version (`AtomicReference`).
`decorate(CallToolResult)`: for a non-error result, if the status has `updateAvailable` and its
`latestVersion` is higher than the last announced one, returns the result plus one text block
"Note: inubit-mcp-server <latest> is available (installed <current>), see <releaseUrl>. Update
manually; see get_health.serverUpdate." and remembers the version; otherwise returns the result
unchanged.
