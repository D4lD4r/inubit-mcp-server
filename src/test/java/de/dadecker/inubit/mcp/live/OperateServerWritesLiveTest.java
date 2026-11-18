package de.dadecker.inubit.mcp.live;

import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Case;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.CliRead;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Read;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.RestRead;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Answer;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.CliWrite;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Outcome;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Property;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.ReadyRead;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.ReadySwitch;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.State;
import de.dadecker.inubit.mcp.live.OperateServerWritesLiveTestSupport.Step;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Feature 022 T054, write set of the live checks L-2 and L-3 (research "Live checks"): the
 * owner-approved writes W1–W10 ({@link OperateServerWritesLiveTestSupport#STEPS}) against a real,
 * <b>non-production</b> INUBIT 8.1 node whose profile enables writes (opt-in, Constitution III),
 * each run once in that order, and their raw output with the before and after state recorded
 * owner-only in {@code <INUBIT_LIVE_RECORD_DIR>/T054-writes/} (a later run with
 * {@code INUBIT_LIVE_T054_RUN=<n>} in {@code T054-writes-<n>/}).
 *
 * <p>The tool {@code operate_server} still refuses 8.1 until the coverage rows are flipped (T066,
 * which waits for this check), so the commands run raw: the StartCLI writes are the production
 * {@link CliCommand} forms on the production path ({@link CliRunner#run} with a write permit),
 * {@code POST /ready/on|off} is the production write request of the REST client to
 * {@link RestEndpoint#READY_SWITCH}; both through {@link OperateServerLiveConnection}. The state
 * after each step is read with the reads R1 ({@code maintenance -s}), R3 ({@code GET /ready}), R6
 * ({@code debug}) and R8 ({@code GET /system/info}) of the read set.
 *
 * <p>The baseline (maintenance off, READY, trace on at level info, debug off) must hold before W1,
 * else nothing is written. At the first error or deviation the sequence stops, the properties off
 * the baseline are restored with commands of the set, each once, and the test fails naming the
 * step and the code; at the end the full baseline is read again. W2b alone also counts an
 * answered 503, which the production write request reports as {@code UNREACHABLE}, if its effect
 * shows ({@link OperateServerWritesLiveTestSupport#readyOffTookEffect}).
 *
 * <p>Run with {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_NODE=<group>/<node>
 * INUBIT_LIVE_T054_WRITES=W1,W2a,W2b,W3,W2u,W4,W5,W6,W7,W7b,W8,W8b,W9,W10
 * INUBIT_LIVE_RECORD_DIR=<private dir> mvn verify -Plive -Dtest=OperateServerWritesLiveTest}, only
 * after the owner approved each command; skipped without any of the three variables. Prints one
 * line per command with its exit code or status and byte counts, and the derived states; never
 * output.
 */
@Tag("live")
@Timeout(3600)
class OperateServerWritesLiveTest {

    @Test
    void theWritesOfL2AndL3RunOnceEachAndLeaveTheBaseline() throws IOException {
        LiveTarget live = LiveTarget.resolve();
        Map<String, String> environment = System.getenv();
        OperateServerWritesLiveTestSupport.requireApproval(environment);
        Path recordDirectory = OperateServerReadsLiveTestSupport.recordDirectory(environment,
            Path.of("").toAbsolutePath());
        NodeId node = live.node();
        EffectiveNodeConfig server = live.loaded().config().effectiveNodes().stream()
            .filter(candidate -> candidate.id().equals(node)).findFirst().orElseThrow();
        OperateServerWritesLiveTestSupport.requireWriteEnabled(node,
            server.effectiveWriteEnabled());
        String taskDirectory = OperateServerWritesLiveTestSupport.taskDirectory(environment);
        PrivateFileSystem files = OsPrivateFileSystem.forHost();
        // refuse an earlier recording before any contact; create the folder only after the 8.1 gate
        OperateServerReadsLiveTestSupport.requireNoTaskDirectory(recordDirectory, taskDirectory);
        Outcome outcome;
        try (OperateServerLiveConnection connection =
                OperateServerLiveConnection.open(live, server, environment, files)) {
            InubitVersion version = connection.requireV81();
            Path directory = OperateServerReadsLiveTestSupport.taskDirectory(files,
                recordDirectory, taskDirectory);
            outcome = OperateServerWritesLiveTestSupport.run(new LiveNode(connection,
                connection.cli(version), new CliOutputClassifier(live.scrubber(),
                    server.secretLabels(), live.windows()),
                new WritePermit(node, Capability.SERVER_OPERATION, version), files, directory));
        }
        OperateServerWritesLiveTestSupport.assertOutcome(outcome);
    }

    /** The node of the sequence: every call records its case files and prints its line. */
    private static final class LiveNode implements OperateServerWritesLiveTestSupport.Node {

        private final OperateServerLiveConnection connection;
        private final CliRunner cli;
        private final CliOutputClassifier classifier;
        private final WritePermit permit;
        private final PrivateFileSystem files;
        private final Path directory;
        private final NodeId node;

        LiveNode(OperateServerLiveConnection connection, CliRunner cli,
            CliOutputClassifier classifier, WritePermit permit, PrivateFileSystem files,
            Path directory) {
            this.connection = connection;
            this.cli = cli;
            this.classifier = classifier;
            this.permit = permit;
            this.files = files;
            this.directory = directory;
            this.node = connection.server.id();
        }

        @Override
        public State read(String label, Set<Property> properties) throws IOException {
            State state = State.NONE;
            for (Read read : OperateServerWritesLiveTestSupport.stateReads(properties)) {
                String id = label + "-" + read.id();
                State shown = switch (read) {
                    case CliRead cliRead -> {
                        Attempt attempt = runCli(new CliRead(id, read.slug(), cliRead.command()),
                            cliRead.command(), Optional.empty());
                        // a read that ended in an error or an exit code other than 0 shows nothing
                        Optional<CliResult> answered =
                            attempt.result().filter(result -> result.exitCode() == 0);
                        yield answered.isEmpty() ? State.NONE
                            : OperateServerWritesLiveTestSupport.parseCli(read.id(),
                                classifier.parse(answered.get()).outputLines());
                    }
                    case RestRead restRead -> {
                        RestRead labelled = new RestRead(id, read.slug(), restRead.endpoint(),
                            restRead.authenticated(), restRead.range());
                        try {
                            RestResponse response = restRead.get(connection.http);
                            print(OperateServerReadsLiveTestSupport.recordRest(files, directory,
                                node, labelled, response));
                            yield OperateServerWritesLiveTestSupport.parseRest(read.id(),
                                response.status(), response.body());
                        } catch (ToolErrorException e) {
                            print(OperateServerReadsLiveTestSupport.recordRestFailure(files,
                                directory, node, labelled, e.error().code()));
                            yield State.NONE;
                        }
                    }
                };
                state = state.with(shown);
            }
            State derived = state.only(properties);
            print(OperateServerWritesLiveTestSupport.stateLine(node, label, derived, properties));
            return derived;
        }

        @Override
        public Answer run(Step step) throws IOException {
            return switch (step.action()) {
                case CliWrite write -> {
                    Attempt attempt = runCli(step, write.command(), Optional.of(permit));
                    yield Answer.of(OperateServerWritesLiveTestSupport.failure(attempt.error(),
                        attempt.result().map(CliResult::exitCode)));
                }
                case ReadySwitch ready -> {
                    try {
                        RestResponse response = connection.http.post(RestEndpoint.READY_SWITCH,
                            List.of(ready.state()), permit, cli.writeLock());
                        print(OperateServerReadsLiveTestSupport.recordRest(files, directory, node,
                            step, response));
                        yield new Answer(Optional.empty(), Optional.of(response.status()),
                            State.NONE);
                    } catch (ToolErrorException e) {
                        print(OperateServerWritesLiveTestSupport.recordSwitchFailure(files,
                            directory, node, step, e.error()));
                        yield new Answer(Optional.of(e.error().code().name()),
                            OperateServerWritesLiveTestSupport.answeredStatus(e.error()),
                            State.NONE);
                    }
                }
                case ReadyRead get -> {
                    try {
                        RestResponse response = get.read().get(connection.http);
                        State shown = OperateServerWritesLiveTestSupport.parseRest(get.read().id(),
                            response.status(), response.body());
                        print(OperateServerReadsLiveTestSupport.recordRest(files, directory, node,
                            step, response) + ", " + shown.describe(Set.of(Property.READINESS)));
                        yield new Answer(Optional.empty(), Optional.of(response.status()), shown);
                    } catch (ToolErrorException e) {
                        print(OperateServerReadsLiveTestSupport.recordRestFailure(files,
                            directory, node, step, e.error().code()));
                        yield Answer.of(Optional.of(e.error().code().name()));
                    }
                }
            };
        }

        @Override
        public void recordStates(Step step, State before, State after, Optional<String> note)
            throws IOException {
            print(OperateServerWritesLiveTestSupport.recordStates(files, directory, node, step,
                before, after, note));
        }

        @Override
        public void recordNotNeeded(Step step, State check) throws IOException {
            print(OperateServerWritesLiveTestSupport.recordNotNeeded(files, directory, node, step,
                check));
        }

        /** One StartCLI run of {@code command}, recorded as the case {@code recorded}. */
        private Attempt runCli(Case recorded, CliCommand command, Optional<WritePermit> write)
            throws IOException {
            int before = connection.recorder.runs().size();
            Optional<CliResult> result = Optional.empty();
            Optional<ErrorCode> error = Optional.empty();
            try {
                cli.checkAvailable(connection.server);
                result = Optional.of(write.isPresent()
                    ? cli.run(connection.server, connection.username, connection.password,
                        command, connection.server.cliTimeout(), connection.guard, write.get())
                    : cli.run(connection.server, connection.username, connection.password,
                        command, connection.server.cliTimeout(), connection.guard));
            } catch (ToolErrorException e) {
                error = Optional.of(e.error().code());
            }
            List<Run> runs = connection.recorder.runs();
            print(OperateServerReadsLiveTestSupport.recordCli(files, directory, node, recorded,
                runs.subList(before, runs.size()), error));
            return new Attempt(result, error);
        }

        private static void print(String line) {
            System.err.println(line);
        }
    }

    /** A StartCLI run: its result, or the error that ended it. */
    private record Attempt(Optional<CliResult> result, Optional<ErrorCode> error) {
    }
}
