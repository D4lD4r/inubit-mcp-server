# Research: Update Check and Manifest Version

All decisions below follow the spec's clarifications (coordinator's assumptions, to be confirmed by
the operator) and the constitution.

## R-1 Release source: GitHub REST "latest release"

- **Decision**: `GET https://api.github.com/repos/D4lD4r/inubit-mcp-server/releases/latest`, no
  authentication, headers `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`,
  `User-Agent: inubit-mcp-server/<running version>` (GitHub rejects requests without a user agent).
- **Why**: the endpoint returns only the newest published release that is neither a draft nor a
  pre-release (FR-004 for free); one request per day per process is far below the anonymous limit
  of 60 per hour and IP.
- **Alternatives**: the HTML page `…/releases/latest` (redirect parsing, fragile); the releases list
  (more data, own filtering); a Maven repository (the server is not published there).

## R-2 What is taken from the answer

- **Decision**: only `tag_name` (must be `v` + a semantic version, else a failed check); `draft` or
  `prerelease` true (should never happen at this endpoint) is a failed check too. The release page
  URL is **built** from the parsed version
  (`https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v<version>`), never copied from the
  answer.
- **Why**: nothing from the network reaches the model context or the log except a validated
  version string (Principle II/VI: no untrusted free text in results).
- The body is read up to 1 MiB; a longer answer is a failed check.

## R-3 Version comparison

- **Decision**: a small own `SemVer` (`MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]`) with SemVer 2.0.0
  precedence: numeric core, a pre-release is lower than its release, pre-release identifiers
  compared numerically or lexically, build metadata ignored. `0.8.0-SNAPSHOT < 0.8.0`.
- **Alternatives**: `java.lang.Runtime.Version` (wrong grammar for `-SNAPSHOT`), Maven's
  `ComparableVersion` (new runtime dependency, rejected by the constitution's minimal-dependency
  rule).

## R-4 Scheduling

- **Decision**: one single-thread `ScheduledExecutorService` with a **daemon** thread named
  `inubit-mcp-update-check`, `scheduleWithFixedDelay(check, 0, 24h)` ("24 hours after the previous
  check", FR-003). Started after the MCP server is started (so the handshake never waits); shut
  down with `shutdownNow()` when the server stops. The executor is injected so tests drive it
  deterministically.
- **Timeouts**: connect 5 s, request 10 s (FR-002: ≤ 10 s per check).
- **Why daemon**: the process exits through `System.exit` anyway; a daemon thread can never keep it
  alive (FR-002 "MUST NOT delay shutdown").

## R-5 HTTP client and proxy

- **Decision**: a dedicated `java.net.http.HttpClient` with `.proxy(ProxySelector.getDefault())`,
  `.followRedirects(NORMAL)`, `.connectTimeout(5s)`.
- **Why**: a `HttpClient` built without `.proxy(...)` uses **no** proxy at all; `ProxySelector
  .getDefault()` honours `https.proxyHost`/`https.proxyPort`/`http.nonProxyHosts` and, with
  `-Djava.net.useSystemProxies=true`, the operating system's proxy (FR-016). The INUBIT clients are
  untouched. Proxy authentication is out of scope (documented).
- TLS: JVM default trust store (GitHub's public certificate), unlike INUBIT's pinned trust stores.

## R-6 Turning it off

- **Decision**: profile key `updateCheck.enabled` (strict YAML boolean, like
  `archives.productionOptIn`; absent = on) and the environment variable `INUBIT_MCP_UPDATE_CHECK`
  (`off|false|0|no` = off, `on|true|1|yes` = on, case-insensitive, blank = unset). Off if either is
  off. Any other environment value: startup warning (the value is not echoed), check stays on.
- **Why the variable**: one switch in the MCP registration covers every profile and works before a
  profile file exists; the key documents the choice in the profile.

## R-7 Where the result shows

- **Decision**: an in-memory `UpdateStatus` holder owned by `Wiring`; `get_health` adds the optional
  top-level field `serverUpdate` from it (FR-006). WARN log line once per newer version and process
  (FR-007); failures at DEBUG with the exception class name only (FR-008).
- **Why `get_health` and not a new tool**: Principle IV (small tool set); the model already calls
  `get_health` to diagnose.

## R-11 Note in the first tool result (FR-017)

- **Decision**: `McpServerFactory` passes every successful `CallToolResult` through an
  `UpdateNotice` (mcp layer) that holds the last announced version; if the update status has an
  available version newer than the last announced one, it returns a copy of the result with one
  added `TextContent` block. stdio serves one session per process, so "per session" is "per
  process".
- **Why a content block**: the model reads every text block, while `_meta` is usually not shown to
  it. `structuredContent` and the first text block (the JSON) stay byte-identical, so the output
  schema validation and clients that parse the first block are unaffected.
- **Alternatives**: an elicitation dialog (needs client support, interrupts, not wanted), a
  logging notification (`notifications/message`, often not shown), `_meta` only (invisible).

## R-12 Local cache for the handshake (FR-018)

- **Decision**: `~/.inubit-mcp/update-check.json` with `{"latestVersion": "0.8.0",
  "checkedAt": "<ISO-8601>"}`; written after every successful check via a temporary file and an
  atomic move (several server processes may share it); read synchronously at start (a small local
  file) before `factory.start`. A cached version newer than the running one seeds `UpdateStatus` and
  adds one sentence to the `instructions`.
- **Why the user's home `.inubit-mcp`**: it already holds the per-profile audit and workspace
  directories; the cache is not configuration and not profile-specific.
- **Alternatives**: the config directory (`~/.config/inubit-mcp`, holds operator-edited files),
  a per-profile file (needless duplicates), the OS cache directory (no portable Java API).

## R-13 Elicitation dialog (FR-019, operator decision)

- **Decision**: the SDK sends `elicitation/create` only through the `McpSyncServerExchange` of a
  running request, so `UpdateNotice` captures the exchange of the first successful tool call and
  hands the request to a single daemon thread (`inubit-mcp-update-dialog`); the handler returns at
  once. The server's `requestTimeout` becomes 10 minutes (it only governs server-to-client
  requests, of which this is the only one), so an operator who answers late is still heard.
  Capability check: `clientCapabilities.elicitation() != null` (an empty object means form mode).
- **Form**: `{"type":"object","properties":{"doNotRemindAgain":{"type":"boolean","title":"Do not
  remind me of this version again","default":false}}}`.
- **Alternatives**: blocking inside the tool call (rejected by the operator: delays the tool), URL
  mode (opens a browser; not chosen), a native OS dialog (no UI in a stdio server).

## R-8 Version source and manifest

- **Decision**: the shade plugin's `ManifestResourceTransformer` (the only manifest of the shaded
  JAR: every other `META-INF/MANIFEST.MF` is filtered out) gets `Implementation-Title:
  ${project.artifactId}` and `Implementation-Version: ${project.version}`. `BuildInfo` reads
  `Implementation-Version` from the main attributes of the JAR its own class was loaded from
  (`CodeSource` location → `JarFile`); when not run from a JAR (tests, IDE) or the attribute is
  missing, it falls back to the filtered `build.properties`. The file name is never used.
- **Why keep `build.properties`**: tests and the IDE run from `target/classes`, which has no
  manifest; the fallback keeps `--version` and `serverInfo` working there.
- **Verification (FR-012)**: a Failsafe integration test (`JarVersionIT`, phase
  `integration-test`, after `package`) reads the built JAR's manifest, copies the JAR to a
  version-free name and runs `java -jar <copy> --version`; both must equal `${project.version}`.
  Failsafe 3.6.1 (same version as Surefire) is a build-time plugin only, no runtime dependency.
- **Alternatives**: a CI-only shell step (not part of `mvn verify` on a developer machine); a unit
  test of the `pom.xml` text (does not prove the JAR).

## R-9 Privacy

- The request has a fixed URL and fixed headers; nothing from the configuration is passed to the
  release source (the class does not receive the configuration at all). A unit test captures the
  request against a local HTTP stub and asserts that no profile, group, node, host or user name of
  a sample configuration appears in URL or headers (SC-005).

## R-10 Test isolation

- In-process tests inject a fake `ReleaseSource` through `Launcher.Context`; child-process tests
  (`MainProcessTest`) set `INUBIT_MCP_UPDATE_CHECK=off` in the child's (cleared) environment, so no
  test ever contacts GitHub.
