package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T004: an external system element with a numeric node id and copied lists. */
class ExternalSystemTest {

    private static final DiagramRef SD = new DiagramRef("SD-HTTP", "GRP-CONNECT");

    private static ExternalSystem withNodeId(String nodeId) {
        return new ExternalSystem(SD, "Central-Backend", nodeId, Optional.empty(), List.of(),
            List.of());
    }

    @Test
    void anExternalSystemKeepsItsFields() {
        Property server = Property.shown("ServerName", "String", "https://central.example.test",
            false);

        ExternalSystem system = new ExternalSystem(SD, "Central-Backend", "201",
            Optional.of("HTTP Connector"), List.of(server), List.of("Demo-HTTP-Out-A"));

        assertThat(system.systemDiagram()).isEqualTo(SD);
        assertThat(system.element()).isEqualTo("Central-Backend");
        assertThat(system.nodeId()).isEqualTo("201");
        assertThat(system.connectorType()).contains("HTTP Connector");
        assertThat(system.settings()).containsExactly(server);
        assertThat(system.linkedNames()).containsExactly("Demo-HTTP-Out-A");
    }

    @Test
    void theNodeIdHasOneToTenDigits() {
        assertThat(withNodeId("7").nodeId()).isEqualTo("7");
        assertThat(withNodeId("1234567890").nodeId()).isEqualTo("1234567890");

        for (String invalid : List.of("", "12345678901", "20a", "-1", " 201", "²")) {
            assertThatIllegalArgumentException().isThrownBy(() -> withNodeId(invalid))
                .withMessageContaining("nodeId");
        }
    }

    @Test
    void theListsAreCopied() {
        List<String> names = new ArrayList<>(List.of("Demo-HTTP-Out-A"));
        ExternalSystem system = new ExternalSystem(SD, "Central-Backend", "201",
            Optional.empty(), List.of(), names);

        names.add("Demo-HTTP-Out-B");

        assertThat(system.linkedNames()).containsExactly("Demo-HTTP-Out-A");
        assertThatThrownBy(() -> system.linkedNames().add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyFieldIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new ExternalSystem(null,
            "Central-Backend", "201", Optional.empty(), List.of(), List.of()))
            .withMessage("systemDiagram");
        assertThatNullPointerException().isThrownBy(() -> new ExternalSystem(SD, null, "201",
            Optional.empty(), List.of(), List.of())).withMessage("element");
        assertThatNullPointerException().isThrownBy(() -> new ExternalSystem(SD,
            "Central-Backend", null, Optional.empty(), List.of(), List.of()))
            .withMessage("nodeId");
        assertThatNullPointerException().isThrownBy(() -> new ExternalSystem(SD,
            "Central-Backend", "201", null, List.of(), List.of()))
            .withMessage("connectorType");
    }
}
