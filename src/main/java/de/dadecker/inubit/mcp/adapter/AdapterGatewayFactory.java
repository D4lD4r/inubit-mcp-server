package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliImportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliTagRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ArtifactAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ImportAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81TagAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ProcessControlAdapter;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81LogAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81MonitoringAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81ProcessQueryAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81UserDirectory;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81VersionDetector;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.config.VersionLine;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.domain.port.UserDirectoryPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
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
 *   <li>{@code V8_1}: the 8.1 adapters.
 *   <li>{@code AUTO}: resolved lazily on the first {@link #forServer} call via
 *       {@code /system/info} and cached per server. If the detection fails, the call still
 *       returns the 8.1 adapters with the warning "INUBIT version could not be detected
 *       (&lt;code&gt;); assuming 8.1". That fallback is cached for {@link #FAILED_DETECTION_TTL}
 *       (60 s): within the window the detection is not retried, so that rejected credentials
 *       ({@code AUTH_FAILED}, {@code FORBIDDEN}) do not cause one extra failed login per call
 *       (account lockout). The first call after the window detects again; a successful
 *       detection replaces the fallback for good.
 *   <li>{@code V9_X}, or a detected version other than 8.1: the 8.1 adapters plus the warning
 *       "unsupported" (only the 8.1 adapters exist in this version).
 * </ul>
 *
 * <p>Each server gets one {@link InubitHttpClient}, created lazily with the version line's
 * maintenance probe (8.1: {@link V81MaintenanceProbe}; never {@code MaintenanceProbe.NONE}), so
 * that HTTP 503 during maintenance is reported as {@code MAINTENANCE_MODE}.
 *
 * <p>Extension point: the user-story phases construct their 8.1 port adapters from the server's
 * client once (with the client, see {@code ensureClient}) and expose them through
 * {@link V81Gateway}. The monitoring (US1), process and log ports (US2) and the inventory port
 * (US3) and the process control port (US4) are also handed out without waiting for a version
 * detection ({@link #monitoring}, {@link #processes}, {@link #logs}, {@link #inventory},
 * {@link #processControl}), because only the 8.1 adapters exist. The inventory's StartCLI exports
 * and the restart/kill calls run with the factory's {@link CliRunner} and the server's
 * {@link CredentialGuard}, shared with its REST client.
 */
public final class AdapterGatewayFactory implements GatewayFactory, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AdapterGatewayFactory.class);
    private static final String V81_PREFIX = "8.1";

    private final Map<NodeId, Slot> slots = new LinkedHashMap<>();
    private final SecretScrubber scrubber;
    private final Clock clock;
    private final CliRunner cliRunner;

    /** How long the 8.1 fallback of a failed {@code AUTO} detection is used before a retry. */
    public static final Duration FAILED_DETECTION_TTL = Duration.ofSeconds(60);

    /**
     * @param servers     the validated servers, in config order
     * @param credentials their resolved credentials
     * @param clock       decides when a failed detection is retried
     * @param cliRunner   the StartCLI runner of the CLI-backed ports (US3 exports)
     */
    public AdapterGatewayFactory(List<EffectiveNodeConfig> servers,
        CredentialResolution credentials, SecretScrubber scrubber, Clock clock,
        CliRunner cliRunner) {
        this.cliRunner = Objects.requireNonNull(cliRunner, "cliRunner");
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

    /** Without waiting for a version detection: only the 8.1 adapters exist (US2). */
    @Override
    public ProcessQueryPort processes(NodeId server) {
        return slot(server).ports().processes();
    }

    /** Without waiting for a version detection: only the 8.1 adapters exist (US2). */
    @Override
    public LogPort logs(NodeId server) {
        return slot(server).ports().logs();
    }

    /** Without waiting for a version detection: only the 8.1 adapters exist (US3). */
    @Override
    public InventoryPort inventory(NodeId server) {
        return slot(server).ports().inventory();
    }

    /** Without waiting for a version detection: only the 8.1 adapters exist (feature 003). */
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
    public UserDirectoryPort users(NodeId server) {
        return slot(server).ports().users();
    }

    /** Without waiting for a version detection: only the 8.1 adapters exist (US4). */
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
     * The lazily created client, monitoring adapter and credential guard of one server, and its
     * cached gateway. Two {@link ReentrantLock}s (not {@code synchronized}, so that waiting
     * virtual threads do not pin their carrier): {@code clientLock} for the quick creation of
     * the client, {@code lock} for the gateway state, held during a blocking detection. The
     * monitoring port never waits for {@code lock}.
     */
    private final class Slot implements V81MonitoringAdapter.SystemInfoListener {

        private final EffectiveNodeConfig server;
        private final NodeCredentials credentials;
        private final CredentialGuard guard;
        private final ReentrantLock clientLock = new ReentrantLock();
        private final ReentrantLock lock = new ReentrantLock();
        private V81MaintenanceProbe probe;
        private InubitHttpClient client;
        private V81MonitoringAdapter monitoring;
        private V81Gateway.Ports ports;
        private V81Gateway gateway;
        /** Set while {@link #gateway} is the fallback of a failed detection. */
        private Instant fallbackUntil;

        Slot(EffectiveNodeConfig server, NodeCredentials credentials) {
            this.server = server;
            this.credentials = credentials;
            this.guard = new CredentialGuard(server.credentialVariables(), clock);
        }

        Gateway gateway() {
            lock.lock();
            try {
                if (valid()) {
                    return gateway;
                }
                ensureClient();
                return switch (server.versionLine()) {
                    case V8_1, V9_X -> configured();
                    case AUTO -> detect();
                };
            } finally {
                lock.unlock();
            }
        }

        MonitoringPort monitoring() {
            ensureClient();
            return monitoring;
        }

        V81Gateway.Ports ports() {
            ensureClient();
            return ports;
        }

        Optional<Gateway> knownGateway() {
            if (!lock.tryLock()) {
                return Optional.empty(); // a detection is running
            }
            try {
                if (valid()) {
                    return Optional.of(gateway);
                }
                if (server.versionLine() == VersionLine.AUTO) {
                    return Optional.empty();
                }
                ensureClient();
                return Optional.of(configured());
            } finally {
                lock.unlock();
            }
        }

        private boolean valid() {
            return gateway != null
                && (fallbackUntil == null || clock.instant().isBefore(fallbackUntil));
        }

        private Gateway configured() {
            return server.versionLine() == VersionLine.V9_X
                ? cache(Optional.empty(), List.of("versionLine V9_X is unsupported in this"
                    + " version; the 8.1 adapters are used"))
                : cache(Optional.empty(), List.of());
        }

        /**
         * Detects the version; a real failure gives an 8.1 gateway with a warning, cached for
         * {@link #FAILED_DETECTION_TTL}. A cancelled (interrupted) detection is not cached.
         */
        private Gateway detect() {
            String version;
            try {
                version = V81VersionDetector.detect(server.id(), client);
            } catch (ToolErrorException e) {
                if (Thread.currentThread().isInterrupted()) {
                    return new V81Gateway(server.id(), Optional.empty(),
                        List.of(fallbackWarning(e.error().code())), client, monitoring, ports,
                        guard);
                }
                return fallback(e.error().code());
            }
            return detected(version);
        }

        /** The health call's {@code /system/info} completes an {@code AUTO} detection. */
        @Override
        public void succeeded(SystemInfo info) {
            if (server.versionLine() != VersionLine.AUTO || !lock.tryLock()) {
                return;
            }
            try {
                if (gateway != null && fallbackUntil == null) {
                    return; // already detected
                }
                if (info.version().isPresent()) {
                    detected(info.version().get());
                } else if (!valid()) {
                    fallback(ErrorCode.UNEXPECTED_RESPONSE);
                }
            } finally {
                lock.unlock();
            }
        }

        /** A real failure of the health call's {@code /system/info} is a failed detection. */
        @Override
        public void failed(ToolError error) {
            if (server.versionLine() != VersionLine.AUTO
                || Thread.currentThread().isInterrupted() || !lock.tryLock()) {
                return;
            }
            try {
                if (!valid()) {
                    fallback(error.code());
                }
            } finally {
                lock.unlock();
            }
        }

        private Gateway detected(String version) {
            List<String> warnings = isV81(version) ? List.of()
                : List.of("INUBIT " + version + " is unsupported in this version; the 8.1"
                    + " adapters are used");
            return cache(Optional.of(version), warnings);
        }

        private Gateway fallback(ErrorCode code) {
            String warning = fallbackWarning(code);
            LOG.warn("{}: {}", server.id(), warning);
            gateway = new V81Gateway(server.id(), Optional.empty(), List.of(warning), client,
                monitoring, ports, guard);
            fallbackUntil = clock.instant().plus(FAILED_DETECTION_TTL);
            return gateway;
        }

        private static String fallbackWarning(ErrorCode code) {
            return "INUBIT version could not be detected (" + code + "); assuming 8.1";
        }

        private Gateway cache(Optional<String> detected, List<String> warnings) {
            warnings.forEach(warning -> LOG.warn("{}: {}", server.id(), warning));
            gateway = new V81Gateway(server.id(), detected, warnings, client, monitoring, ports,
                guard);
            fallbackUntil = null;
            return gateway;
        }

        /**
         * Creates the client (with the 8.1 maintenance probe and the server's credential guard)
         * the monitoring adapter and the ports once; no network access.
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
                    monitoring = new V81MonitoringAdapter(server.id(), client, this);
                    V81InventoryAdapter inventory = new V81InventoryAdapter(server.id(), client,
                        new CliExportRunner(server, credentials, guard, cliRunner,
                            new CliOutputClassifier(scrubber, server.credentialVariables())));
                    ports = new V81Gateway.Ports(
                        new V81ProcessQueryAdapter(server.id(), client),
                        new V81LogAdapter(server.id(), client),
                        inventory,
                        new V81ProcessControlAdapter(server, credentials, guard, cliRunner,
                            new CliOutputClassifier(scrubber, server.credentialVariables())),
                        new V81ArtifactAdapter(new CliExportRunner(server, credentials, guard,
                            cliRunner, new CliOutputClassifier(scrubber,
                                server.credentialVariables())), inventory::confirmCredentials),
                        new V81ImportAdapter(new CliImportRunner(server, credentials, guard,
                            cliRunner, new CliOutputClassifier(scrubber,
                                server.credentialVariables())), inventory::confirmCredentials),
                        new V81TagAdapter(server.id(), new CliExportRunner(server, credentials,
                            guard, cliRunner, new CliOutputClassifier(scrubber,
                                server.credentialVariables())), new CliTagRunner(server,
                            credentials, guard, cliRunner, new CliOutputClassifier(scrubber,
                                server.credentialVariables())), inventory::confirmCredentials),
                        new V81UserDirectory(server.id(), client));
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

    private static boolean isV81(String version) {
        return version.equals(V81_PREFIX) || version.startsWith(V81_PREFIX + ".");
    }
}
