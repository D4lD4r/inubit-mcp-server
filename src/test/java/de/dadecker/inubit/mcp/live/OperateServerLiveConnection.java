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
import de.dadecker.inubit.mcp.adapter.os.PosixSystem;
import de.dadecker.inubit.mcp.adapter.os.WindowsSystem;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81VersionDetector;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.ProcessRecorder;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * The connection of the T054 live tests ({@link OperateServerReadsLiveTest},
 * {@link OperateServerWritesLiveTest}) and of T055 ({@link ManageTagsLiveTest}) to their node: the secrets, guard and clients as the wiring
 * builds them (Wiring, AdapterGatewayFactory). The credentials come from the credential store (or
 * AWS Parameter Store) as the server reads them; the StartCLI runs go through the production
 * {@link CliRunner} (the client chosen for the detected version, the allowlisted environment, the
 * password on stdin, the node's trust store), the REST requests through the production client of
 * the node ({@link InubitHttpClient#create}) without maintenance probe, so a 503 never triggers a
 * request outside the approved set. StartCLI's processes count as contained, as in every wiring
 * test.
 */
final class OperateServerLiveConnection implements AutoCloseable {

    private final LiveTarget live;
    private final Map<String, String> environment;
    private final PrivateFileSystem files;

    final EffectiveNodeConfig server;
    final CredentialGuard guard;
    /** For the production runners that take the credentials ({@code CliTagRunner} and others). */
    final NodeCredentials credentials;
    final String username;
    final Secret password;
    final ProcessRecorder recorder = new ProcessRecorder(new SystemProcessLauncher());
    final InubitHttpClient http;

    private OperateServerLiveConnection(LiveTarget live, Map<String, String> environment,
        PrivateFileSystem files, EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard) {
        this.live = live;
        this.environment = environment;
        this.files = files;
        this.server = server;
        this.guard = guard;
        this.credentials = credentials;
        this.username = credentials.username().orElseThrow().value();
        this.password = credentials.password().orElseThrow().value();
        this.http = InubitHttpClient.create(server, credentials, MaintenanceProbe.NONE,
            live.scrubber(), guard);
    }

    /**
     * Reads the credentials of {@code server}; fails without username and password before
     * anything is contacted.
     */
    static OperateServerLiveConnection open(LiveTarget live, EffectiveNodeConfig server,
        Map<String, String> environment, PrivateFileSystem files) {
        SecretScrubber scrubber = live.scrubber();
        Clock clock = Clock.systemUTC();
        NodeSecrets secrets = server.secretPlan().aws()
            ? NodeSecrets.aws(server.secretPlan(), new SsmParameterStore(), scrubber, clock)
            : NodeSecrets.credentialStore(server, LiveTarget.store(), scrubber, clock);
        NodeCredentials credentials = secrets.current(secrets.timeout());
        if (!credentials.complete()) {
            fail(server.id() + " has no INUBIT username and password; nothing was run");
        }
        CredentialGuard guard =
            new CredentialGuard(server.secretLabels(), clock, secrets).bind(credentials);
        return new OperateServerLiveConnection(live, environment, files, server, credentials,
            guard);
    }

    /**
     * The version the node reports on {@code /system/info}, read as the version detection does,
     * if it is 8.1; else, or if the read fails, the test fails and nothing else runs. A failure
     * names only its code (the message may quote the answer).
     */
    InubitVersion requireV81() {
        NodeId node = server.id();
        String reported;
        try {
            reported = V81VersionDetector.detect(node, http);
        } catch (ToolErrorException e) {
            throw new AssertionError("the version read of " + node + " failed: "
                + e.error().code() + "; nothing else was run");
        }
        return OperateServerReadsLiveTestSupport.requireV81(node, reported);
    }

    /** The production StartCLI runner for a node of {@code version}. */
    CliRunner cli(InubitVersion version) {
        return new CliRunner(recorder, environment, live.windows(),
            live.windows() ? new WindowsSystem() : new PosixSystem(), new NoJob(),
            new CliResources(live.loaded().config().profileInfo().name(), files),
            new HandshakeStartCliPinProbe(), id -> Optional.of(version));
    }

    @Override
    public void close() {
        http.close();
    }
}
