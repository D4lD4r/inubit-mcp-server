package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81MonitoringAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81VersionDetector;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.VersionObserver;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selects the adapter set of each server by its {@code versionLine} (Constitution V, FR-029).
 *
 * <ul>
 *   <li>A configured {@code versionLine} ({@code V8_1}, {@code V9_X} or a pinned version): the
 *       8.1 adapters, the only ones that exist in this version.
 *   <li>{@code AUTO}: resolved lazily on the first {@link #forServer} call via
 *       {@code /system/info} and cached per server. If the detection fails, the call still
 *       returns the 8.1 adapters. That fallback is cached for {@link #FAILED_DETECTION_TTL}
 *       (60 s): within the window the detection is not retried, so that rejected credentials
 *       ({@code AUTH_FAILED}, {@code FORBIDDEN}) do not cause one extra failed login per call
 *       (account lockout). The first call after the window detects again; a successful
 *       detection replaces the fallback for good.
 * </ul>
 *
 * <p>The gateways carry no version warnings (feature 009): what a version means for a tool call
 * is decided by the version gate from the outcomes reported to the {@link VersionObserver}.
 *
 * <p>Each server gets one {@link InubitHttpClient}, created lazily with the version line's
 * maintenance probe (8.1: {@link V81MaintenanceProbe}; never {@code MaintenanceProbe.NONE}), so
 * that HTTP 503 during maintenance is reported as {@code MAINTENANCE_MODE}.
 *
 * <p>Port-set selection (feature 009 research D-16): the {@link PortSet} of a server is built by
 * the {@link PortSetRegistry} factory for the node's effective version, read from the
 * {@link VersionObserver} whenever ports are requested ({@link #processes}, {@link #logs},
 * {@link #inventory}, {@link #processControl}, {@link #artifacts}, {@link #imports},
 * {@link #tags} and the gateway's port methods). The selection itself never detects: the version
 * gate detects before it admits a call, so the ports requested after the admission belong to the
 * detected version. The port set is built once per selected range and rebuilt when the effective
 * version moves to another range (e.g. after a re-detection). The monitoring port (US1) is not
 * part of the port set and is handed out without waiting for a detection. The StartCLI-backed
 * ports run with the factory's {@link CliRunner} and the server's {@link CredentialGuard}, shared
 * with its REST client. Production registers only the 8.1 port set, as default for every version
 * ({@link PortSetRegistry#v81Default()}).
 *
 * <p>Version reporting (feature 009 research D-4, D-10): every {@code AUTO} detection of a slot
 * (through {@link #forServer} or the monitoring's {@code /system/info}) and every
 * {@link #detect} report their outcome to the {@link VersionObserver}; a cancelled call is not
 * reported. Every port handed out is wrapped by {@link StaleReportingPorts}, which reports
 * {@code UNEXPECTED_RESPONSE} failures as stale.
 */
public final class AdapterGatewayFactory implements GatewayFactory, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AdapterGatewayFactory.class);

    private final Map<NodeId, Slot> slots = new LinkedHashMap<>();
    private final SecretScrubber scrubber;
    private final Clock clock;
    private final CliRunner cliRunner;
    private final VersionObserver versions;
    private final PortSetRegistry portSets;

    /** How long the 8.1 fallback of a failed {@code AUTO} detection is used before a retry. */
    public static final Duration FAILED_DETECTION_TTL = Duration.ofSeconds(60);

    /**
     * @param servers     the validated servers, in config order
     * @param credentials their resolved credentials
     * @param clock       decides when a failed detection is retried
     * @param cliRunner   the StartCLI runner of the CLI-backed ports (US3 exports)
     * @param versions    receives the detections, failed detections and stale triggers, and
     *     answers the effective version that selects the port set
     * @param portSets    the port-set factory of each version range, plus the default
     */
    public AdapterGatewayFactory(List<EffectiveNodeConfig> servers,
        CredentialResolution credentials, SecretScrubber scrubber, Clock clock,
        CliRunner cliRunner, VersionObserver versions, PortSetRegistry portSets) {
        this.cliRunner = Objects.requireNonNull(cliRunner, "cliRunner");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.portSets = Objects.requireNonNull(portSets, "portSets");
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (EffectiveNodeConfig server : servers) {
            NodeCredentials serverCredentials = credentials.all().stream()
                .filter(c -> c.node().equals(server.id()))
                .findFirst()
                .orElseGet(() -> new NodeCredentials(server.id(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            slots.put(server.id(), new Slot(server, serverCredentials));
        }
    }

    @Override
    public Gateway forServer(NodeId server) {
        return slot(server).gateway();
    }

    @Override
    public InubitVersion detect(NodeId server) {
        return slot(server).detectNow();
    }

    private Slot slot(NodeId server) {
        Slot slot = slots.get(server);
        if (slot == null) {
            throw new ToolErrorException(ToolError.of(ErrorCode.TARGET_UNKNOWN,
                "No configuration for " + server + ". Configured ids: "
                    + slots.keySet().stream().map(NodeId::value)
                        .collect(Collectors.joining(", ")),
                "The id is misspelled or missing in the configuration",
                "Call list_nodes for the valid ids"));
        }
        return slot;
    }

    @Override
    public MonitoringPort monitoring(NodeId server) {
        return slot(server).monitoring();
    }

    @Override
    public ProcessQueryPort processes(NodeId server) {
        return slot(server).ports().processes();
    }

    @Override
    public LogPort logs(NodeId server) {
        return slot(server).ports().logs();
    }

    @Override
    public InventoryPort inventory(NodeId server) {
        return slot(server).ports().inventory();
    }

    @Override
    public ArtifactPort artifacts(NodeId server) {
        return slot(server).ports().artifacts();
    }

    @Override
    public ImportPort imports(NodeId server) {
        return slot(server).ports().imports();
    }

    @Override
    public TagPort tags(NodeId server) {
        return slot(server).ports().tags();
    }

    @Override
    public ProcessControlPort processControl(NodeId server) {
        return slot(server).ports().processControl();
    }

    @Override
    public Optional<Gateway> knownGateway(NodeId server) {
        return slot(server).knownGateway();
    }

    @Override
    public void close() {
        slots.values().forEach(Slot::close);
    }

    /**
     * The lazily created client, monitoring adapter and credential guard of one server, its
     * selected port set and its cached gateway. Two {@link ReentrantLock}s (not
     * {@code synchronized}, so that waiting virtual threads do not pin their carrier):
     * {@code clientLock} for the quick creation of the client and the port set, {@code lock} for
     * the gateway state, held during a blocking detection. Neither the monitoring port nor the
     * port set waits for {@code lock}.
     */
    private final class Slot implements V81MonitoringAdapter.SystemInfoListener {

        private final EffectiveNodeConfig server;
        private final NodeCredentials credentials;
        private final CredentialGuard guard;
        private final StaleReportingPorts stale;
        private final ReentrantLock clientLock = new ReentrantLock();
        private final ReentrantLock lock = new ReentrantLock();
        private V81MaintenanceProbe probe;
        private InubitHttpClient client;
        private MonitoringPort monitoring;
        private PortSet ports;
        /** The registry range {@link #ports} was built for; empty for the default. */
        private Optional<VersionRange> portsRange;
        private V81Gateway gateway;
        /** Set while {@link #gateway} is the fallback of a failed detection. */
        private Instant fallbackUntil;

        Slot(EffectiveNodeConfig server, NodeCredentials credentials) {
            this.server = server;
            this.credentials = credentials;
            this.guard = new CredentialGuard(server.credentialVariables(), clock);
            this.stale = new StaleReportingPorts(server.id(), versions);
        }

        Gateway gateway() {
            lock.lock();
            try {
                if (valid()) {
                    return gateway;
                }
                ensureClient();
                return auto() ? detect() : cache(Optional.empty());
            } finally {
                lock.unlock();
            }
        }

        MonitoringPort monitoring() {
            ensureClient();
            return monitoring;
        }

        /**
         * The port set for the node's current effective version, built on the first request and
         * rebuilt when the version has moved to another registry range; no network access.
         */
        PortSet ports() {
            PortSetRegistry.Selection selection =
                portSets.select(versions.effectiveVersion(server.id()));
            clientLock.lock();
            try {
                ensureClient();
                if (ports == null || !selection.range().equals(portsRange)) {
                    ports = stale.ports(selection.factory().create(new PortSetRegistry
                        .NodeResources(server, credentials, guard, client, cliRunner, scrubber)));
                    portsRange = selection.range();
                }
                return ports;
            } finally {
                clientLock.unlock();
            }
        }

        Optional<Gateway> knownGateway() {
            if (!lock.tryLock()) {
                return Optional.empty(); // a detection is running
            }
            try {
                if (valid()) {
                    return Optional.of(gateway);
                }
                if (auto()) {
                    return Optional.empty();
                }
                ensureClient();
                return Optional.of(cache(Optional.empty()));
            } finally {
                lock.unlock();
            }
        }

        private boolean auto() {
            return server.versionLine() instanceof VersionSetting.Auto;
        }

        private boolean valid() {
            return gateway != null
                && (fallbackUntil == null || clock.instant().isBefore(fallbackUntil));
        }

        /**
         * Detects the version; a real failure gives the 8.1 gateway, cached for
         * {@link #FAILED_DETECTION_TTL}. A cancelled (interrupted) detection is neither cached
         * nor reported.
         */
        private Gateway detect() {
            String version;
            try {
                version = V81VersionDetector.detect(server.id(), client);
            } catch (ToolErrorException e) {
                if (Thread.currentThread().isInterrupted()) {
                    return new V81Gateway(server.id(), Optional.empty(), List.of(), client,
                        monitoring, this::ports, guard);
                }
                report(e.error().code());
                return fallback(e.error().code());
            }
            report(version);
            return detected(version);
        }

        /**
         * {@link GatewayFactory#detect}: one detection regardless of the cached gateway or the
         * fallback window. For {@code AUTO} the result also updates the adapter selection: a
         * success replaces the gateway, a failure installs the fallback only if no valid gateway
         * is cached (a known version is kept).
         */
        InubitVersion detectNow() {
            lock.lock();
            try {
                ensureClient();
                String version;
                try {
                    version = V81VersionDetector.detect(server.id(), client);
                } catch (ToolErrorException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        report(e.error().code());
                        if (auto() && !valid()) {
                            fallback(e.error().code());
                        }
                    }
                    throw e;
                }
                Optional<InubitVersion> parsed = report(version);
                if (auto()) {
                    detected(version);
                }
                return parsed.orElseThrow(() -> new ToolErrorException(ToolError.of(
                    ErrorCode.UNEXPECTED_RESPONSE,
                    "The INUBIT version " + Names.quote(version) + " of " + server.id()
                        + " has no major.minor",
                    "The system information has an unexpected format",
                    "Check the INUBIT server, or run get_health").withNode(server.id())));
            } finally {
                lock.unlock();
            }
        }

        /** Reports a detected version; one without major.minor is a failed detection. */
        private Optional<InubitVersion> report(String version) {
            Optional<InubitVersion> parsed = InubitVersion.parse(version);
            parsed.ifPresentOrElse(v -> versions.detected(server.id(), v),
                () -> report(ErrorCode.UNEXPECTED_RESPONSE));
            return parsed;
        }

        private void report(ErrorCode failure) {
            versions.detectionFailed(server.id(), failure);
        }

        /**
         * The health call's {@code /system/info} completes an {@code AUTO} detection (and reports
         * it); once detected, later health calls are observations, reported by the health
         * service, not here.
         */
        @Override
        public void succeeded(SystemInfo info) {
            if (!auto() || !lock.tryLock()) {
                return;
            }
            try {
                if (gateway != null && fallbackUntil == null) {
                    return; // already detected
                }
                if (info.version().isPresent()) {
                    report(info.version().get());
                    detected(info.version().get());
                } else if (!valid()) {
                    report(ErrorCode.UNEXPECTED_RESPONSE);
                    fallback(ErrorCode.UNEXPECTED_RESPONSE);
                }
            } finally {
                lock.unlock();
            }
        }

        /** A real failure of the health call's {@code /system/info} is a failed detection. */
        @Override
        public void failed(ToolError error) {
            if (!auto() || Thread.currentThread().isInterrupted() || !lock.tryLock()) {
                return;
            }
            try {
                if (!valid()) {
                    report(error.code());
                    fallback(error.code());
                }
            } finally {
                lock.unlock();
            }
        }

        private Gateway detected(String version) {
            return cache(Optional.of(version));
        }

        private Gateway fallback(ErrorCode code) {
            LOG.warn("{}: INUBIT version could not be detected ({})", server.id(), code);
            gateway = new V81Gateway(server.id(), Optional.empty(), List.of(), client,
                monitoring, this::ports, guard);
            fallbackUntil = clock.instant().plus(FAILED_DETECTION_TTL);
            return gateway;
        }

        private Gateway cache(Optional<String> detected) {
            gateway = new V81Gateway(server.id(), detected, List.of(), client, monitoring,
                this::ports, guard);
            fallbackUntil = null;
            return gateway;
        }

        /**
         * Creates the client (with the 8.1 maintenance probe and the server's credential guard)
         * and the monitoring adapter, wrapped by {@link StaleReportingPorts}, once; no network
         * access.
         *
         * @throws ToolErrorException {@code TLS_ERROR} if the trust store or pin is unusable;
         *     not cached, so a fixed trust store is picked up on the next call
         */
        private void ensureClient() {
            clientLock.lock();
            try {
                if (client != null) {
                    return;
                }
                V81MaintenanceProbe newProbe = new V81MaintenanceProbe(server,
                    credentials.trustStorePassword().map(SourcedValue::value), scrubber);
                try {
                    client = InubitHttpClient.create(server, credentials, newProbe, scrubber,
                        guard);
                    probe = newProbe;
                    monitoring = stale.monitoring(
                        new V81MonitoringAdapter(server.id(), client, this));
                } catch (RuntimeException e) {
                    newProbe.close();
                    throw e;
                }
            } finally {
                clientLock.unlock();
            }
        }

        void close() {
            lock.lock();
            clientLock.lock();
            try {
                gateway = null;
                fallbackUntil = null;
                monitoring = null;
                ports = null;
                portsRange = null;
                if (client != null) {
                    client.close();
                    client = null;
                }
                if (probe != null) {
                    probe.close();
                    probe = null;
                }
            } finally {
                clientLock.unlock();
                lock.unlock();
            }
        }
    }
}
