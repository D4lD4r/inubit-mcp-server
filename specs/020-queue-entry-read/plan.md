# Implementation Plan: Read Queue Manager Entries (unsupported, opt-in)

**Branch**: `020-queue-entry-read` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/020-queue-entry-read/spec.md`. Base: `main` at 0.8.0;
target `[Unreleased]` (next minor).

## Summary

A read-only tool `read_queue_entry` returns the metadata, the data stream and the workflow
variables of one Queue Manager entry in state ERROR, masked and bounded, only for groups that opt
in with `unsupported.queueEntryRead`. INUBIT has no documented interface for the content, so:

- the entry is located through the documented queueLog (new `ProcessQueryPort.queueEntries`, same
  request as `find_processes`/`kill_process`), with the candidate rules of the spec (D-9);
- the content is read by a **Java 17 helper** (`QueueEntryHelper`, JDK-only, reflection on the
  vendor classes) that runs in its own JVM on the INUBIT client installation chosen for the node's
  version (017 `cliFor`), and calls the Workbench "View file" request: 8.1 `IBISSoapClient`, 9.0
  `InubitClientFactory.getRestInstance`, both `MultipartDownloadThread` with
  `queue_manager@@@<workflow>@@@error` (D-1, D-6);
- the helper ships inside the JAR as a resource compiled by a second compiler execution with
  `release 17` (D-2); it is launched through the CLI adapter's process pipeline, extracted from
  `CliRunner` into `ProcessRun` (D-3); request and credentials on stdin, nonce-framed results on
  stdout, content in files of a private temp directory (D-4, D-8);
- TLS is verified by the server itself (trust store, pin, hostname setting); the helper gets a
  one-time PKCS12 store holding only the verified certificate (D-7);
- version → implementation via a new capability `QUEUE_ENTRY_READ` in the matrix (8.1, 9.0;
  other detected versions refused, an undetectable version fails with the detection error) and a variant table in the adapter (D-6);
- the domain classifies text/binary, scrubs known credentials, masks by name with a range-based
  scanner and applies the bounds (D-10 … D-13);
- 8.1 contract tests use the neutralized spike recording; 9.0 needs a recording from an
  operator-approved ERROR entry on a 9.0 test server, else 9.0 ships refused "not yet verified"
  (D-18 … D-20).

## Technical Context

**Language/Version**: Java 21 (server); the helper Java 17 bytecode (`release 17`), JDK classes
only.

**Primary Dependencies**: unchanged (MCP Java SDK, Jackson, logback); no new dependency. The
INUBIT client installation (proprietary, not in Maven) is used only at runtime by the helper,
through reflection.

**Storage**: none persistent; per call a private temporary directory (helper class, trust store,
downloaded files), deleted at the end of the call and on shutdown.

**Testing**:
- JUnit 5, AssertJ, WireMock (queueLog), `FakeProcessLauncher` (helper launches, `onLaunch`
  writes fixture files), in-process helper tests with fake vendor classes under
  `src/test/java/com/inubit/ibis/…`, one real child-JVM helper test;
- contract tests per version against `src/test/resources/fixtures/queue-entry/{v8_1,v9_0}/`;
- `@Tag("live")` `QueueEntryLiveTest` (opt-in, never production);
- baseline: the 0.8.0 suite green (`mvn -o clean verify`).

**Target Platform**: macOS, Linux (live-checked); Windows by construction (direct `java.exe`,
argument-array tests).

**Project Type**: single Maven module, shaded JAR; one extra compiler execution.

**Performance Goals**: one call = one queueLog request (+ one on "not found") + one TLS check +
one helper JVM (≈ 1–3 s start-up of the vendor client); bounded by `cliTimeout`.

**Constraints**:
- SC-002: without an opt-in, tool list, tool behaviour, `list_nodes`, the startup summary and
  `--check-config` are byte-for-byte unchanged (the `queueEntryRead` part of the group lines
  appears only when some group opts in; owner decision);
- FR-016 interpretation (owner decision): helper runs take a slot of the per-node StartCLI
  semaphore (`acquireForHelper`); StartCLI runs on 8.1/9.0 stay without a slot as today;
- US3-4 (owner decision): an undetectable version fails with the detection error's own code
  and message (`VersionGate.admitWithDetection`), never `UNSUPPORTED_VERSION`;
- `PackageBoundaryTest`: `application` only via ports (scrubbing as `UnaryOperator<String>`),
  `Process*` only in `adapter/cli`, `config` not → `adapter`, `mcp` not → `adapter`/`config`;
  the helper references only `java/*`;
- no credential in argv, environment, logs or results (FR-015, Principle II); content never
  logged (FR-018);
- result ≤ ~190,000 characters (FR-010); input ≤ 10 MiB per part;
- customer-neutral artifacts only (FR-020, CONTRIBUTING).

**Scale/Scope**:
- 1 new tool (`read_queue_entry`), 2 new schemas, `list_nodes.output.json` enum +1;
- new production types ≈ 22 (domain ≈ 12, application 1, adapter ≈ 10, tool 1, helper 1);
- changed: `GroupConfig`, `EffectiveNodeConfig`, `ConfigLoader`, `ConfigValidator`,
  `ConfigSummary`, `Capability`, `CapabilityMatrix`, `VersionGate`, `NodeOverview`,
  `ProcessQueryPort` + its adapters and decorators, `PortSet` family, `CliRunner`,
  `CliResources`, `CliConcurrencyLimiter`, `HandshakeStartCliPinProbe`, `Wiring`, `pom.xml`,
  docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Constitution v1.1.0.

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ⚠️ a read through an undocumented vendor interface; must not change state | ✅ <ul><li>Off by default; per-group opt-in, refused with `NOT_CONFIGURED` before any request (FR-002, D-15).</li><li>Only the "View file" read: the helper calls login, `MultipartDownloadThread`, logout — nothing else exists in it (FR-007; test with a recording fake client); server-side review found only error-store reads; live check asserts the entry unchanged (SC-004).</li><li>Annotated read-only/idempotent; no write gate needed (reads only), production groups get a startup warning.</li><li>All inputs validated (schema + service) before any call; nothing reaches a shell (argument arrays, request on stdin, D-3/D-4).</li></ul> |
| II. Secrets Stay Out | ⚠️ business payloads may contain credentials; helper needs credentials | ✅ <ul><li>Credentials and the one-time trust-store password only on the helper's stdin, zero-filled after writing; argv = class name (D-4).</li><li>Known credentials scrubbed, then name-based masking on full text before any cut (D-11); results scrubbed again by `ResultMapper`.</li><li>Helper output never logged; content never logged; error excerpts scrubbed and bounded (D-5, D-16).</li><li>Residual risk (credentials under neutral names) documented as FR-004 requires.</li></ul> |
| III. Test-First | ✅ | ✅ <ul><li>Every task starts with failing tests (quickstart table).</li><li>Dedicated tests for each masking rule, for the opt-in refusal (no request sent), for "no credential in argv/env/log".</li><li>Offline: fake vendor classes, `FakeProcessLauncher`, WireMock; live test opt-in, production refused.</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ "why did this process fail" (spec US1) | ✅ one tool, task-oriented description with the short warning, typed input schema, documented output shape (contracts/mcp-tools-delta.md). |
| V. Adapter Isolation & Version Compatibility | ⚠️ CLI-side channel where REST cannot; 9.0 without recording | ✅ <ul><li>REST for the lookup, CLI adapter (helper on the client installation) only for the content REST does not offer.</li><li>Version choice in `CapabilityMatrix` and `QueueEntryVariant` (adapter), never in tool logic (D-6).</li><li>Argument arrays, explicit timeout (`cliTimeout`), exit-code + frame evaluation, tested parser (D-3 … D-5).</li><li>8.1 and 9.0 declared supported only with recorded, neutralized contract fixtures; without the 9.0 recording 9.0 is refused (FR-019, D-20) — no deviation.</li></ul> |
| VI. Bounded, Structured Output | ✅ | ✅ fixed JSON shape; text ≤ `maxChars`, variables 200 / 2,000 / 40,000, parts ≤ 10 MiB input, explicit truncation markers; actionable errors (D-12, contracts). |
| VII. Observability & Auditability | ✅ (read tool: no audit) | ✅ one structured INFO line per call with node, process ID, file name, sizes, outcome; variant/client at DEBUG; no audit record (not state-changing) (D-16). |
| Tech constraints: TLS for CLI connections; server verifies pins itself | ⚠️ vendor clients' trust handling is unreliable (8.1 ignores a missing store; 9.0 ignores StartCLI's argument) | ✅ the server verifies every `https` connection itself before the launch and restricts the helper to the verified certificate (D-7). |
| Tech constraints: dependencies minimal, single artifact | ✅ | ✅ no new dependency or plugin; the helper is a resource in the same JAR (D-2). |

**Result**: PASS, no deviation (Complexity Tracking empty).

## Project Structure

### Documentation (this feature)

```text
specs/020-queue-entry-read/
├── plan.md
├── research.md                 # D-1…D-22
├── data-model.md
├── quickstart.md               # offline table, live check, validation record
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r1…r3, plan reviews
└── tasks.md                    # /speckit-tasks
```

### Source Code (repository root)

```text
pom.xml                                   # execution compile-queue-entry-helper (release 17)
src/helper/java/de/dadecker/inubit/mcp/helper/
└── QueueEntryHelper.java                 # new; JDK only; reflection on vendor classes
src/main/java/de/dadecker/inubit/mcp/
├── config/              # UnsupportedConfig (new), GroupConfig, EffectiveNodeConfig (queueEntryRead),
│                        # ConfigLoader (strict boolean), ConfigValidator (production warning),
│                        # ConfigSummary (group line, cliOnly filter)
├── domain/model/        # Capability (+QUEUE_ENTRY_READ), CapabilityMatrix (states, unknown, notes),
│                        # QueueEntryRow, QueueEntries, QueueEntryRef, EntryPart, RawEntryContent,
│                        # RawVariable, ContentKind, BinarySignature, EntryContentClassifier,
│                        # SecretTextMasker, SystemVariables, QueueEntryBounds, QueueEntryResult
├── domain/port/         # QueueEntryContentPort (new), ProcessQueryPort (+queueEntries),
│                        # Gateway, GatewayFactory (+queueEntryContent)
├── application/         # QueueEntryService (new), VersionGate (note, cliOnlyStep, admitWithDetection),
│                        # NodeOverview (name)
├── adapter/             # PortSet, V81PortSet, V81Gateway, AdapterGatewayFactory, StaleReportingPorts,
│                        # RefusingPorts (+port)
├── adapter/rest/v81/    # QueueEntryRowParser (new), V81ProcessQueryAdapter (queueEntries)
├── adapter/cli/         # ProcessRun (new, extracted), TlsPeer (new), CliRunner (delegates, javaHome),
│                        # CliResources (process public), CliConcurrencyLimiter (acquireForHelper),
│                        # HandshakeStartCliPinProbe (uses TlsPeer)
├── adapter/cli/queue/   # new: CliQueueEntryAdapter, QueueEntryVariant, HelperRequest, HelperFrames,
│                        # QueueEntryHelperClassifier, HelperTrustStore, HelperInstallation, WfspParser
├── adapter/cli/v61/     # V61PortSet (refusing port), V61ProcessQueryAdapter (refuses queueEntries)
├── mcp/tools/           # ReadQueueEntryTool (new)
└── Wiring.java          # service, tool announcement, allowed(), scrubber function
src/main/resources/schemas/read_queue_entry.{input,output}.json, list_nodes.output.json
src/test/java/…          # tests per quickstart; com/inubit/ibis/… fake vendor classes;
                         # testing/FakeInubitClient; live/QueueEntryLiveTest
src/test/resources/fixtures/queue-entry/{v8_1,v9_0}/, README.md
docs/setup.md, docs/tools.md, docs/live-tests.md, README.md, CHANGELOG.md
```

**Structure Decision**: the same single module; the helper source is a second source root that is
compiled separately and lands as a resource under `META-INF/inubit-mcp/queue-entry-helper/`.
Stages, each ending with a green `mvn -o -q clean verify` and reviewed:

1. **Configuration**: `unsupported.queueEntryRead` binding, strictness, unknown keys, effective
   value, production warning, summary line (FR-003, FR-017).
2. **Capability and gate**: `QUEUE_ENTRY_READ`, matrix states incl. unknown map and notes,
   `VersionGate` (`admitWithDetection`: detection error passed through, back-off respected),
   `NodeOverview`, `list_nodes` enum; `allowed()` only with opt-in + client.
3. **Lookup**: `QueueEntryRowParser`, `ProcessQueryPort.queueEntries` through the port family;
   WireMock tests on queueLog fixtures.
4. **Domain logic**: `EntryContentClassifier`, `SecretTextMasker` (rule by rule), `SystemVariables`,
   bounds (pure tests).
5. **Process pipeline**: extract `ProcessRun` from `CliRunner` (existing tests as regression net);
   `CliResources`, limiter `acquireForHelper`, `TlsPeer`.
6. **Helper**: `pom.xml` execution, `QueueEntryHelper` with fake vendor classes, packaging test,
   child-JVM test, `PackageBoundaryTest` rule.
7. **Adapter**: `HelperInstallation`, `HelperTrustStore`, `HelperRequest`, `HelperFrames`,
   classifier, `WfspParser`, `CliQueueEntryAdapter`, port wiring.
8. **Service and tool**: `QueueEntryService`, `ReadQueueEntryTool`, schemas, announcement,
   logging test.
9. **Fixtures and contracts**: neutralized 8.1 recording, synthetic cases, catalogue test,
   `QueueEntryV81ContractTest`; `QueueEntryV90ContractTest` with the fallback state.
10. **Docs**: setup (FR-004 text), tools, live tests, README, CHANGELOG; `QueueEntryDocsTest`.
11. **Live check and 9.0 recording** (operator): 8.1 and 9.0 live runs; 9.0 recording or fallback
    decision recorded in quickstart.md.

### Touch points with other work

- **017** (`cliClients`, `select`, launch guard): reused unchanged; the helper uses the 017 client
  choice and `checkLaunch`.
- **`017-live-check` commit `b35f166`** (StartCLI trust store via `JAVA_TOOL_OPTIONS` for 9.0, TLS
  text): not on `main`; 020 does not depend on it and touches neither `CliOutputClassifier` nor
  `CliRunner.run`'s trust-store arguments. Extracting `ProcessRun` touches `CliRunner`; if
  `b35f166` is merged first, the extraction rebases over its `extraJavaToolOptions` change
  (no semantic conflict).
- **012** (6.1): 6.1 nodes refuse through the matrix and `RefusingPorts`; `ConfigSummary.cliOnly`
  unchanged for groups without the opt-in.

## Complexity Tracking

No constitution deviation. Notable design weight, kept deliberately:

| Item | Why needed | Simpler alternative rejected because |
|---|---|---|
| A second compiler execution (Java 17) for one helper class | 8.1's client runs on JDK 17; the vendor classes must not enter the server JVM | in-JVM class loading mixes vendor logging/static state with ours and cannot be killed per call; runtime compilation failed in the spike |
| Server-side TLS check + one-time trust store | the vendor clients' own trust handling is unreliable (8.1 ignores a missing store; 9.0 ignored StartCLI's argument) | passing the configured store directly would make FR-014 depend on undocumented vendor behaviour |
