# Implementation Plan: Update Check and Manifest Version

**Branch**: `016-update-check` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/016-update-check/spec.md`

## Summary

Two independent parts. (1) The shaded JAR's manifest gets `Implementation-Title` and
`Implementation-Version`; `BuildInfo` reads the version from the manifest of its own JAR and falls
back to `build.properties` outside a JAR; a Failsafe integration test proves manifest and
`--version` agree for a renamed JAR. (2) After the MCP server has started, a daemon scheduler asks
the GitHub "latest release" endpoint once and then every 24 h; a strictly newer SemVer is logged once
at WARN and exposed as `get_health.serverUpdate`. Off with `updateCheck.enabled: false` or
`INUBIT_MCP_UPDATE_CHECK=off`; failures are DEBUG only; nothing is downloaded. The first successful
tool result after a newer release is known carries a one-time note block, and a local cache
(`~/.inubit-mcp/update-check.json`) lets the next start name a known newer release in the MCP
`instructions` without waiting for the network.

## Technical Context

**Language/Version**: Java 21 (existing)

**Primary Dependencies**: no new runtime dependency — `java.net.http.HttpClient` (JDK), Jackson 3
(existing) for the JSON answer, SLF4J/Logback (existing). Build: `maven-failsafe-plugin` 3.6.1
(build-time only, same version as Surefire).

**Storage**: in-memory status per process; one small JSON cache file `~/.inubit-mcp/update-check.json`

**Testing**: JUnit 5 + AssertJ (unit), WireMock (existing test dependency) as release-source stub,
Failsafe IT against the built JAR

**Target Platform**: macOS, Linux, Windows (JVM)

**Project Type**: single-JAR MCP stdio server

**Performance Goals**: zero added latency to handshake and tool calls (SC-001)

**Constraints**: ≤ 10 s per check; no stdout output; no configuration data in the request; daemon
thread only

**Scale/Scope**: one request per process start and per 24 h

## Constitution Check

| Principle | Assessment |
|---|---|
| I Safe by default | No INUBIT state is touched; nothing is downloaded or replaced (FR-005). The check is on by default, but read-only and to a fixed public endpoint; opt-out provided. ✅ |
| II Secrets | No credential is used or sent; the request carries no configuration data (FR-014, test SC-005). ✅ |
| III Test-first | Every task starts with a failing test; network replaced by WireMock/fakes; no test contacts GitHub (R-10). ✅ |
| IV Use-case tools | No new tool; one optional field on `get_health` (R-7). ✅ |
| V Adapter isolation | GitHub access behind the `ReleaseSource` port in `adapter.github`; the application layer only sees `LatestRelease`. ✅ |
| VI Bounded output | `serverUpdate` is small and built only from validated values; the URL is constructed, not copied (R-2). ✅ |
| VII Observability | WARN/INFO/DEBUG on stderr through the existing logging; no audit record (not state-changing). ✅ |
| Tech constraints | No new runtime dependency; Failsafe is build-time only (justified in R-8). ✅ |

Re-check after design: unchanged, no violations.

## Project Structure

### Documentation (this feature)

```text
specs/016-update-check/
├── spec.md, plan.md, research.md, data-model.md, quickstart.md
├── contracts/get_health-delta.md, contracts/configuration-delta.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
pom.xml                                         # manifest entries, failsafe plugin
src/main/java/de/dadecker/inubit/mcp/
├── infra/BuildInfo.java                        # manifest first, build.properties fallback
├── domain/model/SemVer.java                    # new
├── domain/model/ServerUpdate.java              # new
├── application/ReleaseSource.java              # new port (+ LatestRelease, ReleaseCheckException)
├── application/UpdateStatus.java               # new
├── application/UpdateCheck.java                # new
├── application/UpdateCache.java                # new port (+ CachedRelease)
├── infra/FileUpdateCache.java                  # new; ~/.inubit-mcp/update-check.json
├── mcp/UpdateNotice.java                       # new; note block in the first tool result
├── mcp/McpServerFactory.java                   # instructions sentence, results through UpdateNotice
├── adapter/github/GitHubReleaseSource.java     # new
├── config/UpdateCheckConfig.java               # new; ProfileConfig + ConfigLoader (strict boolean)
├── config/UpdateCheckSetting.java              # new; key + environment variable
├── Wiring.java                                 # UpdateStatus → GetHealthTool
├── Launcher.java                               # Context.releaseSource, start/stop the check
└── mcp/tools/GetHealthTool.java                # Result.serverUpdate
src/main/resources/schemas/get_health.output.json
src/test/java/...                               # unit tests per class above, LauncherTest/MainTest
src/test/java/de/dadecker/inubit/mcp/JarVersionIT.java   # Failsafe
README.md, docs/setup.md, docs/tools.md, CHANGELOG.md
```

**Structure Decision**: existing single project; new code follows the existing layering
(domain → application port → adapter, wiring in `Wiring`/`Launcher`).

## Complexity Tracking

No constitution violations.
