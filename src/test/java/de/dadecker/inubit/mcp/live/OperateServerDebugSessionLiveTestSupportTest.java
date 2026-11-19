package de.dadecker.inubit.mcp.live;

import static de.dadecker.inubit.mcp.testing.TestSecrets.CONFIGURED_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/**
 * Feature 022 T054 (debug clarification of L-3): the offline parts of
 * {@link OperateServerDebugSessionLiveTest} — the lines of the one session and what reaches
 * StartCLI, the opt-in, the task folder, the debug states derived from the transcript and the
 * lines and files. A plain unit test (not tagged {@code live}): fake environments, a fake
 * StartCLI, temporary directories and fake runs; nothing is contacted.
 */
class OperateServerDebugSessionLiveTestSupportTest {

    private static final NodeId NODE = NodeId.of("test", "node1");
    private static final String PROMPT = "CLI|jdoe@inubit-dev-1.example.test> ";
    private static final String OFF = "Debug mode is currently off.";
    private static final String ON = "Debug mode is currently on.";

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    private final Map<String, String> env = new HashMap<>();

    // --- the session -----------------------------------------------------------------------------

    @Test
    void theSessionIsTheFiveDebugLinesInOrderAndStaysOutsideTheAllowlist() {
        assertThat(OperateServerDebugSessionLiveTestSupport.SESSION)
            .extracting(CliSessionCommand::text)
            .containsExactly("debug", "debug on", "debug", "debug off", "debug");
        assertThat(OperateServerDebugSessionLiveTestSupport.SESSION_CASE.stem())
            .isEqualTo("S1-debug-session");
        assertThat(OperateServerDebugSessionLiveTestSupport.SESSION_CASE.form())
            .isEqualTo("session debug; debug on; debug; debug off; debug; exit");
        assertThat(OperateServerDebugSessionLiveTestSupport.AFTER.form()).isEqualTo("debug");
        assertThat(OperateServerDebugSessionLiveTestSupport.AFTER.stem()).isEqualTo("R6-debug");
        assertThatThrownBy(() -> CliSessionCommand.command("debug"))
            .isInstanceOf(ToolErrorException.class);
    }

    @Test
    void oneStartCliProcessReceivesThePasswordAndExactlyTheSessionLinesThenExit()
        throws IOException {
        Path cliHome = Files.createDirectories(temp.resolve("cli"));
        FakeStartCli.install(cliHome);
        EffectiveNodeConfig server = TestNodeConfig.node()
            .baseUrl("https://inubit-dev-1.example.test:8443")
            .cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17"))
            .build();
        FakeProcessLauncher launcher = FakeProcessLauncher.of(transcript(OFF, "", ON, "", OFF), "",
            0);
        CliRunner cli = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()));

        OperateServerDebugSessionLiveTestSupport.runSession(cli, server, "jdoe",
            Secret.of(CONFIGURED_PASSWORD), new CredentialGuard(server.secretLabels(),
                new MutableClock(Instant.parse("2026-11-18T08:00:00Z"))));

        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(CONFIGURED_PASSWORD + "\ndebug\ndebug on\ndebug\ndebug off\ndebug\nexit\n");
        assertThat(String.join(" ", launcher.last().spec().startCliArguments()))
            .doesNotContain(CONFIGURED_PASSWORD).doesNotContain("debug");
    }

    // --- gating ----------------------------------------------------------------------------------

    @Test
    void withoutTheApprovalTheSessionIsSkipped() {
        assertThatThrownBy(() -> OperateServerDebugSessionLiveTestSupport.requireApproval(env))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_T054_DEBUG_SESSION");
        env.put("INUBIT_LIVE_T054_DEBUG_SESSION", " ");
        assertThatThrownBy(() -> OperateServerDebugSessionLiveTestSupport.requireApproval(env))
            .isInstanceOf(TestAbortedException.class);
        // the approval of another T054 set does not count
        env.remove("INUBIT_LIVE_T054_DEBUG_SESSION");
        env.put("INUBIT_LIVE_T054_READS", "approved");
        assertThatThrownBy(() -> OperateServerDebugSessionLiveTestSupport.requireApproval(env))
            .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void anApprovalOtherThanApprovedFails() {
        for (String value : List.of("yes", "true", "approved,again", "APPROVED")) {
            env.put("INUBIT_LIVE_T054_DEBUG_SESSION", value);

            assertThatThrownBy(() -> OperateServerDebugSessionLiveTestSupport.requireApproval(env))
                .as(value)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_T054_DEBUG_SESSION must be exactly approved");
        }
    }

    @Test
    void theApprovedValuePasses() {
        env.put("INUBIT_LIVE_T054_DEBUG_SESSION", "approved");

        assertThatCode(() -> OperateServerDebugSessionLiveTestSupport.requireApproval(env))
            .doesNotThrowAnyException();
    }

    // --- folder ----------------------------------------------------------------------------------

    @Test
    void theTaskFolderIsItsOwnRefusedIfPresentAndCreatedOwnerOnly() throws IOException {
        Path records = temp.resolve("records/022");
        Files.createDirectories(records.resolve("T054"));
        Files.createDirectories(records.resolve("T054-writes"));
        String name = OperateServerDebugSessionLiveTestSupport.TASK_DIRECTORY;

        assertThat(name).isEqualTo("T054-debug-session");
        assertThatCode(() -> OperateServerReadsLiveTestSupport.requireNoTaskDirectory(records,
            name)).doesNotThrowAnyException();
        Path directory = OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            records, name);

        assertThat(directory).isEqualTo(records.resolve("T054-debug-session"));
        assertOwnerOnly(directory, "rwx------");
        assertThatThrownBy(() -> OperateServerReadsLiveTestSupport.requireNoTaskDirectory(records,
            name))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("T054-debug-session already exists")
            .hasMessageContaining("nothing was run");
    }

    // --- the debug states ------------------------------------------------------------------------

    @Test
    void theThreeReadsOfTheSessionGiveTheirStatesInOrder() {
        assertThat(OperateServerDebugSessionLiveTestSupport.sessionStates(
            transcript(OFF, "", ON, "", OFF)))
            .containsExactly("off", "on", "off");
        // the switches' own answers do not count, whatever they print
        assertThat(OperateServerDebugSessionLiveTestSupport.sessionStates(
            transcript(OFF, "Debug mode switched on.", OFF, "", OFF).replace("\n", "\r\n")))
            .containsExactly("off", "off", "off");
    }

    @Test
    void aReadWithAnyOtherAnswerIsUnknown() {
        assertThat(OperateServerDebugSessionLiveTestSupport.sessionStates(
            transcript("Execution Error", "", OFF + "\n" + ON, "", "Debug mode is on.")))
            .containsExactly("unknown", "unknown", "unknown");
        assertThat(OperateServerDebugSessionLiveTestSupport.sessionStates(
            transcript("", "", ON.toUpperCase(), "", " " + ON + " ")))
            .containsExactly("unknown", "unknown", "on");
    }

    @Test
    void aTranscriptThatDoesNotSplitIntoTheSessionsBlocksIsUnknownThroughout() {
        String missingPrompt = "Password: \n" + PROMPT + "debug\n" + OFF + "\n" + PROMPT + "exit\n";
        String outputAfterExit = transcript(OFF, "", ON, "", OFF) + "bye\n";

        for (String stdout : List.of("", missingPrompt, outputAfterExit)) {
            assertThat(OperateServerDebugSessionLiveTestSupport.sessionStates(stdout))
                .as(stdout).containsExactly("unknown", "unknown", "unknown");
        }
    }

    @Test
    void theReadAfterTheSessionGivesItsStateOrUnknown() {
        assertThat(OperateServerDebugSessionLiveTestSupport.state(List.of(OFF))).isEqualTo("off");
        assertThat(OperateServerDebugSessionLiveTestSupport.state(List.of(ON))).isEqualTo("on");
        for (List<String> other : List.of(List.<String>of(), List.of(ON, OFF),
            List.of("Debug mode is currently unknown."), List.of("debug: command not found"))) {
            assertThat(OperateServerDebugSessionLiveTestSupport.state(other)).as("%s", other)
                .isEqualTo("unknown");
        }
    }

    // --- recording -------------------------------------------------------------------------------

    @Test
    void theSessionIsRecordedOwnerOnlyAndItsLineHoldsNoTranscript() throws IOException {
        Path directory = taskDirectory();
        String stdout = transcript(OFF, "", ON, "", OFF);

        String line = OperateServerReadsLiveTestSupport.recordCli(PrivateFiles.forHost(),
            directory, NODE, OperateServerDebugSessionLiveTestSupport.SESSION_CASE,
            List.of(finished(stdout, 0)), Optional.empty());

        assertThat(fileNames(directory)).containsExactlyInAnyOrder("S1-debug-session.stdout",
            "S1-debug-session.stderr", "S1-debug-session.exit");
        assertThat(directory.resolve("S1-debug-session.stdout")).hasContent(stdout);
        assertAllOwnerOnlyFiles(directory);
        assertThat(line).isEqualTo("[live] T054 S1 session debug; debug on; debug; debug off;"
            + " debug; exit on test/node1: exit 0, stdout "
            + stdout.getBytes(StandardCharsets.UTF_8).length + " bytes, stderr 0 bytes");
        assertThat(line).doesNotContain("Debug mode", "CLI|", "Password");
    }

    @Test
    void theStatesAreRecordedOwnerOnlyAndTheirLineHoldsTheDerivedStatesOnly() throws IOException {
        Path directory = taskDirectory();

        String line = OperateServerDebugSessionLiveTestSupport.recordStates(
            PrivateFiles.forHost(), directory, NODE, List.of("off", "on", "off"), "off");

        assertThat(line).isEqualTo("[live] T054 debug states on test/node1: in session:"
            + " off → on → off; after session: off");
        assertThat(directory.resolve("debug-states.state"))
            .hasContent("in session: off → on → off; after session: off\n");
        assertAllOwnerOnlyFiles(directory);
        assertThat(OperateServerDebugSessionLiveTestSupport.recordStates(PrivateFiles.forHost(),
            taskDirectory("again"), NODE, List.of("unknown", "unknown", "unknown"),
            OperateServerDebugSessionLiveTestSupport.NOT_READ))
            .endsWith("in session: unknown → unknown → unknown; after session: not read");
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * The 8.1 session as the client prints it (research "Live recording T014"): the password
     * prompt, then per line of {@link OperateServerDebugSessionLiveTestSupport#SESSION} and
     * {@code exit} the prompt with the echoed line, followed by the answer, if any.
     */
    private static String transcript(String... answers) {
        StringBuilder stdout = new StringBuilder("Password: \n");
        List<CliSessionCommand> session = OperateServerDebugSessionLiveTestSupport.SESSION;
        for (int i = 0; i < session.size(); i++) {
            stdout.append(PROMPT).append(session.get(i).text()).append('\n');
            if (!answers[i].isEmpty()) {
                stdout.append(answers[i]).append('\n');
            }
        }
        return stdout.append(PROMPT).append("exit\n").toString();
    }

    private Path taskDirectory() throws IOException {
        return taskDirectory("records");
    }

    private Path taskDirectory(String records) throws IOException {
        return OperateServerReadsLiveTestSupport.taskDirectory(PrivateFiles.forHost(),
            temp.resolve(records), OperateServerDebugSessionLiveTestSupport.TASK_DIRECTORY);
    }

    private static Run finished(String stdout, int exit) {
        Run run = new Run(false);
        run.stdout().writeBytes(stdout.getBytes(StandardCharsets.UTF_8));
        run.finished(exit);
        return run;
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).toList();
        }
    }

    private static void assertOwnerOnly(Path path, String posixPermissions) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
                .as(path.toString()).isEqualTo(posixPermissions);
        } else {
            assertThat(PrivateFiles.forHost().check(path)).as(path.toString()).isEmpty();
        }
    }

    private static void assertAllOwnerOnlyFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                assertOwnerOnly(file, "rw-------");
            }
        }
    }
}
