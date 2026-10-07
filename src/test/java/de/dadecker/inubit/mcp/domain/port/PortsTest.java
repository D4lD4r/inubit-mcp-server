package de.dadecker.inubit.mcp.domain.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.XsltPort.XsltRequest;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void aGatewayWithoutImportOrUserAdapterReportsThemAsUnavailableForItsNode() {
        // feature 004 (T014)
        assertThatThrownBy(() -> new PreviousGateway().imports())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
                assertThat(e.error().node()).contains(NODE);
            });
        assertThatThrownBy(() -> new PreviousGateway().users())
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
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
