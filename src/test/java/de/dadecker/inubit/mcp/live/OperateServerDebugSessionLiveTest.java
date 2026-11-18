package de.dadecker.inubit.mcp.live;

import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.CliRead;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.ProcessRecorder;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 022 T054, debug clarification of live check L-3: whether {@code debug on|off} is a
 * switch of the interactive StartCLI session rather than of the server on INUBIT 8.1. On a real,
 * <b>non-production</b> 8.1 node (opt-in, Constitution III) it opens exactly one interactive
 * session and sends {@code debug}, {@code debug on}, {@code debug}, {@code debug off},
 * {@code debug}, {@code exit} ({@link OperateServerDebugSessionLiveTestSupport#SESSION}), once,
 * then reads {@code debug} once more in a new StartCLI process (R6 of the read set). The raw
 * transcripts are recorded owner-only in {@code <INUBIT_LIVE_RECORD_DIR>/T054-debug-session/}.
 * Nothing is retried; the test records, it does not assert what INUBIT answers.
 *
 * <p>The session runs on the production session path ({@link CliRunner#runSession}, as the 8.1
 * session readers), the read after it on {@link CliRunner#run}; both through
 * {@link OperateServerLiveConnection}. Before the session the node's version is read from
 * {@code /system/info}; a node that is not 8.1 fails the test before anything else runs. A session
 * that ended in an error or a failed login is not followed by the read.
 *
 * <p>Run with {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_NODE=<group>/<node>
 * INUBIT_LIVE_T054_DEBUG_SESSION=approved INUBIT_LIVE_RECORD_DIR=<private dir> mvn verify -Plive
 * -Dtest=OperateServerDebugSessionLiveTest}; skipped without any of the three variables. Prints
 * the exit code and byte counts of both runs and the derived debug states, e.g.
 * {@code in session: off → on → off; after session: off}; never output.
 */
@Tag("live")
@Timeout(1800)
class OperateServerDebugSessionLiveTest {

    @Test
    void theDebugSwitchIsRecordedInOneSessionAndReadAfterIt() throws IOException {
        LiveTarget live = LiveTarget.resolve();
        Map<String, String> environment = System.getenv();
        OperateServerDebugSessionLiveTestSupport.requireApproval(environment);
        Path recordDirectory = OperateServerReadsLiveTestSupport.recordDirectory(environment,
            Path.of("").toAbsolutePath());
        NodeId node = live.node();
        EffectiveNodeConfig server = live.loaded().config().effectiveNodes().stream()
            .filter(candidate -> candidate.id().equals(node)).findFirst().orElseThrow();
        PrivateFileSystem files = OsPrivateFileSystem.forHost();
        // refuse an earlier recording before any contact; create the folder only after the 8.1 gate
        OperateServerReadsLiveTestSupport.requireNoTaskDirectory(recordDirectory,
            OperateServerDebugSessionLiveTestSupport.TASK_DIRECTORY);
        Map<String, ErrorCode> failed = new LinkedHashMap<>();
        try (OperateServerLiveConnection connection =
                OperateServerLiveConnection.open(live, server, environment, files)) {
            InubitVersion version = connection.requireV81();
            Path directory = OperateServerReadsLiveTestSupport.taskDirectory(files,
                recordDirectory, OperateServerDebugSessionLiveTestSupport.TASK_DIRECTORY);
            CliRunner cli = connection.cli(version);
            ProcessRecorder recorder = connection.recorder;

            int before = recorder.runs().size();
            Optional<CliResult> session = Optional.empty();
            Optional<ErrorCode> error = Optional.empty();
            try {
                session = Optional.of(OperateServerDebugSessionLiveTestSupport.runSession(cli,
                    server, connection.username, connection.password, connection.guard));
            } catch (ToolErrorException e) {
                error = Optional.of(e.error().code());
                failed.put(OperateServerDebugSessionLiveTestSupport.SESSION_CASE.id(),
                    e.error().code());
            }
            print(OperateServerReadsLiveTestSupport.recordCli(files, directory, node,
                OperateServerDebugSessionLiveTestSupport.SESSION_CASE, newRuns(recorder, before),
                error));
            List<String> inSession = session
                .map(result -> OperateServerDebugSessionLiveTestSupport.sessionStates(
                    result.stdout()))
                .orElse(Collections.nCopies(3, OperateServerDebugSessionLiveTestSupport.UNKNOWN));
            if (session.isPresent() && CliOutputClassifier.isLoginFailure(session.get())) {
                // a second login after a failed one is not sent
                failed.put(OperateServerDebugSessionLiveTestSupport.SESSION_CASE.id(),
                    ErrorCode.AUTH_FAILED);
            }

            String after = OperateServerDebugSessionLiveTestSupport.NOT_READ;
            if (failed.isEmpty()) {
                CliRead read = OperateServerDebugSessionLiveTestSupport.AFTER;
                before = recorder.runs().size();
                Optional<CliResult> result = Optional.empty();
                error = Optional.empty();
                try {
                    cli.checkAvailable(server);
                    result = Optional.of(cli.run(server, connection.username,
                        connection.password, read.command(), server.cliTimeout(),
                        connection.guard));
                } catch (ToolErrorException e) {
                    error = Optional.of(e.error().code());
                    failed.put(read.id(), e.error().code());
                }
                print(OperateServerReadsLiveTestSupport.recordCli(files, directory, node, read,
                    newRuns(recorder, before), error));
                CliOutputClassifier classifier = new CliOutputClassifier(live.scrubber(),
                    server.secretLabels(), live.windows());
                after = result.filter(answered -> answered.exitCode() == 0)
                    .map(answered -> OperateServerDebugSessionLiveTestSupport.state(
                        classifier.parse(answered).outputLines()))
                    .orElse(OperateServerDebugSessionLiveTestSupport.UNKNOWN);
            }
            print(OperateServerDebugSessionLiveTestSupport.recordStates(files, directory, node,
                inSession, after));
        }
        OperateServerReadsLiveTestSupport.assertNoFailure(failed);
    }

    private static List<Run> newRuns(ProcessRecorder recorder, int before) {
        List<Run> runs = recorder.runs();
        return runs.subList(before, runs.size());
    }

    private static void print(String line) {
        System.err.println(line);
    }
}
