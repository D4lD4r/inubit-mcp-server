package de.dadecker.inubit.mcp.mcp.tools;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import de.dadecker.inubit.mcp.adapter.AdapterGatewayFactory;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.application.ConfirmationRegistry;
import de.dadecker.inubit.mcp.application.DeployGuard;
import de.dadecker.inubit.mcp.application.DeployHarness;
import de.dadecker.inubit.mcp.application.ImportHarness;
import de.dadecker.inubit.mcp.application.ImportService;
import de.dadecker.inubit.mcp.application.ProcessControlService;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.WriteGuard;
import de.dadecker.inubit.mcp.config.ConfirmationMode;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.infra.AuditLog;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.testing.ArgvRecorder;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.RecordingVersionObserver;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import de.dadecker.inubit.mcp.testing.WindowsAcl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 013 (T027, FR-001): the write tools through the real launch of the host platform — on
 * Windows {@code startcli.bat} through the command interpreter. The scripts of the POSIX tests
 * stay; each scripted answer is printed by the substitute StartCLI with CRLF line ends, as the
 * client prints it on Windows ({@link ScriptedProcessLauncher#throughTheLaunch}). So the command
 * lines and their quoting, the password on stdin, the output and the exit codes take the real way;
 * the export ZIPs and the fake servers' state stay in the test, as in the POSIX tests. Confirmation
 * codes and audit records are checked as there; the packages of a package-only target are read
 * back with their DACL on Windows (FR-007).
 */
@Timeout(300)
class WindowsWriteToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MODULE_IMPORT =
        "--importModule --importUser 'jdoe' --returnProtocol";
    private static final String TAG = "TAG-01";
    private static final String PID = "110219899";
    private static final String PROCESS_PASSWORD = "windows-write-tools-pw";

    private static WireMockServer dev;

    @TempDir
    Path temp;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeAll
    static void start() {
        dev = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        dev.stop();
    }

    @BeforeEach
    void reset() {
        dev.resetAll();
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    private McpTestClient client(ToolHandler... tools) {
        McpTestClient client = McpTestClient.start(List.of(tools));
        closeables.add(client);
        client.initialize();
        return client;
    }

    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return result.path("structuredContent");
    }

    private static Map<String, Object> confirmed(Map<String, Object> arguments,
        JsonNode challenge) {
        Map<String, Object> copy = new HashMap<>(arguments);
        copy.put("confirmationCode", challenge.path("confirmationCode").asString());
        return copy;
    }

    private static List<AuditOutcome> outcomes(List<AuditRecord> audit, String capability) {
        return audit.stream().filter(record -> record.capability().equals(capability))
            .map(AuditRecord::outcome).toList();
    }

    /**
     * Every launch of {@code cli} was started for real: the substitute recorded it, got the
     * password on stdin only and the command line unchanged.
     */
    private static void assertLaunchedForReal(ScriptedProcessLauncher cli, String password)
        throws IOException {
        assertThat(cli.launches()).isNotEmpty();
        for (ScriptedProcessLauncher.Launch launch : cli.launches()) {
            assertThat(launch.replyDirectory()).as(launch.execCommand()).isNotNull();
            FakeStartCli.Recorded recorded = FakeStartCli.read(
                launch.replyDirectory().resolve(ArgvRecorder.RECORD_FILE));
            assertThat(new String(recorded.stdin(), StandardCharsets.UTF_8).strip())
                .as(launch.execCommand()).isEqualTo(password);
            assertThat(recorded.args()).as(launch.execCommand())
                .containsSequence("--execCommand", launch.execCommand())
                .noneMatch(arg -> arg.contains(password));
        }
    }

    // --- the development tools (ImportService, TagService) -------------------------------------

    @Test
    void importArtifactsWithItsCodeRunsThroughTheLaunch() throws IOException {
        ImportHarness harness = ImportHarness.grpAThroughTheLaunch(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        McpTestClient client = client(new ImportArtifactsTool(harness.service()));
        Map<String, Object> arguments = Map.of("node", "dev/node1", "diagramGroup", "GRP-01",
            "reason", "Move it");
        harness.exportGroup();

        JsonNode challenge = structured(client.callTool("import_artifacts", arguments))
            .path("challenge");
        harness.exportGroup().importApplied().exportGroup();
        JsonNode result = structured(client.callTool("import_artifacts",
            confirmed(arguments, challenge))).path("result");

        assertThat(challenge.path("modify")).extracting(JsonNode::asString)
            .containsExactly("Workflow-0001");
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("modified")).extracting(JsonNode::asString)
            .containsExactly("Workflow-0001");
        assertThat(result.path("backupRef").asString()).isEqualTo(
            result.path("auditId").asString());
        assertThat(outcomes(harness.audit, "import_artifacts")).containsExactly(
            AuditOutcome.CHALLENGE_ISSUED, AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        harness.cli.verifyComplete();
        assertLaunchedForReal(harness.cli, ImportHarness.PASSWORD);
    }

    @Test
    void restoreBackupWithItsCodeRunsThroughTheLaunch() throws IOException {
        ImportHarness harness = ImportHarness.grpAThroughTheLaunch(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();
        WriteOutcome imported = ((ImportService.Response.Completed) harness.service()
            .importArtifacts(harness.group("Move it"))).outcome();
        String ref = imported.backupRef().orElseThrow();
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        McpTestClient client = client(new RestoreBackupTool(harness.service()));
        Map<String, Object> arguments = Map.of("node", "dev/node1", "backupRef", ref,
            "reason", "Undo");
        harness.exportGroup();

        JsonNode challenge = structured(client.callTool("restore_backup", arguments))
            .path("challenge");
        harness.exportGroup().importRestored().exportGroup();
        JsonNode result = structured(client.callTool("restore_backup",
            confirmed(arguments, challenge))).path("result");

        assertThat(challenge.path("modify")).extracting(JsonNode::asString)
            .containsExactly("Workflow-0001");
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("modified")).extracting(JsonNode::asString)
            .containsExactly("Workflow-0001");
        assertThat(outcomes(harness.audit, "restore_backup")).containsExactly(
            AuditOutcome.CHALLENGE_ISSUED, AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        harness.cli.verifyComplete();
        assertLaunchedForReal(harness.cli, ImportHarness.PASSWORD);
    }

    @Test
    void setActiveWithItsCodeRunsThroughTheLaunch() throws IOException {
        ImportHarness harness = ImportHarness.grpAThroughTheLaunch(temp);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        McpTestClient client = client(new SetActiveTool(harness.service()));
        Map<String, Object> arguments = Map.of("node", "dev/node1", "diagramGroup", "GRP-01",
            "workflow", "Workflow-0001", "active", true, "reason", "On");
        harness.exportGroup();

        JsonNode challenge = structured(client.callTool("set_active", arguments))
            .path("challenge");
        harness.exportGroup().importApplied("--importWorkflow --importWorkflowActive"
            + " --importUser 'jdoe' --returnProtocol").exportGroup();
        JsonNode result = structured(client.callTool("set_active",
            confirmed(arguments, challenge))).path("result");

        assertThat(challenge.path("modify")).extracting(JsonNode::asString)
            .containsExactly("Workflow-0001");
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(outcomes(harness.audit, "set_active")).containsExactly(
            AuditOutcome.CHALLENGE_ISSUED, AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        harness.cli.verifyComplete();
        assertLaunchedForReal(harness.cli, ImportHarness.PASSWORD);
    }

    @Test
    void tagArtifactsRunsThroughTheLaunch() throws IOException {
        ImportHarness harness = ImportHarness.grpAThroughTheLaunch(temp);
        McpTestClient client = client(new TagArtifactsTool(harness.tagService()));
        harness.historyExport("REL-1", "Workflow-0001", "Workflow-0002", "Module-0003")
            .tagMoved("REL-1", false).historyExport("REL-1");

        JsonNode result = structured(client.callTool("tag_artifacts", Map.of("node",
            "dev/node1", "diagramGroups", List.of("GRP-01"), "tag", "REL-1", "reason",
            "Tested state"))).path("result");

        assertThat(result.path("outcome").asString()).as(result.toString())
            .isEqualTo("EXECUTED");
        assertThat(result.path("workflows").asInt()).isEqualTo(2);
        assertThat(result.path("modules").asInt()).isEqualTo(1);
        assertThat(outcomes(harness.audit, "tag_artifacts")).containsExactly(
            AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        harness.cli.verifyComplete();
        assertLaunchedForReal(harness.cli, ImportHarness.PASSWORD);
    }

    // --- deploy_release (DeployService) --------------------------------------------------------

    private static void nodeExports(DeployHarness harness, NodeId node) {
        harness.exportGroup(node).exportRepository(node, DeployHarness.RELEASE_XSL);
    }

    private static void sourceReads(DeployHarness harness) {
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, TAG));
        harness.exportGroup(DeployHarness.SOURCE);
    }

    private static void deployed(DeployHarness harness, NodeId node) {
        nodeExports(harness, node); // re-check
        harness.importRepositoryApplied(node).importApplied(node, MODULE_IMPORT);
        nodeExports(harness, node); // verification
        harness.tagVerified(node, "GRP-01", TAG);
    }

    private static void assertDeployLaunchedForReal(DeployHarness harness) throws IOException {
        for (ScriptedProcessLauncher cli : harness.cli.values()) {
            if (!cli.launches().isEmpty()) {
                assertLaunchedForReal(cli, DeployHarness.PASSWORD);
            }
        }
    }

    @Test
    void deployReleaseWithItsCodeDeploysEveryNodeThroughTheLaunch() throws IOException {
        DeployHarness harness = new DeployHarness(temp).throughTheLaunch();
        McpTestClient client = client(new DeployReleaseTool(
            harness.deployService(List.of()), 100));
        Map<String, Object> arguments = Map.of("target", "int", "tag", TAG);
        harness.tagged("GRP-01", TAG);
        sourceReads(harness);
        DeployHarness.TARGETS.forEach(node -> nodeExports(harness, node));

        JsonNode challenge = structured(client.callTool("deploy_release", arguments))
            .path("challenge");
        sourceReads(harness);
        DeployHarness.TARGETS.forEach(node -> nodeExports(harness, node));
        DeployHarness.TARGETS.forEach(node -> deployed(harness, node));
        JsonNode result = structured(client.callTool("deploy_release",
            confirmed(arguments, challenge))).path("result");

        assertThat(challenge.path("executable").asBoolean()).isTrue();
        assertThat(challenge.path("confirmationCode").asString()).hasSize(22);
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("commit").asString()).isNotBlank();
        assertThat(result.path("nodes")).hasSize(3).allSatisfy(node -> {
            assertThat(node.path("state").asString()).isEqualTo("DEPLOYED");
            assertThat(node.path("backupRef").asString()).isNotBlank();
            assertThat(node.path("tag").path("applied").asBoolean()).isTrue();
        });
        assertThat(harness.audit.stream().filter(r -> r.capability().equals("deploy_release")
            && r.node().equals("int")).map(AuditRecord::outcome)).containsExactly(
                AuditOutcome.CHALLENGE_ISSUED, AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        harness.verifyComplete();
        assertDeployLaunchedForReal(harness);
    }

    @Test
    void aRefusedImportOnNode2RollsNode2BackThroughTheLaunch() throws IOException {
        DeployHarness harness = new DeployHarness(temp, false).throughTheLaunch();
        DeployGuard.Request request = new DeployGuard.Request("int", TAG, Optional.empty(),
            Optional.empty(), Optional.of("client/1.0"));
        harness.tagged("GRP-01", TAG);
        sourceReads(harness);
        DeployHarness.TARGETS.forEach(node -> nodeExports(harness, node));
        DeploymentPreview preview = harness.deployService(List.of()).deploy(request).preview()
            .orElseThrow();
        sourceReads(harness);
        DeployHarness.TARGETS.forEach(node -> nodeExports(harness, node));
        deployed(harness, DeployHarness.INT1);
        nodeExports(harness, DeployHarness.INT2); // re-check
        harness.importRepositoryApplied(DeployHarness.INT2).importRefused(DeployHarness.INT2);
        nodeExports(harness, DeployHarness.INT2); // the state after the failure

        DeploymentResult result = harness.deployService(List.of()).deploy(
            new DeployGuard.Request("int", TAG, Optional.empty(), preview.confirmationCode(),
                Optional.of("client/1.0"))).result().orElseThrow();

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.FAILED);
        assertThat(result.nodes().get(0).state()).isEqualTo(DeploymentResult.State.DEPLOYED);
        DeploymentResult.NodeOutcome second = result.nodes().get(1);
        assertThat(second.state()).isEqualTo(DeploymentResult.State.ROLLED_BACK);
        assertThat(second.failure()).get().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.step()).isEqualTo("import");
        });
        assertThat(second.backupRef()).isPresent();
        assertThat(result.nodes().get(2).state()).isEqualTo(DeploymentResult.State.NOT_STARTED);
        assertThat(result.commit()).isEmpty();
        assertThat(harness.audit.stream().filter(r -> r.node().equals("int/node2"))
            .map(AuditRecord::outcome)).containsExactly(AuditOutcome.PENDING,
                AuditOutcome.FAILED);
        harness.verifyComplete();
        assertDeployLaunchedForReal(harness);
    }

    @Test
    void aVerificationMismatchOnNode2IsRolledBackFromItsBackupThroughTheLaunch()
        throws IOException {
        DeployHarness harness = new DeployHarness(temp, false).throughTheLaunch();
        harness.tagged("GRP-01", TAG);
        sourceReads(harness);
        DeployHarness.TARGETS.forEach(node -> nodeExports(harness, node));
        DeploymentPreview preview = harness.deployService(List.of()).deploy(
            new DeployGuard.Request("int", TAG, Optional.empty(), Optional.empty(),
                Optional.of("client/1.0"))).preview().orElseThrow();
        sourceReads(harness);
        DeployHarness.TARGETS.forEach(node -> nodeExports(harness, node));
        deployed(harness, DeployHarness.INT1);
        nodeExports(harness, DeployHarness.INT2); // re-check
        harness.importRepositoryApplied(DeployHarness.INT2).importApplied(DeployHarness.INT2,
            MODULE_IMPORT);
        harness.cli.get(DeployHarness.INT2).then(spec -> harness.servers.get(
            DeployHarness.INT2).tamperNextImport = new String[] {"xslt_mode", "xslt_mode_X"});
        nodeExports(harness, DeployHarness.INT2); // verification sees the tampered module
        harness.importApplied(DeployHarness.INT2, MODULE_IMPORT); // rollback from the backup
        nodeExports(harness, DeployHarness.INT2); // verification of the rollback

        DeploymentResult result = harness.deployService(List.of()).deploy(
            new DeployGuard.Request("int", TAG, Optional.empty(), preview.confirmationCode(),
                Optional.of("client/1.0"))).result().orElseThrow();

        DeploymentResult.NodeOutcome second = result.nodes().get(1);
        assertThat(second.state()).isEqualTo(DeploymentResult.State.ROLLED_BACK);
        assertThat(second.failure()).get().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
            assertThat(failure.step()).isEqualTo("verify");
        });
        // the backup's content is back, imported through the real launch
        assertThat(new String(de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures
            .entries(harness.servers.get(DeployHarness.INT2).exportModule("XSLT Converter",
                "Module-0005").orElseThrow()).get("module/module-0005.xml"),
            java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("xslt_mode_X")
            .contains("xslt_mode");
        assertThat(result.nodes().get(2).state()).isEqualTo(DeploymentResult.State.NOT_STARTED);
        harness.verifyComplete();
        assertDeployLaunchedForReal(harness);
    }

    @Test
    void aPackageOnlyTargetGetsPrivatePackagesThroughTheLaunch() throws Exception {
        DeployHarness harness = new DeployHarness(temp).throughTheLaunch();
        DeployHarness.TARGETS.forEach(node -> {
            harness.servers.get(node).publishModule("Module-0003", text -> text.replace(
                "IsModuleTemplate", "IsModuleTemplateX"));
            harness.servers.get(node).tag("GRP-01", TAG);
        });
        Runnable reads = () -> {
            DeployHarness.TARGETS.forEach(node -> harness.exportRelease(node, TAG));
            harness.exportGroup(DeployHarness.INT1);
            harness.exportGroup(DeployHarness.PROD);
        };
        reads.run();
        DeploymentPreview preview = harness.deployService(List.of()).deploy(
            new DeployGuard.Request("prod", TAG, Optional.empty(), Optional.empty(),
                Optional.of("client/1.0"))).preview().orElseThrow();
        reads.run();
        harness.exportGroup(DeployHarness.PROD);

        DeploymentResult result = harness.deployService(List.of()).deploy(
            new DeployGuard.Request("prod", TAG, Optional.empty(), preview.confirmationCode(),
                Optional.of("client/1.0"))).result().orElseThrow();

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.PACKAGED);
        Path dir = Path.of(result.nodes().get(0).packageDir().orElseThrow());
        assertThat(dir.resolve("1-modules.zip")).isRegularFile();
        assertThat(Files.readString(dir.resolve("README.md"))).contains(MODULE_IMPORT);
        assertThat(harness.launches()).allMatch(line -> line.matches("^[a-z0-9/-]+ export .*"));
        assertThat(harness.audit.stream().filter(r -> r.auditId().equals(result.auditId()))
            .map(AuditRecord::outcome)).containsExactly(AuditOutcome.PENDING,
                AuditOutcome.PACKAGED, AuditOutcome.PACKAGED);
        harness.verifyComplete();
        assertDeployLaunchedForReal(harness);
        if (Platform.windows()) {
            for (Path directory : List.of(harness.profileHome.resolve("packages"),
                dir.getParent(), dir)) {
                WindowsAcl.assertPrivate(directory, WindowsAcl.DIRECTORY_INHERITANCE);
            }
            try (Stream<Path> walk = Files.walk(dir.getParent())) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    WindowsAcl.assertPrivate(file, "None");
                }
            }
        }
    }

    // --- restart_process and kill_process (ProcessControlService) ------------------------------

    /** The process control tools on {@code dev/node1}: WireMock, the substitute replaying. */
    private McpTestClient processControl(String fixtureCase, ConfirmationMode confirmation)
        throws IOException {
        Path cliHome = temp.resolve("cli");
        FakeStartCli.install(cliHome, Map.of(ArgvRecorder.REPLAY, fixtureCase,
            ArgvRecorder.CRLF, "true"));
        EffectiveNodeConfig server = TestNodeConfig.node().id("dev/node1")
            .baseUrl("https://localhost:" + dev.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).versionLine(VersionSetting.V8_1)
            .cliHome(cliHome).cliJavaHome(Path.of(System.getProperty("java.home")))
            .write(true, false, confirmation).build();
        SecretScrubber scrubber = new SecretScrubber();
        AdapterGatewayFactory gateways = new AdapterGatewayFactory(List.of(server),
            TestSecrets.credentialStore(List.of(server), Map.of("INUBIT_DEV_USERNAME", "jdoe",
                "INUBIT_DEV_PASSWORD", PROCESS_PASSWORD), scrubber), scrubber, Clock.systemUTC(),
            TestCliRunners.forHost(new SystemProcessLauncher(), System.getenv(),
                new CliResources("acme", PrivateFiles.forHost())),
            new RecordingVersionObserver(), PortSetRegistry.v81Default());
        closeables.add(gateways);
        WritePolicy policy = new WritePolicy(server.id(), false, true, false,
            confirmation == ConfirmationMode.SERVER ? WritePolicy.Confirmation.SERVER
                : WritePolicy.Confirmation.CLIENT, Duration.ofMinutes(5), Optional.of("jdoe"));
        Function<NodeId, WritePolicy> policyOf = Map.of(server.id(), policy)::get;
        ProcessControlService service = new ProcessControlService(
            new WriteGuard(new TargetResolver(List.of(server.id())), policyOf, gateways,
                FakeVersionDetection.pinned81()), policyOf, gateways,
            new ConfirmationRegistry(Clock.systemUTC()), new AuditLog(temp.resolve("audit"),
                scrubber, PrivateFiles.forHost()), Clock.systemUTC(), UUID::randomUUID, "acme");
        McpTestClient client = McpTestClient.start(List.of(new RestartProcessTool(service),
            new KillProcessTool(service)), scrubber);
        closeables.add(client);
        client.initialize();
        return client;
    }

    /** Successive state reads: answers in order, the last one repeats. */
    private static void stubReads(boolean... withRow) {
        String state = Scenario.STARTED;
        for (int i = 0; i < withRow.length; i++) {
            String next = i + 1 < withRow.length ? "read-" + (i + 1) : state;
            dev.stubFor(post(urlPathEqualTo("/ibis/rest/log/queueLog")).inScenario("reads")
                .whenScenarioStateIs(state).withRequestBody(containing(PID))
                .willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(queueLog(withRow[i])))
                .willSetStateTo(next));
            state = next;
        }
    }

    private static String queueLog(boolean withRow) {
        if (!withRow) {
            return "{\"queueLog\":{\"total\":0,\"success\":true,\"count\":0}}";
        }
        return """
            {"queueLog":{"total":1,"success":true,"count":1,"row":[{"owner":"OWNERS",
            "moduleType":"Workflow Connector","moduleName":"Module-0036(23379903)",
            "workflowName":"Workflow-0424","priority":"normal","node":"ip-192-0-2-1",
            "globalPId":110219899,"startTime":1790861373734,"tag":"","workflowId":110219899,
            "status":{"level":0,"content":"Error"}}]}}""";
    }

    private List<String> auditOutcomes() throws IOException {
        List<String> outcomes = new ArrayList<>();
        try (Stream<Path> files = Files.list(temp.resolve("audit"))) {
            for (Path file : files.sorted().toList()) {
                for (String line : Files.readAllLines(file)) {
                    outcomes.add(JSON.readTree(line).path("outcome").asString());
                }
            }
        }
        return outcomes;
    }

    private void assertProcessLaunch(String execCommand) throws IOException {
        FakeStartCli.Recorded recorded = FakeStartCli.read(temp.resolve("cli")
            .resolve("argv-record.json"));
        assertThat(recorded.args()).containsSequence("--execCommand", execCommand)
            .noneMatch(arg -> arg.contains(PROCESS_PASSWORD));
        assertThat(new String(recorded.stdin(), StandardCharsets.UTF_8).strip())
            .isEqualTo(PROCESS_PASSWORD);
    }

    @Test
    void restartProcessWithItsCodeRunsThroughTheLaunch() throws IOException {
        stubReads(true, true, false);
        McpTestClient client = processControl("processErrorStart_ok", ConfirmationMode.SERVER);
        Map<String, Object> arguments = Map.of("node", "dev/node1", "processId", PID,
            "reason", "mapping fixed");

        JsonNode challenge = structured(client.callTool("restart_process", arguments))
            .path("challenge");
        JsonNode result = structured(client.callTool("restart_process",
            confirmed(arguments, challenge))).path("result");

        assertThat(challenge.path("confirmationCode").asString()).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("stateBefore").asString()).isEqualTo("ERROR");
        assertThat(result.path("stateAfter").asString()).isEqualTo("NOT_IN_QUEUE");
        assertThat(auditOutcomes()).containsExactly("CHALLENGE_ISSUED", "PENDING", "EXECUTED");
        assertProcessLaunch("processErrorStart " + PID);
    }

    @Test
    void killProcessRunsThroughTheLaunch() throws IOException {
        stubReads(true, false);
        McpTestClient client = processControl("kill_ok", ConfirmationMode.CLIENT);

        JsonNode result = structured(client.callTool("kill_process", Map.of(
            "node", "dev/node1", "processId", PID))).path("result");

        assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
        assertThat(result.path("action").asString()).isEqualTo("KILL");
        assertThat(result.path("message").asString()).isEqualTo("Process 110190387 killed.");
        assertThat(auditOutcomes()).containsExactly("PENDING", "EXECUTED");
        assertProcessLaunch("kill " + PID);
    }
}
