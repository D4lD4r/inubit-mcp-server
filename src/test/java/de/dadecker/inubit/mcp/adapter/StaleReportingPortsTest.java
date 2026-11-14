package de.dadecker.inubit.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.LogPresence;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import de.dadecker.inubit.mcp.domain.model.node.IoLogListing;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValues;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ClusterNodePort;
import de.dadecker.inubit.mcp.domain.port.DownloadedLog;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.IoLogPort;
import de.dadecker.inubit.mcp.domain.port.LogLevelPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.NodeConfigPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.domain.port.ServerLogPort;
import de.dadecker.inubit.mcp.domain.port.ServerSettingsPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.domain.port.UserDirectoryPort;
import de.dadecker.inubit.mcp.testing.RecordingVersionObserver;
import de.dadecker.inubit.mcp.testing.TestPermits;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Feature 009 T007 (research D-10): every method of every port the factory hands out reports
 * {@code stale(node)} for an {@code UNEXPECTED_RESPONSE} and rethrows the same exception; other
 * failures and results pass through unreported. Feature 017 (research D-24): a write method
 * reports {@code staleAfterWrite(node, permit)} with its own permit instead. Feature 018 (T013,
 * research D-4): the partner management port is wrapped like every other port. Feature 024
 * (T015, research D-24): so are the node-tool ports.
 */
class StaleReportingPortsTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");
    private static final WritePermit PERMIT = TestPermits.of(NODE, Capability.DEVELOPMENT_WRITE);

    private final RecordingVersionObserver versions = new RecordingVersionObserver();
    private final StaleReportingPorts stale = new StaleReportingPorts(NODE, versions);

    /** A port whose every method throws {@code failure}. */
    private static <T> T throwing(Class<T> type, RuntimeException failure) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                throw failure;
            }));
    }

    private static PortSet throwingPorts(RuntimeException failure) {
        return new PortSet(
            throwing(ProcessQueryPort.class, failure),
            throwing(LogPort.class, failure),
            throwing(InventoryPort.class, failure),
            throwing(ProcessControlPort.class, failure),
            throwing(ArtifactPort.class, failure),
            throwing(ImportPort.class, failure),
            throwing(TagPort.class, failure),
            throwing(PartnerManagementPort.class, failure),
            throwing(QueueEntryContentPort.class, failure),
            throwing(ServerLogPort.class, failure),
            throwing(ServerSettingsPort.class, failure),
            throwing(UserDirectoryPort.class, failure),
            throwing(ClusterNodePort.class, failure),
            throwing(IoLogPort.class, failure),
            throwing(NodeConfigPort.class, failure),
            throwing(LogLevelPort.class, failure));
    }

    /** Each port of the wrapped set with its interface, monitoring included. */
    private List<Object[]> wrapped(RuntimeException failure) {
        PortSet ports = stale.ports(throwingPorts(failure));
        List<Object[]> all = new ArrayList<>();
        all.add(new Object[] {MonitoringPort.class,
            stale.monitoring(throwing(MonitoringPort.class, failure))});
        all.add(new Object[] {ProcessQueryPort.class,
            ports.processes()});
        all.add(new Object[] {LogPort.class, ports.logs()});
        all.add(new Object[] {InventoryPort.class,
            ports.inventory()});
        all.add(new Object[] {ProcessControlPort.class,
            ports.processControl()});
        all.add(new Object[] {ArtifactPort.class,
            ports.artifacts()});
        all.add(new Object[] {ImportPort.class,
            ports.imports()});
        all.add(new Object[] {TagPort.class, ports.tags()});
        all.add(new Object[] {PartnerManagementPort.class, ports.partners()});
        all.add(new Object[] {ClusterNodePort.class, ports.clusterNodes()});
        all.add(new Object[] {IoLogPort.class, ports.ioLog()});
        all.add(new Object[] {NodeConfigPort.class, ports.nodeConfig()});
        all.add(new Object[] {LogLevelPort.class, ports.logLevel()});
        return all;
    }

    /** Calls {@code method} with default arguments and returns what it threw. */
    private static Throwable thrownBy(Method method, Object port) throws IllegalAccessException {
        Object[] args = new Object[method.getParameterCount()];
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            args[i] = types[i] == int.class ? 0 : types[i] == long.class ? 0L
                : types[i] == boolean.class ? false : types[i] == WritePermit.class ? PERMIT
                : null;
        }
        try {
            method.invoke(port, args);
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
        throw new AssertionError(method + " did not throw");
    }

    private static ToolErrorException failure(ErrorCode code) {
        return new ToolErrorException(ToolError.of(code, "message", "cause", "next step")
            .withNode(NODE));
    }

    @Test
    void everyPortMethodReportsAnUnexpectedResponseAsStaleAndRethrowsIt() throws Exception {
        ToolErrorException unexpected = failure(ErrorCode.UNEXPECTED_RESPONSE);
        int methods = 0;
        int writes = 0;
        for (Object[] entry : wrapped(unexpected)) {
            for (Method method : ((Class<?>) entry[0]).getMethods()) {
                if (method.isDefault() || Modifier.isStatic(
                    method.getModifiers())) {
                    continue;
                }
                assertThat(thrownBy(method, entry[1])).as(method.toString()).isSameAs(unexpected);
                methods++;
                boolean write = List.of(method.getParameterTypes()).contains(WritePermit.class);
                writes += write ? 1 : 0;
                assertThat(versions.events()).as(method.toString()).hasSize(methods)
                    .last().isEqualTo(write ? "staleAfterWrite dev/node1" : "stale dev/node1");
            }
        }
        assertThat(methods).isGreaterThan(20);
        // feature 017 (review of stage 5, research D-24): the mark of a write's own response
        // carries that write's permit object
        assertThat(writes).as("restart, kill, importArchive, importRepository, tag").isEqualTo(5);
        assertThat(versions.staleWritePermits()).hasSize(5).allSatisfy(permit ->
            assertThat(permit).isSameAs(PERMIT));
    }

    @Test
    void otherFailuresAreRethrownWithoutReport() throws Exception {
        for (RuntimeException other : List.of(failure(ErrorCode.NOT_FOUND),
            failure(ErrorCode.AUTH_FAILED), new IllegalStateException("bug"))) {
            for (Object[] entry : wrapped(other)) {
                for (Method method : ((Class<?>) entry[0]).getMethods()) {
                    if (!method.isDefault() && !Modifier.isStatic(
                        method.getModifiers())) {
                        assertThat(thrownBy(method, entry[1])).isSameAs(other);
                    }
                }
            }
        }
        assertThat(versions.events()).isEmpty();
    }

    @Test
    void queueEntriesIsForwardedAndAnUnexpectedResponseMarksTheNodeStale() {
        // feature 020 T012 (research D-9): without the override the wrapped 8.1 port would
        // never be asked and the refusing default would answer
        QueueEntries answer = new QueueEntries(List.of(), 0);
        List<Object> asked = new ArrayList<>();
        ProcessQueryPort answering = (ProcessQueryPort) Proxy.newProxyInstance(
            ProcessQueryPort.class.getClassLoader(), new Class<?>[] {ProcessQueryPort.class},
            (proxy, method, args) -> {
                if (method.getName().equals("queueEntries")) {
                    asked.add(args[0]);
                    return answer;
                }
                throw new AssertionError(method + " was not expected");
            });
        PortSet others = throwingPorts(failure(ErrorCode.NOT_FOUND));

        assertThat(stale.ports(new PortSet(answering, others.logs(), others.inventory(),
            others.processControl(), others.artifacts(), others.imports(), others.tags(),
            others.partners())).processes().queueEntries("4711")).isSameAs(answer);
        assertThat(asked).containsExactly("4711");
        assertThat(versions.events()).isEmpty();

        ToolErrorException notFound = failure(ErrorCode.NOT_FOUND);
        assertThat(catchThrowable(() -> stale.ports(throwingPorts(notFound)).processes()
            .queueEntries("4711"))).isSameAs(notFound);
        assertThat(versions.events()).isEmpty();

        ToolErrorException unexpected = failure(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(catchThrowable(() -> stale.ports(throwingPorts(unexpected)).processes()
            .queueEntries("4711"))).isSameAs(unexpected);
        assertThat(versions.events()).containsExactly("stale dev/node1");
    }

    @Test
    void queueEntryContentIsForwardedAndAnUnexpectedResponseMarksTheNodeStale() {
        // feature 020 T022 (research D-17): the content port is wrapped like every other port
        QueueEntryRef ref = new QueueEntryRef(NODE, "Workflow-0001", "Order-0001.xml", 772);
        InubitVersion version = InubitVersion.parse("8.1.17").orElseThrow();
        Set<EntryPart> parts = Set.of(EntryPart.DATA);
        RawEntryContent answer = new RawEntryContent(new PartResult.Read<>(new byte[] {1}),
            new PartResult.NotRequested<>("not requested"));
        List<Object> asked = new ArrayList<>();
        QueueEntryContentPort answering = (r, v, p) -> {
            asked.addAll(List.of(r, v, p));
            return answer;
        };

        PortSet wrapped = stale.ports(withContent(answering));
        assertThat(wrapped.queueEntryContent()).isInstanceOf(
            StaleReportingPorts.QueueEntryContent.class);
        assertThat(wrapped.queueEntryContent().read(ref, version, parts)).isSameAs(answer);
        assertThat(asked).containsExactly(ref, version, parts);
        assertThat(versions.events()).isEmpty();

        for (RuntimeException other : List.of(failure(ErrorCode.NOT_FOUND),
            failure(ErrorCode.TIMEOUT), new IllegalStateException("bug"))) {
            assertThat(catchThrowable(() -> stale.ports(withContent(throwing(
                QueueEntryContentPort.class, other))).queueEntryContent().read(ref, version,
                    parts))).isSameAs(other);
        }
        assertThat(versions.events()).isEmpty();

        ToolErrorException unexpected = failure(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(catchThrowable(() -> stale.ports(withContent(throwing(
            QueueEntryContentPort.class, unexpected))).queueEntryContent().read(ref, version,
                parts))).isSameAs(unexpected);
        assertThat(versions.events()).containsExactly("stale dev/node1");
    }

    /** A port set of throwing ports whose queue-entry content port is {@code content}. */
    private static PortSet withContent(QueueEntryContentPort content) {
        PortSet others = throwingPorts(failure(ErrorCode.NOT_FOUND));
        return new PortSet(others.processes(), others.logs(), others.inventory(),
            others.processControl(), others.artifacts(), others.imports(), others.tags(),
            others.partners(), content);
    }

    @Test
    void serverLogsAreForwardedAndAnUnexpectedResponseMarksTheNodeStale() {
        // feature 021 T011 (research D-19): the server-log port is wrapped like every other port
        InubitVersion version = InubitVersion.parse("8.1.17").orElseThrow();
        long deadline = System.nanoTime();
        DownloadedLog answer = new DownloadedLog(Path.of("trace.log"), 0, Optional.empty(),
            LogPresence.PRESENT, () -> { });
        List<Object> asked = new ArrayList<>();
        ServerLogPort answering = (k, v, d) -> {
            asked.addAll(List.of(k, v, d));
            return answer;
        };

        PortSet wrapped = stale.ports(withServerLogs(answering));
        assertThat(wrapped.serverLogs()).isInstanceOf(StaleReportingPorts.ServerLogs.class);
        assertThat(wrapped.serverLogs().download(ServerLogKind.TRACE, version, deadline))
            .isSameAs(answer);
        assertThat(asked).containsExactly(ServerLogKind.TRACE, version, deadline);
        assertThat(versions.events()).isEmpty();

        for (RuntimeException other : List.of(failure(ErrorCode.NOT_FOUND),
            failure(ErrorCode.TIMEOUT), new IllegalStateException("bug"))) {
            assertThat(catchThrowable(() -> stale.ports(withServerLogs(throwing(
                ServerLogPort.class, other))).serverLogs().download(ServerLogKind.WEBSERVICE,
                    version, deadline))).isSameAs(other);
        }
        assertThat(versions.events()).isEmpty();

        ToolErrorException unexpected = failure(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(catchThrowable(() -> stale.ports(withServerLogs(throwing(
            ServerLogPort.class, unexpected))).serverLogs().download(ServerLogKind.TRACE,
                version, deadline))).isSameAs(unexpected);
        assertThat(versions.events()).containsExactly("stale dev/node1");
    }

    /** A port set of throwing ports whose server-log port is {@code serverLogs}. */
    private static PortSet withServerLogs(ServerLogPort serverLogs) {
        PortSet others = throwingPorts(failure(ErrorCode.NOT_FOUND));
        return new PortSet(others.processes(), others.logs(), others.inventory(),
            others.processControl(), others.artifacts(), others.imports(), others.tags(),
            others.partners(), others.queueEntryContent(), serverLogs);
    }

    @Test
    void serverSettingsAreForwardedAndAnUnexpectedResponseMarksTheNodeStale() throws Exception {
        // feature 022 T020: the settings port is wrapped like every other port
        LicenseFacts license = new LicenseFacts(Optional.empty(), Optional.of(32L),
            Optional.empty());
        ConfigurationView view = ConfigurationView.sections(List.of("GUIThreadsCount"));
        CliSettingsSnapshot snapshot = new CliSettingsSnapshot(Optional.of(12L),
            Optional.empty(), Optional.empty(), Optional.empty(), List.of());
        List<Object> asked = new ArrayList<>();
        ServerSettingsPort answering = new ServerSettingsPort() {
            @Override
            public LicenseFacts license() {
                return license;
            }

            @Override
            public ConfigurationView configuration(Optional<String> section, int maxValues,
                int maxChars) {
                asked.addAll(List.of(section, maxValues, maxChars));
                return view;
            }

            @Override
            public CliSettingsSnapshot cliSettings(Set<String> parts) {
                asked.add(parts);
                return snapshot;
            }
        };

        ServerSettingsPort wrapped = stale.ports(withServerSettings(answering)).serverSettings();
        assertThat(wrapped).isInstanceOf(StaleReportingPorts.ServerSettings.class);
        assertThat(wrapped.license()).isSameAs(license);
        assertThat(wrapped.configuration(Optional.of("S"), 7, 9_000)).isSameAs(view);
        assertThat(wrapped.cliSettings(Set.of("threads"))).isSameAs(snapshot);
        assertThat(asked).containsExactly(Optional.of("S"), 7, 9_000, Set.of("threads"));
        assertThat(versions.events()).isEmpty();

        for (RuntimeException other : List.of(failure(ErrorCode.NOT_FOUND),
            failure(ErrorCode.FORBIDDEN), new IllegalStateException("bug"))) {
            ServerSettingsPort port = stale.ports(withServerSettings(throwing(
                ServerSettingsPort.class, other))).serverSettings();
            for (Method method : ServerSettingsPort.class.getMethods()) {
                assertThat(thrownBy(method, port)).as(method.toString()).isSameAs(other);
            }
        }
        assertThat(versions.events()).isEmpty();

        ToolErrorException unexpected = failure(ErrorCode.UNEXPECTED_RESPONSE);
        ServerSettingsPort port = stale.ports(withServerSettings(throwing(
            ServerSettingsPort.class, unexpected))).serverSettings();
        for (Method method : ServerSettingsPort.class.getMethods()) {
            assertThat(thrownBy(method, port)).as(method.toString()).isSameAs(unexpected);
        }
        assertThat(versions.events()).containsExactly("stale dev/node1", "stale dev/node1",
            "stale dev/node1");
    }

    /** A port set of throwing ports whose settings port is {@code serverSettings}. */
    private static PortSet withServerSettings(ServerSettingsPort serverSettings) {
        PortSet others = throwingPorts(failure(ErrorCode.NOT_FOUND));
        return new PortSet(others.processes(), others.logs(), others.inventory(),
            others.processControl(), others.artifacts(), others.imports(), others.tags(),
            others.partners(), others.queueEntryContent(), others.serverLogs(), serverSettings);
    }

    @Test
    void userReadsAreForwardedAndAnUnexpectedResponseMarksTheNodeStale() throws Exception {
        // feature 022 T033: the user directory port is wrapped like every other port
        List<ListedUser> users = List.of(new ListedUser("jdoe", "ProcessEngineUser"));
        List<UserFacts> facts = List.of();
        List<LoginEntry> logins = List.of(new LoginEntry("jdoe", Optional.empty()));
        List<Object> asked = new ArrayList<>();
        UserDirectoryPort answering = new UserDirectoryPort() {
            @Override
            public List<ListedUser> listUsers() {
                return users;
            }

            @Override
            public List<UserFacts> readUsers(List<String> page) {
                asked.add(page);
                return facts;
            }

            @Override
            public List<LoginEntry> loggedIn() {
                return logins;
            }
        };

        UserDirectoryPort wrapped = stale.ports(withUserDirectory(answering)).userDirectory();
        assertThat(wrapped).isInstanceOf(StaleReportingPorts.UserDirectory.class);
        assertThat(wrapped.listUsers()).isSameAs(users);
        assertThat(wrapped.readUsers(List.of("jdoe"))).isSameAs(facts);
        assertThat(wrapped.loggedIn()).isSameAs(logins);
        assertThat(asked).containsExactly(List.of("jdoe"));
        assertThat(versions.events()).isEmpty();

        for (RuntimeException other : List.of(failure(ErrorCode.NOT_FOUND),
            failure(ErrorCode.FORBIDDEN), new IllegalStateException("bug"))) {
            UserDirectoryPort port = stale.ports(withUserDirectory(throwing(
                UserDirectoryPort.class, other))).userDirectory();
            for (Method method : UserDirectoryPort.class.getMethods()) {
                assertThat(thrownBy(method, port)).as(method.toString()).isSameAs(other);
            }
        }
        assertThat(versions.events()).isEmpty();

        ToolErrorException unexpected = failure(ErrorCode.UNEXPECTED_RESPONSE);
        UserDirectoryPort port = stale.ports(withUserDirectory(throwing(
            UserDirectoryPort.class, unexpected))).userDirectory();
        for (Method method : UserDirectoryPort.class.getMethods()) {
            assertThat(thrownBy(method, port)).as(method.toString()).isSameAs(unexpected);
        }
        assertThat(versions.events()).containsExactly("stale dev/node1", "stale dev/node1",
            "stale dev/node1");
    }

    /** A port set of throwing ports whose user directory port is {@code userDirectory}. */
    private static PortSet withUserDirectory(UserDirectoryPort userDirectory) {
        PortSet others = throwingPorts(failure(ErrorCode.NOT_FOUND));
        return new PortSet(others.processes(), others.logs(), others.inventory(),
            others.processControl(), others.artifacts(), others.imports(), others.tags(),
            others.partners(), others.queueEntryContent(), others.serverLogs(),
            others.serverSettings(), userDirectory);
    }

    @Test
    void theNodeToolPortsForwardEveryCallUnchanged() {
        // feature 024 T015 (research D-24): arguments and results pass through the wrappers
        InubitVersion version = InubitVersion.parse("8.1.17").orElseThrow();
        long deadline = System.nanoTime();
        Duration timeout = Duration.ofSeconds(30);
        IoMessageRef ref = IoMessageRef.of("4711@@@Orders(acme)(Module-0003)@@@Input.1");
        LoggerName logger = new LoggerName("com.inubit.ibis.acme.Orders");
        ClusterNodes nodes = new ClusterNodes("node1", List.of(), 0, false, Optional.empty());
        IoLogListing listing = new IoLogListing("node1", List.of());
        IoLogPort.IoLogRead noMatch = new IoLogPort.IoLogRead.NoMatch(listing);
        NodeConfigValues values = new NodeConfigValues("node1", List.of());
        List<List<Object>> asked = new ArrayList<>();
        PortSet others = throwingPorts(failure(ErrorCode.NOT_FOUND));
        PortSet wrapped = stale.ports(new PortSet(others.processes(), others.logs(),
            others.inventory(), others.processControl(), others.artifacts(), others.imports(),
            others.tags(), others.partners(), others.queueEntryContent(), others.serverLogs(),
            others.serverSettings(), others.userDirectory(),
            answering(ClusterNodePort.class, asked, method -> nodes),
            answering(IoLogPort.class, asked, method ->
                method.equals("list") ? listing : noMatch),
            answering(NodeConfigPort.class, asked, method -> values),
            answering(LogLevelPort.class, asked, method -> "node1")));

        assertThat(wrapped.clusterNodes().nodes(Optional.of("node2"), version, deadline))
            .isSameAs(nodes);
        assertThat(wrapped.ioLog().list("4711", "Workflow-0001", version, deadline))
            .isSameAs(listing);
        assertThat(wrapped.ioLog().read("4711", "Workflow-0001", ref, version, deadline))
            .isSameAs(noMatch);
        assertThat(wrapped.nodeConfig().values(List.of(NodeConfigName.CLUSTER_NAME), version,
            deadline)).isSameAs(values);
        assertThat(wrapped.logLevel().answeringNode(version, deadline)).isEqualTo("node1");
        wrapped.logLevel().raise(TemporaryLevel.DEBUG, logger, version, timeout);
        wrapped.logLevel().resetAll(version, timeout);

        assertThat(asked).containsExactly(
            List.of("nodes", Optional.of("node2"), version, deadline),
            List.of("list", "4711", "Workflow-0001", version, deadline),
            List.of("read", "4711", "Workflow-0001", ref, version, deadline),
            List.of("values", List.of(NodeConfigName.CLUSTER_NAME), version, deadline),
            List.of("answeringNode", version, deadline),
            List.of("raise", TemporaryLevel.DEBUG, logger, version, timeout),
            List.of("resetAll", version, timeout));
        assertThat(versions.events()).isEmpty();
    }

    /**
     * A {@code type} port recording each call (method name, arguments) and answering it with
     * {@code answer} of the method name.
     */
    private static <T> T answering(Class<T> type, List<List<Object>> asked,
        Function<String, Object> answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                List<Object> call = new ArrayList<>(List.of(method.getName()));
                call.addAll(Arrays.asList(args));
                asked.add(call);
                return method.getReturnType() == void.class ? null
                    : answer.apply(method.getName());
            }));
    }

    @Test
    void everyWriteMethodHandsTheSamePermitToThePort() throws Exception {
        // feature 017 (T045, FR-018, research D-24): the wrapper passes the permit unchanged
        WritePermit permit = TestPermits.of(NODE, Capability.DEVELOPMENT_WRITE);
        List<Object> received = new ArrayList<>();
        ToolErrorException done = failure(ErrorCode.NOT_FOUND);
        int writes = 0;
        for (Class<?> type : List.of(ProcessControlPort.class, ImportPort.class, TagPort.class)) {
            PortSet ports = stale.ports(recording(type, received, done));
            Object port = type == ProcessControlPort.class ? ports.processControl()
                : type == ImportPort.class ? ports.imports() : ports.tags();
            for (Method method : type.getMethods()) {
                int index = List.of(method.getParameterTypes()).indexOf(WritePermit.class);
                if (index < 0) {
                    continue;
                }
                Object[] args = new Object[method.getParameterCount()];
                args[index] = permit;
                received.clear();
                try {
                    method.invoke(port, args);
                } catch (InvocationTargetException e) {
                    assertThat(e.getCause()).isSameAs(done);
                }
                assertThat(received).as(method.toString()).containsExactly(permit);
                writes++;
            }
        }
        assertThat(writes).as("restart, kill, importArchive, importRepository, tag").isEqualTo(5);
        assertThat(versions.events()).isEmpty();
    }

    /** A port set whose {@code type} port records every write permit it gets and throws. */
    private static <T> PortSet recording(Class<T> type, List<Object> received,
        RuntimeException failure) {
        T port = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                for (Object arg : args == null ? new Object[0] : args) {
                    if (arg instanceof WritePermit) {
                        received.add(arg);
                    }
                }
                throw failure;
            }));
        PortSet others = throwingPorts(failure);
        return new PortSet(others.processes(), others.logs(), others.inventory(),
            type == ProcessControlPort.class ? (ProcessControlPort) port
                : others.processControl(),
            others.artifacts(),
            type == ImportPort.class ? (ImportPort) port : others.imports(),
            type == TagPort.class ? (TagPort) port : others.tags(), others.partners());
    }
}
