package de.dadecker.inubit.mcp.live;

import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.CliRead;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Read;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.RestRead;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.ProcessRecorder;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 022 T054, read set of the live checks L-2 and L-3 (research "Live checks"): the nine
 * owner-approved reads R1–R9 ({@link OperateServerReadsLiveTestSupport#READS}) against a real,
 * <b>non-production</b> INUBIT 8.1 node (opt-in, Constitution III), each run once in that order,
 * and their raw output recorded owner-only in {@code <INUBIT_LIVE_RECORD_DIR>/T054/}. Nothing is
 * switched: R1, R2 and R4–R7 only show a state or the help, R3, R8 and R9 are {@code GET}s.
 *
 * <p>The StartCLI reads run on the production path ({@link CliRunner#run}: the client chosen for
 * the detected 8.1 version, the allowlisted environment, the password on stdin, the node's
 * {@code cliTimeout} and trust store); the REST reads on the production client of the node (base
 * URL, TLS trust store or pin, Basic credentials); both through
 * {@link OperateServerLiveConnection}. Before R1 the node's version is read from
 * {@code /system/info} as the version detection does; a node that is not 8.1 fails the test
 * before anything else runs.
 *
 * <p>Run with {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_NODE=<group>/<node>
 * INUBIT_LIVE_T054_READS=approved INUBIT_LIVE_RECORD_DIR=<private dir> mvn verify -Plive
 * -Dtest=OperateServerReadsLiveTest}; skipped without any of the three variables. Prints one
 * line per read with its exit code or status and byte counts, never output.
 */
@Tag("live")
@Timeout(1800)
class OperateServerReadsLiveTest {

    @Test
    void theReadsOfL2AndL3AreRecordedOnceEach() throws IOException {
        LiveTarget live = LiveTarget.resolve();
        Map<String, String> environment = System.getenv();
        OperateServerReadsLiveTestSupport.requireApproval(environment);
        Path recordDirectory = OperateServerReadsLiveTestSupport.recordDirectory(environment,
            Path.of("").toAbsolutePath());
        NodeId node = live.node();
        EffectiveNodeConfig server = live.loaded().config().effectiveNodes().stream()
            .filter(candidate -> candidate.id().equals(node)).findFirst().orElseThrow();
        PrivateFileSystem files = OsPrivateFileSystem.forHost();
        // refuse an earlier recording before any contact; create the folder only after the 8.1 gate
        OperateServerReadsLiveTestSupport.requireNoTaskDirectory(recordDirectory,
            OperateServerReadsLiveTestSupport.TASK_DIRECTORY);
        Map<String, ErrorCode> failed = new LinkedHashMap<>();
        try (OperateServerLiveConnection connection =
                OperateServerLiveConnection.open(live, server, environment, files)) {
            InubitVersion version = connection.requireV81();
            Path directory = OperateServerReadsLiveTestSupport.taskDirectory(files,
                recordDirectory, OperateServerReadsLiveTestSupport.TASK_DIRECTORY);
            CliRunner cli = connection.cli(version);
            ProcessRecorder recorder = connection.recorder;
            for (Read read : OperateServerReadsLiveTestSupport.READS) {
                String line = switch (read) {
                    case CliRead cliRead -> {
                        int before = recorder.runs().size();
                        Optional<ErrorCode> error = Optional.empty();
                        try {
                            cli.checkAvailable(server);
                            cli.run(server, connection.username, connection.password,
                                cliRead.command(), server.cliTimeout(), connection.guard);
                        } catch (ToolErrorException e) {
                            error = Optional.of(e.error().code());
                            failed.put(read.id(), e.error().code());
                        }
                        List<Run> runs = recorder.runs();
                        yield OperateServerReadsLiveTestSupport.recordCli(files, directory, node,
                            cliRead, runs.subList(before, runs.size()), error);
                    }
                    case RestRead restRead -> {
                        try {
                            yield OperateServerReadsLiveTestSupport.recordRest(files, directory,
                                node, restRead, restRead.get(connection.http));
                        } catch (ToolErrorException e) {
                            failed.put(read.id(), e.error().code());
                            yield OperateServerReadsLiveTestSupport.recordRestFailure(files,
                                directory, node, restRead, e.error().code());
                        }
                    }
                };
                System.err.println(line);
            }
        }
        OperateServerReadsLiveTestSupport.assertNoFailure(failed);
    }
}
