package de.dadecker.inubit.mcp.domain.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.XsltPort.XsltRequest;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** T006: the ports of feature 003 and the default of {@link Gateway#artifacts()}. */
class PortsTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");

    /** A gateway that implements only what existed before feature 003. */
    private static final class PreviousGateway implements Gateway {

        @Override
        public NodeId node() {
            return NODE;
        }

        @Override
        public AdapterLine adapterLine() {
            return AdapterLine.V8_1;
        }

        @Override
        public Optional<String> detectedVersion() {
            return Optional.empty();
        }

        @Override
        public List<String> warnings() {
            return List.of();
        }

        @Override
        public MonitoringPort monitoring() {
            throw new AssertionError();
        }

        @Override
        public ProcessQueryPort processes() {
            throw new AssertionError();
        }

        @Override
        public LogPort logs() {
            throw new AssertionError();
        }

        @Override
        public InventoryPort inventory() {
            throw new AssertionError();
        }

        @Override
        public ProcessControlPort processControl() {
            throw new AssertionError();
        }
    }

    @Test
    void aGatewayWithoutArtifactAdapterReportsTheCliAsUnavailableForItsNode() {
        assertThatThrownBy(() -> new PreviousGateway().artifacts())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().node()).contains(NODE);
                assertThat(e.error().message()).contains("dev/node1");
            });
    }

    @Test
    void aGatewayWithoutImportOrTagAdapterReportsThemAsUnavailableForItsNode() {
        // feature 004 (T014)
        assertThatThrownBy(() -> new PreviousGateway().imports())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().node()).contains(NODE);
            });
        // T021
        assertThatThrownBy(() -> new PreviousGateway().tags())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().node()).contains(NODE);
            });
    }

    @Test
    void aGatewayWithoutPartnerManagementAdapterRefusesItAsUnsupportedForItsNode() {
        // feature 018 (T013, research D-4)
        assertThatThrownBy(() -> new PreviousGateway().partners())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                assertThat(e.error().node()).contains(NODE);
                assertThat(e.error().message())
                    .contains("partner management is not available for this INUBIT version");
            });
    }

    @Test
    void aGatewayWithoutQueueEntryAdapterReportsTheCliAsUnavailableForItsNode() {
        // feature 020 T022 (research D-17): adapter lines without the CLI read
        assertThatThrownBy(() -> new PreviousGateway().queueEntryContent())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().node()).contains(NODE);
                assertThat(e.error().message()).contains("dev/node1");
            });
        GatewayFactory factory = (GatewayFactory) Proxy.newProxyInstance(
            GatewayFactory.class.getClassLoader(), new Class<?>[] {GatewayFactory.class},
            (proxy, method, args) -> {
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                if (method.getName().equals("forServer")) {
                    return new PreviousGateway();
                }
                throw new AssertionError(method + " was not expected");
            });
        assertThatThrownBy(() -> factory.queueEntryContent(NODE))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE));
    }

    @Test
    void aGatewayWithoutServerLogAdapterReportsTheCliAsUnavailableForItsNode() {
        // feature 021 T011 (research D-19): adapter lines without a server-log adapter
        assertThatThrownBy(() -> new PreviousGateway().serverLogs())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().node()).contains(NODE);
                assertThat(e.error().message()).contains("dev/node1");
            });
        GatewayFactory factory = (GatewayFactory) Proxy.newProxyInstance(
            GatewayFactory.class.getClassLoader(), new Class<?>[] {GatewayFactory.class},
            (proxy, method, args) -> {
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                if (method.getName().equals("forServer")) {
                    return new PreviousGateway();
                }
                throw new AssertionError(method + " was not expected");
            });
        assertThatThrownBy(() -> factory.serverLogs(NODE))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE));
    }

    @Test
    void aGatewayWithoutServerSettingsAdapterRefusesThemAsUnsupportedForItsNode() {
        // feature 022 T020: adapter lines without a settings adapter
        assertThatThrownBy(() -> new PreviousGateway().serverSettings())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                assertThat(e.error().node()).contains(NODE);
                assertThat(e.error().message()).contains("Server settings", "dev/node1");
            });
        GatewayFactory factory = (GatewayFactory) Proxy.newProxyInstance(
            GatewayFactory.class.getClassLoader(), new Class<?>[] {GatewayFactory.class},
            (proxy, method, args) -> {
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                if (method.getName().equals("forServer")) {
                    return new PreviousGateway();
                }
                throw new AssertionError(method + " was not expected");
            });
        assertThatThrownBy(() -> factory.serverSettings(NODE))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION));
    }

    @Test
    void aGatewayWithoutUserAdapterRefusesUserReadsAsUnsupportedForItsNode() {
        // feature 022 T033: adapter lines without a user directory adapter
        assertThatThrownBy(() -> new PreviousGateway().userDirectory())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                assertThat(e.error().node()).contains(NODE);
                assertThat(e.error().message()).contains("User reads", "dev/node1");
            });
        GatewayFactory factory = (GatewayFactory) Proxy.newProxyInstance(
            GatewayFactory.class.getClassLoader(), new Class<?>[] {GatewayFactory.class},
            (proxy, method, args) -> {
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                if (method.getName().equals("forServer")) {
                    return new PreviousGateway();
                }
                throw new AssertionError(method + " was not expected");
            });
        assertThatThrownBy(() -> factory.userDirectory(NODE))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION));
    }

    @Test
    void aGatewayWithoutNodeToolAdaptersReportsTheCliAsUnavailableForItsNode() {
        // feature 024 T015 (research D-24): adapter lines without the node-tool adapters
        Gateway gateway = new PreviousGateway();
        List<ThrowingCallable> accessors = List.of(
            gateway::clusterNodes, gateway::ioLog, gateway::nodeConfig, gateway::logLevel);
        for (ThrowingCallable accessor : accessors) {
            assertThatThrownBy(accessor)
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                    assertThat(e.error().node()).contains(NODE);
                    assertThat(e.error().message()).contains("dev/node1");
                });
        }
        GatewayFactory factory = (GatewayFactory) Proxy.newProxyInstance(
            GatewayFactory.class.getClassLoader(), new Class<?>[] {GatewayFactory.class},
            (proxy, method, args) -> {
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                if (method.getName().equals("forServer")) {
                    return new PreviousGateway();
                }
                throw new AssertionError(method + " was not expected");
            });
        List<ThrowingCallable> ports = List.of(
            () -> factory.clusterNodes(NODE), () -> factory.ioLog(NODE),
            () -> factory.nodeConfig(NODE), () -> factory.logLevel(NODE));
        for (ThrowingCallable port : ports) {
            assertThatThrownBy(port)
                .isInstanceOfSatisfying(ToolErrorException.class, e ->
                    assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE));
        }
    }

    @Test
    void anXsltRequestCopiesItsParameters() {
        Map<String, String> params = new HashMap<>(Map.of("lang", "de"));
        XsltRequest request = new XsltRequest(Path.of("a.xsl"), Path.of("in.xml"), params,
            Optional.of(Instant.parse("2026-10-06T10:00:00Z")));

        params.put("other", "x");

        assertThat(request.params()).containsExactly(Map.entry("lang", "de"));
        assertThatThrownBy(() -> request.params().put("x", "y"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new XsltRequest(Path.of("a.xsl"), Path.of("in.xml"), null, null).now())
            .isEmpty();
        assertThatNullPointerException().isThrownBy(() -> new XsltRequest(null,
            Path.of("in.xml"), Map.of(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new XsltRequest(Path.of("a.xsl"),
            null, Map.of(), Optional.empty()));
    }

    @Test
    void theHistoryPortOffersNoWayToTransmitTheHistory() {
        // FR-007: never a remote, push, fetch or clone; feature 004 (T007): the only writing
        // methods are init, commitAll and restore, the others read
        assertThat(Arrays.stream(VersionHistoryPort.class.getMethods()).map(Method::getName))
            .containsExactlyInAnyOrder("init", "status", "commitAll", "commitAll", "restore",
                "lastServerState", "serverStateOf", "show", "changedPaths", "localChanges");
    }
}
