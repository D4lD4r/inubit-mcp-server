package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.DiagramDetail;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** T081: the nodes of {@code GET /ibis/rest/model/modelByName/<name>?user=<owner>}. */
class ModelDetailParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    private static DiagramDetail parse(String xml) {
        return ModelDetailParser.parse(DEV, xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theNodesBecomeModulesWithNameTypeAndNodeIdAndTheVersionIsRead() {
        DiagramDetail detail = ModelDetailParser.parse(DEV,
            RestFixtures.bytes("modelByName_sample.xml"));

        assertThat(detail.name()).isEqualTo("Workflow-0101");
        assertThat(detail.type()).contains("technical");
        assertThat(detail.version()).contains("head");
        assertThat(detail.modules()).containsExactly(
            new ModuleRef("Module-0030", "twEmpty", "133"),
            new ModuleRef("Module-0032", "twWorkflowConnector", "102"),
            new ModuleRef("Module-0030", "twEmpty", "105"));
    }

    @Test
    void nodesWithoutNameAreSkippedAndAMissingIdOrTypeIsEmpty() {
        DiagramDetail detail = parse("<Model name='W'><Node type='twStart' id='1'/>"
            + "<Node name='M1'/></Model>");

        assertThat(detail.type()).isEmpty();
        assertThat(detail.version()).isEmpty();
        assertThat(detail.modules()).containsExactly(new ModuleRef("M1", "", ""));
    }

    @Test
    void anotherDocumentIsAnUnexpectedResponseOfTheServer() {
        assertThatThrownBy(() -> parse("<ModelList/>"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
            });
        assertThatThrownBy(() -> parse("<Model type='technical'/>"))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }
}
