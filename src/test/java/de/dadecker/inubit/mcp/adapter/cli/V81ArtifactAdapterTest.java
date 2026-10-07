package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ArtifactAdapter;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T020: the 8.1 artifact port on top of the StartCLI exports. */
class V81ArtifactAdapterTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");

    @TempDir
    Path cliHome;
    @TempDir
    Path tempRoot;

    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void installFakeCli() throws IOException {
        Files.writeString(Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh"),
            "#!/bin/sh\n");
    }

    private ArtifactPort adapter(FakeProcessLauncher launcher) {
        CliRunner runner = new CliRunner(launcher, Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of("adapter-test-pw"), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new V81ArtifactAdapter(new CliExportRunner(TestNodeConfig.node()
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17")).build(), credentials,
            new CredentialGuard(new CredentialVariables("INUBIT", DEV),
                new MutableClock(Instant.parse("2026-10-06T08:00:00Z"))),
            runner, new CliOutputClassifier(new SecretScrubber()), tempRoot),
            () -> events.add("confirm"));
    }

    private FakeProcessLauncher exporting(String ok, byte[] zip) {
        return FakeProcessLauncher.of("1-OK: " + ok + " exported successfully.\n", "", 0)
            .onLaunch(spec -> {
                events.add("launch");
                List<String> command = spec.command();
                Matcher file = EXPORT_FILE.matcher(command.get(command.indexOf("--execCommand")
                    + 1));
                assertThat(file.find()).isTrue();
                try {
                    Files.write(Path.of(file.group(1)), zip);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
    }

    @Test
    void exportsTheTechnicalWorkflowsOfADiagramGroup() {
        byte[] zip = ArtifactFixtures.bytes("grp-a.zip");
        FakeProcessLauncher launcher = exporting("Workflow group", zip);

        assertThat(adapter(launcher).exportWorkflowGroup("jdoe", "GRP-01")).isEqualTo(zip);
        assertThat(launcher.last().spec().command()).anyMatch(argument -> argument.startsWith(
            "export --exportWorkflowUser 'jdoe' --exportWorkflowType 'technical'"
                + " --exportWorkflowGroup 'GRP-01'"));
        assertThat(events).as("credentials confirmed before StartCLI holds the guard")
            .containsExactly("confirm", "launch");
    }

    @Test
    void anInvalidRequestIsRefusedBeforeTheLoginAndStartCli() {
        FakeProcessLauncher launcher = exporting("Workflow group", new byte[0]);

        assertThatThrownBy(() -> adapter(launcher).exportWorkflowGroup("jdoe", " "))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> adapter(launcher).exportModule("jdoe", "XSLT Converter",
            "it's")).isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(events).isEmpty();
    }

    @Test
    void exportsASingleModule() {
        byte[] zip = ArtifactFixtures.bytes("module-one.zip");
        FakeProcessLauncher launcher = exporting("Module", zip);

        assertThat(adapter(launcher).exportModule("OWNERS", "XSLT Converter", "Module-0023"))
            .isEqualTo(zip);
    }

    @Test
    void aMissingDiagramGroupIsNotFoundForTheNode() {
        var recording = ArtifactFixtures.cliRecording("export-group-missing");
        FakeProcessLauncher launcher = FakeProcessLauncher.of(recording.stdout(),
            recording.stderr(), recording.exitCode());

        assertThatThrownBy(() -> adapter(launcher).exportWorkflowGroup("OWNERS",
            "MCP-FIXTURE-NO-SUCH-GROUP")).isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
                assertThat(e.error().node()).contains(DEV);
            });
    }

    // --- feature 005 (T007, research D-1, D-4) ----------------------------------------------

    @Test
    void exportsTheReleaseOfATagAfterConfirmingTheCredentials() {
        byte[] zip = FakeProcessLauncher.fixture("export_release.zip");
        FakeProcessLauncher launcher = exporting("Workflow group", zip);

        assertThat(adapter(launcher).exportRelease("jdoe", "TAG-01")).isEqualTo(zip);
        assertThat(launcher.last().spec().command()).anyMatch(argument -> argument.startsWith(
            "export --exportWorkflowUser 'jdoe' --exportWorkflowType 'technical'"
                + " --exportWorkflowGroup '' --exportTag 'TAG-01'"));
        assertThat(events).containsExactly("confirm", "launch");
    }

    @Test
    void exportsARepositoryPathAfterConfirmingTheCredentials() {
        byte[] zip = FakeProcessLauncher.fixture("export_repository.zip");
        FakeProcessLauncher launcher = exporting("Repository path", zip);

        assertThat(adapter(launcher).exportRepository("/Root/jdoe/xsd")).isEqualTo(zip);
        assertThat(launcher.last().spec().command()).anyMatch(argument -> argument.startsWith(
            "export --exportRepositoryPath '/Root/jdoe/xsd'"));
        assertThat(events).containsExactly("confirm", "launch");
    }

    @Test
    void anInvalidReleaseOrRepositoryRequestIsRefusedBeforeTheLogin() {
        FakeProcessLauncher launcher = exporting("Workflow group", new byte[0]);

        assertThatThrownBy(() -> adapter(launcher).exportRelease("jdoe", " "))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> adapter(launcher).exportRepository("/Root/../etc"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(events).isEmpty();
    }
}
