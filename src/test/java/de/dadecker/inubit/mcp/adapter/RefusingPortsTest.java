package de.dadecker.inubit.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature 011 T025: the ports INUBIT 6.1 does not offer refuse with {@code UNSUPPORTED_VERSION}
 * without contact (research D-4, second line of defence behind the version gate).
 */
class RefusingPortsTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");

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

        Throwable thrown = catchThrowable(() ->
            method.invoke(refusing, new Object[method.getParameterCount()]));

        assertThat(thrown).isInstanceOf(InvocationTargetException.class);
        assertThat(thrown.getCause()).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
            assertThat(e.error().node()).contains(NODE);
        });
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
