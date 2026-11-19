package de.dadecker.inubit.mcp.adapter.cli.v74;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.dadecker.inubit.mcp.adapter.PortSet;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature 025 T008: the 7.4 port set refuses every port and the StartCLI health port with
 * {@code UNSUPPORTED_VERSION} naming the node and INUBIT 7.4, without contact (research D-5,
 * spec FR-011).
 */
class V74PortSetTest {

    private static final NodeId NODE = NodeId.parse("legacy74/node1");

    /** As {@code AdapterGatewayFactory} passes them for a CLI-only range: no REST client. */
    private static final PortSetRegistry.NodeResources RESOURCES =
        new PortSetRegistry.NodeResources(TestNodeConfig.node().id(NODE.value())
            .versionLine(VersionSetting.V7_4).build(), null, null, null, null, null);

    /**
     * Every abstract method of every port of the 7.4 port set, and every default method the port
     * overrides (such as {@code queueEntries}), with the port it is called on.
     */
    static Stream<Arguments> portMethods() throws ReflectiveOperationException {
        PortSet ports = V74PortSet.factory().create(RESOURCES);
        Stream.Builder<Arguments> calls = Stream.builder();
        for (RecordComponent component : PortSet.class.getRecordComponents()) {
            Object port = component.getAccessor().invoke(ports);
            for (Method method : component.getType().getMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                    && (!method.isDefault() || overrides(port, method))) {
                    calls.add(Arguments.of(component.getName(), method, port));
                }
            }
        }
        return calls.build();
    }

    private static boolean overrides(Object port, Method method)
        throws NoSuchMethodException {
        return port.getClass().getMethod(method.getName(), method.getParameterTypes())
            .getDeclaringClass() != method.getDeclaringClass();
    }

    @Test
    void servesExactlyTheSevenFourRange() {
        assertThat(V74PortSet.RANGE).isEqualTo(new VersionRange(7, 4, 7, 4));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("portMethods")
    void everyPortRefusesWithTheSevenFourRefusal(String component, Method method, Object port) {
        Object[] args = Stream.of(method.getParameterTypes())
            .map(type -> type == long.class ? (Object) 0L : type == int.class ? (Object) 0
                : type == boolean.class ? (Object) false : null)
            .toArray();
        Throwable thrown = catchThrowable(() -> method.invoke(port, args));

        assertThat(thrown).isInstanceOf(InvocationTargetException.class);
        assertThat(thrown.getCause()).isInstanceOfSatisfying(ToolErrorException.class,
            e -> assertSevenFourRefusal(e.error()));
    }

    @Test
    void theHealthPortRefusesWithTheSevenFourRefusal() {
        assertThatThrownBy(() -> V74PortSet.cliHealth().create(RESOURCES)
            .snapshot(Duration.ofSeconds(5)))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertSevenFourRefusal(e.error()));
    }

    private static void assertSevenFourRefusal(ToolError error) {
        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.node()).contains(NODE);
        assertThat(error.message()).isEqualTo("This operation is not available on "
            + "legacy74/node1 (INUBIT 7.4, StartCLI only); nothing was sent");
        assertThat(error.likelyCause())
            .isEqualTo("INUBIT 7.4 is recognised, but no tool is verified on it yet");
    }
}
