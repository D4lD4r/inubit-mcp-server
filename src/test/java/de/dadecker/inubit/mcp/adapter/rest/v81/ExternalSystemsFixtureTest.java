package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.management.ExternalSystem;
import de.dadecker.inubit.mcp.domain.model.management.ExternalSystems;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * T005: {@link ExternalSystems#from} on the {@code sdExtSystem} answer of
 * {@code admin-model-node-by-model-sd-ext-system} (structure probed on 8.1.17, values synthetic,
 * four planted test secrets) as {@link ModuleDetailXmlParser} reads it.
 */
class ExternalSystemsFixtureTest {

    private static final DiagramRef SD = new DiagramRef("SD-HTTP", "GRP-CONNECT");
    private static final String[] PLANTED = {"synthetic-secret-3911", "synthetic-secret-3912",
        "synthetic-secret-3913", "synthetic-secret-3914"};

    @Test
    void theRecordedElementBecomesAnExternalSystemWithoutItsSecrets() {
        String fixtureCase = "admin-model-node-by-model-sd-ext-system";
        ModuleDetail detail = ModuleDetailXmlParser.parse(NodeId.parse("dev/node1"), SD.name(),
            "49", new RestResponse(200, Optional.of("application/xml;charset=UTF-8"),
                RestFixtures.bytes(fixtureCase + ".xml")));

        ExternalSystems.Result result = ExternalSystems.from(SD,
            new ModuleRef("Demo-ExtSystem", "sdExtSystem", "49"), detail);

        ExternalSystem system = result.system();
        assertThat(system.systemDiagram()).isEqualTo(SD);
        assertThat(system.element()).isEqualTo("Demo-ExtSystem");
        assertThat(system.nodeId()).isEqualTo("49");
        assertThat(system.connectorType()).contains("HTTP Connector");
        assertThat(system.settings()).hasSize(14)
            .startsWith(Property.shown("ServerName", "String",
                "https://demo-backend.example.test/api/rates", false))
            .contains(Property.masked("Password", "Password"),
                Property.masked("OAUTH_PROPERTY.client_secret", "String"),
                Property.masked("OAUTH_PROPERTY.client_assertion", "String"),
                Property.masked("OAUTH_PROPERTY.password", "String"));
        assertThat(system.linkedNames()).containsExactly("Demo-HTTP-Out-A", "Demo-HTTP-Out-B");
        assertThat(result.warnings()).isEmpty();

        assertThat(result.toString()).doesNotContain(PLANTED);
        assertThat(ResultJson.mapper().writeValueAsString(result)).doesNotContain(PLANTED);
    }
}
