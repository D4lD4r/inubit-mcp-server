package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T021 (feature 004, research D-16, SC-005): the StartCLI tag commands with the recorded
 * outputs — the exact command lines, always limited to one diagram group and technical
 * workflows; blank, empty and unquotable values refused before anything is launched; a failed
 * run is {@code IMPORT_FAILED}, a hanging one {@code TIMEOUT} naming {@code cliExportTimeout}.
 */
@Timeout(30)
class CliTagRunnerTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String PASSWORD = "S3cr3t-tag-pw";

    @TempDir
    Path cliHome;

    private final CredentialGuard guard = new CredentialGuard(new CredentialVariables("INUBIT",
        DEV), new MutableClock(Instant.parse("2026-10-07T08:00:00Z")));

    @BeforeEach
    void installFakeCli() throws IOException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
    }

    private CliTagRunner runner(ScriptedProcessLauncher cli) {
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliTagRunner(TestNodeConfig.node().cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).cliExportTimeout(Duration.ofMillis(500)).build(),
            credentials, guard, new CliRunner(cli, Map.of("PATH", "/usr/bin"), false,
                new CliResources("acme")), new CliOutputClassifier(new SecretScrubber()));
    }

    @Test
    void aTagIsSetForOneDiagramGroupOfTechnicalWorkflowsOfTheOwner() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("tag --tagMove 'REL-2026-10-07' --tagWorkflowGroup 'GRP-01'"
                + " --tagWorkflowType 'technical' --tagUser 'jdoe'").replying("tag_ok");

        runner(cli).tag("REL-2026-10-07", "GRP-01", "jdoe");

        cli.verifyComplete();
        assertThat(cli.execCommands()).containsExactly("tag --tagMove 'REL-2026-10-07'"
            + " --tagWorkflowGroup 'GRP-01' --tagWorkflowType 'technical' --tagUser 'jdoe'");
        assertThat(cli.launches().get(0).spec().command()).doesNotContain(PASSWORD);
        assertThat(new String(cli.launches().get(0).process().stdinBytes(),
            StandardCharsets.UTF_8)).isEqualTo(PASSWORD + "\n");
    }

    @Test
    void aTagIsRemovedForTheOwner() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("tag --tagDelete 'REL-2026-10-07' --tagUser 'jdoe'")
            .replying("tag_delete_ok");

        runner(cli).deleteTag("REL-2026-10-07", "jdoe");

        cli.verifyComplete();
        assertThat(cli.execCommands()).containsExactly("tag --tagDelete 'REL-2026-10-07'"
            + " --tagUser 'jdoe'");
    }

    @Test
    void blankEmptyOrUnquotableValuesAreRefusedBeforeAnythingIsLaunched() {
        // SC-005: StartCLI would tag every diagram of the owner without a group
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        CliTagRunner runner = runner(cli);
        for (List<String> values : List.of(List.of("T", "", "jdoe"), List.of("T", " ", "jdoe"),
            List.of("T", "G*", "jdoe"), List.of("T", "-x", "jdoe"), List.of("", "G", "jdoe"),
            List.of("T", "G", "O'Brien"), List.of("T'1", "G", "jdoe"))) {
            assertThatThrownBy(() -> runner.tag(values.get(0), values.get(1), values.get(2)))
                .as(values.toString()).isInstanceOfSatisfying(ToolErrorException.class,
                    e -> assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT));
        }
        assertThatThrownBy(() -> runner.deleteTag("", "jdoe"))
            .isInstanceOf(ToolErrorException.class);
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void aFailedRunIsImportFailedAndAHangingOneATimeout() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("tag ").replying("JAVA_HOME is set\nPassword: \n2-NOK: Tag failed.\n", "",
                1)
            .expect("tag ").replying("import_timeout").hanging();
        CliTagRunner runner = runner(cli);

        assertThatThrownBy(() -> runner.tag("T", "GRP-01", "jdoe"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.IMPORT_FAILED);
                assertThat(e.error().message()).contains("Tag failed.");
            });
        assertThatThrownBy(() -> runner.tag("T", "GRP-01", "jdoe"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT);
                assertThat(e.error().nextStep()).contains("cliExportTimeout");
            });
        cli.verifyComplete();
    }
}
