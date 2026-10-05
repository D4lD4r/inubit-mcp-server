---

description: "Task list for INUBIT MCP Server MVP"
---

# Tasks: INUBIT MCP Server MVP

**Input**: Design documents from `/specs/001-inubit-mcp-mvp/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/mcp-tools.md](contracts/mcp-tools.md),
[contracts/configuration.md](contracts/configuration.md), [quickstart.md](quickstart.md)

**Tests**: REQUIRED. Constitution III (Test-First, NON-NEGOTIABLE) makes TDD mandatory. Every test
task MUST be written first and observed to fail before the implementation task it precedes.

**Organization**: Tasks are grouped by user story so that each story can be implemented and tested
independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 = P1 health, US2 = P2 failure diagnosis, US3 = P3 inventory, US4 = P4 process control

## Path Conventions

Single Maven module at the repository root (see plan.md → Project Structure):

> Note: namespace renamed in feature 002; the paths and coordinates below use the new namespace.

- `main/…` stands for `src/main/java/de/dadecker/inubit/mcp/…`
- `test/…` stands for `src/test/java/de/dadecker/inubit/mcp/…`
- `res/…` stands for `src/main/resources/…`
- `fixtures/…` stands for `src/test/resources/fixtures/v8_1/…`

Conventions that apply to all tasks:

- Java 21 records for value objects.
- No class in `domain` or `application` imports the MCP SDK, `java.net.http`, or `ProcessBuilder`
  (Constitution V).
- Only `main/mcp/**` imports `io.modelcontextprotocol.*` (research R-2).

## Live-system rules (apply to every task that touches DEV/QA)

- Only the non-production stages **DEV** (`https://inubit-dev-1.example.test:8443`) and
  **QA** (`https://inubit-qa-1.example.test:8443`, `https://inubit-qa-2.example.test:8443`)
  may be contacted. TEST, STAGING and PROD are never contacted by any task.
- Credentials come from `INUBIT_DEV_*` / `INUBIT_QA_*`. If the current shell does not have them, run
  the command via `zsh -ic '…'`.
  - Never print them.
  - Never pass them as process arguments. curl gets them via `-K -` on stdin, StartCLI via stdin.
- TLS: use the trust store `~/.config/inubit-mcp/truststore.p12` and the pinned certificate
  `AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89`
  (curl: `-k --pinnedpubkey 'sha256//q83vASNFZ4mrze8BI0VniavN7wEjRWeJq83vASNFZ4k='`).
- StartCLI:
  - `/opt/inubit/client/bin/startcli.sh` with
    `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`.
  - Flags `--trustStoreFilePath <p12> --disableHostNameVerification`.
  - Remove `INUBIT_*` from the child environment.
- Any state-changing command (`processErrorStart`, `kill` on an existing process) requires explicit
  approval from the user in chat, given per command.

---

## Phase 1: Setup & Verification Spikes

**Purpose**: Project skeleton plus the remaining verification spikes from research.md (S-0, S-3,
S-4, S-5, S-6b). Their recordings become the contract-test fixtures.

- [X] T001 Create `pom.xml` at the repository root with these settings:
  - **Coordinates**: `groupId` `de.dadecker`, `artifactId` `inubit-mcp-server`, `version`
    `0.1.0-SNAPSHOT`, `maven.compiler.release` 21, `project.build.sourceEncoding` UTF-8.
  - **Dependency management**: import `io.modelcontextprotocol.sdk:mcp-bom:2.0.1`.
  - **Runtime dependencies**: `io.modelcontextprotocol.sdk:mcp`,
    `tools.jackson.dataformat:jackson-dataformat-yaml` (same Jackson 3 version the SDK resolves),
    `ch.qos.logback:logback-classic` (latest 1.5.x).
  - **Test dependencies**: `org.junit.jupiter:junit-jupiter`, `org.assertj:assertj-core`,
    `org.wiremock:wiremock:3.13.2`.
  - **Plugins**:
    - `maven-surefire-plugin` with `<excludedGroups>live</excludedGroups>`
    - `maven-shade-plugin` producing `target/inubit-mcp-server-<version>.jar` with a
      `ManifestResourceTransformer` (Main-Class `de.dadecker.inubit.mcp.Main`) and a
      **`ServicesResourceTransformer`** (mandatory, research R-3)
  - **Profile `live`**: runs only `@Tag("live")` tests.
  - Every version is pinned explicitly.
- [X] T002 [P] Create `.gitignore` (target/, *.iml, .idea/, .vscode/, *.log) and `.editorconfig`
  (UTF-8, LF, 4-space Java indentation, 100-column guideline) at the repository root
- [X] T003 [P] Create the package skeleton with an empty `package-info.java` in each of:
  `main/config`, `main/domain/model`, `main/domain/port`, `main/application`, `main/adapter/rest`,
  `main/adapter/rest/v81`, `main/adapter/cli`, `main/adapter/cli/v81`, `main/mcp`, `main/infra`.
  Each file gets a one-line Javadoc with the package's role taken from plan.md → Structure Decision.
- [X] T004 Spike S-0: verify that Claude Code connects to an SDK-2.0.1 stdio server.
  - Add a throw-away `src/spike/java/S0HelloServer.java` (not in the main source set) that
    registers one tool `ping` with `readOnlyHint=true`, an `outputSchema`, and a `structuredContent`
    result. Run it with `java -cp $(mvn -q dependency:build-classpath …)`.
  - Register it with `claude mcp add s0 -- …`, then ask the user to call `ping` in Claude Code and
    report whether the annotations and the result arrive.
  - Record the outcome in `specs/001-inubit-mcp-mvp/research.md` → Spike results as "S-0".
  - **If the connection fails**: stop and escalate to the user (research R-2).
  - Delete `src/spike/` afterwards.
  - Done (2026-10-03): Claude Code listed and called `ping` with structured result; `s0` removed, `src/spike/` deleted (see research "S-0").
  - Progress: server and `src/spike/run-s0.sh` done; local piped JSON-RPC check passed (annotations,
    `outputSchema`, `structuredContent` present; research "S-0"). Pending: `claude mcp add`, the
    user's `ping` call in Claude Code, recording the outcome, deleting `src/spike/`.
- [X] T005 Create `tools/record-fixtures.sh` (research R-18).
  - **Usage**: `tools/record-fixtures.sh <stage> <server> <case>` (`--list` prints the case
    catalogue). It refuses any stage other than `dev` or `qa`, and any case that is not in its
    built-in catalogue.
  - **Behaviour**:
    - Reads the base URL from `~/.config/inubit-mcp/config.yaml`.
    - Calls REST with curl (credentials via `-K -`, pinned key) or StartCLI (password via stdin,
      allowlisted environment).
    - Keeps the raw output only in a private temporary directory and writes the **anonymized**
      result to `src/test/resources/fixtures/v8_1/{rest|cli}/<case>.*`. For CLI cases it writes
      `<case>.stdout`, `<case>.stderr` and `<case>.exit`; REST cases get `<case>.http` (status and
      content type).
  - **Anonymization**: pipe everything through `tools/anonymize.py`, which replaces:
    - hostnames → `inubit.example.test`
    - IPs → `192.0.2.x`
    - usernames → `user1`, `user2`, …
    - free-text message bodies in log rows → `<message n>`, keeping exception class names and
      INUBIT error codes
  - Make both files executable.
- [X] T006 [P] Create `tools/anonymize.py` (Python 3, standard library only) as specified in T005,
  with a `--self-test` flag that checks the replacement rules on built-in samples
- [X] T007 Spike S-5: record logs and metrics on DEV.
  - With `tools/record-fixtures.sh dev node1`, record
    `GET /ibis/rest/log/{logName}?format=json&noOfItems=5` for every `logName` in `processLog`,
    `systemLog`, `queueLog`, `connectionLog`, `schedulerLog`, `auditLog`, `keyManagerLog` and
    `webserviceManager`. Also record one `POST /ibis/rest/log/systemLog?format=json` with a
    `<logRequest>` that uses `BETWEEN` on the date field and `LIKE` on the message field, plus the
    response of `GET /ibis/rest/metrics?format=json`.
  - Document in `specs/001-inubit-mcp-mvp/research.md` → Spike results "S-5":
    - the field names per log type
    - the accepted date format
    - the `LIKE` wildcard
    - how an unlicensed `/metrics` responds
  - Add the per-log-type filter table to `specs/001-inubit-mcp-mvp/contracts/mcp-tools.md` §4.
- [X] T008 Spike S-4: complete the `ps -csv` format on DEV or QA.
  - Ask the user for a server and time window with live process instances, or for permission to
    trigger a harmless test workflow.
  - Record `ps -csv -a`, `ps -csv -e`, `ps -csv -w`, `ps -csv -q` and
    `ps -csv -a -f 'workflow=<name>'` as fixtures `cli/ps_*`.
  - Document in research.md "S-4": the STATE values, the DATE format and time zone, what DATE means
    (state change versus start), the PID format, and the quoting of `-f` values that contain spaces.
  - Fill in the **state table** (`rawState` → `state` → `final?`) in data-model.md → ProcessInstance.
  - Update the `processId` pattern in `specs/001-inubit-mcp-mvp/contracts/mcp-tools.md` §7 and the
    state mapping table in `specs/001-inubit-mcp-mvp/data-model.md` → ProcessInstance.
  - Done (read-only, no user input needed after the queueLog finding): `cli/ps_*` fixtures plus
    `rest/log_queueLog*.json`; `ps` and `queueLog` compared 695/695. Decision: `find_processes`
    uses the REST `queueLog` (research R-8, S-4); `ps` is not implemented. `processId` =
    `workflowId` (`^[0-9]{1,19}$`).
- [X] T009 Spike S-3: record CLI outcomes on DEV.
  - **Without approval**: record `processErrorStart 999999999`, `kill 999999999`, a wrong password
    (a dummy value that is not the real one), an unreachable URL, and an unknown command. Store
    them as fixtures `cli/processErrorStart_unknown`, `cli/kill_unknown`, `cli/login_failed`,
    `cli/unreachable` and `cli/unknown_command`.
  - **Only after explicit user approval for a named process instance in ERROR on DEV**: record
    `processErrorStart <pid>` (success) and `kill <pid>` (success).
  - Document the messages and exit codes in research.md "S-3".
  - Done: the five no-approval cases are recorded (plus `processErrorStart_unknown_default_locale`)
    and documented in research "S-3". The user chose synthetic success fixtures:
    `cli/processErrorStart_ok.*` and `cli/kill_ok.*` with a `.README` "SYNTHETIC – verify in
    T115"; no state-changing command was run.
- [ ] T010 Spike S-6b: check whether module version history is complete.
  - Export one diagram group with `--includeHistory`.
  - Compare `versionHistory.xml` → `Modules/Module` against the Workbench history of 3 modules that
    the user names.
  - Determine the time zone of `DateTime` and `LastUpdate` by comparing them with a check-in time
    known from the Workbench.
  - Verify the quoting rule from research R-11: `--exportWorkflowGroup '<group>'` in single quotes is
    accepted, using a group with a space if one exists.
  - Record the result in research.md "S-6b". If incomplete, set "module version history:
    unavailable" in `specs/001-inubit-mcp-mvp/data-model.md` → InventoryDetail.
  - Record anonymized fixtures:
    - `rest/model_models_owner.xml`
    - `rest/modelByName_sample.xml`
    - `rest/model_export_sample.zip`, keeping only `workflow/workflow.xml` and
      `archive.properties`
    - `cli/export_history_sample.zip`, keeping only `versionHistory.xml`, about 3 workflows
    - `cli/export_modules_sample.zip`, keeping only `module/module.xml`, about 20 modules
  - Progress: export of `GRP-41` with `--includeHistory` done (single-quoted values
    accepted; no group with a space exists on DEV), time zone `Europe/Berlin` for `DateTime` and
    `LastUpdate` (correlated with `auditLog`), all five fixtures recorded, research "S-6b" written.
    Awaiting the user's comparison with the Workbench for `Module-9101`,
    `Module-9102` and `Module-9103`.

**Checkpoint**: The project builds (`mvn -q verify` with no tests). The spike results S-0 (local
part), S-3, S-4, S-5 and S-6b are documented; S-0 (Claude Code) and S-6b (Workbench comparison)
wait for the user. Recorded or synthetic fixtures exist for every adapter call of the MVP
(REST: health, system info, metrics, logs, queueLog, models; CLI: version, failure and synthetic
success cases, exports).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Configuration, credentials, scrubbing, logging, TLS, the CLI runner, fan-out, result
limits and the MCP harness. Every story needs these.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests for Foundational (write first, must fail)

- [X] T011 [P] Write `test/domain/model/ServerIdTest.java` and `test/domain/model/ToolErrorTest.java`
  (`excerpt` is truncated to 500 chars).
  - Valid ids: `dev/node1`, `qa/node2`.
  - Rejected: `QA/x`, `a//b`, `a/b/c`, and names longer than 32 characters.
  - Pattern under test: `^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$`.
- [X] T012 [P] Write `test/config/ConfigLoaderTest.java`.
  - Location order: `--config`, then `INUBIT_MCP_CONFIG`, then `~/.config/inubit-mcp/config.yaml`.
  - YAML parsing of stages and servers.
  - Inheritance: server → stage → `defaults` → built-in default for every field listed in
    data-model.md (`timeout` `PT5S`, `cliTimeout` `PT30S`, `cliExportTimeout` `PT120S`,
    `hangingThreshold` `PT60M`, `confirmationTtl` `PT5M`, `inventory.owner` `OWNERS`,
    `inventory.cacheTtl` `PT10M`, `write.enabled` false, `write.confirmation` `SERVER`).
  - Top-level `x-*` keys and YAML anchors are accepted. Any other unknown key is rejected.
  - Test resources live in `src/test/resources/config/`.
- [X] T013 [P] Write `test/config/CredentialResolverTest.java`.
  - Server-specific `INUBIT_<STAGE>_<SERVER>_{USERNAME|PASSWORD}` wins over stage-wide
    `INUBIT_<STAGE>_{…}`. Username and password are resolved independently.
  - Name derivation: uppercase, with non-`[A-Z0-9]` replaced by `_`.
  - Collisions are detected, e.g. stage `a-b`/server `c` versus stage `a`/server `b-c`.
  - A missing value produces an error that names the expected variable names but no values.
  - The source variable name is recorded for each value.
  - An `INUBIT_*_PASSWORD` variable that matches no configured server produces a warning.
  - Use an injectable `Map<String,String>` environment.
- [X] T014 [P] Write `test/config/ConfigValidatorTest.java`. Each row in the "Startup validation
  outcomes" table of `specs/001-inubit-mcp-mvp/contracts/configuration.md` gets one test case:
  - credential keys in the YAML → error
  - `write.confirmation: CLIENT` on a `production: true` stage → error
  - `disableHostnameVerification: true` without `pinnedCertificateSha256` → error
  - a resolved trust-store password variable for a server with CLI configured → error (research
    R-6: StartCLI cannot take it without exposing it as a process argument)
  - `http://` without `allowInsecureHttp` → error
  - missing `startcli.sh` → warning
  - `V9_X` → warning
  - the errors are collected and reported together
- [X] T015 [P] Write `test/infra/SecretScrubberTest.java`.
  - Every registered secret and its Basic-auth Base64 form `base64(user:password)` is replaced by
    `***` in arbitrary strings, also when the secret occurs repeatedly or overlaps other text.
  - `Secret.toString()` returns `***`.
- [X] T016 [P] Write `test/infra/ScrubbingJsonEncoderTest.java`. A Logback event whose message or
  exception contains a registered secret is encoded as JSON on stderr without the secret.
- [X] T017 [P] Write `test/application/TargetResolverTest.java`.
  - A stage id resolves to all its servers in config order.
  - A server id resolves to one server.
  - An unknown id gives `ENVIRONMENT_UNKNOWN`, and the message lists all ids.
  - `resolveSingleServer` rejects a stage id with `INVALID_INPUT` (Story 4 / AS 8).
- [X] T018 [P] Write `test/application/FanOutTest.java`.
  - Calls run in parallel on virtual threads, each with its own deadline.
  - A server that exceeds its deadline yields `TIMEOUT` while the others return.
  - Total wall time is at most the maximum deadline plus 1 s, measured with servers delayed by
    6 s and 0 s against a 5 s deadline (SC-002).
- [X] T019 [P] Write `test/application/ResultLimiterTest.java`.
  - Paging with `offset` and `limit`.
  - The `maxItems=100` and `maxChars=50000` limits.
  - The `truncated` flag and `nextOffset`.
  - Log messages are truncated to 2,000 chars with a marker.
- [X] T020 [P] Write `test/adapter/rest/PinningTrustManagerTest.java`.
  - Chain validation against a test trust store.
  - A leaf whose SHA-256 differs from the pin is rejected.
  - Hostname mismatch is accepted only when disabled and pinned.
  - Use self-signed test certificates generated into `src/test/resources/tls/` by the test setup
    via `keytool`, or as committed test-only files.
  - Done as: `test/adapter/rest/TestCertificates.java` runs the JDK's `keytool` once per test JVM
    and writes the material to `target/test-tls/` (build output, never committed, regenerated on
    every run) instead of `src/test/resources/tls/`.
- [X] T021 [P] Write `test/adapter/rest/InubitHttpClientTest.java` against WireMock over HTTPS.
  - Request headers: Basic auth, `Accept: application/json, application/xml;q=0.9, */*;q=0.8`.
  - Error mapping (research R-5):
    - 401 with a Tomcat HTML page → `AUTH_FAILED`, without parsing the body
    - 403 → `FORBIDDEN`
    - 404 → `NOT_FOUND`
    - 503 with the maintenance flag → `MAINTENANCE_MODE`
    - timeout → `TIMEOUT`
    - connect refused → `UNREACHABLE`
    - TLS failure → `TLS_ERROR`
  - Secrets never appear in `ToolError`.
- [X] T022 [P] Write `test/adapter/cli/CliRunnerTest.java` with `FakeProcessLauncher`.
  - The command is the argument array
    `[startcli.sh, -u, <user>, [--trustStoreFilePath, <p12>], [--disableHostNameVerification], --execCommand, <cmd>, <cliUrl>]`.
    There is never a `-p` argument.
  - `--trustStoreFilePath <p12>` is present only if `tls.trustStore` is set, and
    `--disableHostNameVerification` only if `tls.disableHostnameVerification: true`; both branches
    (present and absent) are tested.
  - The password plus `\n` is written to stdin.
  - `JAVA_HOME` is set from `cli.javaHome`.
  - `JAVA_TOOL_OPTIONS` is set to `-Duser.language=en -Duser.country=US` (research R-6), also when
    the parent environment has a different value.
  - The child environment is an **allowlist** (research R-6, R-12): it contains only `PATH`,
    `HOME`, `TMPDIR`, `LANG`, `LC_ALL`, `USER` (copied if set), `JAVA_HOME` and
    `JAVA_TOOL_OPTIONS`. A test seeds `INUBIT_*`, `*PASSWORD*` and arbitrary other variables in the
    parent environment and asserts that none of them reaches the child.
  - The working directory is `cliHome`.
  - Timeout: `destroy`, then `destroyForcibly` after 2 s.
  - stdout and stderr are bounded to 1 MB each.
  - Only allow-listed command tokens are accepted.
  - Quoting rule (research R-11): every value inserted into `--execCommand` (workflow, tag, owner,
    group) MUST match `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}$` (no leading `-`) and is wrapped in
    single quotes. A value containing `'` or failing the pattern is rejected with `INVALID_INPUT`
    before launch. The literal empty argument `''` is supported. Export paths are absolute, use
    the same characters plus `/` and contain no `.`/`..` segment.
  - Options are bound per command: `processErrorStart`/`kill` take exactly one process id and no
    option; `export` takes only the export options and no process id.
  - Windows: `CLI_UNAVAILABLE` (CLI tools not supported on Windows in this version).
  - On timeout every member of the process tree still alive after the grace period is killed,
    also when the root has already exited.
- [X] T023 [P] Write `test/adapter/cli/CliOutputClassifierTest.java` using the fixtures from T009
  and from research S-2.
  - The classifier strips the preamble lines `JAVA_HOME is set`, `Password: ` and
    `SECURITY WARNING: Hostname verification is disabled…` on stdout, and
    `Picked up JAVA_TOOL_OPTIONS: …` on stderr or stdout (research R-6).
  - It separates log lines matching `^(ERROR|WARN|INFO|DEBUG) \d{2}:\d{2}:\d{2},\d{3} \[` and their
    `\tat …` continuation lines.
  - It extracts the INUBIT messages `n-OK:` / `n-NOK:` and the markers `@Start@<code>@@@<text>@End@`.
  - It classifies **primarily by the markers, secondarily by the English texts**, per the table in
    research R-7: `LoginFailure` → `AUTH_FAILED` (`cli/login_failed`), `LoginFailed` with
    `ConnectException` → `UNREACHABLE` (`cli/unreachable`), `No process found with id` →
    `NOT_FOUND` (`cli/processErrorStart_unknown`, `cli/kill_unknown`), `Command not found.` →
    `INTERNAL` (`cli/unknown_command`), exit 0 with `1-OK:` → success (`cli/*_ok`, synthetic), and
    unknown output → `UNEXPECTED_RESPONSE` with a scrubbed excerpt of at most 500 chars.
  - `cli/processErrorStart_unknown_default_locale` (German summary lines) is still classified as
    `NOT_FOUND` through the marker.
- [X] T024 [P] Write `test/mcp/McpTestClient.java`, a test helper.
  - It starts `McpServerFactory` on `StdioServerTransportProvider(mapper, pipedIn, pipedOut)`.
  - It offers `initialize()`, `listTools()` and `callTool(name, args)` over raw JSON-RPC lines.
  - Done as: the protocol output is captured verbatim (`rawOutput()`) for the stdout-hygiene
    assertions; `close()` sends EOF and waits for the server.
- [X] T025 [P] Write `test/mcp/McpServerSmokeTest.java` using `McpTestClient`.
  - `initialize` succeeds.
  - `tools/list` is empty with a config that has no capabilities enabled yet.
  - Nothing except JSON-RPC lines is written to the protocol stdout.
  - Also covered (with the test tool `test/mcp/EchoTestTool.java` and
    `src/test/resources/schemas/echo_test.*.json`): annotations and schemas in `tools/list`,
    `structuredContent` plus identical compact JSON text, `ToolError` → `isError`, `INTERNAL` for
    unexpected exceptions without stack trace, scrubbing, SDK input validation (handler not
    called) and SDK output validation. Unit tests `test/mcp/ResultMapperTest.java` and
    `test/mcp/SchemaResourcesTest.java` were added for T048.
- [X] T026 [P] Write `test/MainTest.java`.
  - `--version` prints the version and exits 0.
  - `--check-config` prints per server `id`, read-only/write flag, `cli: available|unavailable`,
    and the source variable names. It prints no values and exits 0 or 1.
  - A missing config file gives an error listing the searched locations.
  - Also covered: `INUBIT_MCP_CONFIG`, usage errors (exit 2), refusal to start with all errors on
    stderr and nothing on the protocol stdout, server mode over piped stdio (only JSON-RPC lines,
    exit 0 on EOF), audit directory created/tightened to `rwx------`. Environment and user home
    are injected (`Main.Context`); no test reads the real `~/.config` or environment.
- [X] T027 [P] Write `test/infra/StdoutGuardTest.java`. After `StdoutGuard.install()`, `System.out`
  writes end up in stderr, and the captured original stream is returned for the MCP transport.
  `ClockProvider` returns an injectable fixed `Clock` in tests.
- [X] T028 [P] Write `test/adapter/rest/XmlSupportTest.java`. The following MUST fail safely, without
  network or file access:
  - a document with an external entity (`<!ENTITY x SYSTEM "file:///etc/passwd">`)
  - a DTD declaration
  - a billion-laughs payload

  Namespace-agnostic lookup by local name works on the `ns4:ModelList` sample.
- [X] T029 [P] Write `test/adapter/AdapterGatewayFactoryTest.java` with a fake `MonitoringPort`.
  - `versionLine: V8_1` → the V8_1 adapters.
  - `AUTO` → `/system/info` is called once, lazily and cached per server; `8.1.17` → V8_1.
  - `V9_X`, or a detected `9.0.x` → the V8_1 adapters plus the warning "unsupported" (FR-029).
  - Detection failure → the error is propagated as a `ToolError`.
  - Done as: `MonitoringPort` does not exist before US1, so the test runs the real REST client
    against WireMock (HTTPS) with the recorded fixture `rest/system_info.xml` instead of a fake
    port. The `V81MaintenanceProbe` wiring is asserted behaviourally: HTTP 503 on
    `/system/info` plus `maintenancemode: 1` on the healthcheck → `MAINTENANCE_MODE` (with
    `MaintenanceProbe.NONE` it would be `UNREACHABLE`). Also covered: concurrent calls detect
    once, failed detections are not cached, missing `Version` → `UNEXPECTED_RESPONSE`, unknown
    server → `ENVIRONMENT_UNKNOWN`. `TestServerConfig` got a `versionLine(...)` setter.
- [X] T030 [P] Write `test/adapter/cli/CliVersionProbeTest.java` with `FakeProcessLauncher` and the
  fixture `cli/version.stdout` (output of `startcli.sh -v`: `CLI 8.1.17` and
  `Supported inubit Process Engine versions from 4.0.1 to 8.1.17.`).
  - It parses `CLI <version>`.
  - It warns on a mismatch with the server version.
  - It needs no credentials and writes no stdin.

### Implementation for Foundational

- [X] T031 [P] Implement the domain base types in `main/domain/model/`:
  - `ServerId.java`, `StageId.java`, `Target.java`
  - `ErrorCode.java`, an enum exactly as listed in data-model.md → ToolError
  - `ToolError.java` (`code`, `message`, `likelyCause`, `nextStep`, `server`, `excerpt` with at most
    500 chars)
  - `Page.java` (`items`, `offset`, `limit`, `total`, `totalIsLowerBound`, `truncated`,
    `nextOffset`)
  - `ServerResult.java` (`server`, optional payload, optional `ToolError`)

  Makes T011 pass. `ToolError` truncates `excerpt` to 500 chars; cover this in T011 as well.
- [X] T032 Implement the configuration types in `main/config/`: `ServerConfig.java`,
  `Defaults.java`, `StageConfig.java`, `ServerEntryConfig.java`, `TlsConfig.java`,
  `WriteConfig.java`, `CliConfig.java`, `InventoryConfig.java` and `EffectiveServerConfig.java`
  (the fully resolved per-server view), with all fields, defaults and patterns from data-model.md →
  Configuration entities. Make T012 pass together with T033.
- [X] T033 Implement `main/config/ConfigLoader.java`.
  - Jackson 3 YAML with `FAIL_ON_UNKNOWN_PROPERTIES`, except that top-level keys starting with
    `x-` are ignored.
  - `~` expansion in paths.
  - Location order as in `specs/001-inubit-mcp-mvp/contracts/configuration.md`.
  - Makes T012 pass.
- [X] T034 [P] Implement `main/infra/Secret.java` and `main/infra/SecretScrubber.java`
  (thread-safe registry, longest-match replacement) to make T015 pass
- [X] T035 Implement `main/config/CredentialResolver.java`.
  - Injected environment map; naming convention and precedence as in data-model.md → Credentials.
  - Passwords are wrapped in `Secret` and registered with `SecretScrubber`.
  - The source variable name is recorded.
  - Makes T013 pass.
- [X] T036 Implement `main/config/ConfigValidator.java` (collects all errors and warnings) and
  `main/config/ConfigSummary.java` (output of `--check-config`) to make T014 pass
- [X] T037 [P] Implement `main/infra/ScrubbingJsonEncoder.java`, which wraps Logback's
  `JsonEncoder` and scrubs message and throwable, and `res/logback.xml`, a `ConsoleAppender` with
  `target=System.err` using `ScrubbingJsonEncoder`. The level comes from config. Makes T016 pass.
- [X] T038 [P] Implement `main/infra/StdoutGuard.java`, which captures the real `System.out` and
  then calls `System.setOut(System.err)` (research R-16), and `main/infra/ClockProvider.java`, an
  injectable `java.time.Clock`. Makes T027 pass.
- [X] T039 [P] Implement `main/application/TargetResolver.java` to make T017 pass
- [X] T040 [P] Implement `main/application/FanOut.java`. It uses
  `Executors.newVirtualThreadPerTaskExecutor()` with a deadline per server and returns
  `List<ServerResult<T>>` in config order. Makes T018 pass.
- [X] T041 [P] Implement `main/application/ResultLimiter.java` to make T019 pass
- [X] T042 Implement `main/adapter/rest/PinningTrustManager.java` (an `X509ExtendedTrustManager`
  wrapper as in research R-5a) and `main/adapter/rest/SslContexts.java`, which builds a per-server
  `SSLContext` from `tls.trustStore` (a PKCS12 store without password is allowed). Makes T020 pass.
- [X] T043 Implement `main/adapter/rest/InubitHttpClient.java`.
  - One `java.net.http.HttpClient` per server with the `SSLContext` from T042.
  - Methods `get(path, query)` and `post(path, query, body, contentType)`.
  - Basic auth, the `Accept` header and the timeout as in research R-5.
  - Error mapping to `ToolError`.
  - Makes T021 pass.
- [X] T044 [P] Implement `main/adapter/rest/XmlSupport.java`: a secure `DocumentBuilderFactory` and
  `XMLInputFactory` with external entities and DTDs disabled and `FEATURE_SECURE_PROCESSING` on,
  plus namespace-agnostic helpers by local name. Makes T028 pass.
- [X] T045 Implement the CLI process layer in `main/adapter/cli/`:
  - `ProcessLauncher.java` (interface)
  - `SystemProcessLauncher.java` (`ProcessBuilder`)
  - `CliCommand.java` (allow-listed command builder; only validated tokens)
  - `CliRunner.java` (argument array, stdin password, allowlisted environment, `JAVA_HOME`,
    timeouts, bounded buffers)
  - `CliResult.java` (`exitCode`, `stdout`, `stderr`, `duration`, `truncated`)

  Also create `test/adapter/cli/FakeProcessLauncher.java`, which replays `fixtures/cli/<case>.*`.
  Makes T022 pass.
- [X] T046 Implement `main/adapter/cli/CliOutputClassifier.java` (preamble and log stripping,
  `n-OK`/`n-NOK` extraction, error classification) to make T023 pass
- [X] T047 Implement `main/adapter/cli/CliVersionProbe.java`, which runs `startcli.sh -v` (no server,
  no credentials), parses `CLI <version>`, and warns when it differs from the server version
  detected via `/system/info` (spec edge case "CLI client version mismatch"). Makes T030 pass.
- [X] T048 Implement the MCP layer in `main/mcp/`:
  - `McpServerFactory.java`: `McpServer.sync(StdioServerTransportProvider(mapper, System.in, realOut))`
    with server info `inubit-mcp-server`/version and tools capability. It takes the list of
    `ToolHandler`s.
  - `ToolHandler.java`: an interface with `name()`, `descriptionText()`, `inputSchemaResource()`,
    `outputSchemaResource()`, `annotations()` and `handle(Map args)`.
  - `SchemaResources.java`: loads `res/schemas/<tool>.input.json` and `<tool>.output.json`.
  - `ResultMapper.java`: maps to `structuredContent` plus a compact JSON text block. A `ToolError`
    becomes `isError=true` with the `ToolError` payload. Everything goes through `SecretScrubber`.

  Makes T024 and T025 pass.

  Done as: `annotations()` returns the SDK-free record `ToolHints` (title plus the four hints), so
  handlers do not depend on SDK types. `handle` returns the payload object (record or map);
  failures are thrown as `ToolErrorException`, anything else becomes `INTERNAL` (logged, no
  stack trace in the result). An error result carries the payload as text `{"error": ToolError}`
  **without** `structuredContent`, because clients validate `structuredContent` against the
  output schema, which an error does not match. Scrubbing walks the JSON tree (all strings and
  keys) before both channels are built. The SDK's input validation is enabled explicitly; output
  validation is the SDK default for tools with an `outputSchema`.

  Review fixes (Phase 2c): the three failure kinds (tool error, schema violation rejected by the
  SDK, unknown tool → JSON-RPC `-32602`) are documented in contracts/mcp-tools.md (C2/C3). Object
  keys that scrub to the same text are kept with a suffix (`***`, `***_2`, …) in order (N6). The
  smoke test checks stray `System.out` writes against a stream captured by `StdoutGuard`, as in
  production (N2).
- [X] T049 Implement `main/Main.java`.
  - Startup order: `Locale.setDefault(Locale.ENGLISH)`, `StdoutGuard`, argument parsing
    (`--config`, `--check-config`, `--version`), `ConfigLoader`, `CredentialResolver`,
    `ConfigValidator` (exit 1 on errors, all printed to stderr), audit directory creation with
    owner-only permissions, wiring, `McpServerFactory` start.
  - Makes T026 pass.
  - Done as: wiring lives in `main/Wiring.java` (composition root; the user-story phases add
    their handlers in `toolHandlers()`, which is empty for now). `--version` reads the Maven
    version from the filtered resource `res/de/dadecker/inubit/mcp/build.properties`
    (`infra/BuildInfo.java`; `pom.xml` filters only that file). `--check-config` has no side
    effects (no audit directory). Exit codes: 0 ok, 1 configuration/startup error, 2 usage
    error. An existing audit directory is also tightened to `rwx------`.
  - Review fixes (Phase 2c): `Main` only sets the locale, installs `StdoutGuard` and delegates to
    `main/Launcher.java`, so no logger (and no Logback initialization) exists before the guard
    (C1); `test/MainProcessTest.java` starts `Main` in a child JVM with `-Dlogback.debug=true` and
    asserts JSON-RPC-only stdout. `res/logback.xml` registers `infra/StderrStatusListener.java`
    (Logback warnings/errors to stderr, no INFO noise; deviation from the suggested
    `OnErrorConsoleStatusListener`, which prints every INFO status on each start). Configuration
    warnings are printed to stderr independent of `logLevel` and logged (C7). A failure during
    wiring/start is one scrubbed line `Startup failed: …` on stderr, exit 1 (N3). Unknown options
    are echoed only up to `=`, stray values not at all (N1). Exit codes and stdout/stderr use are
    documented in contracts/configuration.md and docs/setup.md (C4).
- [X] T050 Implement `main/domain/port/GatewayFactory.java` and
  `main/adapter/AdapterGatewayFactory.java`. They select the adapter set per server by
  `versionLine`; `AUTO` is resolved lazily via `/system/info`. `V9_X` adds the warning "unsupported"
  (FR-029). For now only the V8_1 adapters are registered; V9_X falls back to them and adds the
  warning. The factory MUST create each server's `InubitHttpClient` with the version line's
  `MaintenanceProbe` (8.1: `V81MaintenanceProbe`), never `MaintenanceProbe.NONE` (Phase 2b review
  N-1); `AdapterGatewayFactoryTest` asserts this. Makes T029 pass.
  - Done as: minimal port `main/domain/port/Gateway.java` (`server()`, `adapterLine()`,
    `detectedVersion()`, `warnings()`; Javadoc names the accessors US1–US4 add), implemented by
    `main/adapter/V81Gateway.java`, which owns the server's `InubitHttpClient` and
    `V81MaintenanceProbe`. Clients are created lazily per server, so a broken server does not
    block startup. Detection: `main/adapter/rest/v81/V81VersionDetector.java` (`GET
    /ibis/rest/system/info`, `XmlSupport`, `<SystemInformation name="Version" …/>`); any version
    other than `8.1`/`8.1.x` gets the "unsupported" warning.
  - Review decision C9: a failed `AUTO` detection does **not** fail `forServer`; it returns the
    V8_1 adapters with the warning "INUBIT version could not be detected (<ErrorCode>); assuming
    8.1". That result is not cached, so the next call retries the detection. Only an unusable
    trust store or pin (`TLS_ERROR`) fails `forServer`. The blocking detection is guarded by a
    `ReentrantLock` per server (no `synchronized`, no virtual-thread pinning on JDK 21).
- [X] T051 Create `docs/setup.md` and `README.md` (Constitution: documentation in the same change).
  - `docs/setup.md`:
    - prerequisites: JDK 21+, Maven, the INUBIT 8.1.17 client, Temurin 17 for StartCLI
    - building the JAR
    - `~/.config/inubit-mcp/config.yaml` per `specs/001-inubit-mcp-mvp/contracts/configuration.md`
    - the `INUBIT_*` variable convention, with the warning against `claude mcp add -e`
    - the trust store and the pinned certificate for the ACME selfsigned cert
    - `--check-config`
  - `README.md`: purpose, the safety model (read-only default, production lock, two-step
    confirmation, audit), and links to `docs/setup.md`, the spec and the constitution.

**Checkpoint**: `mvn verify` is green. `java -jar target/inubit-mcp-server-*.jar --check-config`
works against `~/.config/inubit-mcp/config.yaml`. The MCP server starts and lists 0 tools.

---

## Phase 3: User Story 1 - Check environment health (Priority: P1) 🎯 MVP

**Goal**: Report reachability, readiness, maintenance mode, version and load per server, per
stage, or for all servers (FR-003, FR-005 – FR-008).

**Independent Test**: With DEV, QA and an unreachable `dev/bogus` configured, `list_servers` and
`get_health` (all servers, stage `qa`, server `dev/node1`) return correct reports. The bogus
server is reported as `UNREACHABLE` within timeout + 1 s (quickstart V1–V3).

### Tests for User Story 1 (write first, must fail)

- [X] T052 [P] [US1] Check the recorded fixtures `rest/healthcheck.json`, `rest/ready.json`,
  `rest/system_info.xml` and `rest/metrics.json` (recorded in Phase 1 with
  `tools/record-fixtures.sh dev node1 <case>`). Hand-write `healthcheck_maintenance.json`
  (`"maintenancemode":1`), `ready_not_ready.json` (HTTP 503) and `metrics_unlicensed.json` +
  `.http` (HTTP 403, non-JSON body) according to the documented shapes, each with a sibling
  `.README` "SYNTHETIC" (an unlicensed `/metrics` could not be observed, research R-10).
- [X] T053 [P] [US1] Write `test/adapter/rest/v81/V81MonitoringAdapterTest.java` (WireMock plus the
  T052 fixtures). It checks parsing of:
  - `status`, `maintenancemode` 0/1 and `timestamp` in Java `Date.toString()` format
    `EEE MMM dd HH:mm:ss zzz yyyy` (English)
  - `/ready` 200 and 503
  - `SystemInformationList` into `SystemInfo`, including `version` = `8.1.17`, plus `raw`
  - `/metrics` JSON into `LoadFigures` with the derived percentages
  - unlicensed metrics (`metrics_unlicensed.*`, and any other `/metrics` failure except 401, 503,
    timeout and unreachable) → `unavailable {load, METRICS_NOT_LICENSED}` with the HTTP status in
    the reason (research R-10)
- [X] T054 [P] [US1] Write `test/application/HealthServiceTest.java` with a fake `MonitoringPort`.
  The four calls per server run concurrently, and the report is assembled from them.
  - `reachable` is decided by `/healthcheck`.
  - A failed part goes into `unavailable` with its reason, and the rest is still reported (FR-008).
  - Maintenance mode is stated.
  - An unreachable server gets a report with `reachable=false` and `error` set.
  - Version line ≠ 8.1 adds a warning.
  - `includeSystemInfo` toggles `systemInfo`.
  - Health is reported even when `/system/info` is unavailable: the version becomes an
    `unavailable` part, and the gateway's warnings (e.g. "INUBIT version could not be detected
    (…); assuming 8.1", Phase 2c review C9) are copied into `warnings`.
- [X] T055 [P] [US1] Write `test/mcp/ListServersToolTest.java` (`McpTestClient`).
  - The tool `list_servers` has the annotations readOnly, idempotent, closedWorld.
  - Output `{stages:[{name, production, servers: ServerSummary[]}]}` has `writeEnabled` computed as
    `write.enabled && (!production || productionOptIn)` and contains no URL, username or
    credential.
  - *Done (Phase 3):* T055/T056 live in `test/mcp/tools/` next to the handlers they test.
- [X] T056 [P] [US1] Write `test/mcp/GetHealthToolTest.java` (`McpTestClient` plus WireMock).
  - The tool `get_health` is annotated readOnly, idempotent, openWorld.
  - Input validation: an unknown property → `isError`; a bad `target` pattern → `isError`.
  - Omitting `target` reports all servers. `qa` reports 2 reports. An unknown target →
    `ENVIRONMENT_UNKNOWN` listing the ids.
  - `structuredContent` validates against `get_health.output.json`.
  - *Done (Phase 3):* `get_health.input.json` adds the `target` pattern of the common input
    conventions (needed for the "bad target pattern" check); contracts/mcp-tools.md §2 was
    updated to match.

### Implementation for User Story 1

- [X] T057 [P] [US1] Create the domain records `main/domain/model/HealthReport.java`,
  `LoadFigures.java`, `SystemInfo.java`, `UnavailablePart.java` and `ServerSummary.java` with the
  fields listed in data-model.md
- [X] T058 [P] [US1] Create `main/domain/port/MonitoringPort.java` with `healthcheck()`, `ready()`,
  `systemInfo()` and `metrics()`, each returning a result or a `ToolError`
- [X] T059 [US1] Implement `main/adapter/rest/v81/V81MonitoringAdapter.java` and the parsers
  `main/adapter/rest/v81/HealthcheckParser.java`, `SystemInfoXmlParser.java` and
  `MetricsJsonParser.java` to make T053 pass
  - *Done (Phase 3):* `/healthcheck` and `/ready` are sent without credentials
    (`InubitHttpClient.getWithoutCredentials`), so a wrong password never counts as a failed
    login there. `V81VersionDetector` now reuses `SystemInfoXmlParser`. `/metrics` accepts every
    non-2xx status except 401/503 and reports it as `NotLicensed` with the status; a
    TLS failure keeps its own code.
- [X] T060 [US1] Implement `main/application/HealthService.java` (uses `FanOut` and
  `GatewayFactory`) to make T054 pass
  - *Done (Phase 3):* `MonitoringPort.metrics()` returns a sealed `Metrics`
    (`Available` | `NotLicensed`) instead of throwing for the license case. Deadlines: parts get
    timeout + 250 ms (then `TIMEOUT` in `unavailable`), the fan-out timeout + 900 ms, so a
    hanging server or a hanging version detection still yields a report within timeout + 1 s.
    `checkedAt` = INUBIT healthcheck timestamp, else the MCP server clock.
- [X] T061 [P] [US1] Create the schemas `res/schemas/list_servers.input.json`,
  `list_servers.output.json`, `get_health.input.json` and `get_health.output.json`, exactly as in
  `specs/001-inubit-mcp-mvp/contracts/mcp-tools.md` §1–2, with `additionalProperties: false`
- [X] T062 [US1] Implement `main/mcp/tools/ListServersTool.java` and
  `main/mcp/tools/GetHealthTool.java` with the descriptions taken verbatim from mcp-tools.md §1–2.
  Register them in `Main.java` wiring. Makes T055 and T056 pass.
  - *Done (Phase 3):* registered in `Wiring.toolHandlers()` (composition root; `Main` delegates to
    `Launcher`/`Wiring`). `Wiring` now takes `exists`/`windows` for `cliAvailable`
    (`EffectiveServerConfig.summary(…)`).
- [X] T063 [US1] Add a live test `test/live/HealthLiveTest.java` (`@Tag("live")`). It reads
  `INUBIT_LIVE_SERVER` (e.g. `dev/node1`), aborts if that server's stage is `production: true`,
  calls `get_health` through `McpTestClient` against the real config, and asserts `reachable`,
  `ready` and `version` starting with `8.1`.
  - *Done (Phase 3):* runs the production `Wiring` (test helper `TestWiring`) from the real
    config. Run against `dev/node1`: passed (8.1.17).
- [X] T064 [US1] Create `docs/tools.md` with the sections `list_servers` and `get_health`: purpose,
  inputs (from `res/schemas/*.input.json`), output fields, and example prompts from quickstart V1–V3.
  Create `docs/live-tests.md` covering `INUBIT_LIVE_SERVER=dev/node1 mvn verify -Plive` and the
  production refusal. Add the Claude Code registration to `docs/setup.md`, including the hint to
  restart Claude Code after changing `INUBIT_*` variables.

**Phase 3 review fixes (done, TDD):**

- H1: `get_health` never waits for the `AUTO` version detection. `GatewayFactory.monitoring()`
  gives the 8.1 monitoring port at once, `/healthcheck` and `/ready` start immediately, and the
  health call's `/system/info` completes the detection (`knownGateway()` supplies the warnings
  without blocking). TIMEOUT messages name the part ("… to the healthcheck within 2 s").
- M1: concurrent `tools/call` responses were lost because SDK 2.0.1's stdio transport emits
  into a non-serialized Reactor sink (`FAIL_NON_SERIALIZED` → "Failed to enqueue message");
  `mcp/SerializingTransportProvider` serializes outbound sends. An interrupted detection is
  not cached as a 60 s fallback.
- M2: per-server `adapter/CredentialGuard` shared by REST and CLI: at most one failed login per
  server per 60 s (cached `AUTH_FAILED` afterwards; one in-flight attempt while unconfirmed;
  `/metrics` after `/system/info` while unconfirmed).
- m1: `UnavailablePart` carries `likelyCause`/`nextStep`. m2: `checkedAt` documented. m3: hang
  bounds are timeout + 1 s, end-to-end tests with the real adapters. m4: `ResultMapper` no
  longer scrubs keys; a password shorter than 4 characters is a configuration error.
- NITs: human durations (`Durations.human`) in all messages; `raw` name collisions get a
  `~n` suffix; raw bounds documented; `HealthLiveTest` uses the production `Wiring` via
  `TestWiring`; `target` patterns aligned in contracts §3–6.

**Checkpoint**: US1 is complete. Quickstart V1–V3 pass against DEV and QA. This is a shippable MVP.

---

## Phase 4: User Story 2 - Diagnose failed and hanging processes (Priority: P2)

**Goal**: Find process instances by state, workflow, tag and time range, mark the hanging ones, and
query the INUBIT logs with filters and paging (FR-009 – FR-013).

**Independent Test**: On DEV or QA, `find_processes(states=[ERROR], since=PT24H)` and
`query_logs(logType=systemLog, processId=…)` return instances and log rows matching the Workbench,
with paging and totals (quickstart V4–V6).

### Tests for User Story 2 (write first, must fail)

- [X] T065 [P] [US2] Write `test/adapter/rest/v81/QueueLogRowParserTest.java` (fixtures
  `rest/log_queueLog.json` and `rest/log_queueLog_error.json` from T008; research R-8, S-4).
  - A row maps to `ProcessInstance`: `processId` = `workflowId`, `globalProcessId` = `globalPId`
    (number **or** UUID string), `owner`, `priority`, `node`, `workflow` = `workflowName`,
    `module` = `moduleName`, `moduleType`, `tag`, `since` = `startTime` (epoch ms).
  - `status` `{level, content}` → `rawState` = `content`, `state` per the state table in
    data-model.md → ProcessInstance (`Error` → `ERROR`, `Waiting`/`Retry` → `WAITING`,
    `Queued` → `QUEUED`, `Processing` → `ACTIVE`, anything else → `OTHER`).
  - Several rows with the same `processId` (sub-workflows) stay separate rows.
  - An empty result (`total: 0`, no `row` key) gives an empty page.
  - Size bound (data-model.md → LogEntry / `Page<T>`): every mapped item's serialized size is at
    most `ResultLimiter.MAX_ITEM_CHARS`; a row with oversized text fields (e.g. a 10,000-char
    `tag` or `moduleName`) is cut (text first, with the truncation marker), never rejected.
  - *Done (Phase 4):* all text fields (also `rawState`, `globalProcessId`) are cut to 200 chars
    (`ItemBounds.MAX_FIELD_CHARS`); blank values are absent; a row without `workflowId` or
    `startTime` is `UNEXPECTED_RESPONSE`, a missing `status` is `OTHER`. Item bounds live in
    `domain/model/ItemBounds` (the adapter must not depend on `application`).
- [X] T066 [P] [US2] Write `test/adapter/rest/v81/V81ProcessQueryAdapterTest.java` (WireMock plus
  the T008 fixtures). The request is `POST /ibis/rest/log/queueLog?format=json` with a
  `<logRequest>` (built by `LogRequestBuilder`, T067):
  - one request per raw state value (`status EQUAL Error|Waiting|Retry|Queued|Processing`); no
    `states` and no `hangingOnly` → no status filter
  - paging rule (research R-9): a single request has `startIndex` = offset, `noOfItems` = limit
    and the response `total`; merged requests each have `startIndex` = 0, `noOfItems` = offset +
    limit, are sorted by `since` descending, sliced to `[offset, offset + limit)` and report
    `total` = sum of the totals; `offset + limit > 10000` → `INVALID_INPUT` without a request
  - `workflow` / `tag` → `workflowName` / `tag` `EQUAL`, XML-escaped (values with spaces are
    allowed)
  - `since`/`until` → `startTime` `BETWEEN` (or `GREATER`/`LESSER`) in epoch ms
  - `hangingOnly` → only the raw states `Waiting`, `Retry`, `Queued`, `Processing`, intersected
    with `states`, plus `startTime LESSER now − threshold`; `states=[ERROR]` + `hangingOnly` →
    empty page and no request
  - sorting `startTime DESCENDING`
  - `findByProcessId(pid)` (used by US4) → `workflowId EQUAL <pid>`
  - HTTP 400/500 HTML responses → `UNEXPECTED_RESPONSE` with a scrubbed excerpt
  - *Done (Phase 4):* merged requests run in parallel (the credential guard serializes them while
    unconfirmed). `since`/`until` are inclusive (`GREATER since−1 ms`, `LESSER until+1 ms`);
    `hangingOnly` lowers the upper bound to `now − threshold` (strict), so `until` and the
    hanging bound are one filter. `findByProcessId(pid, now, threshold)` reads up to 100 rows.
- [X] T067 [P] [US2] Write `test/adapter/rest/v81/LogRequestBuilderTest.java` (compare with
  `rest/log_systemLog_filtered.request.xml`). The XML `<logRequest>` must contain:
  - `noOfItems` is always sent (research R-9: without it INUBIT returns every match); the values
    of `startIndex` and `noOfItems` follow the R-9 paging rule below
  - `BETWEEN` (`min`/`max`) on the log type's time field in **epoch milliseconds**, or
    `GREATER`/`LESSER` when only `since` or `until` is given
  - `LIKE` for `text` on the log type's text field (mcp-tools.md §4), passed through unchanged:
    `%` and `_` stay wildcards, nothing is escaped except XML
  - `EQUAL` for workflow and status; `processId` → `workflowId` for digits, `globalPId` otherwise
  - `severity` → the success/status values of the severity table in data-model.md → LogEntry,
    one request per raw value
  - the paging rule of research R-9: single request `startIndex` = offset, `noOfItems` = limit;
    merged requests (e.g. `severity: [WARN]` on queueLog = `Waiting` + `Retry`) each
    `startIndex` = 0, `noOfItems` = offset + limit, merged, sorted by time descending, sliced to
    `[offset, offset + limit)`, `total` = sum; `offset + limit > 10000` → `INVALID_INPUT`
  - sorting by the log type's time field `DESCENDING`

  XML-escape all values, and reject filters that the per-log-type filter table (mcp-tools.md §4)
  does not allow for a log type with `INVALID_INPUT`. `processLog` is not a `LogType`.
  - *Done (Phase 4):* `LogFilter` (sealed: Equal/Like/Between/Greater/Lesser), `LogRequestPlan`
    (requests + paging-rule `combine`) and `LogRequestBuilder.validate/plan/xml`; the XML matches
    the recorded `*.request.xml` byte for byte (after `strip`). Filter order: time, workflow,
    processId, text, then the status/success value.
- [X] T068 [P] [US2] Write `test/adapter/rest/v81/V81LogAdapterTest.java` (WireMock plus fixtures
  `rest/log_*` from T007).
  - Response `{<logName>:{total,success,count,row[]}}` → `Page<LogEntry>` with `total`; a response
    without `row` (`total: 0`) → an empty page.
  - Value-shape variants (research S-5) are all accepted: `success` `true` or
    `{"level":0,"content":false}`; queueLog `status` `{level, content}`; `globalPId` number or
    UUID string; keyManagerLog `validity` number or `{level, content}`; webserviceManager
    `webserviceStatus` boolean or string (e.g. `"workflowNotActive"`); `nextStartTime` number or
    `""`.
  - Size bound (data-model.md → LogEntry): each `fields` value is cut to 200 chars with the
    truncation marker, and every mapped `LogEntry` serializes to at most
    `ResultLimiter.MAX_ITEM_CHARS` chars (`message` cut first, then `fields` values, then trailing
    `fields` entries dropped, then `message` cut further until the item fits; any cut sets
    `Page.truncated`), tested with a synthetic row carrying a 10,000-char message and
    many long extra columns.
  - Severity normalization with `rawSeverity` per the severity table in data-model.md → LogEntry
    (`OTHER` for log types without a success/status field).
  - Messages truncated to 2,000 chars.
  - Remaining columns go into `fields`.
  - *Done (Phase 4):* empty column values are dropped from `fields`; `index` is never a field;
    `endTime`/`nextStartTime` columns are ISO-8601. A single `row` object (instead of an array)
    is accepted; `success:false` or a body without `{<logName>:{total}}` is
    `UNEXPECTED_RESPONSE`. Sizes are measured with `infra/ResultJson` (the result mapper's JSON).
- [X] T069 [P] [US2] Write `test/application/DiagnosisServiceTest.java` with fake ports and a fixed
  `Clock`.
  - Time-range filtering (`since`/`until` as ISO-8601 or durations like `PT24H`).
  - `hanging` = state ∈ `NON_FINAL` (state table in data-model.md → ProcessInstance; `ERROR` is
    never hanging) and `timeInState` exceeds the threshold. The threshold comes from
    the request, else the server config, else `PT60M`.
  - `hangingOnly`, including `hangingOnly ∩ states` (`states=[ERROR]` + `hangingOnly` → empty
    page).
  - Sorting by `since` descending.
  - Paging through `ResultLimiter`.
  - Stage fan-out with a per-server `error`.
  - `find_processes` works without `cliHome` (REST only).
  - *Done (Phase 4):* the service recomputes `timeInState`/`hanging` for every instance with its
    clock (`ProcessInstance.assessedAt`), validates time, paging, `processId` and the log filters
    of every resolved server (`LogPort.validate`) before the fan-out, and bounds each page with
    `share(n)`. Per-server deadline 2 × timeout + 1 s (first request alone while unconfirmed).
- [X] T070 [P] [US2] Write `test/mcp/FindProcessesToolTest.java` and
  `test/mcp/QueryLogsToolTest.java` (`McpTestClient`).
  - Schemas as in mcp-tools.md §3–4, including `limit` in 1–100 and `logType` as an enum of the 7
    log names (no `processLog`; a call with `processLog` → `isError` from schema validation).
  - The `query_logs` description states that `text` is case-sensitive and that `%`/`_` are
    wildcards.
  - Annotations readOnly, idempotent, openWorld.
  - Output `{results:[{server, page?, error?}]}`.
  - *Done (Phase 4):* in `test/mcp/tools/` (like US1) with the real adapters on HTTPS WireMock
    (`DiagnosisToolFixture`). The schema-violation cases already passed at the red step because
    the SDK validates against the schema resources of T076.

### Implementation for User Story 2

- [X] T071 [P] [US2] Create `main/domain/model/ProcessInstance.java`, `ProcessState.java`,
  `LogEntry.java`, `LogType.java`, `Severity.java`, `ProcessQuery.java` and `LogQuery.java` with the
  fields and enums from data-model.md (`ProcessInstance` with `globalProcessId`, `owner`,
  `moduleType`; `LogType` without `processLog`)
- [X] T072 [P] [US2] Create `main/domain/port/ProcessQueryPort.java` (`find(ProcessQuery)`,
  `findByProcessId(pid)` for US4) and `main/domain/port/LogPort.java`
  - *Done (Phase 4):* `LogPort.validate(query)` added (filter check without network, FR-028);
    `findByProcessId` takes `now` and the threshold. `Gateway.processes()/logs()` and
    `GatewayFactory.processes(server)/logs(server)`: the ports are handed out without waiting for a
    version detection (same rule as `monitoring`, Phase 3 review H1).
- [X] T073 [US2] Implement `main/adapter/rest/v81/LogRequestBuilder.java`,
  `main/adapter/rest/v81/LogFilterTable.java` and `main/adapter/rest/v81/V81LogAdapter.java` to make
  T067 and T068 pass
- [X] T074 [US2] Implement `main/adapter/rest/v81/QueueLogRowParser.java` and
  `main/adapter/rest/v81/V81ProcessQueryAdapter.java` (REST `queueLog`, research R-8; reuses
  `LogRequestBuilder` from T073) to make T065 and T066 pass
- [X] T075 [US2] Implement `main/application/DiagnosisService.java` to make T069 pass
- [X] T076 [P] [US2] Create `res/schemas/find_processes.input.json`, `find_processes.output.json`,
  `query_logs.input.json` and `query_logs.output.json` per mcp-tools.md §3–4
  - *Done (Phase 4):* input schemas verbatim from the contract; output schemas
    `{results:[{server, page?, error?}]}` with `Page`, `ProcessInstance`/`LogEntry` and
    `ToolError` from data-model.md.
- [X] T077 [US2] Implement `main/mcp/tools/FindProcessesTool.java` and
  `main/mcp/tools/QueryLogsTool.java` (descriptions verbatim from mcp-tools.md) and register them.
  Makes T070 pass.
  - *Done (Phase 4):* registered in `Wiring.toolHandlers()`; `ResultJson` serializes `LogType` as
    its log name.
- [X] T078 [US2] Add a live test `test/live/DiagnosisLiveTest.java` (`@Tag("live")`, read-only,
  refuses production) covering `find_processes(states=[ERROR], since=PT24H)` and
  `query_logs(logType=systemLog, limit=5)`
  - *Done (Phase 4):* shared `live/LiveTarget` (config, credentials, production refusal);
    `HealthLiveTest` uses it too. Run against `dev/node1`: passed (counts only, see
    docs/live-tests.md).
- [X] T079 [US2] Extend `docs/tools.md` with `find_processes` and `query_logs`: inputs, the
  per-log-type filter table from mcp-tools.md §4, the `text` semantics (case-sensitive substring,
  `%`/`_` as wildcards), the derived severity (INUBIT has no severity field; table in data-model.md
  → LogEntry), why `processLog` is missing (defective on 8.1.17, use `systemLog`), the state table
  and the hanging definition and threshold, and example prompts from quickstart V4–V6.
  - *Done (Phase 4):* also documents the first-call trade-off of `get_health` (re-review N2) and
    the concrete credential variables of `AUTH_FAILED` (N5).

**Phase 3 re-review fixes (done in Phase 4, TDD):**

- N1: `CliRunner`'s unguarded `run(server, user, password, command, timeout)` is private
  (`runUnguarded`); the only public `run` takes the server's `CredentialGuard` (tests: a paused
  guard refuses without starting StartCLI; no non-private `run` without a guard). The
  package-private `execute` stays for `startcli -v`, which does not log in.
- N2: `docs/tools.md` documents the first-call trade-off; a `/metrics` TIMEOUT after a serialized
  `/system/info` names the remaining budget ("within the remaining 400 ms of the 2 s budget …") or
  says that the call did not start.
- N3: `SerializingTransportProvider` queues the sends: the next send is subscribed only after the
  previous one completed, so a send subscribed before the transport is ready (emitted later on the
  ready thread) cannot overlap with a later one; cancelling a send disposes its inner subscription
  or skips it (tests with an SDK-like fake transport).
- N5: a direct HTTP 401 names the concrete variables of the server like the cached message
  (`CredentialGuard.fixCredentials`).

**Phase 4 review fixes (done, TDD):**

- D1: item bounds are measured after JSON escaping: `QueueLogRowParser` halves (finally drops) the
  longest optional text, then `rawState`/`processId`; `LogEntryMapper` after fields and message
  also shortens `workflow`/`module`/`processId`/`rawSeverity`; never rejected (tests with U+0001,
  quotes, backslashes in every text field).
- D2: `ToolArguments` rejects integers outside the `int` range (or not integral) with
  `INVALID_INPUT`; `offset` has `maximum: 9999` in both schemas and in contracts/mcp-tools.md.
- D3: `since`/`until` that do not fit epoch milliseconds (`+999999999-12-31T23:59:59Z`,
  `P106751991167300D`) are `INVALID_INPUT` before any request; `LogFilter.timeRange` uses exact
  arithmetic.
- D4: `hanging` is decided on the untruncated duration (`ProcessInstance.hanging(state, since,
  now, threshold)`).
- D5: characters that XML 1.0 does not allow are `INVALID_INPUT` (`LogRequestBuilder
  .checkSendable`), checked before the fan-out via `ProcessQueryPort.validate` / `LogPort.validate`.
- D6: `findByProcessId` returns `ProcessRows(rows, total)` with `truncated()`; the cap
  `ProcessQueryPort.MAX_ROWS_PER_PROCESS` = 100 is documented in the port.
- D7: adapter tests: a merged plan answered with 401 makes exactly one authenticated request
  (passed at once: the guard already serializes unconfirmed attempts).
- D8: server-independent validation errors carry no server id.
- D9: docs/tools.md examples include the required `target`.

**Checkpoint**: US1 and US2 work independently. Quickstart V4–V6 pass.

---

## Phase 5: User Story 3 - Inspect inventory of diagrams and modules (Priority: P3)

**Goal**: List diagrams and modules of the owner `OWNERS` and show details with version history, tags,
active flag and modules used. Data is cached per server (FR-014 – FR-017, research R-11).

**Independent Test**: On DEV, `list_inventory(kind=DIAGRAM, type=technical, group=GRP-35)`,
`list_inventory(kind=MODULE, type='XSLT Converter')` and `get_inventory_item` for one workflow on
stage `qa` match the Workbench. A repeated module listing comes from the cache and states
`collectedAt` (quickstart V7, V7a, V8).

### Tests for User Story 3 (write first, must fail)

- [X] T080 [P] [US3] Write `test/adapter/rest/v81/ModelListParserTest.java` (fixture
  `rest/model_models_owner.xml`). `ns4:Model` elements → `InventoryItem` with `name`, `type`,
  `group` and `owner`, namespace-agnostic. An empty `ModelList` gives an empty list.
- [X] T081 [P] [US3] Write `test/adapter/rest/v81/ModelDetailParserTest.java` (fixture
  `rest/modelByName_sample.xml`). `Node` → `{name, type, nodeId}`, and the `version` attribute is
  read.
- [X] T082 [P] [US3] Write `test/adapter/rest/v81/DiagramExportParserTest.java` (fixture
  `rest/model_export_sample.zip`).
  - The ZIP is parsed **in memory** and nothing is written to disk.
  - Only `workflow/workflow.xml` is read, for `IsActive`, `CheckinComment` and
    `UserOrUserGroupName`.
  - Every other entry is ignored.
  - The download has no `Content-Type` header (S-6b); the ZIP is recognised by its `PK` signature.
- [X] T083 [P] [US3] Write `test/adapter/cli/v81/VersionHistoryParserTest.java` (fixture
  `cli/export_history_sample.zip` → `versionHistory.xml`).
  - Per workflow, `Version{versionNode, CheckinUser, CheckinComment, DateTime, UserComment, Tags/Tag*}`.
  - `DateTime` uses `dd.MM.yyyy HH:mm:ss`, local time `Europe/Berlin` without offset (S-6b).
  - `Tags/Tag*` are the current tag assignment (S-6b).
  - Newest first.
  - Modules section as decided in S-6b.
- [X] T084 [P] [US3] Write `test/adapter/cli/v81/ModuleIndexParserTest.java` (fixture
  `cli/export_modules_sample.zip` → `module/module.xml`). `ModuleGroup/ModuleGroupName` becomes
  `group` (the plugin type). Each `Module` maps `ModuleName`, `PluginName`, `LastUpdate`
  (`dd.MM.yyyy HH:mm:ss`, `Europe/Berlin`; the last content change, not the last check-in, S-6b),
  `IsActive`, `WorkflowName`, `CheckinComment`, `UserComment`,
  `IsInputConnector`, `IsOutputConnector` and `IsScheduled`.
- [X] T085 [P] [US3] Write `test/adapter/cli/CliExportRunnerTest.java` (`FakeProcessLauncher`).
  - Diagram history: `export --exportWorkflowUser <owner> --exportWorkflowType <type> --exportWorkflowGroup <group> --includeHistory --exportFile <tmp>/history.zip`.
  - Modules: `export --exportModule '' --exportModuleGroup '' --exportModuleUser <owner> --exportFile <tmp>/modules.zip`.
    The literal `''` must be present inside the single `--execCommand` argument (research R-11).
  - The temp directory is created owner-only (`rwx------`) and deleted in `finally`, also on
    timeout or error.
  - The timeout is `cliExportTimeout`.
  - Only the allow-listed entries `versionHistory.xml`, `module/module.xml` and
    `workflow/workflow.xml` are read.
  - The success message `1-OK: … exported successfully.` is required.
  - Group and owner values follow the quoting rule from R-11: allowlist plus single quotes. A group
    containing `'` is rejected before launch.
- [X] T086 [P] [US3] Write `test/application/InventoryCacheTest.java` with a fixed `Clock`.
  - The key is `(server, kind, scope)`.
  - Entries live for `inventory.cacheTtl` (default `PT10M`).
  - Single flight: concurrent loads of the same key trigger exactly one load.
  - `refresh=true` replaces the entry.
  - `collectedAt` is exposed.
  - A failed load is not cached.
- [X] T087 [P] [US3] Write `test/application/InventoryServiceTest.java` with fake ports.
  - Filters: `nameContains` (case-insensitive), `type`, `group`.
  - Sorting by name; paging.
  - Detail assembly: list entry + `modelByName` + export metadata + version history of the
    diagram's group.
  - Not found → `NOT_FOUND` with up to 5 `similarNames` by Levenshtein distance.
  - No `activeVersion` field.
  - The stage target gives per-server results in an identical structure (FR-017).
- [X] T088 [P] [US3] Write `test/mcp/ListInventoryToolTest.java` and
  `test/mcp/GetInventoryItemToolTest.java` (`McpTestClient`).
  - Schemas as in mcp-tools.md §5–6, including `refresh` and `kind` as an enum `DIAGRAM|MODULE`.
  - Annotations readOnly, idempotent, openWorld.
  - Output includes `collectedAt`.
- [X] T089 [P] [US3] Write `test/adapter/V81InventoryAdapterTest.java`, the contract test for the
  combined adapter: WireMock (`rest/model_models_owner.xml`, `rest/modelByName_sample.xml`,
  `rest/model_export_sample.zip`) plus `FakeProcessLauncher` (`cli/export_history_sample.zip`,
  `cli/export_modules_sample.zip`).
  - `listDiagrams` calls `/model/models?user=OWNERS`.
  - `diagramDetail` merges `modelByName` with the export metadata.
  - `versionHistory` calls the CLI export with `--exportWorkflowGroup '<group>'`, quoted.
  - A group failing the allowlist → `UNEXPECTED_RESPONSE`, and the CLI is not launched.
  - `listModules` calls the module export.
  - A REST or CLI failure is mapped to `ToolError`.
  - Done (Phase 5): the port keeps `diagramDetail` (nodes) and `diagramMetadata` (export) separate
    as in T091; `InventoryService` merges them (T087). The quoting check lives in
    `CliExportRunner` and reaches the adapter unchanged.

### Implementation for User Story 3

- [X] T090 [P] [US3] Create `main/domain/model/InventoryItem.java`, `InventoryKind.java`,
  `InventoryDetail.java`, `VersionEntry.java`, `ModuleRef.java` and `ConnectorFlags.java` per
  data-model.md
- [X] T091 [P] [US3] Create `main/domain/port/InventoryPort.java` with `listDiagrams(owner)`,
  `diagramDetail(owner, name)`, `diagramMetadata(name)`, `versionHistory(owner, type, group)` and
  `listModules(owner)`
- [X] T092 [US3] Implement `main/adapter/rest/v81/ModelListParser.java`, `ModelDetailParser.java`
  and `DiagramExportParser.java` to make T080, T081 and T082 pass
- [X] T093 [US3] Implement `main/adapter/cli/CliExportRunner.java`,
  `main/adapter/cli/v81/VersionHistoryParser.java` and `main/adapter/cli/v81/ModuleIndexParser.java`
  to make T083, T084 and T085 pass
- [X] T094 [US3] Implement `main/adapter/V81InventoryAdapter.java`, which combines the REST and CLI
  sources per research R-11 and implements `InventoryPort`. Makes T089 pass.
- [X] T095 [US3] Implement `main/application/InventoryCache.java` and
  `main/application/InventoryService.java` to make T086 and T087 pass
- [X] T096 [P] [US3] Create `res/schemas/list_inventory.input.json`, `list_inventory.output.json`,
  `get_inventory_item.input.json` and `get_inventory_item.output.json` per mcp-tools.md §5–6
- [X] T097 [US3] Implement `main/mcp/tools/ListInventoryTool.java` and
  `main/mcp/tools/GetInventoryItemTool.java` (descriptions verbatim from mcp-tools.md) and register
  them. Makes T088 pass.
- [X] T098 [US3] Add a live test `test/live/InventoryLiveTest.java` (`@Tag("live")`, read-only).
  - Diagram list on `dev/node1` has ≥ 1 `technical` entry in a group starting with `OWNERS`.
  - Module list (≈ 10–15 s) is non-empty.
  - A second call is cached, with the same `collectedAt`.
  - Done (Phase 5): also one `get_inventory_item` of the first diagram of `GRP-41`
    (versions present, nothing unavailable). Measured on DEV 2026-10-03: diagram list 22 entries
    of the group in 0.4 s; module list 1,630 modules in 8.7 s, cached repeat 2 ms; detail with 6
    versions and 3 modules in 4.7 s.
  - Review fixes (Phase 5 review I1–I9): the group is `INUBIT_LIVE_GROUP` or the group of the
    first technical diagram (QA has no `GRP-41`: 0 of 297 technical diagrams of `OWNERS` on
    `qa/node1`). QA run 2026-10-03: 297 technical diagrams in 0.3 s; 1,289 modules in 8.1 s,
    cached repeat 1 ms; detail with 3 versions and 22 modules in 4.5 s. Running exports are
    stopped and their directories deleted on stdin EOF/SIGTERM (`CliResources`, JVM shutdown
    hook), stale ones swept at startup; a REST login precedes exports while the credentials are
    unconfirmed; fan-out deadlines include 2 × kill grace per export.
- [X] T099 [US3] Extend `docs/tools.md` with `list_inventory` and `get_inventory_item`: owner `OWNERS`,
  the meaning of diagram group versus module group (plugin type), caching with `collectedAt` and
  `refresh`, the expected duration of the first module listing (≈ 10–15 s), and why there is no
  "active version". Document `inventory.owner`, `inventory.cacheTtl` and `cliExportTimeout` in
  `docs/setup.md`.

**Checkpoint**: US1–US3 work independently. Quickstart V7, V7a and V8 pass.

---

## Phase 6: User Story 4 - Restart or kill a process instance (Priority: P4)

**Goal**: Restart a single process instance in ERROR, or kill one, on a single server. It is only
possible with write access, has a production lock, uses server-side two-step confirmation by
default, and every step is audited (FR-018 – FR-024).

**Independent Test**:
- With the default config, `restart_process` is not offered, and a direct call is refused and
  audited.
- With write enabled on stage `dev`, a restart of an ERROR instance runs through preview and code
  and ends `EXECUTED`, with two audit records.
- `prod` without opt-in is refused (quickstart V9–V13).

### Tests for User Story 4 (write first, must fail)

- [X] T100 [P] [US4] Write `test/infra/AuditLogTest.java`.
  - JSON Lines in `<auditDirectory>/audit-YYYY-MM.jsonl`, file mode `rw-------`.
  - Fields exactly as in data-model.md → AuditRecord (`auditId`, `timestamp`, `server`, `stage`,
    `capability`, `step`, `inputs`, `account`, `outcome`, `reason`, `mcpClient`).
  - The confirmation code is stored only as its SHA-256 prefix.
  - Every field is scrubbed.
  - `FileChannel.force(true)` is called before `append` returns.
  - An append failure throws, so the caller can fail closed.
  - Survives reopen: records are appended, not overwritten.
- [X] T101 [P] [US4] Write `test/application/WriteGuardTest.java`, with one test per branch of the
  decision flow in mcp-tools.md §7–8:
  - stage or unknown id → `INVALID_INPUT` / `ENVIRONMENT_UNKNOWN`
  - `write.enabled=false` → `WRITE_DISABLED`, with a hint on how to enable it
  - production without `productionOptIn` → `PRODUCTION_PROTECTED`, regardless of `write.enabled`
  - no CLI → `CLI_UNAVAILABLE`
- [X] T102 [P] [US4] Write `test/application/ConfirmationRegistryTest.java`.
  - The code has 22 URL-safe Base64 chars from 16 `SecureRandom` bytes.
  - It is bound to `(server, action, processId)`.
  - It is single use: removed on the first redemption attempt, whether that succeeds or not.
  - It expires after `confirmationTtl` (default `PT5M`), checked with a fixed `Clock`.
  - Any mismatch or expiry → `CONFIRMATION_INVALID`.
- [X] T103 [P] [US4] Write `test/adapter/cli/v81/V81ProcessControlAdapterTest.java`
  (`FakeProcessLauncher` with the S-3 fixtures).
  - `processErrorStart <pid>` and `kill <pid>`, where `pid` matches `^[0-9]{1,19}$` (the Queue
    Manager id = queueLog `workflowId` = `ps` PID, S-4); a UUID `globalPId` is rejected.
  - Success (`cli/processErrorStart_ok.*`, `cli/kill_ok.*`), unknown process → `NOT_FOUND`
    (`cli/*_unknown`), and auth failure (`cli/login_failed`), each classified as in research R-7.
  - The success fixtures are **synthetic** (user decision in T009, see their `.README`
    "SYNTHETIC – verify in T115"); T115 replaces them with real recordings.
- [X] T104 [P] [US4] Write `test/application/ProcessControlServiceTest.java` with fakes for the
  ports, `AuditLog`, `ConfirmationRegistry` and `Clock`. It covers the full decision flow of
  mcp-tools.md §7–8:
  - The current state is read via `ProcessQueryPort.findByProcessId` (REST `queueLog`,
    `workflowId EQUAL <pid>`; R-8). Restarting an instance that is not in ERROR
    → `PRECONDITION_FAILED` with the actual state. No queue entry → `NOT_FOUND`.
  - Confirmation `SERVER` without a code → `ConfirmationChallenge` with a preview, and audit
    `CHALLENGE_ISSUED`. With a valid code → the `EXECUTE` audit record is written **before** the CLI
    call, and the result is `EXECUTED` with `stateAfter` re-read.
  - An audit write failure → `INTERNAL`, and the CLI is never called.
  - Confirmation `CLIENT` → executes on the first call.
  - Every refusal is audited as `REFUSED`.
  - Concurrent state change: the instance completed between preview and execution → the actual
    state is reported, and nothing is executed.
- [X] T105 [P] [US4] Write `test/mcp/ProcessControlToolsTest.java` (`McpTestClient`).
  - With no server write-enabled, `tools/list` does not contain `restart_process`/`kill_process`
    (Story 4 / AS 6).
  - With `dev` write-enabled, both are present with `destructiveHint=true`, `idempotentHint=false`
    and `readOnlyHint=false`.
  - The input schema requires `server` matching `^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$`,
    an optional `confirmationCode` matching `^[A-Za-z0-9_-]{22}$`, and an optional `reason` with
    `maxLength` 500.
  - The two-step flow works end to end with fakes.

### Implementation for User Story 4

- [X] T106 [P] [US4] Create `main/domain/model/ProcessAction.java`, `ConfirmationChallenge.java`,
  `ProcessControlResult.java`, `AuditRecord.java` and `AuditOutcome.java` per data-model.md
- [X] T107 [P] [US4] Create `main/domain/port/ProcessControlPort.java` (`restart(pid)`,
  `kill(pid)`) and `main/domain/port/AuditPort.java`
- [X] T108 [US4] Implement `main/infra/AuditLog.java` (`AuditPort`, `FileChannel` with `APPEND`,
  `force(true)`, POSIX permissions) to make T100 pass
- [X] T109 [P] [US4] Implement `main/application/WriteGuard.java` to make T101 pass
- [X] T110 [P] [US4] Implement `main/application/ConfirmationRegistry.java` to make T102 pass
- [X] T111 [US4] Implement `main/adapter/cli/v81/V81ProcessControlAdapter.java` to make T103 pass
- [X] T112 [US4] Implement `main/application/ProcessControlService.java` to make T104 pass
- [X] T113 [P] [US4] Create `res/schemas/restart_process.input.json`, `restart_process.output.json`,
  `kill_process.input.json` and `kill_process.output.json` per mcp-tools.md §7–8
- [X] T114 [US4] Implement `main/mcp/tools/RestartProcessTool.java` and
  `main/mcp/tools/KillProcessTool.java` (descriptions verbatim from mcp-tools.md). Register them in
  `Main.java` **only if** at least one server has effective write access, and pass the
  `mcpClient` info from the initialize exchange into the audit. Makes T105 pass.
- [ ] T115 [US4] Run a manual live check on DEV **only with explicit user approval per action**.
  - Temporarily enable `write.enabled: true` for stage `dev` in `~/.config/inubit-mcp/config.yaml`.
  - Run quickstart V9–V12 against a process instance in ERROR that the user names.
  - Verify the audit file.
  - Record the real successes of `processErrorStart <pid>` and `kill <pid>` for the approved
    instances with `tools/record-fixtures.sh` (add catalogue cases that take the approved PID,
    each run only with per-action user approval), replacing the synthetic
    `cli/processErrorStart_ok.*` and `cli/kill_ok.*`. Delete their `.README` files and re-run
    `V81ProcessControlAdapterTest` (T103) and `CliOutputClassifierTest` (T023).
  - Revert the config afterwards.
  - Record the outcome in `specs/001-inubit-mcp-mvp/research.md` → Spike results.
- [X] T116 [US4] Extend `docs/tools.md` with `restart_process` and `kill_process` (two-step
  confirmation flow, refusal codes). Extend `docs/setup.md` with write configuration
  (`write.enabled`, `write.confirmation`, `write.productionOptIn`), audit file location and format.
  Extend `docs/live-tests.md` with the manual, approval-only write checks from T115.

**Checkpoint**: All four stories work independently. Quickstart V9–V13 pass.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T117 [P] Write `test/security/NoSecretLeakTest.java`, which checks SC-006 end to end.
  - It seeds unique fake passwords via the injected environment.
  - It drives every tool through `McpTestClient`, including error paths such as auth failure,
    unreachable, timeout and unexpected output.
  - It asserts that no seed value and no `base64(user:seed)` occurs in any of: protocol stdout,
    captured stderr logs, audit files, `ToolError` excerpts, or any `CliRunner` argument array.
- [X] T118 [P] Write `test/performance/HealthOverviewPerformanceTest.java` for SC-002.
  - Setup: 10 WireMock servers, one delayed by 30 s.
  - `get_health` without `target` must return within 10 s, with 9 healthy reports and 1 `TIMEOUT`.
- [X] T119 [P] Review `docs/setup.md` end to end against the final behaviour. Measure SC-001 by
  following it on a clean shell (target: first successful health check in under 15 minutes) and fix
  any gaps.
- [X] T120 [P] Cross-check `docs/tools.md` against `res/schemas/*.json` and the tool descriptions in
  `main/mcp/tools/*`. Every input property, enum value and output field is documented exactly once.
- [X] T121 [P] Review `docs/live-tests.md` for completeness (all `@Tag("live")` tests, production
  refusal, approval-only write checks).
- [X] T122 [P] Update `README.md` with the final tool list and the current status of the stories.
- [X] T123 Add the dependency-boundary check `test/architecture/PackageBoundaryTest.java`, a plain
  JUnit test that scans the compiled classes and asserts Constitution V and research R-2:
  - `domain` and `application` import no `io.modelcontextprotocol`, `java.net.http` or
    `java.lang.ProcessBuilder`
  - only `mcp` imports `io.modelcontextprotocol`
- [X] T124 Run the full quickstart validation against DEV and QA (`specs/001-inubit-mcp-mvp/quickstart.md`
  §1–6, with V9–V12 only with user approval). Record the results, including the measured SC-002
  times, in a new section "Validation log 2026-xx-xx" of `specs/001-inubit-mcp-mvp/quickstart.md`.
- [ ] T125 Run the walkthrough for SC-003, SC-004 and SC-008 with the user:
  - at least 5 real failure cases on DEV or QA, using only the MCP tools in Claude Code
  - the time to answer "why did process X fail?" against the manual baseline the user states
  - inventory comparison of the same workflow on `qa/node1` and `qa/node2` against the
    Workbench

  Record the results in the validation log section of `specs/001-inubit-mcp-mvp/quickstart.md`.
- [X] T126 [US3] Module usage from the workflow nodes (finding F1 of the T124 validation; user
  decision option A). INUBIT 8.1's module export names a workflow only for connector modules, so
  "no `WorkflowName`" did not mean "unused".
  - `test/application/ModuleUsageTest.java` first: `ModuleUsageIndexer` reads the `modelByName`
    nodes of every technical workflow of the owner, at most 8 at a time, within
    `cliExportTimeout`; a node names a module; union with the connector's `WorkflowName`;
    duplicate module names resolved by the node type; an unreadable or late workflow makes the
    index incomplete (first failure, counts); an interrupted caller gets `TIMEOUT`.
  - `InventoryService`: usage index cached as `(server, MODULE, usage)` with the module list's
    TTL, single flight and `refresh`; an incomplete index is served but discarded
    (`InventoryCache.discard`). Module list items carry `workflows` (at most 5) and
    `workflowCount`, the server entry `usageComplete`; module details carry all `workflows`,
    `workflowCount`, `usageComplete` and take the history from the group of the first using
    workflow in the diagram list; "not used by any technical workflow" only for a complete index.
    Deadlines include the diagram list and the index budget.
  - `ModuleEntry.connectorWorkflow` replaces `InventoryItem.workflow`; schemas, contracts §5–6,
    data-model.md, research.md R-11, spec FR-015, docs/tools.md and README updated;
    `InventoryLiveTest` asserts that an XSLT Converter module is used by a workflow and that its
    history is available through it.
  - Follow-up (review N1–N6): the index reads one workflow at a time until the first read
    succeeds and starts no read after an `AUTH_FAILED` (at most one failed login when a password
    became invalid mid-session); reads completed before the budget count after a timeout;
    details cut `workflows` to a quarter of the budget before dropping versions.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup and spikes)** has no prerequisites.
  - T001 → T003, T004.
  - T005 and T006 come before T007 – T010.
  - T008 needed live process instances (done on DEV). T009's success fixtures are synthetic
    (user decision); T115 verifies them.
- **Phase 2 (Foundational)** depends on T001 – T003. It **blocks all stories**.
  - T023 needs the T009 fixtures. T009's failure cases are enough to start.
- **US1 (Phase 3)** depends on Phase 2 and T007 (metrics fixture).
- **US2 (Phase 4)** depends on Phase 2, T007 (log fixtures) and T008 (`queueLog` fixtures). It is
  independent of US1.
- **US3 (Phase 5)** depends on Phase 2 and T010. It is independent of US1 and US2.
- **US4 (Phase 6)** depends on Phase 2, T009 and on `ProcessQueryPort` from US2 (T072–T074; T074 builds on T073), which
  it uses to read the current state.
- **Polish (Phase 7)** depends on the stories that are being delivered.

### User Story Dependencies

```text
Phase 1 ──► Phase 2 ──┬──► US1 (P1) 🎯 MVP
                      ├──► US2 (P2) ──► US4 (P4)   (US4 reuses ProcessQueryPort from US2)
                      └──► US3 (P3)
```

### Within Each User Story

1. Fixtures, then tests that fail.
2. Domain records and ports.
3. Adapters and parsers.
4. Application service.
5. Schemas and tool handlers.
6. Live test.
7. Documentation for the story (`docs/tools.md` and others) in the same change (Constitution:
   Development Workflow).

---

## Parallel Examples

### Phase 2 tests (all [P], different files)

```text
T011 ServerIdTest · T012 ConfigLoaderTest · T013 CredentialResolverTest · T014 ConfigValidatorTest
T015 SecretScrubberTest · T016 ScrubbingJsonEncoderTest · T017 TargetResolverTest · T018 FanOutTest
T019 ResultLimiterTest · T020 PinningTrustManagerTest · T021 InubitHttpClientTest
T022 CliRunnerTest · T023 CliOutputClassifierTest · T024 McpTestClient · T025 McpServerSmokeTest
T026 MainTest · T027 StdoutGuardTest · T028 XmlSupportTest · T029 AdapterGatewayFactoryTest
T030 CliVersionProbeTest
```

### User Story 1

```text
T053 V81MonitoringAdapterTest · T054 HealthServiceTest · T055 ListServersToolTest · T056 GetHealthToolTest
then: T057 domain records · T058 MonitoringPort · T061 schemas   (parallel)
```

### User Story 3

```text
T080 ModelListParserTest · T081 ModelDetailParserTest · T082 DiagramExportParserTest
T083 VersionHistoryParserTest · T084 ModuleIndexParserTest · T085 CliExportRunnerTest
T086 InventoryCacheTest · T087 InventoryServiceTest · T088 inventory tool tests
T089 V81InventoryAdapterTest
```

### Across stories (after Phase 2)

US1, US2 and US3 can be built in parallel by different people or agents. US4 starts once T072,
T073 and T074 from US2 are done.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: setup and spikes T001 – T007. T008 – T010 can run in parallel later.
2. Phase 2: foundational.
3. Phase 3: US1.
4. **Stop and validate**: run quickstart V1–V3 against DEV and QA, then register the server in
   Claude Code.

### Incremental Delivery

1. Setup and Foundational → a server that starts and passes `--check-config`.
2. Add US1 → health for all stages (MVP).
3. Add US2 → failure diagnosis.
4. Add US3 → inventory with version history.
5. Add US4 → guarded process control. Enable it per stage only after T115.

Each increment is merged only with a green `mvn verify` and a satisfied Constitution Check
(plan.md).

---

## Notes

- [P] tasks touch different files and do not depend on unfinished tasks.
- The [Story] label maps each task to its story for traceability.
- Verify that each test fails before implementing (Red → Green → Refactor).
- Commit after each task or logical group. The SDD artifacts are updated in the same commit when a
  spike changes them.
- Never contact TEST, STAGING or PROD. Never run state-changing CLI commands without per-action user
  approval.
