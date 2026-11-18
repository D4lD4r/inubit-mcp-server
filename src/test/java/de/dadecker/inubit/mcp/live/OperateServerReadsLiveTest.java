package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.fail;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.NodeSecrets;
import de.dadecker.inubit.mcp.adapter.aws.SsmParameterStore;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.HandshakeStartCliPinProbe;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.os.NoJob;
import de.dadecker.inubit.mcp.adapter.os.OsPrivateFileSystem;
import de.dadecker.inubit.mcp.adapter.os.PosixSystem;
import de.dadecker.inubit.mcp.adapter.os.WindowsSystem;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81VersionDetector;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.CliRead;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.Read;
import de.dadecker.inubit.mcp.live.OperateServerReadsLiveTestSupport.RestRead;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.ProcessRecorder;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
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
 * {@code cliTimeout} and trust store); the REST reads on the production client of the node
 * ({@link InubitHttpClient#create}: base URL, TLS trust store or pin, Basic credentials). Both
 * share one credential guard, and the credentials come from the credential store (or AWS
 * Parameter Store) as the server reads them. Before R1 the node's version is read from
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
        OperateServerReadsLiveTestSupport.requireNoTaskDirectory(recordDirectory);

        // the node's secrets, guard and clients as the wiring builds them (Wiring,
        // AdapterGatewayFactory); StartCLI's processes count as contained, as in every wiring test
        SecretScrubber scrubber = live.scrubber();
        Clock clock = Clock.systemUTC();
        NodeSecrets secrets = server.secretPlan().aws()
            ? NodeSecrets.aws(server.secretPlan(), new SsmParameterStore(), scrubber, clock)
            : NodeSecrets.credentialStore(server, LiveTarget.store(), scrubber, clock);
        NodeCredentials credentials = secrets.current(secrets.timeout());
        if (!credentials.complete()) {
            fail(node + " has no INUBIT username and password; nothing was run");
        }
        CredentialGuard guard =
            new CredentialGuard(server.secretLabels(), clock, secrets).bind(credentials);
        ProcessRecorder recorder = new ProcessRecorder(new SystemProcessLauncher());
        Map<String, ErrorCode> failed = new LinkedHashMap<>();
        // no maintenance probe: a 503 must not trigger a request outside the approved read set
        try (InubitHttpClient http = InubitHttpClient.create(server, credentials,
                MaintenanceProbe.NONE, scrubber, guard)) {
            InubitVersion version = OperateServerReadsLiveTestSupport.requireV81(node,
                reportedVersion(node, http));
            Path directory = OperateServerReadsLiveTestSupport.taskDirectory(files,
                recordDirectory);
            CliRunner cli = new CliRunner(recorder, environment, live.windows(),
                live.windows() ? new WindowsSystem() : new PosixSystem(), new NoJob(),
                new CliResources(live.loaded().config().profileInfo().name(), files),
                new HandshakeStartCliPinProbe(), id -> Optional.of(version));
            String username = credentials.username().orElseThrow().value();
            Secret password = credentials.password().orElseThrow().value();
            for (Read read : OperateServerReadsLiveTestSupport.READS) {
                String line = switch (read) {
                    case CliRead cliRead -> {
                        int before = recorder.runs().size();
                        Optional<ErrorCode> error = Optional.empty();
                        try {
                            cli.checkAvailable(server);
                            cli.run(server, username, password, cliRead.command(),
                                server.cliTimeout(), guard);
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
                                node, restRead, restRead.get(http));
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

    /**
     * The version {@code node} reports on {@code /system/info}, read as the version detection
     * does; a failure names only its code (the message may quote the answer).
     */
    private static String reportedVersion(NodeId node, InubitHttpClient http) {
        try {
            return V81VersionDetector.detect(node, http);
        } catch (ToolErrorException e) {
            throw new AssertionError("the version read of " + node + " before R1 failed: "
                + e.error().code() + "; nothing else was run");
        }
    }
}
