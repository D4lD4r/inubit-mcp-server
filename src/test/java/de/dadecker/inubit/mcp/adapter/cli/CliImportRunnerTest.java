package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.OwnerKind;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ImportPort.Mode;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T013 (feature 004, research D-8): the StartCLI import with the recorded protocols — the exact
 * command line, the archive in a private temporary directory that is gone afterwards on every
 * path, the parsed protocol, NOK and timeout.
 */
@Timeout(30)
class CliImportRunnerTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String PASSWORD = "S3cr3t-import-pw";
    private static final byte[] ARCHIVE = {'P', 'K', 3, 4, 'i', 'm', 'p'};
    private static final Pattern IMPORT_FILE = Pattern.compile("--importFile '([^']+)'");

    @TempDir
    Path cliHome;
    @TempDir
    Path tempRoot;

    private final CredentialGuard guard = new CredentialGuard(new CredentialVariables("INUBIT",
        DEV), new MutableClock(Instant.parse("2026-10-07T08:00:00Z")));

    @BeforeEach
    void installFakeCli() throws IOException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
    }

    private CliImportRunner runner(ScriptedProcessLauncher cli) {
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliImportRunner(TestNodeConfig.node().cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).cliExportTimeout(Duration.ofMillis(500)).build(),
            credentials, guard, new CliRunner(cli, Map.of("PATH", "/usr/bin"), false,
                new CliResources("acme")), new CliOutputClassifier(new SecretScrubber()),
            tempRoot);
    }

    private List<Path> leftovers() throws IOException {
        try (Stream<Path> files = Files.list(tempRoot)) {
            return files.toList();
        }
    }

    private static Path importFile(String commandLine) {
        Matcher matcher = IMPORT_FILE.matcher(commandLine);
        assertThat(matcher.find()).as(commandLine).isTrue();
        return Path.of(matcher.group(1));
    }

    @Test
    void aWorkflowImportForAUserRunsTheExactCommandAndParsesTheProtocol() throws IOException {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect(Pattern.compile("^import --importFile '[^']+/import\\.zip' --importWorkflow"
                + " --importUser 'jdoe' --returnProtocol$"))
            .replying("import_created").capturingImportFile();

        ImportProtocol protocol = runner(cli).importArchive(ARCHIVE, Mode.WORKFLOW, "jdoe",
            OwnerKind.USER);

        assertThat(protocol.total()).isEqualTo(5);
        assertThat(protocol.created()).hasSize(5);
        assertThat(cli.launches().get(0).importFile()).isEqualTo(ARCHIVE);
        Path file = importFile(cli.execCommands().get(0));
        assertThat(file.getParent().getParent()).isEqualTo(tempRoot);
        assertThat(file).doesNotExist();
        assertThat(leftovers()).isEmpty();
        cli.verifyComplete();
    }

    @Test
    void theArchiveIsOwnerOnlyWhileStartCliRuns() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher().expect("import ")
            .replying("import_workflow_only").then(spec -> {
                Path file = importFile(ScriptedProcessLauncher.execCommand(spec));
                try {
                    assertThat(java.nio.file.attribute.PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(file.getParent()))).isEqualTo("rwx------");
                    assertThat(java.nio.file.attribute.PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

        runner(cli).importArchive(ARCHIVE, Mode.WORKFLOW, "jdoe", OwnerKind.USER);

        cli.verifyComplete();
    }

    @Test
    void modesAndOwnerKindsSelectTheOptions() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect(Pattern.compile("^import --importFile '[^']+' --importWorkflow"
                + " --importWorkflowActive --importUser 'jdoe' --returnProtocol$"))
            .replying("import_workflow_only")
            .expect(Pattern.compile("^import --importFile '[^']+' --importWorkflow"
                + " --importWorkflowInactive --importUser 'jdoe' --returnProtocol$"))
            .replying("import_workflow_only")
            .expect(Pattern.compile("^import --importFile '[^']+' --importModule"
                + " --importUserGroup 'OWNERS' --returnProtocol$"))
            .replying("import_module_only");
        CliImportRunner runner = runner(cli);

        runner.importArchive(ARCHIVE, Mode.WORKFLOW_ACTIVE, "jdoe", OwnerKind.USER);
        runner.importArchive(ARCHIVE, Mode.WORKFLOW_INACTIVE, "jdoe", OwnerKind.USER);
        ImportProtocol module = runner.importArchive(ARCHIVE, Mode.MODULE, "OWNERS",
            OwnerKind.USER_GROUP);

        assertThat(module.modified()).containsExactly("SPIKE_C_XSLT-Converter-01");
        cli.verifyComplete();
    }

    @Test
    void anNokResultIsAnImportFailureAndTheDirectoryIsGone() throws IOException {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher().expect("import ")
            .replying("import_nok");

        assertThatThrownBy(() -> runner(cli).importArchive(ARCHIVE, Mode.WORKFLOW, "jdoe",
            OwnerKind.USER)).isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.IMPORT_FAILED);
                assertThat(e.error().message()).contains("Import failed.");
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().toString()).doesNotContain(PASSWORD);
            });
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aTimeoutNamesCliExportTimeoutAndTheDirectoryIsGone() throws IOException {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher().expect("import ")
            .replying("import_timeout").hanging();

        assertThatThrownBy(() -> runner(cli).importArchive(ARCHIVE, Mode.WORKFLOW, "jdoe",
            OwnerKind.USER)).isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT);
                assertThat(e.error().nextStep()).contains("cliExportTimeout");
            });
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void anUnreadableProtocolIsAnImportFailure() throws IOException {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher().expect("import ")
            .replying("JAVA_HOME is set\nPassword: \nsomething else\n", "", 0);

        assertThatThrownBy(() -> runner(cli).importArchive(ARCHIVE, Mode.WORKFLOW, "jdoe",
            OwnerKind.USER)).isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.IMPORT_FAILED));
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void anOwnerStartCliQuotingCannotCarryIsRefusedBeforeAnythingIsWritten()
        throws IOException {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();

        assertThatThrownBy(() -> runner(cli).importArchive(ARCHIVE, Mode.WORKFLOW, "o'brien",
            OwnerKind.USER)).isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(cli.launches()).isEmpty();
        assertThat(leftovers()).isEmpty();
    }
}
