package de.dadecker.inubit.mcp.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static de.dadecker.inubit.mcp.application.FakeProcessControl.row;
import static de.dadecker.inubit.mcp.application.FakeProcessControl.rows;
import static de.dadecker.inubit.mcp.testing.FakeVersionDetection.gateWithoutClients;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.soap.SoapE2eClient;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessAction;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.RecordingWriteLock;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Feature 017 T046 (FR-018, SC-007, research D-24, contracts "Every write tool"): for each of
 * the eight write tools, INUBIT on the node changes from 8.1.17 to 9.0.7 after the call was
 * admitted and before its first write is handed off. The write is refused with the contract's
 * {@code UNSUPPORTED_VERSION} text, nothing is launched or sent, and every permit the call
 * handed to the lock carries the admitted node, version and the tool's write capability.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WriteRaceToolsTest extends DeployExecution {

    private static final InubitVersion V9_0_7 = InubitVersion.parse("9.0.7").orElseThrow();
    private static final String REFUSAL = "INUBIT on %s changed from 8.1.17 to 9.0.7 during"
        + " this call; the write was admitted for INUBIT 8.1; nothing was sent";
    private static final NodeId DEV = ImportHarness.DEV;
    private static final String PID = "110190387";
    private static WireMockServer wireMock;

    enum Tool {
        IMPORT_ARTIFACTS(Capability.DEVELOPMENT_WRITE),
        SET_ACTIVE(Capability.DEVELOPMENT_WRITE),
        RESTORE_BACKUP(Capability.DEVELOPMENT_WRITE),
        TAG_ARTIFACTS(Capability.DEVELOPMENT_WRITE),
        RUN_E2E_TEST(Capability.E2E_TEST),
        DEPLOY_RELEASE(Capability.DEPLOYMENT),
        RESTART_PROCESS(Capability.PROCESS_CONTROL),
        KILL_PROCESS(Capability.PROCESS_CONTROL);

        final Capability capability;

        Tool(Capability capability) {
            this.capability = capability;
        }
    }

    /**
     * What one call did: what it answered (the error or the result), the writes it launched or
     * sent and the permits it handed to the lock.
     */
    private record Race(NodeId node, String answer, long writes, List<WritePermit> permits) {
    }

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @ParameterizedTest
    @EnumSource(Tool.class)
    void aVersionChangeBeforeTheFirstWriteRefusesItAndSendsNothing(Tool tool) throws Exception {
        Race race = switch (tool) {
            case IMPORT_ARTIFACTS -> importArtifacts();
            case SET_ACTIVE -> setActive();
            case RESTORE_BACKUP -> restoreBackup();
            case TAG_ARTIFACTS -> tagArtifacts();
            case RUN_E2E_TEST -> runE2eTest();
            case DEPLOY_RELEASE -> deployRelease();
            case RESTART_PROCESS -> processControl(ProcessAction.RESTART);
            case KILL_PROCESS -> processControl(ProcessAction.KILL);
        };

        assertThat(race.answer()).contains(REFUSAL.formatted(race.node()));
        assertThat(race.writes()).as("writes launched or sent").isZero();
        assertThat(race.permits()).isNotEmpty().allSatisfy(permit -> {
            assertThat(permit.node()).isEqualTo(race.node());
            assertThat(permit.capability()).isEqualTo(tool.capability);
            assertThat(permit.admitted().raw()).isEqualTo("8.1.17");
        });
    }

    /** The answer of {@code call}: its result or the error it threw. */
    private static String answer(Supplier<Object> call) {
        try {
            return String.valueOf(call.get());
        } catch (ToolErrorException e) {
            return e.error().toString();
        }
    }

    // --- import_artifacts, set_active, restore_backup, tag_artifacts (StartCLI) ----------------

    /** {@link ImportHarness#DEV} runs AUTO and is detected as 8.1.17 when a call is admitted. */
    private ImportHarness imports() throws IOException {
        ImportHarness imports = ImportHarness.grpA(temp.resolve("imports"));
        imports.versionLines.put(DEV, VersionSetting.AUTO);
        imports.detection.answer(DEV, "8.1.17");
        return imports;
    }

    private static long cliWrites(ImportHarness imports) {
        return imports.cli.execCommands().stream().filter(command -> command.startsWith("import ")
            || command.startsWith("tag ")).count();
    }

    private Race importArtifacts() throws IOException {
        ImportHarness imports = imports();
        imports.edit(imports.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        imports.exportGroup().exportGroup();
        imports.writeLock.changeBefore(1, () -> imports.versions.detected(DEV, V9_0_7));

        String answer = answer(() -> imports.service().importArtifacts(imports.group("Move")));

        imports.cli.verifyComplete();
        return new Race(DEV, answer, cliWrites(imports) + imports.inubit.imported.size(),
            imports.writeLock.permits());
    }

    private Race setActive() throws IOException {
        ImportHarness imports = imports();
        imports.exportGroup().exportGroup();
        imports.writeLock.changeBefore(1, () -> imports.versions.detected(DEV, V9_0_7));

        String answer = answer(() -> imports.service().setActive(
            new ImportService.ActivationRequest("dev/node1", Optional.empty(), "GRP-01",
                "Workflow-0001", true, "Switch it", Optional.empty(), Optional.empty())));

        imports.cli.verifyComplete();
        return new Race(DEV, answer, cliWrites(imports) + imports.inubit.imported.size(),
            imports.writeLock.permits());
    }

    private Race restoreBackup() throws IOException {
        ImportHarness imports = imports();
        imports.edit(imports.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        imports.exportGroup().importApplied().exportGroup();
        WriteOutcome imported = ((ImportService.Response.Completed) imports.service()
            .importArtifacts(imports.group("Move it"))).outcome();
        assertThat(imported.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        long writesBefore = cliWrites(imports);
        int permitsBefore = imports.writeLock.permits().size();
        imports.exportGroup().exportGroup();
        imports.writeLock.changeBefore(1, () -> imports.versions.detected(DEV, V9_0_7));

        String answer = answer(() -> imports.service().restore(new ImportService.RestoreRequest(
            "dev/node1", imported.backupRef().orElseThrow(), "Undo the move", Optional.empty(),
            Optional.empty())));

        imports.cli.verifyComplete();
        List<WritePermit> permits = imports.writeLock.permits();
        return new Race(DEV, answer, cliWrites(imports) - writesBefore
            + imports.inubit.imported.size() - 1, permits.subList(permitsBefore,
                permits.size()));
    }

    private Race tagArtifacts() throws IOException {
        ImportHarness imports = imports();
        imports.historyExport("REL-1", "Workflow-0001", "Workflow-0002", "Module-0003");
        imports.writeLock.changeBefore(1, () -> imports.versions.detected(DEV, V9_0_7));

        String answer = answer(() -> imports.tagService().tag(new TagService.TagRequest(
            "dev/node1", Optional.empty(), List.of("GRP-01"), "REL-1", "Tested state",
            Optional.empty(), Optional.empty())));

        imports.cli.verifyComplete();
        return new Race(DEV, answer, cliWrites(imports), imports.writeLock.permits());
    }

    // --- deploy_release (StartCLI on the target nodes) -----------------------------------------

    private Race deployRelease() {
        harness(false);
        DeployHarness.TARGETS.forEach(node -> {
            harness.versionLines.put(node, VersionSetting.AUTO);
            harness.detection.answer(node, "8.1.17");
        });
        DeploymentPreview preview = preview();
        executeStart();
        nodeExports(DeployHarness.INT1); // re-check
        nodeExports(DeployHarness.INT1); // the state after the refusal
        harness.writeLock.changeBefore(1, () -> harness.versions.detected(DeployHarness.INT1,
            V9_0_7));

        String answer = answer(() -> execute(preview));

        harness.verifyComplete();
        return new Race(DeployHarness.INT1, answer, harness.launches().stream().filter(line ->
            line.contains(" import ") || line.contains(" tag ")).count(),
            harness.writeLock.permits());
    }

    // --- restart_process, kill_process ---------------------------------------------------------

    private Race processControl(ProcessAction action) {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-06T08:00:00Z"));
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.AUTO,
            node -> Duration.ofMinutes(10));
        FakeVersionDetection detection = new FakeVersionDetection(versions);
        detection.answer(DEV, "8.1.17");
        RecordingWriteLock lock = new RecordingWriteLock(versions);
        lock.changeBefore(1, () -> versions.detected(DEV, V9_0_7));
        FakeProcessControl ports = new FakeProcessControl();
        ports.lock = lock;
        ProcessInstance error = row(DEV, PID, ProcessState.ERROR, "Error",
            Instant.parse("2026-11-06T07:00:00Z"), "Workflow-9001", "Throw Exception");
        ports.reading(rows(error), rows(error));
        WritePolicy policy = new WritePolicy(DEV, false, true, false,
            WritePolicy.Confirmation.CLIENT, Duration.ofMinutes(5), Optional.of("jdoe"));
        List<AuditRecord> audit = new CopyOnWriteArrayList<>();
        ProcessControlService service = new ProcessControlService(new WriteGuard(
            new TargetResolver(List.of(DEV)), Map.of(DEV, policy)::get, ports,
            gateWithoutClients(versions, detection, CapabilityMatrix.production())),
            Map.of(DEV, policy)::get, ports, new ConfirmationRegistry(clock), audit::add, clock,
            UUID::randomUUID, "acme");

        String answer = answer(() -> service.execute(new ProcessControlService.ControlRequest(
            action, "dev/node1", PID, Optional.empty(), Optional.of("fixed the mapping"),
            Optional.empty())));

        return new Race(DEV, answer, ports.actions().size(), lock.permits());
    }

    // --- run_e2e_test (SOAP) -------------------------------------------------------------------

    private Race runE2eTest() throws IOException {
        wireMock.resetAll();
        wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)
            .withBody("<ok/>")));
        Path root = Files.createDirectories(temp.resolve("e2e"));
        Files.createDirectories(root.resolve("samples"));
        Files.writeString(root.resolve("samples/order.xml"), "<soapenv:Envelope xmlns:soapenv="
            + "\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body/></soapenv:Envelope>");
        MutableClock clock = new MutableClock(Instant.parse("2026-11-06T08:00:00Z"));
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.AUTO,
            node -> Duration.ofMinutes(10));
        FakeVersionDetection detection = new FakeVersionDetection(versions);
        detection.answer(DEV, "8.1.17");
        RecordingWriteLock lock = new RecordingWriteLock(versions);
        lock.changeBefore(1, () -> versions.detected(DEV, V9_0_7));
        URI endpoint = URI.create("https://localhost:" + wireMock.httpsPort());
        DevelopmentPolicy policy = new DevelopmentPolicy(DEV, false, true,
            WritePolicy.Confirmation.CLIENT, Duration.ofMinutes(5), E2ePolicy.FREE,
            Optional.of(endpoint), false);
        LogPort logs = new LogPort() {
            @Override
            public void validate(LogQuery query) {
            }

            @Override
            public Page<LogEntry> query(LogQuery query) {
                return new Page<>(List.of(), 0, query.limit(), OptionalLong.of(0), false, false,
                    OptionalInt.empty());
            }
        };
        ProcessQueryPort processes = new ProcessQueryPort() {
            @Override
            public void validate(ProcessQuery query) {
            }

            @Override
            public Page<ProcessInstance> find(ProcessQuery query) {
                return new Page<>(List.of(), 0, query.limit(), OptionalLong.of(0), false, false,
                    OptionalInt.empty());
            }

            @Override
            public ProcessRows findByProcessId(String processId, Instant now,
                Duration threshold) {
                return new ProcessRows(List.of(), 0);
            }
        };
        List<AuditRecord> audit = new CopyOnWriteArrayList<>();
        try (SoapE2eClient client = new SoapE2eClient(TestNodeConfig.node().id(DEV.value())
            .baseUrl(endpoint.toString()).trustStore(TestCertificates.get().trustStore())
            .build(), endpoint, Optional.empty(), Optional.empty(), lock)) {
            E2eTestService service = new E2eTestService(new E2eTestService.Dependencies(root,
                root.resolve("backups"), "acme", new DevelopmentGuard(new TargetResolver(
                    List.of(DEV)), Map.of(DEV, policy)::get, node -> { }, gateWithoutClients(
                        versions, detection, CapabilityMatrix.production()),
                    group -> Optional.empty(), Duration.ofMinutes(30)), node -> client,
                node -> logs, node -> processes, node -> Duration.ofMinutes(15),
                node -> new ImportService.Account("jdoe", "inubit-dev-1.example.test"),
                new WriteChallengeRegistry(clock), audit::add, clock, UUID::randomUUID));

            String answer = answer(() -> service.run(new E2eTestService.E2eRequest("dev/node1",
                "samples/order.xml", "/ibis/ws/Service-01", Optional.of("urn:order"),
                Optional.of("Workflow-0001"), 10, false, Optional.empty(), Optional.empty())));

            return new Race(DEV, answer, wireMock.countRequestsMatching(anyRequestedFor(anyUrl())
                .build()).getCount(), lock.permits());
        }
    }
}
