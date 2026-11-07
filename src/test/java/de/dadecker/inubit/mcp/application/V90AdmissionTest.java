package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.application.VersionGate.Admission;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T032 (feature 017, US1, SC-001, SC-004, research D-9): every tool on a node pinned to
 * {@code V9_0} and on an {@code AUTO} node detected as 9.0.7 runs like on 8.1: reads without the
 * "not verified" warning, process control, development writes and the end-to-end test admitted,
 * and StartCLI started at the base URL (no servlet path). An INUBIT version outside the matrix
 * (9.1) is the contrast: reads warned, writes refused.
 */
class V90AdmissionTest {

    private static final NodeId PINNED = NodeId.parse("prod/node1");
    private static final NodeId AUTO = NodeId.parse("dev/node1");
    private static final NodeId NEWER = NodeId.parse("qa/node1");
    private static final String BASE_URL = "https://inubit-acme.example.test:8443";
    private static final Path JDK_17 = Path.of("/opt/jdk-17");
    private static final Path JDK_21 = Path.of("/opt/jdk-21");

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-06T08:00:00Z"));
    private final NodeVersions versions = new NodeVersions(clock, Map.of(
        PINNED, VersionSetting.V9_0, AUTO, VersionSetting.AUTO, NEWER, VersionSetting.AUTO)::get,
        node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions)
        .answer(AUTO, "9.0.7").answer(NEWER, "9.1.0");

    private VersionGate gate() {
        // every node has a 9.0 StartCLI client whose range covers 9.0.0 to 9.0.7
        return new VersionGate(versions, detection, CapabilityMatrix.production(),
            (node, version, timeout) -> new ProbedClient(ClientKey.NONE, new ClientVersion(
                version("9.0.7"), Optional.of(version("9.0.0")), Optional.of(version("9.0.7")))),
            TestClientSelection.of(node -> true), node -> Duration.ofSeconds(45),
            Terminology.DEFAULT);
    }

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    /** The tools of each capability (spec, tool classification). */
    static Stream<Arguments> tools() {
        return Stream.of(
            Arguments.of("find_processes", Capability.PROCESS_DIAGNOSIS),
            Arguments.of("query_logs", Capability.LOG_DIAGNOSIS),
            Arguments.of("list_inventory", Capability.INVENTORY),
            Arguments.of("get_inventory_item", Capability.INVENTORY),
            Arguments.of("export_workspace", Capability.WORKSPACE_EXPORT),
            Arguments.of("restart_process", Capability.PROCESS_CONTROL),
            Arguments.of("kill_process", Capability.PROCESS_CONTROL),
            Arguments.of("import_workspace", Capability.DEVELOPMENT_WRITE),
            Arguments.of("tag_diagram_group", Capability.DEVELOPMENT_WRITE),
            Arguments.of("run_e2e_test", Capability.E2E_TEST),
            Arguments.of("deploy_release", Capability.DEPLOYMENT));
    }

    @ParameterizedTest
    @MethodSource("tools")
    void aNodePinnedToV90RunsEveryToolWithoutAVersionWarning(String tool,
        Capability capability) {
        Admission admission = gate().admit(tool, PINNED, capability, capability.kind(),
            capability.channels());

        assertThat(admission.warnings()).as(tool).isEmpty();
        assertThat(admission.version()).map(InubitVersion::raw).contains("9.0");
        assertThat(detection.requests()).as("a pinned line is not detected").isEmpty();
    }

    @ParameterizedTest
    @MethodSource("tools")
    void anAutoNodeDetectedAs907RunsEveryToolWithoutAVersionWarning(String tool,
        Capability capability) {
        Admission admission = gate().admit(tool, AUTO, capability, capability.kind(),
            capability.channels());

        assertThat(admission.warnings()).as(tool).isEmpty();
        assertThat(admission.version()).contains(version("9.0.7"));
        assertThat(detection.requests()).containsExactly(AUTO);
    }

    @Test
    void monitoringOf90CarriesNoVersionWarning() {
        assertThat(gate().admitMonitoring(PINNED, Optional.of(version("9.0.7"))).warnings())
            .isEmpty();
        assertThat(gate().admitMonitoring(AUTO, Optional.of(version("9.0.7"))).warnings())
            .isEmpty();
    }

    @Test
    void aVersionOutsideTheMatrixIsTheContrastReadsWarnedWritesRefused() {
        Admission read = gate().admit("find_processes", NEWER, Capability.PROCESS_DIAGNOSIS,
            Capability.Kind.READ, Capability.PROCESS_DIAGNOSIS.channels());
        ToolErrorException write = catchThrowableOfType(ToolErrorException.class,
            () -> gate().admit("restart_process", NEWER, Capability.PROCESS_CONTROL,
                Capability.Kind.WRITE, Capability.PROCESS_CONTROL.channels()));

        assertThat(read.warnings()).singleElement().asString().contains("9.1.0");
        assertThat(write).isNotNull();
        assertThat(write.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
    }

    // --- StartCLI address (SC-004) -------------------------------------------------------------

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX launch; the Windows address is"
        + " covered by CliRunnerWindowsSelectTest")
    void startCliOf90IsLaunchedAtTheBaseUrlWithTheNineZeroClient() throws IOException {
        Path client81 = Files.createDirectories(root.resolve("client-8.1"));
        Path client90 = Files.createDirectories(root.resolve("client-9.0"));
        FakeStartCli.install(client81);
        FakeStartCli.install(client90);
        gate().admit("restart_process", AUTO, Capability.PROCESS_CONTROL, Capability.Kind.WRITE,
            Capability.PROCESS_CONTROL.channels());

        for (NodeId node : List.of(PINNED, AUTO)) {
            EffectiveNodeConfig server = TestNodeConfig.node().id(node.value()).baseUrl(BASE_URL)
                .versionLine(node.equals(PINNED) ? VersionSetting.V9_0 : VersionSetting.AUTO)
                .cliClient("8.1", client81, Optional.of(JDK_17))
                .cliClient("9.0", client90, Optional.of(JDK_21)).build();
            FakeProcessLauncher launcher = FakeProcessLauncher.of("1-OK: Process 1 restarted.\n",
                "", 0);
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost()), versions::effectiveVersion)
                .run(server, "jdoe", Secret.of("v90-admission-pw"),
                    CliCommand.processErrorStart("1"), Duration.ofSeconds(5),
                    new CredentialGuard(server.secretLabels(), clock));

            ProcessLauncher.LaunchSpec spec = launcher.last().spec();
            assertThat(spec.command()).as(node.value()).last().isEqualTo(BASE_URL);
            assertThat(spec.command().get(0)).as(node.value())
                .isEqualTo(client90.resolve("bin/startcli.sh").toString());
            assertThat(spec.environment()).as(node.value())
                .containsEntry("JAVA_HOME", JDK_21.toString());
        }
    }
}
