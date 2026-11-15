package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.application.WriteGuard;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogLevelPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The environment of the lease scheduler tests (feature 024 T052): one profile directory, a fake
 * INUBIT reached through one fake {@link LogLevelPort} per server, one clock, one table of running
 * processes and one audit log. {@link #instance} starts a fake MCP server instance on it, with
 * its own {@link LeaseStore}, {@link NodeLogLevelLocks}, {@link LogLevelService},
 * {@link LeaseScheduler} and {@link ProcessLiveness} identity, as a second process of the profile
 * would have.
 */
final class LeaseFixture {

    static final NodeId TEST = NodeId.parse("test/node1");
    static final NodeId TEST2 = NodeId.parse("test/node2");
    /** No longer configured. */
    static final NodeId GONE = NodeId.parse("acme/node9");

    static final Instant NOW = Instant.parse("2026-11-12T08:25:30Z");
    static final Duration CLI_TIMEOUT = Duration.ofSeconds(5);
    static final String ORDERS = "com.inubit.ibis.acme.Orders";
    static final String INVOICES = "com.inubit.ibis.acme.Invoices";

    /** {@code log_level_write} supported on 8.1. */
    private static final CapabilityMatrix MATRIX = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Map.of(Capability.LOG_LEVEL_WRITE, SupportState.SUPPORTED), Map.of(), false)),
        Map.of());

    final MutableClock clock = new MutableClock(NOW);
    final RecordingAudit auditLog = new RecordingAudit();
    /** The processes that run; an owner not in here no longer runs. */
    final Set<LeaseOwner> running = ConcurrentHashMap.newKeySet();
    /**
     * Every call of the fake INUBIT: {@code "<server> answeringNode"}, {@code "<server> resetAll"}
     * or {@code "<server> raise <logger>"}.
     */
    final List<String> calls = new CopyOnWriteArrayList<>();
    /** Servers whose credentials are missing ({@code AUTH_FAILED}: a cleanup precondition). */
    final Set<NodeId> withoutCredentials = ConcurrentHashMap.newKeySet();
    /** Servers whose credential lookup throws an unexpected {@link IllegalStateException}. */
    final Set<NodeId> brokenCredentials = ConcurrentHashMap.newKeySet();
    /** Thrown by every {@code trace --reset} while set. */
    volatile RuntimeException resetFailure;
    /** Runs at the start of every {@code trace --reset}, with the server. */
    volatile Consumer<NodeId> onReset = server -> { };

    private final Path directory;

    LeaseFixture(Path home) {
        this.directory = LeaseStore.defaultDirectory(home, "acme");
    }

    /** One MCP server instance of the profile, running as {@code self}. */
    record Instance(LeaseOwner self, LeaseStore store, LogLevelService service,
        LeaseScheduler scheduler) {

        /** Sets DEBUG for {@code loggers} on {@code server} for one minute (no confirmation). */
        void set(NodeId server, String... loggers) {
            service.set(new SetLevelRequest(server.value(), List.of(loggers), "DEBUG",
                OptionalInt.of(1), Optional.empty(), Optional.empty()));
        }

        /** One tick of the scheduler; returns when the cleanups it started have ended. */
        void tick() {
            finished(scheduler.tick());
        }
    }

    /** Waits up to 10 s for each of {@code tasks}. */
    static void finished(List<Future<?>> tasks) {
        for (Future<?> task : tasks) {
            try {
                task.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new AssertionError("a cleanup did not end", e);
            }
        }
    }

    /** Starts an instance as {@code self}, which runs until removed from {@link #running}. */
    Instance instance(LeaseOwner self) {
        return instance(self, PrivateFiles.forHost());
    }

    /** As {@link #instance(LeaseOwner)}, its lease store on {@code storeFiles}. */
    Instance instance(LeaseOwner self, PrivateFileSystem storeFiles) {
        running.add(self);
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions), MATRIX);
        Map<NodeId, WritePolicy> policies = Map.of(TEST, policy(TEST), TEST2, policy(TEST2));
        FakeGateways gateways = new FakeGateways();
        WriteGuard guard = new WriteGuard(new TargetResolver(List.of(TEST, TEST2)),
            policies::get, gateways, gate);
        ProcessLiveness liveness = new ProcessLiveness() {
            @Override
            public LeaseOwner self() {
                return self;
            }

            @Override
            public boolean alive(LeaseOwner owner) {
                return running.contains(owner);
            }
        };
        LeaseStore store = new LeaseStore(directory, storeFiles, clock);
        LogLevelAudit audit = new LogLevelAudit(auditLog, clock, "acme", UUID::randomUUID);
        LogLevelService service = new LogLevelService(guard, policies::containsKey, gateways,
            new WriteChallengeRegistry(clock), store,
            new NodeLogLevelLocks(directory, PrivateFiles.forHost()), liveness, audit,
            node -> CLI_TIMEOUT, clock, System::nanoTime);
        LeaseScheduler scheduler = new LeaseScheduler(store, service, liveness, audit,
            policies::containsKey, server -> Optional.of("jdoe"), clock);
        return new Instance(self, store, service, scheduler);
    }

    /** A store of the profile that belongs to no instance: seeds and reads the leases. */
    LeaseStore store() {
        return new LeaseStore(directory, PrivateFiles.forHost(), clock);
    }

    Path directory() {
        return directory;
    }

    /** Stores {@code leases} as the only leases of the profile. */
    void seed(Lease... leases) {
        store().update(stored -> List.of(leases), CLI_TIMEOUT);
    }

    List<Lease> leases() {
        return store().read();
    }

    Lease onlyLease() {
        List<Lease> leases = leases();
        if (leases.size() != 1) {
            throw new AssertionError("expected one lease, found " + leases);
        }
        return leases.get(0);
    }

    /** An {@code ACTIVE} lease of {@code server} on INUBIT node node1, set 10 minutes earlier. */
    static Lease activeLease(NodeId server, LeaseOwner owner, Instant expiresAt) {
        SortedMap<LoggerName, TemporaryLevel> loggers =
            new TreeMap<>(Comparator.comparing(LoggerName::value));
        loggers.put(new LoggerName(ORDERS), TemporaryLevel.DEBUG);
        return new Lease(UUID.randomUUID(), 1, server, "node1", loggers,
            expiresAt.minus(Duration.ofMinutes(10)), expiresAt, owner, LeaseState.ACTIVE,
            UUID.randomUUID(), Optional.empty(), Optional.empty());
    }

    /** The {@code trace --reset} runs so far. */
    long resets() {
        return calls.stream().filter(call -> call.endsWith(" resetAll")).count();
    }

    private static WritePolicy policy(NodeId server) {
        return new WritePolicy(server, false, true, false, Confirmation.CLIENT,
            Duration.ofMinutes(5), Optional.of("jdoe"));
    }

    /** INUBIT node node1 answers for every server. */
    private final class FakePort implements LogLevelPort {

        private final NodeId server;

        FakePort(NodeId server) {
            this.server = server;
        }

        @Override
        public String answeringNode(InubitVersion version, long deadlineNanos) {
            calls.add(server + " answeringNode");
            return "node1";
        }

        @Override
        public void raise(TemporaryLevel level, LoggerName name, InubitVersion version,
            Duration timeout) {
            calls.add(server + " raise " + name.value());
        }

        @Override
        public void resetAll(InubitVersion version, Duration timeout) {
            calls.add(server + " resetAll");
            onReset.accept(server);
            RuntimeException failure = resetFailure;
            if (failure != null) {
                throw failure;
            }
        }
    }

    private final class FakeGateways implements GatewayFactory {

        @Override
        public CredentialState prepareCredentials(NodeId server, Duration bound) {
            if (brokenCredentials.contains(server)) {
                throw new IllegalStateException("credential store broken");
            }
            if (withoutCredentials.contains(server)) {
                throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                    "No login credentials for " + server, "none stored", "store them")
                    .withNode(server));
            }
            return CredentialState.READY;
        }

        @Override
        public LogLevelPort logLevel(NodeId server) {
            return new FakePort(server);
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            return new ProcessControlPort() {
                @Override
                public void checkAvailable() {
                }

                @Override
                public String restart(String processId, WritePermit permit) {
                    throw new AssertionError("not expected");
                }

                @Override
                public String kill(String processId, WritePermit permit) {
                    throw new AssertionError("not expected");
                }
            };
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public InubitVersion detect(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public MonitoringPort monitoring(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public ProcessQueryPort processes(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public LogPort logs(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public InventoryPort inventory(NodeId server) {
            throw new AssertionError("not expected");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            throw new AssertionError("not expected");
        }
    }

    /** Records every appended audit record; fails every append while {@code failing}. */
    static final class RecordingAudit implements AuditPort {

        final List<AuditRecord> records = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public void append(AuditRecord record) {
            if (failing) {
                throw new UncheckedIOException(new IOException("disk full"));
            }
            records.add(record);
        }
    }
}
