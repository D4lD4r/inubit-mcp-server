package de.dadecker.inubit.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature 012 T025: the ports INUBIT 6.1 does not offer refuse with {@code UNSUPPORTED_VERSION}
 * without contact (research D-4, second line of defence behind the version gate).
 */
class RefusingPortsTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");

    private static final QueueEntryRef REF = new QueueEntryRef(NODE, "Workflow-0001",
        "Order-0001.xml", 772);
    private static final InubitVersion V61 = InubitVersion.parse("6.1.0").orElseThrow();

    private final RefusingPorts refusing = new RefusingPorts(NODE);

    /** Every abstract method of every port a {@link PortSet} holds. */
    static Stream<Method> portMethods() {
        return Stream.of(PortSet.class.getRecordComponents()).map(RecordComponent::getType)
            .flatMap(type -> Stream.of(type.getMethods()))
            .filter(method -> !method.isDefault() && !Modifier.isStatic(method.getModifiers()));
    }

    @ParameterizedTest
    @MethodSource("portMethods")
    void everyPortMethodRefusesWithUnsupportedVersion(Method method) {
        assertThat(method.getDeclaringClass()).isAssignableFrom(RefusingPorts.class);

        Object[] args = Stream.of(method.getParameterTypes())
            .map(type -> type == long.class ? (Object) 0L : type == int.class ? (Object) 0 : null)
            .toArray();
        Throwable thrown = catchThrowable(() -> method.invoke(refusing, args));

        assertThat(thrown).isInstanceOf(InvocationTargetException.class);
        assertThat(thrown.getCause()).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(e.error().node()).contains(NODE);
        });
    }

    @Test
    void queueEntriesRefusesWithTheSixOneRefusalWithoutContact() {
        // feature 020 T012 (research D-9): overridden, not the adapter-line default
        assertThatThrownBy(() -> refusing.queueEntries("4711"))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error()).isEqualTo(refusing.refusal().error()));
    }

    @Test
    void queueEntryContentRefusesWithTheSixOneRefusalWithoutContact() {
        // feature 020 T022 (research D-17): not the adapter-line default
        assertThatThrownBy(() -> refusing.read(REF, V61, Set.of(EntryPart.DATA)))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error()).isEqualTo(refusing.refusal().error()));
    }

    @Test
    void thePortSetCompatibilityConstructorFillsInThisRefusalForTheEntrysNode() {
        // feature 020 T022 (research D-17): port sets built with the previous eight ports
        PortSet ports = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing);

        NodeId other = NodeId.parse("int/b");
        QueueEntryRef ref = new QueueEntryRef(other, "Workflow-0001", "Order-0001.xml", 772);

        assertThatThrownBy(() -> ports.queueEntryContent().read(ref, V61,
            Set.of(EntryPart.VARIABLES)))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error()).isEqualTo(new RefusingPorts(other).refusal().error()));
    }

    @Test
    void serverLogsRefuseWithTheSixOneRefusalWithoutContact() {
        // feature 021 T011 (research D-19): not the CLI_UNAVAILABLE of a port set without adapter
        assertThatThrownBy(() -> refusing.download(ServerLogKind.TRACE, V61, System.nanoTime()))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error()).isEqualTo(refusing.refusal().error());
                assertThat(e.error().message()).contains("dev/node1", "INUBIT 6.1",
                    "nothing was sent");
            });
    }

    @Test
    void thePortSetCompatibilityConstructorsFillInAnUnavailableServerLogPort() {
        // feature 021 T011 (research D-19): port sets built with the previous eight or nine ports
        PortSet eight = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing);
        PortSet nine = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing, refusing);

        for (PortSet ports : List.of(eight, nine)) {
            assertThatThrownBy(() -> ports.serverLogs().download(ServerLogKind.WEBSERVICE, V61,
                System.nanoTime()))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                    assertThat(e.error().message()).contains("Server log downloads");
                });
        }
    }

    /** One call of every method of the node-tool ports of feature 024 on {@code ports}. */
    private static List<ThrowingCallable> nodeToolCalls(PortSet ports) {
        long deadline = System.nanoTime();
        Duration timeout = Duration.ofSeconds(30);
        return List.of(
            () -> ports.clusterNodes().nodes(Optional.empty(), V61, deadline),
            () -> ports.ioLog().list("4711", "Workflow-0001", V61, deadline),
            () -> ports.ioLog().read("4711", "Workflow-0001",
                IoMessageRef.of("4711@@@Orders(acme)(Module-0003)@@@Input.1"), V61, deadline),
            () -> ports.nodeConfig().values(List.of(NodeConfigName.CLUSTER_NAME), V61, deadline),
            () -> ports.logLevel().answeringNode(V61, deadline),
            () -> ports.logLevel().raise(TemporaryLevel.DEBUG,
                new LoggerName("com.inubit.ibis.acme.Orders"), V61, timeout),
            () -> ports.logLevel().resetAll(V61, timeout));
    }

    @Test
    void theNodeToolPortsRefuseWithTheSixOneRefusalWithoutContact() {
        // feature 024 T015 (research D-24): not the CLI_UNAVAILABLE of a port set without adapter
        PortSet ports = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing, refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing);

        for (ThrowingCallable call : nodeToolCalls(ports)) {
            assertThatThrownBy(call)
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error()).isEqualTo(refusing.refusal().error());
                    assertThat(e.error().message()).contains("dev/node1", "INUBIT 6.1",
                        "nothing was sent");
                });
        }
    }

    @Test
    void thePortSetCompatibilityConstructorsFillInUnavailableNodeToolPorts() {
        // feature 024 T015 (research D-24): port sets built with the previous eight to ten ports
        PortSet eight = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing);
        PortSet nine = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing, refusing);
        PortSet ten = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing, refusing, refusing);

        for (PortSet ports : List.of(eight, nine, ten)) {
            for (ThrowingCallable call : nodeToolCalls(ports)) {
                assertThatThrownBy(call)
                    .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                        assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                        assertThat(e.error().message()).contains("not available");
                    });
            }
        }
    }

    @Test
    void serverSettingsRefuseWithTheSixOneRefusalWithoutContact() {
        // feature 022 T020: no settings reads on 6.1 (spec edge case "INUBIT 6.1")
        for (Runnable call : List.<Runnable>of(refusing::license,
            () -> refusing.configuration(Optional.empty(), 100, 50_000),
            () -> refusing.cliSettings(Set.of(UnavailablePart.THREADS)))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error()).isEqualTo(refusing.refusal().error()));
        }
    }

    @Test
    void thePortSetCompatibilityConstructorsFillInARefusingServerSettingsPort() {
        // feature 022 T020: port sets built with the previous eight, nine or ten ports
        PortSet eight = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing);
        PortSet nine = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing, refusing);
        PortSet ten = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing, refusing, refusing);

        for (PortSet ports : List.of(eight, nine, ten)) {
            for (Runnable call : List.<Runnable>of(() -> ports.serverSettings().license(),
                () -> ports.serverSettings().configuration(Optional.empty(), 100, 50_000),
                () -> ports.serverSettings().cliSettings(Set.of()))) {
                assertThatThrownBy(call::run).isInstanceOfSatisfying(ToolErrorException.class,
                    e -> {
                        assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                        assertThat(e.error().message()).contains("Server settings");
                    });
            }
        }
    }

    @Test
    void userReadsRefuseWithTheSixOneRefusalWithoutContact() {
        // feature 022 T033: no user reads on 6.1 (spec edge case "INUBIT 6.1")
        for (Runnable call : List.<Runnable>of(refusing::listUsers,
            () -> refusing.readUsers(List.of("jdoe")), refusing::loggedIn)) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error()).isEqualTo(refusing.refusal().error()));
        }
    }

    @Test
    void thePortSetCompatibilityConstructorsFillInARefusingUserDirectoryPort() {
        // feature 022 T033: port sets built with the previous eight or eleven ports
        PortSet eight = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing);
        PortSet eleven = new PortSet(refusing, refusing, refusing, refusing, refusing, refusing,
            refusing, refusing, refusing, refusing, refusing);

        for (PortSet ports : List.of(eight, eleven)) {
            for (Runnable call : List.<Runnable>of(() -> ports.userDirectory().listUsers(),
                () -> ports.userDirectory().readUsers(List.of()),
                () -> ports.userDirectory().loggedIn())) {
                assertThatThrownBy(call::run).isInstanceOfSatisfying(ToolErrorException.class,
                    e -> {
                        assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                        assertThat(e.error().message()).contains("User reads");
                    });
            }
        }
    }

    @Test
    void theRefusalNamesTheNodeAndThatNothingWasSent() {
        assertThatThrownBy(() -> refusing.query(null))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().message()).contains("dev/node1", "INUBIT 6.1",
                    "nothing was sent");
                assertThat(e.error().nextStep()).contains("list_nodes");
            });
    }
}
