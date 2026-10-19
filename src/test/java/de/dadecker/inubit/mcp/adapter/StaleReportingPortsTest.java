package de.dadecker.inubit.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.testing.RecordingVersionObserver;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Feature 009 T007 (research D-10): every method of every port the factory hands out reports
 * {@code stale(node)} for an {@code UNEXPECTED_RESPONSE} and rethrows the same exception; other
 * failures and results pass through unreported.
 */
class StaleReportingPortsTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");

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
            throwing(TagPort.class, failure));
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
        return all;
    }

    /** Calls {@code method} with default arguments and returns what it threw. */
    private static Throwable thrownBy(Method method, Object port) throws IllegalAccessException {
        Object[] args = new Object[method.getParameterCount()];
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            args[i] = types[i] == int.class ? 0 : types[i] == boolean.class ? false : null;
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
        for (Object[] entry : wrapped(unexpected)) {
            for (Method method : ((Class<?>) entry[0]).getMethods()) {
                if (method.isDefault() || Modifier.isStatic(
                    method.getModifiers())) {
                    continue;
                }
                assertThat(thrownBy(method, entry[1])).as(method.toString()).isSameAs(unexpected);
                methods++;
                assertThat(versions.events()).as(method.toString()).hasSize(methods)
                    .allMatch("stale dev/node1"::equals);
            }
        }
        assertThat(methods).isGreaterThan(20);
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
}
