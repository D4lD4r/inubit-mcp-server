# Research: INUBIT MCP Server MVP

Phase 0 output of `/speckit-plan`. INUBIT interface facts come from
[docs/research/inubit-interfaces.md](../../docs/research/inubit-interfaces.md); MCP/Java facts were
researched on 2026-10-01 (sources at the end). Items marked **SPIKE** cannot be resolved from
documentation and are resolved by verification spikes against the non-production 8.1.17 systems (DEV, QA) as
the first implementation tasks (see "Verification spikes"). Each spike has a defined fallback, so no
open item blocks the design.

---

## R-1 MCP server library

- **Decision**: Official MCP Java SDK **2.0.1** (`io.modelcontextprotocol.sdk:mcp`, managed via
  `mcp-bom:2.0.1`), synchronous API (`McpServer.sync`), `StdioServerTransportProvider`.
- **Rationale**: about 6 MB of dependencies (14 JARs), latest SDK, no framework startup or
  banner/logging traps. It supports everything the constitution requires: tool annotations
  (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`), `outputSchema` plus
  `structuredContent` with server-side output validation, and automatic input-schema validation
  that returns `isError` without calling the handler. A minimal stdio server was compiled and run
  successfully during research.
- **Alternatives considered**: Spring AI 2.0.1 MCP server starter. It offers annotation-based tools
  and generated schemas, but brings about 53 JARs (21 MB), Spring Boot 4 startup and stdio
  pitfalls (banner, console logging), and it still depends on SDK 2.0.0. It is rejected because DI
  and annotations do not justify the weight for 8 tools.

## R-2 MCP protocol revision risk

- **Decision**: Accept SDK 2.x, which implements protocol revisions up to **2025-11-25**, for the
  MVP. The MCP SDK stays confined to the `mcp` package (tool registration and result mapping), so
  that moving to SDK 3.x (revision 2026-07-28, stateless, `server/discover`) is a change local to
  that package.
- **Rationale**: 3.x is announced but not released. Under the 2026-07-28 compatibility matrix a
  2.x server is a "legacy" server: it works with legacy and dual-era clients, but not with
  modern-only clients.
- **Follow-up**: SPIKE S-0 verifies the connection with the team's actual MCP client (Claude Code)
  in the first task. If it fails, this becomes a blocking issue that is escalated before any further
  work.

## R-3 Language level, build, packaging

- **Decision**: Java **21** (`maven.compiler.release=21`), runtime JDK 21 or newer. **Maven** 3.9+
  builds a single shaded JAR (`maven-shade-plugin` with `ServicesResourceTransformer` and a
  `Main-Class` manifest).
- **Rationale**:
  - Java 21 is the LTS release named by the constitution and runs on JDK 25 as well.
  - Virtual threads (R-15) are available.
  - Maven is the common default in corporate builds and integrates well with shading.
  - `ServicesResourceTransformer` is **mandatory**: the SDK discovers its JSON mapper and schema
    validator through `ServiceLoader`, so the `META-INF/services` entries must be merged.
- **Alternatives considered**:
  - Gradle: equally capable. Maven is chosen for convention; switching later is cheap.
  - jlink/native image: unnecessary for a developer tool.

## R-4 JSON, YAML, XML handling

- **Decision**:
  - **JSON**: Jackson **3** (`tools.jackson`), already pulled in by `mcp-json-jackson3`.
  - **YAML config**: `tools.jackson.dataformat:jackson-dataformat-yaml`, same Jackson 3 version.
  - **XML responses**: the JDK's built-in StAX/DOM with secure processing enabled. External
    entities and DTDs are disabled to prevent XXE.
- **Rationale**: one JSON stack for the SDK, the config and the REST responses. XML needs no extra
  library.
- **Alternatives considered**:
  - Jackson 2: it would duplicate the stack.
  - JAXB: an extra dependency, and the XML shapes are small.

## R-5 REST client

- **Decision**: `java.net.http.HttpClient`, one client per environment with its own `SSLContext`
  (trust store from config; hostname verification on unless explicitly disabled). The
  `Authorization: Basic` header is built per request.
  - Always send `Accept: application/json, application/xml;q=0.9, */*;q=0.8`. This is already
    valid in 8.1 and required from 9.0 on, where an XML-only endpoint returns 406 for a strict
    JSON Accept.
  - Request timeout = environment `timeout`.
- **Credential guard**: authenticated requests go through the server's `CredentialGuard`
  (R-12, account-lockout protection); a 401 blocks all authenticated requests of that server
  for 60 s, `/healthcheck` and `/ready` are always sent without credentials.
- **HTTP error mapping**:
  - 401 → `AUTH_FAILED`. In 8.1 the body is a Tomcat HTML page, so the body is never parsed.
  - 403 → `FORBIDDEN`.
  - 404 → `NOT_FOUND`.
  - 503 → the client asks an injectable `MaintenanceProbe` (version-specific; 8.1: ONE
    unauthenticated `GET /ibis/rest/healthcheck`, timeout `min(timeout, 2 s)`, no recursion,
    `maintenancemode` 1/true = on) → `MAINTENANCE_MODE` if on.
  - HTTP 503 without maintenance flag → `UNREACHABLE` (server answered but is not serving:
    starting up, overloaded, or behind a proxy that is not serving), with a scrubbed excerpt.
  - Response bodies are bounded to 64 MB (larger → `UNEXPECTED_RESPONSE`, "response too
    large"); error excerpts are taken from a 4 KB prefix, scrubbed, HTML-stripped.
  - `HttpTimeoutException` → `TIMEOUT`.
  - `ConnectException` / `UnresolvedAddressException` → `UNREACHABLE`.
  - `SSLHandshakeException` → `TLS_ERROR`.
- `/healthcheck.timestamp` uses Java `Date.toString()` format (`EEE MMM dd HH:mm:ss zzz yyyy`,
  English), observed on DEV/QA.
- **Rationale**: built into the JDK, supports per-client TLS and timeouts, and adds no dependency.
- **Alternatives considered**: Apache HttpClient 5 or OkHttp. Unnecessary dependencies.

### R-5a TLS with the ACME certificates (finding 2026-10-01)

- **Finding**: DEV, QA/inubit1 and QA/inubit2 all present the same self-signed certificate
  (`CN=selfsigned.example.test`, `CA:TRUE`, no subjectAltName, valid until 2036-09-19, SHA-256
  `AB:CD:EF:01:…:67:89`).
  - Chain validation therefore needs a dedicated trust store.
  - Hostname verification **cannot** succeed, because the CN does not match and there is no SAN.
- **Decision**:
  - A per-stage trust store containing exactly this certificate, plus
    `disableHostnameVerification: true`, which is only allowed together with
    `pinnedCertificateSha256` (startup error otherwise).
  - Implementation: a per-client `SSLContext` whose `X509ExtendedTrustManager` wrapper validates
    the chain against the trust store, then compares the leaf fingerprint with the pin, and skips
    only the endpoint-identification step.
  - This keeps the decision per environment. The JVM-wide switch
    `jdk.internal.httpclient.disableHostnameVerification` is **not** used, because it would
    disable the check for every server.
  - For the ACME servers (config-driven, R-6), StartCLI gets `--trustStoreFilePath <p12>` and
    `--disableHostNameVerification`. StartCLI cannot pin a fingerprint, so this remains a residual
    risk for CLI calls and is documented.
- **Recommendation to operations**: issue certificates with proper subjectAltNames per host. With
  those, `disableHostnameVerification` and the pin can be removed from the config, with no code
  change.

## R-6 CLI invocation and password handling

- **Decision**:
  - Invoke `<cliHome>/bin/startcli.sh` (`startcli.bat` on Windows) through `ProcessBuilder` with an
    **argument array**:
    `[script, "-u", username, [TLS flags], "--execCommand", "<command line>", cliUrl]`.
    The username is not a secret (spec FR-025); `-u` is the only way StartCLI accepts it.
    The `<command line>` is assembled only from validated tokens (allow-listed command names,
    patterns from the tool contract) and never contains user-supplied free text.
  - TLS flags follow the server's `tls` config: `--trustStoreFilePath <p12>` only if
    `tls.trustStore` is set, `--disableHostNameVerification` only if
    `tls.disableHostnameVerification: true`. StartCLI has no way to receive a trust-store password
    except as a process argument, so the trust store of a CLI-enabled server MUST be
    password-less (startup error otherwise, see configuration.md).
  - The working directory is `cliHome`. The child environment is built from an **allowlist**
    (decision after review): only `PATH`, `HOME`, `TMPDIR`, `LANG`, `LC_ALL` and `USER` are copied
    from the server's environment (if set), plus `JAVA_HOME` (from `cli.javaHome`) and
    `JAVA_TOOL_OPTIONS` (below). Nothing else reaches StartCLI (R-12). Hard timeout =
    `cliTimeout`: `destroy`, then `destroyForcibly` after 2 s. stdout and stderr are captured in separate bounded buffers
    (1 MB each).
  - **Locale** (decided after S-3): the child environment always gets
    `JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US`. Without it StartCLI prints its
    summary lines in the JVM default locale (German on a `de_DE` workstation); `LANG`/`LC_ALL` do
    not help on macOS, and `startcli.sh` offers no other hook for JVM options. The JVM then writes
    `Picked up JAVA_TOOL_OPTIONS: …` as the first line of stderr, which the classifier treats as
    preamble.
- **Password**: it is never passed via `-p`, because that would show it in the process list and
  violate Constitution II / FR-025. **SPIKE S-2** determines how StartCLI reads the password when
  `-p` is missing. The strategies are tried in this order, and the first one that works is
  implemented behind a `PasswordFeeder` interface:
  1. **stdin pipe**: write the password plus a newline to the process's stdin. This works if
     StartCLI reads from `System.in` or a `Scanner`, or if its bundled JDK 17 build still returns
     a `Console` for piped stdin.
  2. **pseudo-terminal**: run StartCLI under a PTY via `org.jetbrains.pty4j:pty4j:0.13.13`
     (bundles natives for macOS x86_64/arm64, Linux and Windows/ConPTY), wait for the password
     prompt, then write the password. Research confirmed that `Console.readPassword` works under a
     PTY on JDK 21/25/27, while JDK-8361911 makes `System.console()` return `null` for redirected
     stdin on current 21/25 builds.
  3. If neither works, this is a **constitution gate failure**. It is escalated to the user with
     options such as a dedicated low-privilege CLI account or an amendment proposal. Silently
     falling back to `-p` is not allowed.
- **Offline findings (2026-10-01, local 8.1.14 client at `/opt/inubit/client`)**:
  - Main class `com.inubit.ibis.cli.CLI`. `startcli.sh` requires `JAVA_HOME`. The workbench
    installation has no bundled JDK or `java_home_path_setter.sh`, so the server MUST set
    `JAVA_HOME` for the child process from config (`cli.javaHome`; Temurin 17 for 8.1). The script
    prints `JAVA_HOME is set` on stdout before any CLI output.
  - The password is read by `com.inubit.ibis.cli.utils.PasswordReader` through **JLine 2**
    `ConsoleReader.readLine('*')`, not `java.io.Console`. Without `-p`, the CLI prints
    `Password: ` and consumed a newline-terminated value from a **stdin pipe**, then proceeded to
    `loginUserCli`. Connection refused → exit code 1, no hang. **Strategy 1 (stdin pipe) is
    therefore expected to work. pty4j is not planned.** Spike S-2 only needs to confirm a
    successful login against dev.
  - Exit status enum `CLI$ExitStatus { SUCCESS, FAILURE }`; observed: `-v` → 0, failed login → 1.
  - Log output, including ERROR lines with stack traces, goes to **stdout**; stderr stayed empty.
    The output classifier must separate log lines (`^(ERROR|WARN|INFO|DEBUG) \d{2}:\d{2}:\d{2},\d{3} \[`
    plus following `\tat …` lines) from command output.
  - Servers DEV/QA run **8.1.17** (from `/system/info`). The 8.1.14 client only supports servers
    up to 8.1.14; the local 8.1.17 client (`/opt/inubit/client`) supports up
    to 8.1.17 and has an identical `startcli.sh`. The CLI client version MUST match the server
    patch level, so `cli.home` is set per stage or server where versions differ.
  - `startcli.sh -v` prints `CLI <version>` and the supported server versions. It is used for the CLI
    version check (edge case "CLI client version mismatch") and needs no server or credentials.
- **Known limitation (decision 2026-10-01)**: CLI-backed tools are **not supported on Windows**
  in the MVP. `CliRunner` returns `CLI_UNAVAILABLE` ("CLI tools are not supported on Windows in
  this version") when `os.name` starts with `Windows`, and `--check-config` warns when
  `cliHome` is configured there. REST tools remain supported on Windows.
- **Process tree on timeout**: `destroy()` snapshots the process and all descendants (the JVM
  started by `startcli.sh`); members still alive after the 2 s grace — also after the root
  exited — are killed forcibly together with their current descendants.
- **Rationale**: an argument array removes shell-injection risk (Constitution I/V). pty4j costs
  about 2.8 MB plus kotlin-stdlib and JNA, so it is added **only** if spike S-2 shows that the stdin
  pipe is insufficient. That would be recorded in Complexity Tracking.
- **Alternatives considered**:
  - `expect`/`script` wrappers: platform-specific and adding a shell layer.
  - `--encryptString`: only for config files, not for login.

## R-7 CLI result interpretation

- **Decision**: success or failure is decided from **both** the exit code and the parsed output,
  per command. **SPIKE S-3** recorded the exit codes and outputs (see Spike results "S-3"). Parsers
  are written test-first against these recordings (Constitution III).
  - The exit code is 0 for success and 1 for **every** failure, so it never identifies the cause.
  - Preamble lines are skipped: `JAVA_HOME is set`, `Password: `, `SECURITY WARNING: Hostname
    verification is disabled…` on stdout and `Picked up JAVA_TOOL_OPTIONS: …` on stderr (R-6).
  - Classification uses **primarily** the locale-independent INUBIT markers
    `@Start@<code>@@@<text>@End@` inside the exception line, and **secondarily** the English texts
    (guaranteed by the locale setting of R-6):

    | Marker / text | `ToolError.code` |
    |---|---|
    | `@Start@LoginFailure@@@` | `AUTH_FAILED` |
    | `@Start@LoginFailed@@@` together with `ConnectException`, `UnknownHostException` or `SocketTimeoutException` | `UNREACHABLE` |
    | `No process found with id` (processErrorStart: `[<id>]`, kill: `<id>` without brackets) | `NOT_FOUND` |
    | `Command not found.` | `INTERNAL` (an allow-listed command must never produce it) |
    | `@Start@cli.process.option.filter.argument.expression.invalidFilterExpression@@@` | `INVALID_INPUT` |
    | exit 0 with `<n>-OK: …` | success |

  - Unknown output always yields `UNEXPECTED_RESPONSE` with a scrubbed excerpt, never a guessed
    success.
- **Rationale**: exit codes are undocumented, so the recorded behaviour becomes the contract.

## R-8 Process listing (P2) and state model — revised after spike S-4

- **Decision**: `find_processes` reads the Queue Manager through **REST**:
  `POST /ibis/rest/log/queueLog?format=json` with the `<logRequest>` body of R-9.
  - `status EQUAL <rawState>` per raw state value (state table in data-model.md →
    ProcessInstance). Requested states that map to several raw values (e.g. `WAITING` = `Waiting`
    + `Retry`, or several `states`) → one request per raw value, combined by the **paging rule of
    R-9**. No `states` and no `hangingOnly` → no status filter, a single request.
  - `hangingOnly`: only the raw states that count for hanging (`Waiting`, `Retry`, `Queued`,
    `Processing`) are queried, intersected with `states` if given — so `states=[ERROR]` with
    `hangingOnly` gives an empty page without any request — and `startTime LESSER now −
    threshold` is added.
  - `workflowName EQUAL`, `tag EQUAL`, `startTime BETWEEN`/`GREATER`/`LESSER` (epoch ms) are
    evaluated by INUBIT.
  - Sorting `startTime DESCENDING`; paging and `total` per R-9.
  - Row mapping (S-4): `processId` = `workflowId`, `globalProcessId` = `globalPId` (number or
    UUID), `rawState` = `status.content`, `since` = `startTime`, `workflow` = `workflowName`,
    `module` = `moduleName`, `tag`, `node`, `owner`, `priority`.
- **The CLI stays only for process control** (US4: `processErrorStart`, `kill`). The current state
  that US4 checks before acting is read through the same REST query (`workflowId EQUAL <pid>`).
- **Rationale** (Constitution V, REST preferred): S-4 showed that `queueLog` is the Queue Manager
  view. On DEV it returned exactly the rows of `ps -csv -a` (695 of 695 in one snapshot, matched on
  PID/workflowId, workflow, module and timestamp) and its total equals `/metrics`
  `groups.OWNERS.ERROR`. It provides
  every `ps` column plus `globalPId`, `moduleType` and user-defined fields. In addition it filters
  by time, workflow names with spaces and tag on the server, sorts, pages and counts. `ps` can do
  none of that: it has no time filter, its rows are unsorted, `-f` rejects values with spaces
  (`Invalid filter expression!`), and every call costs a StartCLI start (≈ 2 s).
- **Corrections to the original plan**: `ps -a` means **all** entries, not ACTIVE; `ps` without a
  selector returns nothing (`Total: 0`). There is no "running" selector in `ps`.
- **Alternative considered**: `ps -csv` (the original decision). Kept as recorded fixtures
  (`cli/ps_*`) and as a possible fallback, but not implemented in the MVP.

## R-9 Log queries (P2)

- **Decision**: `POST /ibis/rest/log/{logName}?format=json` with an XML `<logRequest>` body:
  - **`noOfItems` is always sent** (S-5: without it the POST returns every match).
  - **Paging rule** (the one rule for `query_logs` and `find_processes`):
    - **single request** (no filter expands to several raw values): `startIndex` = offset,
      `noOfItems` = limit, `total` from the response.
    - **merged requests** (a filter expands to several raw values, e.g. `severity: [WARN]` on
      queueLog = `Waiting` + `Retry`, or several `states`): every request has `startIndex` = 0 and
      `noOfItems` = offset + limit; the rows are merged, sorted by the time field descending and
      sliced to `[offset, offset + limit)`; `total` = the sum of the response totals.
    - `offset + limit` MUST be ≤ 10,000, otherwise `INVALID_INPUT` (bounds the merge; applies to
      both cases).
  - `<filtering>` blocks: dates are **epoch milliseconds** with `BETWEEN` (or `GREATER`/`LESSER`
    for an open range), text uses `LIKE`, workflow/status/processId use `EQUAL`.
  - `LIKE` is a case-sensitive substring match in which `%` and `_` act as SQL wildcards. They are
    passed through unchanged (no escaping) and documented as wildcards in the tool description.
  - Only the fields of the per-log-type filter table in
    [contracts/mcp-tools.md](contracts/mcp-tools.md) §4 are used; anything else gives HTTP 400/500.
  - `<sorting>` is the log type's time field `DESCENDING`.
  - `processLog` is **not offered**: it fails on 8.1.17 (S-5). Workflow executions are in
    `systemLog`.

  The response `{<logName>: {total, success, count, row[]}}` maps onto `Page<LogEntry>` with
  `total` per the paging rule.
- **SPIKE S-5** (done): field names per log type, date format, `LIKE` semantics and the
  per-log-type filter table in `contracts/mcp-tools.md` §4.
- **Rationale**: the POST variant is the only one that supports range and comparison filters.

## R-10 Health (P1)

- **Decision**:

  | Endpoint | Auth | Provides |
  |---|---|---|
  | `GET /healthcheck` | none | status, maintenance mode |
  | `GET /ready` | none | 200 = ready, 503 = not ready, message |
  | `GET /system/info` | auth, XML `SystemInformationList` | version, platform; `versionLine` detection |
  | `GET /metrics?format=json` | auth, licensed | load figures |

  Rules:
  - The four calls run concurrently per environment.
  - Each failure is mapped to an `unavailable` part (FR-008).
  - Reachability is decided by `/healthcheck`.
  - **Order (Phase 3 review H1)**: `/healthcheck` and `/ready` start at once, without waiting
    for an `AUTO` version detection; `/system/info` runs in parallel and its result completes
    the detection (no second `/system/info`; a real failure is cached as the 60 s 8.1
    fallback, a cancelled call is not). `/metrics` runs in parallel once the credentials are
    confirmed, otherwise after `/system/info` (R-12).
- **Unlicensed `/metrics`** (decision after S-5, where DEV and QA were both licensed): any
  `/metrics` failure other than 401, 503, timeout or unreachable (e.g. 402/403/404/500, or a
  body that is not JSON) is reported as `unavailable {load, METRICS_NOT_LICENSED}` with the HTTP
  status in the reason. The contract test uses a hand-written `metrics_unlicensed.*` fixture
  marked SYNTHETIC (T052).
- **Rationale**: REST covers P1 completely; P1 needs no CLI.
- **Alternative considered**: the CLI options `-sv`, `-pctmem` and `getBlockingQueueSize` as a
  metrics fallback. They are deferred, because they add CLI dependency to P1 for marginal value.

## R-11 Inventory (P3) — revised after spike S-6

Every lookup uses the stage's owner, `inventory.owner` (default `OWNERS`; FR-016a).

| Need | Source | Measured on DEV |
|---|---|---|
| Diagram list | REST `GET /model/models?user=<owner>` → `name`, `type`, `group` (diagram group) | 443 models, 72 KB, 0.3 s |
| Diagram detail: modules used | REST `GET /model/modelByName/{name}?user=<owner>` → `Node` elements (`name`, `type`, `id`) | < 0.5 s |
| Diagram detail: active flag, check-in comment, owner | REST `GET /model/export/{name}` (ZIP) → `workflow/workflow.xml`: `IsActive`, `CheckinComment`, `UserOrUserGroupName` | 4 KB, < 0.5 s |
| Diagram detail: version history and tags | CLI `export --exportWorkflowUser <owner> --exportWorkflowType <type> --exportWorkflowGroup <diagram group> --includeHistory --exportFile <tmp>.zip` → `versionHistory.xml`: per workflow `Version{versionNode, CheckinUser, CheckinComment, DateTime "dd.MM.yyyy HH:mm:ss", UserComment, Tags/Tag*}` | group of 17 workflows: 110 KB, 5 s |
| Module list | CLI `export --exportModule '' --exportModuleGroup '' --exportModuleUser <owner> --exportFile <tmp>.zip` → `module/module.xml`. `ModuleGroup` is the plugin type, e.g. XSLT Converter. Each `Module` carries `ModuleName`, `PluginName`, `LastUpdate`, `IsActive`, `WorkflowName`, `CheckinComment`, `UserComment`, `IsInput/OutputConnector`, `IsScheduled` | 1,630 modules in 46 groups, 4.3 MB, 11 s |
| Module detail | entry from the module index above; version history via `versionHistory.xml` → `Modules/Module` of the diagram export where the module occurs | **SPIKE S-6b**: confirm that module history is complete there, otherwise version history is shown for diagrams only |
| Module usage (T126) | REST `GET /model/modelByName/{name}?user=<owner>` of **every** technical workflow → `Node` `name` = module name, `type` = `tw<PluginName without spaces>`; joined with `WorkflowName` of connector modules | 392 workflows, 4,730 nodes, **all** matched a module of the owner by name, module names unique per owner (1,630), node type = plugin type in every case; 8 in flight ≈ 2–3 s in the server (9 s with one curl process per call); XSLT Converter: 630 of 678 used |

- **Decisions**:
  - **No "active version"**: INUBIT 8.1 exposes no active-version marker. Tags are plain text
    (for example ticket IDs like `RPN-4659`). Results show the version history and the `IsActive` flag
    and do not invent an active version (FR-016).
  - **Cache** (FR-016b): an `InventoryCache` per server holds the diagram list, the module index and
    the version history per diagram group. TTL is `inventory.cacheTtl` (default `PT10M`). Results
    carry `collectedAt`. Tools take `refresh: true` to bypass the cache. Concurrent requests for the
    same key share one in-flight load.
  - **CLI exports** run with `cliExportTimeout` (default `PT120S`) into a fresh owner-only temporary
    directory. Only allow-listed index files are parsed (`versionHistory.xml`, `module/module.xml`,
    `workflow/workflow.xml`), and the directory is deleted in `finally`. According to the user, the
    module configurations hold no live credentials, since those are resolved at runtime from
    PartnerManagement and the Credentials Manager. Deletion is kept as hygiene. Module
    configurations are never returned to the assistant.
  - **Values from INUBIT data inside `--execCommand`** (analysis finding S1, Constitution I):
    diagram-group names come from `/model/models` and are inserted into
    `--exportWorkflowGroup <group>`. They MUST match the allowlist
    `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}$` (1–200 chars, no leading `-` or space, amended
    after review) and are always wrapped in single quotes (`'<group>'`). Values containing `'` or failing the allowlist are
    not exported: their history is reported as unavailable with `UNEXPECTED_RESPONSE` ("group name not
    supported by CLI quoting"). Spike S-6b (T010) verifies that a quoted group name containing a space
    is accepted by StartCLI. If no such group exists, it verifies quoting on a group without a space.
    The same rule applies to `--exportWorkflowUser`/`--exportModuleUser` (owner from config).
  - **CLI quoting**: the empty-string arguments (`''`) must reach StartCLI inside `--execCommand`.
    With an argument array this is the literal text `--exportModule '' --exportModuleGroup ''`
    within the single `--execCommand` argument, verified in S-6.
  - **Module usage** (T126; finding F1 of the validation 2026-10-03, user decision option A):
    `WorkflowName` in `module.xml` is set only for connector modules (the workflow the connector
    is bound to: 350 of 1,630 modules on DEV, 0 of 678 XSLT Converter, 0 of 317 Workflow
    Connector), so "no `WorkflowName`" does not mean "unused". The usage of a module is the set of
    technical workflows of the owner whose `modelByName` nodes name it, joined with the
    connector's `WorkflowName` (12 connectors are bound to a workflow whose nodes do not list
    them). A per-server usage index reads all technical workflows — one at a time until the first
    read succeeds, then 8 in flight, no new read after an `AUTH_FAILED` (account-lockout
    protection: credentials may have become invalid since they were confirmed) — through the
    credential guard, within `cliExportTimeout`; it is cached with the module list (same TTL,
    single flight, `refresh`). A workflow that cannot be read (also `NOT_FOUND`) or is not read
    in time, or a failed diagram list, makes the index incomplete: results state
    `usageComplete: false`, an empty usage is then not reported as "unused", and the incomplete
    index is not cached. Duplicate module names (none on DEV) are resolved by the node type. A
    module's version history comes from the group export of the first using workflow (name
    order) that is in the diagram list.
- **Rationale**: these are the only documented read sources for module lists and version history
  in 8.1 (user decision 2026-10-01: version history and the module list via the CLI). REST is used
  wherever it suffices (Constitution V).
- **Alternatives considered**:
  - Deriving modules from the `Node` elements of all workflows: about 390 REST calls, and modules
    not used by any workflow would be missed. Rejected by the user *as the source of the module
    list*; the same calls are now used for the usage of the modules of the CLI module list
    (T126, user decision option A).
  - Showing no history: rejected by the user.

## R-12 Credentials and scrubbing

- **Decision**: credentials come **only** from operating-system environment variables, following
  the convention `INUBIT_<STAGE>_<SERVER>_{USERNAME|PASSWORD}`. If no server-specific variable is
  set, the stage-wide `INUBIT_<STAGE>_{USERNAME|PASSWORD}` is used.
  - Names are uppercased, and every character outside `[A-Z0-9]` becomes `_`.
  - Collisions between derived names are a startup error.
  - A `CredentialResolver` reads them once at startup and records the **source variable name** of
    each value, so that `--check-config` and error messages can say which variable is used or
    missing, without ever showing the value.
  - Credential keys in the YAML are rejected.
  - Passwords are wrapped in a `Secret` value object. Its `toString()` returns `***`, and it
    registers its value and the derived Basic-auth Base64 token with a central `SecretScrubber`.
- **Scrubbing**: applied to every outgoing string: tool results, `ToolError` messages and excerpts,
  log events (through a Logback encoder wrapper) and audit records.
- **CLI child processes**: they get an **allowlisted** environment (R-6): `PATH`, `HOME`, `TMPDIR`,
  `LANG`, `LC_ALL`, `USER`, `JAVA_HOME`, `JAVA_TOOL_OPTIONS` — nothing else. Otherwise StartCLI,
  which would inherit the MCP server's environment, would receive every stage's credentials.
- **What counts as a secret** (spec FR-025): passwords, the Basic-auth token and trust-store
  passwords. The username is a non-secret identifier: it is needed for audit attribution and is
  passed to StartCLI with `-u`, because StartCLI offers no other way.
- **Account-lockout protection** (decision after the Phase 3 review, M2): a per-server
  `CredentialGuard` (adapter layer, owned by the server's gateway slot) is shared by the REST
  client and the StartCLI calls of that server.
  - Every authenticated call takes a permit. HTTP 401 or a CLI `LoginFailure` marks the
    credentials as rejected; a 2xx or 403 answer (or a CLI run with exit code 0) confirms them.
  - After a rejection, every authenticated call of that server fails for **60 s** with a cached
    `AUTH_FAILED` ("authentication failed recently; not retried for N s to avoid account
    lockout"; next step: fix `INUBIT_…_PASSWORD` and restart Claude Code) without network or CLI
    access. Unauthenticated calls (`/healthcheck`, `/ready`, the maintenance probe) are not
    affected.
  - While the credentials are unconfirmed, only one authenticated call is in flight per server;
    the others wait for its outcome. `get_health` additionally runs `/metrics` after
    `/system/info` while unconfirmed. Result: at most **one failed login per server per 60 s**.
  - The 60 s fallback of a failed `AUTO` version detection (R-10) is independent of this, but
    both use the same window.
- **Rationale**: this is the user's requirement, and a stage-wide fallback matches the common case
  of one account per stage. It is also the simplest option for a developer workstation, since the
  YAML can be shared or committed because it holds no secrets. Defence in depth for
  Constitution II / FR-025: unit tests assert that seeded secrets never appear in any channel
  (SC-006).
- **Alternatives considered**:
  - `env:`/`file:` references in the YAML: dropped in favour of a single convention.
  - OS keychain: deferred; it could be added later as an additional credential source.

## R-13 Server-side confirmation (FR-022)

- **Decision**: an in-memory `ConfirmationRegistry` (a `ConcurrentHashMap`) holds
  `PendingConfirmation` entries.
  - Codes are 16 random bytes from `SecureRandom`, URL-safe Base64 without padding (22 chars).
  - A code is removed on its first redemption attempt.
  - The binding (environment, action, processId) and expiry are checked on redemption.
  - Expired entries are swept lazily.
- **Rationale**: a stdio server lives for exactly one client session, so persistence is
  unnecessary. Codes not surviving a restart is the safe behaviour.

## R-14 Audit log (FR-023)

- **Decision**: JSON Lines at `<auditDirectory>/audit-YYYY-MM.jsonl`, written through a
  `FileChannel` opened with `APPEND`, with owner-only permissions (POSIX).
  - Each record is written and `force(true)`d before the tool returns.
  - The `EXECUTE` record is written **before** the CLI call. If that write fails, the call is never
    made (fail closed). A second record with the final outcome follows the call.
- **Rationale**: append-only, it survives restarts, it is greppable, and it needs no dependency.
- **Alternatives considered**: SQLite or an embedded database. Unnecessary.

## R-15 Concurrency and timeouts

- **Decision**: fan out across the environments of a stage or "all" with
  `Executors.newVirtualThreadPerTaskExecutor()`, with a per-environment deadline equal to the
  environment timeout (REST) or `cliTimeout` (CLI). An environment that misses its deadline is
  reported as `TIMEOUT` while the others are returned.
- **Rationale**: SC-002 requires ≤ 10 s across up to 10 servers even if one hangs.

## R-16 Logging and stdout hygiene

- **Decision**:
  - SLF4J (pulled in by the SDK) with **Logback** `logback-classic`, a `ConsoleAppender` with
    `target=System.err` and the built-in `JsonEncoder` for structured logs (Constitution VII).
    The level comes from config.
  - At startup, `Main` captures the real `System.out`, passes it explicitly to
    `StdioServerTransportProvider(mapper, System.in, realOut)`, then calls
    `System.setOut(System.err)` so that stray library output cannot corrupt the protocol stream.
  - `Locale.setDefault(Locale.ENGLISH)` keeps SDK validation messages in English.
- **Rationale**: the MCP stdio spec forbids non-protocol output on stdout. Logback writes to stdout
  by default, so the explicit `System.err` target is required.

## R-17 Testing strategy (Constitution III)

| Layer | Tooling | What |
|---|---|---|
| Unit | JUnit Jupiter, AssertJ | config validation, target resolution, write guard, confirmation registry, scrubber, result limiter, parsers |
| Contract — REST | WireMock 3.13.2 (`org.wiremock:wiremock`, Java 11+) serving **recorded** 8.1.17 responses | each REST adapter method against fixtures in `src/test/resources/fixtures/v8_1/rest/` |
| Contract — CLI | `FakeProcessLauncher` replaying recorded stdout/stderr/exit code | each CLI adapter method against fixtures in `src/test/resources/fixtures/v8_1/cli/` |
| MCP end-to-end | real `McpSyncServer` on `StdioServerTransportProvider` with piped streams, driving raw JSON-RPC (`initialize`, `tools/list`, `tools/call`) | tool registration, annotations, schemas, `structuredContent`, error results, write-tool visibility |
| Live (opt-in) | `@Tag("live")`, Maven profile `live`, `INUBIT_LIVE_ENV` | read-only checks against a named non-production environment; the test harness refuses `production: true` |

- The SDK publishes no in-memory transport, so piped stdio is used. This is the same approach the
  SDK's own tests take.
- Mockito is not needed: ports are interfaces with hand-written fakes.
- WireMock limitation: the pom pins `com.networknt:json-schema-validator` to the SDK's 3.x line,
  so WireMock's JSON-schema matchers (`matchingJsonSchema`, built for 1.x) must not be used in
  stubs. Request bodies are matched with `equalToXml`/`matchingXPath` instead.

## R-18 Fixture recording

- **Decision**: a script `tools/record-fixtures.sh` captures raw responses and CLI outputs from a
  named **non-production** environment into the fixture folders. It reads credentials from the
  environment and feeds the CLI password according to S-2.
  - It then anonymizes the fixtures: hostnames, usernames and IPs are replaced, business payload in
    log messages is reduced to generic text.
  - The recorded fixtures are reviewed and committed.
- **Rationale**: the contract tests need real shapes, and the undocumented behaviour must be
  captured reproducibly.

---

## Verification spikes (first implementation tasks)

| ID | Question | Method | Fallback if negative |
|---|---|---|---|
| S-0 | Does Claude Code connect to an SDK-2.0.1 stdio server and list and call tools with annotations and `structuredContent`? | hello-world server | Escalate (protocol revision); evaluate SDK 3.x milestone |
| S-1 | ~~Basic REST access with a personal account~~ **Done ✅** | — | — |
| S-2 | ~~How does StartCLI read the password?~~ **Done ✅**: stdin pipe, login on DEV successful | — | — |
| S-3 | ~~Messages per outcome of `processErrorStart` and `kill`~~ **Done ✅** (failure cases recorded, success cases synthetic) | record | Output-only classification |
| S-4 | ~~`ps -csv` format~~ **Done ✅**: `ps` documented; `queueLog` (REST) chosen for process lists | record | Text parser for the non-CSV `ps` output |
| S-5 | ~~Log field names per log type, filter syntax, unlicensed `/metrics`~~ **Done ✅** (unlicensed `/metrics` not observable) | record | Reduce the filters offered per log type |
| S-6 | ~~Read sources for P3~~ **Done ✅** (see R-11) | — | — |
| S-6b | Is the module version history complete in `versionHistory.xml` → `Modules`? Time zone of `DateTime`/`LastUpdate`? Quoted group names accepted (see R-11)? **Measured ✅** (Europe/Berlin, quoting OK); Workbench comparison by the user pending | export a group, compare with the Workbench | version history for diagrams only; assume `Europe/Berlin` |

## Spike results (2026-10-01, DEV + QA, read-only)

**S-0 SDK 2.0.1 stdio server: local check ✅, Claude Code check ✅ (2026-10-03)**
- Claude Code check: with `s0` registered (`claude mcp add --scope local`), a new Claude Code session
  listed the tool `mcp__s0__ping` with its input schema (`additionalProperties: false`, `maxLength`)
  and the call returned the structured result `{"pong":true,"echo":…,"serverTime":…}`. The protocol
  risk from R-2 is resolved for the team's client. `s0` was unregistered and `src/spike/` deleted
  afterwards.
- Throw-away server `src/spike/java/S0HelloServer.java`, started by `src/spike/run-s0.sh` (classpath
  from `mvn dependency:build-classpath`, single-file source launch). One tool `ping` with
  `readOnlyHint=true`, an `outputSchema` and a `structuredContent` result.
- Raw JSON-RPC lines piped into the server (SDK 2.0.1, JDK 25):
  - `initialize` (protocol `2025-11-25`) → `protocolVersion: "2025-11-25"`,
    `capabilities: {logging: {}, tools: {listChanged: false}}`, `serverInfo`.
  - `tools/list` → the tool carries `name`, `title`, `description`, `inputSchema`, `outputSchema`
    and `annotations {title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint}`.
  - `tools/call ping` → `content` (one text block with the JSON) **and** `structuredContent`,
    `isError: false`.
  - An unknown argument is rejected by the SDK's input validation without calling the handler:
    `isError: true`. The message follows the JVM default locale (German on a `de_DE` machine),
    which confirms the need for `Locale.setDefault(Locale.ENGLISH)` (R-16).
  - The server exits when stdin is closed; stdout carried only protocol messages.
- `McpSchema.Tool.builder()` without arguments is deprecated in 2.0.1; use
  `Tool.builder(name, mapper, inputSchemaJson)`.
- **Pending**: registration with Claude Code (`claude mcp add s0 -- <abs path>/src/spike/run-s0.sh`)
  and a `ping` call by the user, then delete `src/spike/`.

**S-1 REST access: ✅**
- A personal account with Basic auth gets HTTP 200 on `/system/info` (XML), `/log/systemLog?format=json`
  (JSON) and `/model/models` (XML) on DEV. `/system/info` returns HTTP 200 on QA/inubit1 and
  QA/inubit2.
- All servers run **8.1.17**.
- `/healthcheck` and `/ready` work unauthenticated. The timestamp is in `Date.toString()` format.

**S-2 CLI password via stdin: ✅**
- Setup: 8.1.17 client with Temurin 17, `--trustStoreFilePath` and `--disableHostNameVerification`.
  The password was written to stdin and **not** passed with `-p`; `INUBIT_*` was removed from the
  child environment.
- Result: `uptime` and `ps -csv -l 3` both exit with 0. Principle II gate is satisfied. **pty4j is
  dropped.**
- Output preamble on stdout, which the classifier skips:
  - `JAVA_HOME is set`
  - `Password: `
  - `SECURITY WARNING: Hostname verification is disabled. Your connection might not be secured!`
- `uptime` output: `Thu Oct 01 13:07:29 CEST 2026 up 8 days 20:55:33s`.

**S-3 CLI outcomes: ✅ failure cases recorded; success cases synthetic (user decision)**
- Recorded on DEV with `tools/record-fixtures.sh dev node1 <case>` → `fixtures/v8_1/cli/<case>.*`.
  No case changed anything: PID `999999999` does not exist, and the failed login used the dummy
  password `dummy-wrong-password` once.
- **Exit code is 1 for every failure**; it does not distinguish the cause. stderr is empty (apart
  from the `Picked up JAVA_TOOL_OPTIONS` line, see locale below). Everything else is on stdout:
  the preamble (`JAVA_HOME is set`, `Password: `, `SECURITY WARNING: …`), an `ERROR hh:mm:ss,SSS
  [main      ] CLI … ERROR` log line with exception and stack trace, then a summary block.
- The exception message carries locale-independent INUBIT codes in the form
  `@Start@<code>@@@<text>@End@`. The summary block starts with `EXECUTION ERROR` (the command ran
  and failed) or `CONNECTION ERROR` (login or connection failed).

| Case (fixture) | Exit | Key output (English locale) | Proposed `ToolError.code` |
|---|---|---|---|
| `processErrorStart 999999999` (`processErrorStart_unknown`) | 1 | `InubitException: Exception from service object: @Start@IError@@@Internal INUBIT error!@End@@Start@No process found with id [999999999]!@@@…@End@`; summary `EXECUTION ERROR` / `No process found with id [999999999]!` | `NOT_FOUND` |
| `kill 999999999` (`kill_unknown`) | 1 | `@Start@IError@@@Internal INUBIT error!@End@@Start@Kill processes failed:` then `999999999: Exception from service object: : Internal INUBIT error! : No process found with id 999999999!` (no brackets around the id); summary `EXECUTION ERROR` | `NOT_FOUND` |
| unknown command `noSuchCommand` (`unknown_command`) | 1 | `@Start@IError@@@Internal INUBIT error!@End@Command not found.`; summary `EXECUTION ERROR` / `Command not found.` (login succeeded first) | `INTERNAL` (an allow-listed command must never hit this) |
| wrong password (`login_failed`) | 1 | `CliConnectionException: @Start@LoginFailure@@@The user does not exist or password does not match.@End@`; summary `CONNECTION ERROR` | `AUTH_FAILED` |
| unreachable `https://127.0.0.1:9/ibis/servlet/IBISSoapServlet` (`unreachable`) | 1 (after ~1 s) | `java.net.ConnectException: Connection refused` (stack trace via `loginUserCli`), then `CliConnectionException: @Start@LoginFailed@@@Login to the server failed.@End@Error opening socket: java.net.ConnectException: Connection refused`; summary `CONNECTION ERROR` | `UNREACHABLE` |

- **Classification** (adopted in R-7): markers `@Start@<code>@@@…@End@` first, English texts
  second; the mapping table is in R-7.
- **Locale**: the summary lines follow the JVM default locale of the StartCLI process. On a
  `de_DE` workstation they read `Interner INUBIT-Fehler!` and `Anmeldung am Server ist
  fehlgeschlagen.` (fixture `processErrorStart_unknown_default_locale`). `LANG`/`LC_ALL` do not
  change this on macOS. `JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US` in the child
  environment does (`startcli.sh` has no other hook for JVM options); the JVM then prints
  `Picked up JAVA_TOOL_OPTIONS: …` on stderr. **Adopted** (R-6): every StartCLI call sets this
  variable, and the stderr line is preamble. All other fixtures were recorded with it.
- **Success cases**: the user chose synthetic fixtures instead of running state-changing commands.
  `cli/processErrorStart_ok.*` and `cli/kill_ok.*` follow the recorded preamble, exit code 0 and
  the `<n>-OK: <text>` pattern of successful commands (`1-OK: Workflow group exported
  successfully.` from the export in S-6b). A sibling `.README` marks them
  `SYNTHETIC – verify in T115`; the exact result text is unknown until then.

**S-4 Process lists: ✅ — recommendation: REST `queueLog` instead of `ps` (adopted in R-8)**
- Recorded on DEV (read-only): `cli/ps_error`, `ps_waiting`, `ps_all`, `ps_queued` (`ps -csv -<sel>
  -l 5`), `ps_no_selector`, `ps_filter_workflow`, `ps_filter_space`, and `rest/log_queueLog`,
  `rest/log_queueLog_error` (POST `status EQUAL Error`). Full listings (`ps -csv -a -l 2000`,
  queueLog `noOfItems=2000`) were compared in memory and not stored.
- **`ps -csv` format**:
  - Header `UID,PID,PRIO,STATE,DATE,WORKFLOW,MODULE,NODE,TAG`, comma-separated, trailer
    `Total: <n>`, no quoting observed.
  - `UID` is the owning user group (`OWNERS`), not a person. `PRIO` was `normal` in all rows.
  - `PID`: 9-digit decimal (`110190387`). The same PID can appear in several rows (131 of 695 in
    snapshot 2):
    one row per workflow/module of a process that called sub-workflows.
  - `STATE`: only `Error` on DEV (all rows). Docs show `Waiting` as a second value.
  - `DATE`: `yyyy-MM-dd'T'HH:mm:ss`, **no offset, local time of the server (`Europe/Berlin`)**:
    every row equals the queueLog `startTime` (epoch ms) converted to Europe/Berlin and truncated
    to seconds (695 of 695). It is the time the entry entered the Queue Manager, i.e. for `Error`
    rows the time the error occurred (the queue file name carries the same timestamp).
  - Rows are **not sorted** (neither by DATE nor by PID).
  - Selectors: `-a` = **all** entries (not "active"), `-e` = error, `-w` = waiting, `-q` = queued.
    Without a selector `ps` returns `Total: 0`; this explains the empty result of the first S-4
    attempt. There is no selector for running instances.
  - `-f 'workflow=<name>'` works quoted and unquoted. A value with a space is rejected in every
    quoting variant (`'workflow=No Such Workflow'`, `"workflow=…"`, `'workflow="…"'`):
    `@Start@cli.process.option.filter.argument.expression.invalidFilterExpression@@@Invalid
    filter expression!`, exit 1 (`ps_filter_space`). On DEV no technical workflow and no group has
    a space in its name (only 33 BPDs do).
- **`queueLog` = Queue Manager view**:
  - Snapshot 1 (15:16): counts were equal — `ps -c -a` = `ps -c -e` = 693, queueLog
    `status=Error` = 693, `/metrics` `groups.OWNERS.ERROR` = 693.
  - Snapshot 2 (15:20, two new errors meanwhile): `ps -csv -a` and queueLog both listed 695
    entries, and every `ps` row matched one queueLog row on `PID = workflowId`,
    `WORKFLOW = workflowName`, `MODULE = moduleName` and `DATE = startTime` (Berlin).
  - Snapshot 3 (706 rows, later): `globalPId` equals `workflowId` in 420 rows (top-level
    processes), is a different number in 53 rows (the id of the calling process) and a **UUID** in
    233 rows (sub-workflows). `ps` shows `workflowId`. `processErrorStart`/`kill` take the
    `ps` PID (docs: "processId from the Queue Manager"), so the tool's `processId` is
    **`workflowId`**.
  - Accepted `status` filter values: `Error`, `Waiting`, `Queued`, `Processing`, `Retry`
    (HTTP 200); `Running`, `Active`, `OK`, `Suspended`, `ERROR` → HTTP 500 (case-sensitive).
    Observed row values: `{"level": 0, "content": "Error"}` and `{"level": 1, "content":
    "Waiting"}` (58 rows earlier in the day; they had disappeared at the time of the `ps -w`
    call).
  - Filters verified on queueLog: `status`, `workflowName` (`EQUAL`/`LIKE`), `workflowId`,
    `globalPId` (also UUID), `tag`, `moduleName` (`LIKE`), `node`, `startTime`
    (`BETWEEN`/`LESSER`), combinations with AND, sorting and paging.
- **Comparison**:

  | Need of `find_processes` | `ps -csv` (CLI) | `queueLog` (REST) |
  |---|---|---|
  | PID, state, timestamp, workflow, module, tag, node, owner | ✅ | ✅ (`workflowId`, `status`, `startTime`, …) plus `globalPId`, `moduleType`, `userDefined1…5` |
  | state filter | one selector per call | `status EQUAL` per request |
  | workflow filter | `-f`, no spaces | `workflowName EQUAL`, any value |
  | time range | ✗ (client side) | `startTime BETWEEN` |
  | sorting / paging / total | ✗ / `-l` only / trailer | ✅ / ✅ / ✅ |
  | cost per call | StartCLI start ≈ 2 s, CLI installation needed | ≈ 0.2 s, no CLI |
  | timestamp | local time without offset | epoch ms |

- **Recommendation (adopted)**: `find_processes` and the state check before restart/kill use
  `queueLog` (R-8). `ps` is not implemented in the MVP; its fixtures stay for reference.
- **State table**: see data-model.md → ProcessInstance.

**S-5 Logs and metrics: ✅ (unlicensed `/metrics` not observable)**
- Recorded on DEV with `tools/record-fixtures.sh dev node1 <case>` → `fixtures/v8_1/rest/`:
  `log_<logType>.json` (GET, `noOfItems=5`) for all 8 log types, `log_systemLog_filtered.*`
  (POST with `BETWEEN` + `LIKE`, request body included) and `metrics.json`. Each body has a
  `<case>.http` file with status and content type.
- **`processLog` is unusable on 8.1.17** (DEV and QA/inubit1) and is **removed from the
  `logType` enum** (decision 2026-10-01): `GET` → HTTP 500 HTML,
  `@Start@IError@@@…@End@@Start@DBConnectorFieldNotFoundInAvailableNames@@@Failed to find database
  identifier null…` (fixture `log_processLog.html`); `POST` → HTTP 400 even without filters.
  Workflow executions are in `systemLog` instead (`workflowName`, `globalPId`, `success`,
  `message`, `startTime`, …).
- **POST body** (documented shape plus one undocumented element):

  ```xml
  <logRequest>
      <startIndex>0</startIndex>          <!-- 0-based offset -->
      <noOfItems>50</noOfItems>           <!-- page size; not in the docs example, but honoured -->
      <filtering><field>startTime</field><comparison>BETWEEN</comparison>
                 <min>1790255382000</min><max>1790860182000</max></filtering>
      <filtering><field>message</field><value>error</value><comparison>LIKE</comparison></filtering>
      <sorting><field>startTime</field><order>DESCENDING</order></sorting>
  </logRequest>
  ```

  - **Always send `noOfItems`**: without it the POST returns *every* match (40,442 rows, 32 s on
    DEV). `noOfItems`/`startIndex` as query parameters are ignored for POST.
  - The response is `{<logName>: {total, success, count, row[]}}`; `total` = all matches,
    `count` = rows in this page, `row[].index` = 1-based position **within the page**.
  - Several `<filtering>` blocks are combined with AND. `<order>` is `ASCENDING`/`DESCENDING`.
    A GET without sorting is **not** newest-first, so the adapter always sorts by the time field.
- **Date format**: epoch **milliseconds** only (`BETWEEN` `min`/`max`, `GREATER`, `LESSER`). ISO
  values (`2026-09-24 00:00:00`, `2026-09-24T00:00:00Z`) → HTTP 500 `NumberFormatException`.
  `EQUAL` additionally accepts `lastDay` (and, per docs, `last7Days`/`last30Days`).
- **`LIKE`**: substring match (`GZIP` finds `… Not in GZIP format`), **case-sensitive** (`gzip`
  finds nothing). SQL wildcards are active: `%` = any sequence, `_` = one character (`G_IP`
  matches). `*` is literal. No escape syntax was found. **Decision**: `text` is passed through
  unchanged, and `%`/`_` are documented as wildcards in the tool description and docs.
- **Errors**: unknown field, non-filterable field (`userDefined1…5`) or unknown comparison →
  HTTP 400 HTML "Bad Request" (not 404 as documented); an invalid enum value (queueLog `status`
  other than `Error`, `Waiting`, `Queued`, `Processing`, `Retry` — case-sensitive, see S-4) →
  HTTP 500 HTML. Both map to `UNEXPECTED_RESPONSE`; the tool
  avoids them by validating filters against the per-log-type table in
  [contracts/mcp-tools.md](contracts/mcp-tools.md) §4.
- **Value shapes** the parser must accept:
  - `success`: `true` or `{"level": 0, "content": false}` (systemLog, auditLog)
  - queueLog `status`: `{"level": 0, "content": "Error"}` or `{"level": 1, "content": "Waiting"}`
  - `globalPId`: number **or** UUID string (queueLog)
  - keyManagerLog `validity`: number or `{"level": 0, "content": <ms>}`
  - webserviceManager `webserviceStatus`: `true` or a string such as `"workflowNotActive"`
  - `nextStartTime`: number or `""`
- **Field names per log type** (all times epoch ms): see the filter table in
  [contracts/mcp-tools.md](contracts/mcp-tools.md) §4. No log type has a severity field; the
  closest are `success` (systemLog, auditLog) and `status` (queueLog).
- **`/metrics?format=json`**: HTTP 200 JSON on DEV and QA/inubit1, i.e. both are licensed, so the
  unlicensed response could not be observed. Shape: `serverName`, `serverId`, `maintenanceMode`,
  `usedMemoryInMByte`, `freeMemoryInMByte`, `maxMemoryInMByte`, `threadsInUse`, `licensedThreads`,
  `maxThreadSize`, `privilegedThreadsInUse`, `maxPrivilegedThreads`, `blockingQueueEntries`,
  `maxBlockingQueueSize`, `licenseExpirationDate`, `daysUntilLicenseExpiry`,
  `logsDBDataSourcePool`/`ibisDBDataSourcePool` `{active, idle, size, maxActive, waitCount,
  removeAbandonedCount}`, `users {}`, `groups.<owner>.{QUEUED, SUSPENDED, PROCESSING, WAITING,
  ERROR, OK, RETRY}`, `tomcatThreadPool {currentThreadCount, currentThreadsBusy, maxThread}`.
  **Decision** (adopted in R-10): any `/metrics` failure other than 401/503/timeout/unreachable
  (e.g. 402/403/404/500 or a non-JSON body) is classified as `{load, METRICS_NOT_LICENSED}`, with
  the HTTP status in the reason.
- **Observation that led to the S-4 rework**: `queueLog` lists the Queue Manager entries, and its
  total matches `/metrics` `groups.OWNERS.ERROR` + `WAITING`. See "S-4".

**S-6 Inventory sources:**

| Need | Source (8.1.17) | Result |
|---|---|---|
| Diagram list | `GET /model/models?user=<owner>` | ✅ Without `user` the list is **empty**. `user` is the owning Workbench user or **user group**. For owner `OWNERS` (the standard on all stages): 443 models (392 technical, 39 bpd, 10 systemdiagram, 1 organigram, 1 constraintsdiagram). Attributes are `name`, `type`, `group` (diagram group, e.g. `GRP-35`, `GRP-26`) and `version` (always `head` in the list). `deep`, `views`, `businessUser` give nothing extra. |
| Diagram detail | `GET /model/modelByName/{name}?user=<owner>[&version=n]` | ✅ The model plus its **modules as `Node` elements** (`name`, `type`, `id`). `deep=true` adds connections and properties. `version=1` works, so versions exist. |
| Active flag, check-in comment, last change | `GET /model/export/{name}` (ZIP) → `workflow/workflow.xml` (`IsActive`, `CheckinComment`, `UserOrUserGroupName`) and `module/module.xml` (`ModuleGroupName`, `PluginName`, `LastUpdate`, `IsActive`, …) | ✅ Head version only. **The ZIP also contains full module configurations, which may hold connector credentials**: it is parsed in memory only, never written to disk, and only allow-listed metadata fields are extracted. The `module/module.xml` part is superseded by R-11 (module list from the CLI module export). |
| Version list / tags / active version | not in any REST response; `/process/metadata/{name}` → HTTP 500 | ❌ Only the CLI `export … --includeHistory` (`versionHistory.xml`) remains, and it works per user/group/type filter, not per diagram. *Superseded by R-11 (user decision): version history from the CLI export.* |
| Module list | no list endpoint; derived from `Node` elements of all technical workflows of the owner (parallel `modelByName` calls, cached per session) or CLI `export --exportModule ''` | ⚠️ *Superseded by R-11 (user decision): the module list comes from the CLI module export, into a private temporary directory that is deleted afterwards.* |

**S-6b Module version history, time zone, quoting: ✅ measured, awaiting the user's Workbench comparison**
- Export (DEV, read-only, into a private temporary directory):
  `export --exportWorkflowUser 'OWNERS' --exportWorkflowType 'technical' --exportWorkflowGroup
  'GRP-41' --includeHistory --exportFile '<tmp>/history.zip'` → exit 0,
  `1-OK: Workflow group exported successfully.`, 97 KB ZIP with 60 entries (`archive.properties`,
  `versionHistory.xml` 604 KB, `Repository.zip`, `workflow/workflow.xml`, `module/module.xml` and
  one configuration file per module).
- **Quoting (R-11)**: all four values single-quoted (`'OWNERS'`, `'technical'`, `'GRP-41'`,
  the file path) are accepted. DEV has no diagram group with a space, so a quoted group with a
  space could not be tested. The R-11 allowlist keeps allowing spaces inside the quotes; whether
  StartCLI accepts them stays unverified until such a group exists.
- **`versionHistory.xml`**: `VersionInformation/Workflows/WorkflowGroup[@Name]/Workflow[@Name,
  @Type]/Version` and `VersionInformation/Modules/Module[@Name, @Type="technical"]/Version`, each
  `Version` with `versionNode`, `CheckinUser`, `CheckinComment`, `DateTime`, optional
  `UserComment` and `Tags/Tag*`. Group `GRP-41`: 22 workflows, 55 modules (the same 55 as in
  `module/module.xml`), 733 versions, 45 of them tagged.
- **Module history completeness**: for `Module-9101` (34 versions) every version 2–33
  matches an `auditLog` entry `AuditLogModifyModule` `Module-9101[<n>]` to the second.
  Versions 1 (creation) and 34 have no `AuditLogModifyModule` entry. So far the history looks
  complete; the user compares three modules with the Workbench: `Module-9101`
  (34 versions), `Module-9102` (26) and `Module-9103` (23), the modules with the most
  versions and tags.
- **Tags** are the *current* assignment: a tag that was moved later appears only on the version it
  is on now. Example: `auditLog` shows `TAG-01` set on Demultiplexer versions 4–9 one after the
  other (`AuditLogTagModule …[4/TAG-01]`, …), while `versionHistory.xml` has no `TAG-01` on
  that module any more. The tag history is only in `auditLog`.
- **Time zone**: `DateTime` is **local time `Europe/Berlin` without offset**. Workflow
  `Workflow-0101` versions 3–6 equal the `auditLog` `AuditLogModifyWorkflow` epoch values
  converted to Europe/Berlin to the second (e.g. version 6 `23.01.2026 07:54:27` =
  `06:54:27Z`, CET; version 3 `05.12.2025 09:26:24` = `08:26:24Z`). `module.xml` `LastUpdate`
  uses the same format and zone: for all 55 modules it lies 0–35 minutes **before** one of the
  module's version timestamps (14 exactly equal). It is the last content change of the exported
  head module and can be much older than the newest version (`Module-9104`: `LastUpdate`
  12.11.2025, newest version 13.07.2026). `LastUpdate` is therefore shown as "last change", not as
  "last check-in".
- **Content notes**: `CheckinComment` may hold structured INUBIT texts such as
  `DefaultMovedWorkflowCheckinComment@@@<from>/<to>@@@` or
  `DefaultCommitCommentImport###<comment>###@@@Deploying User: <login>@@@Server: <host>@@@Version:
  <n>@@@Export/Deployment: <date>@@@`. `module.xml` additionally has `ExportUser` and
  `CheckoutUser`. These values are anonymized in the fixtures.
- **Fixtures** (anonymized, comments replaced by `[message n]`):
  - `rest/model_models_owner.xml` (full list, 443 models), `rest/modelByName_sample.xml`
    (`Workflow-0101`)
  - `rest/model_export_sample.zip`: `workflow/workflow.xml` (without `WorkflowModule` subtrees) and
    `archive.properties`. The REST download has **no `Content-Type` header**; the adapter detects
    the ZIP by its `PK` signature.
  - `cli/export_history_sample.*` with a ZIP holding only `versionHistory.xml` (3 workflows,
    3 modules)
  - `cli/export_modules_sample.*` with a ZIP holding only `module/module.xml` (20 modules)

## Sources

- MCP Java SDK: https://github.com/modelcontextprotocol/java-sdk (v2.0.1: README, ROADMAP,
  MIGRATION-2.0, docs/server.md), https://repo1.maven.org/maven2/io/modelcontextprotocol/sdk/
- MCP spec: https://modelcontextprotocol.io/specification/2025-11-25/basic/transports,
  https://modelcontextprotocol.io/specification/versioning,
  https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning
- Spring AI MCP: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
- WireMock: https://wiremock.org/docs/download-and-installation/
- pty4j: https://repo1.maven.org/maven2/org/jetbrains/pty4j/pty4j/0.13.13/
- JDK console behaviour: https://bugs.openjdk.org/browse/JDK-8308591,
  https://bugs.openjdk.org/browse/JDK-8351435, https://bugs.openjdk.org/browse/JDK-8361911
- INUBIT: see [docs/research/inubit-interfaces.md](../../docs/research/inubit-interfaces.md) §6
