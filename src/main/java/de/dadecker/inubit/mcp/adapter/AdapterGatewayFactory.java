package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81MonitoringAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81VersionDetector;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
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
 *
 * <p>Credentials (feature 010, research R-7, FR-006a): each server's {@link NodeSecrets} holds
 * its startup credentials (environment variables) or fetches them from AWS Parameter Store on
 * first use. Every call that builds or uses the client ({@link #forServer}, {@link #detect},
 * {@link #monitoring} and the port methods) first waits for them with
 * {@link NodeSecrets#current} for at most {@link #SECRETS_WAIT}, holding no lock of the slot, so
 * an interrupted caller returns at once even while another caller's fetch is pending; a failed
 * fetch fails the call before anything is sent to INUBIT. The client is built with the
 * credentials, because the trust store password is part of its TLS context. Construction,
 * {@link #credentialsHeld} and {@link #knownGateway} never start a fetch;
 * {@link #prepareCredentials} starts or joins it and waits at most its bound (research R-9).
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

    /** How long a call waits for the secrets of its server (research R-7). */
    private static final Duration SECRETS_WAIT = NodeSecrets.STORE_TIMEOUT;

    /**
     * @param servers     the validated servers, in config order
     * @param secrets     the credentials of each server
     * @param clock       decides when a failed detection is retried
     * @param cliRunner   the StartCLI runner of the CLI-backed ports (US3 exports)
     * @param versions    receives the detections, failed detections and stale triggers, and
     *     answers the effective version that selects the port set
     * @param portSets    the port-set factory of each version range, plus the default
     */
    public AdapterGatewayFactory(List<EffectiveNodeConfig> servers,
        Map<NodeId, NodeSecrets> secrets, SecretScrubber scrubber, Clock clock,
        CliRunner cliRunner, VersionObserver versions, PortSetRegistry portSets) {
        this.cliRunner = Objects.requireNonNull(cliRunner, "cliRunner");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.portSets = Objects.requireNonNull(portSets, "portSets");
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (EffectiveNodeConfig server : servers) {
            slots.put(server.id(), new Slot(server, Objects.requireNonNull(
                secrets.get(server.id()), () -> "No secrets for " + server.id())));
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
    public boolean credentialsHeld(NodeId server) {
        return slot(server).secrets.held().isPresent();
    }

    /**
     * {@link NodeSecrets#current} within {@code bound}: its {@code TIMEOUT} (the fetch outlived
     * the bound and continues) is {@code PENDING}; a failed fetch is {@code AUTH_FAILED} or
     * {@code UNREACHABLE} and is thrown, as is the {@code TIMEOUT} of an interrupted caller.
     */
    @Override
    public CredentialState prepareCredentials(NodeId server, Duration bound) {
        Slot slot = slot(server);
        try {
            slot.secrets.current(bound);
            return CredentialState.READY;
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.TIMEOUT && !Thread.currentThread().isInterrupted()) {
                return CredentialState.PENDING;
            }
            throw e;
        }
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
     * port set waits for {@code lock}. The wait for the secrets happens before either lock is
     * taken ({@link #awaitCredentials()}).
     *
     * <p>Rotation (feature 010, research R-10): an AWS node's guard re-reads the secrets after a
     * rejected login, without touching the slot. When {@link NodeSecrets#held()} answers other
     * credentials than the client was built with, the next call builds a new client and
     * monitoring adapter and drops the port set; the gateway, built for the old client, is no
     * longer {@link #valid}. The old client is closed in the background; the guard refuses
     * attempts with its credentials ({@link CredentialGuard#bind}).
     */
    private final class Slot implements V81MonitoringAdapter.SystemInfoListener {

        /** A client with the credentials it was built with and what belongs to it. */
        private record Client(NodeCredentials credentials, CredentialGuard guard,
            V81MaintenanceProbe probe, InubitHttpClient http, MonitoringPort monitoring) {

            void close() {
                http.close();
                probe.close();
            }
        }

        private final EffectiveNodeConfig server;
        private final NodeSecrets secrets;
        private final CredentialGuard guard;
        private final StaleReportingPorts stale;
        private final ReentrantLock clientLock = new ReentrantLock();
        private final ReentrantLock lock = new ReentrantLock();
        /** Guarded by {@code clientLock}. */
        private Client client;
        private PortSet ports;
        /** The registry range {@link #ports} was built for; empty for the default. */
        private Optional<VersionRange> portsRange;
        /** Guarded by {@code lock}. */
        private V81Gateway gateway;
        /** The client {@link #gateway} was built with. */
        private Client gatewayClient;
        /** Set while {@link #gateway} is the fallback of a failed detection. */
        private Instant fallbackUntil;

        Slot(EffectiveNodeConfig server, NodeSecrets secrets) {
            this.server = server;
            this.secrets = secrets;
            this.guard = secrets.rereadable()
                ? new CredentialGuard(server.secretLabels(), clock, secrets)
                : new CredentialGuard(server.secretLabels(), clock);
            this.stale = new StaleReportingPorts(server.id(), versions);
        }

        /**
         * Waits (interruptibly) for a pending fetch of the server's credentials. Called before
         * {@code lock} or {@code clientLock} is taken; afterwards {@link NodeSecrets#held()} is
         * present for good.
         *
         * @throws ToolErrorException the fetch failure, or {@code TIMEOUT} if the wait ends
         */
        private void awaitCredentials() {
            secrets.current(SECRETS_WAIT);
        }

        Gateway gateway() {
            awaitCredentials();
            lock.lock();
            try {
                Client current = ensureClient();
                if (valid(current)) {
                    return gateway;
                }
                return auto() ? detect(current) : cache(current, Optional.empty());
            } finally {
                lock.unlock();
            }
        }

        MonitoringPort monitoring() {
            awaitCredentials();
            return ensureClient().monitoring();
        }

        /**
         * The port set for the node's current effective version, built on the first request and
         * rebuilt when the version has moved to another registry range or the client was
         * replaced; no network access.
         */
        PortSet ports() {
            awaitCredentials();
            PortSetRegistry.Selection selection =
                portSets.select(versions.effectiveVersion(server.id()));
            clientLock.lock();
            try {
                Client current = ensureClient();
                if (ports == null || !selection.range().equals(portsRange)) {
                    ports = stale.ports(selection.factory().create(new PortSetRegistry
                        .NodeResources(server, current.credentials(), current.guard(),
                            current.http(), cliRunner, scrubber)));
                    portsRange = selection.range();
                }
                return ports;
            } finally {
                clientLock.unlock();
            }
        }

        Optional<Gateway> knownGateway() {
            if (secrets.held().isEmpty() || !lock.tryLock()) {
                return Optional.empty(); // secrets not fetched yet, or a detection is running
            }
            try {
                if (gateway == null && auto()) {
                    return Optional.empty(); // needs a detection
                }
                Client current = ensureClient();
                if (valid(current)) {
                    return Optional.of(gateway);
                }
                return auto() ? Optional.empty() : Optional.of(cache(current, Optional.empty()));
            } finally {
                lock.unlock();
            }
        }

        private boolean auto() {
            return server.versionLine() instanceof VersionSetting.Auto;
        }

        /** A gateway of {@code current}, outside the window of a failed detection. */
        private boolean valid(Client current) {
            return gateway != null && gatewayClient == current
                && (fallbackUntil == null || clock.instant().isBefore(fallbackUntil));
        }

        /**
         * Detects the version; a real failure gives the 8.1 gateway, cached for
         * {@link #FAILED_DETECTION_TTL}. A cancelled (interrupted) detection is neither cached
         * nor reported.
         */
        private Gateway detect(Client current) {
            String version;
            try {
                version = V81VersionDetector.detect(server.id(), current.http());
            } catch (ToolErrorException e) {
                if (Thread.currentThread().isInterrupted()) {
                    return new V81Gateway(server.id(), Optional.empty(), List.of(),
                        current.http(), current.monitoring(), this::ports, current.guard());
                }
                report(e);
                return fallback(current, e.error().code());
            }
            report(version);
            return detected(current, version);
        }

        /**
         * {@link GatewayFactory#detect}: one detection regardless of the cached gateway or the
         * fallback window. For {@code AUTO} the result also updates the adapter selection: a
         * success replaces the gateway, a failure installs the fallback only if no valid gateway
         * is cached (a known version is kept).
         */
        InubitVersion detectNow() {
            awaitCredentials();
            lock.lock();
            try {
                Client current = ensureClient();
                String version;
                try {
                    version = V81VersionDetector.detect(server.id(), current.http());
                } catch (ToolErrorException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        report(e);
                        if (auto() && !valid(current)) {
                            fallback(current, e.error().code());
                        }
                    }
                    throw e;
                }
                Optional<InubitVersion> parsed = report(version);
                if (auto()) {
                    detected(current, version);
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

        /** A failed detection request; an HTTP 404 is reported as a missing endpoint. */
        private void report(ToolErrorException failure) {
            versions.detectionFailed(server.id(), failure.error().code(),
                failure instanceof InubitHttpClient.EndpointMissing);
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
                Client current = builtClient();
                if (current == null || (valid(current) && fallbackUntil == null)) {
                    return; // already detected
                }
                if (info.version().isPresent()) {
                    report(info.version().get());
                    detected(current, info.version().get());
                } else if (!valid(current)) {
                    report(ErrorCode.UNEXPECTED_RESPONSE);
                    fallback(current, ErrorCode.UNEXPECTED_RESPONSE);
                }
            } finally {
                lock.unlock();
            }
        }

        /** A real failure of the health call's {@code /system/info} is a failed detection. */
        @Override
        public void failed(ToolErrorException failure) {
            if (!auto() || Thread.currentThread().isInterrupted() || !lock.tryLock()) {
                return;
            }
            try {
                Client current = builtClient();
                if (current != null && !valid(current)) {
                    report(failure);
                    fallback(current, failure.error().code());
                }
            } finally {
                lock.unlock();
            }
        }

        private Gateway detected(Client current, String version) {
            return cache(current, Optional.of(version));
        }

        private Gateway fallback(Client current, ErrorCode code) {
            LOG.warn("{}: INUBIT version could not be detected ({})", server.id(), code);
            cache(current, Optional.empty());
            fallbackUntil = clock.instant().plus(FAILED_DETECTION_TTL);
            return gateway;
        }

        private Gateway cache(Client current, Optional<String> detected) {
            gateway = new V81Gateway(server.id(), detected, List.of(), current.http(),
                current.monitoring(), this::ports, current.guard());
            gatewayClient = current;
            fallbackUntil = null;
            return gateway;
        }

        /** The client built last, without building one; {@code null} if there is none. */
        private Client builtClient() {
            clientLock.lock();
            try {
                return client;
            } finally {
                clientLock.unlock();
            }
        }

        /**
         * The client for the credentials held now: created once (with them, the 8.1 maintenance
         * probe and the server's credential guard bound to them) together with the monitoring
         * adapter, wrapped by {@link StaleReportingPorts}; replaced, together with the port set,
         * when other credentials are held (research R-10); no network access. Call after
         * {@link #awaitCredentials()}.
         *
         * @throws ToolErrorException {@code TLS_ERROR} if the trust store or pin is unusable;
         *     not cached, so a fixed trust store is picked up on the next call
         */
        private Client ensureClient() {
            clientLock.lock();
            try {
                NodeCredentials current = secrets.held().orElseThrow();
                if (client != null && client.credentials() == current) {
                    return client;
                }
                if (client != null) {
                    // a caller may still wait for a response of the old client
                    Thread.ofVirtual().name("client-close-" + server.id().value())
                        .start(client::close);
                    client = null;
                    ports = null;
                    portsRange = null;
                }
                V81MaintenanceProbe newProbe = new V81MaintenanceProbe(server,
                    current.trustStorePassword().map(SourcedValue::value), scrubber);
                try {
                    CredentialGuard bound = guard.bind(current);
                    InubitHttpClient http = InubitHttpClient.create(server, current, newProbe,
                        scrubber, bound);
                    client = new Client(current, bound, newProbe, http, stale.monitoring(
                        new V81MonitoringAdapter(server.id(), http, this)));
                    return client;
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
                gatewayClient = null;
                fallbackUntil = null;
                ports = null;
                portsRange = null;
                if (client != null) {
                    client.close();
                    client = null;
                }
            } finally {
                clientLock.unlock();
                lock.unlock();
            }
        }
    }
}
