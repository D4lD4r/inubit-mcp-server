package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** T080: the diagram list of {@code GET /ibis/rest/model/models?user=<owner>} (R-11, S-6). */
class ModelListParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    private static List<InventoryItem> parse(String xml) {
        return ModelListParser.parse(DEV, "OWNERS", xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void everyModelOfTheRecordedListBecomesADiagramOfTheOwner() {
        List<InventoryItem> items = ModelListParser.parse(DEV, "OWNERS",
            RestFixtures.bytes("model_models_owner.xml"));

        assertThat(items).hasSize(443);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.node()).isEqualTo(DEV);
            assertThat(item.kind()).isEqualTo(InventoryKind.DIAGRAM);
            assertThat(item.owner()).isEqualTo("OWNERS");
            assertThat(item.active()).isEmpty();
            assertThat(item.lastChange()).isEmpty();
            assertThat(item.workflows()).isEmpty();
            assertThat(item.workflowCount()).isEmpty();
        });
        Map<String, Long> types = items.stream()
            .collect(Collectors.groupingBy(InventoryItem::type, Collectors.counting()));
        assertThat(types).containsExactlyInAnyOrderEntriesOf(Map.of("technical", 392L,
            "bpd", 39L, "systemdiagram", 10L, "organigram", 1L, "constraintsdiagram", 1L));
        assertThat(items.get(0)).isEqualTo(InventoryItem.diagram(DEV,
            "Workflow-0054", "technical", "GRP-06", "OWNERS"));
        assertThat(items).filteredOn(item -> item.group().equals("GRP-41")).hasSize(22);
        assertThat(items).contains(InventoryItem.diagram(DEV, "Workflow-0012",
            "bpd", "OWNERS", "OWNERS"));
    }

    @Test
    void elementsAndAttributesAreMatchedByLocalNameInAnyNamespace() {
        List<InventoryItem> prefixed = parse("<a:ModelList xmlns:a='urn:x' xmlns:b='urn:y'>"
            + "<a:Model name='W1' type='technical' b:version='head' group='G1'/></a:ModelList>");
        List<InventoryItem> plain = parse("<ModelList xmlns='urn:z'>"
            + "<Model name='W1' type='technical' version='head' group='G1'>"
            + "<Comment>c</Comment></Model></ModelList>");

        assertThat(prefixed).containsExactly(InventoryItem.diagram(DEV, "W1", "technical", "G1",
            "OWNERS"));
        assertThat(plain).isEqualTo(prefixed);
    }

    @Test
    void anEmptyModelListGivesAnEmptyList() {
        assertThat(parse("<?xml version='1.0'?><ns4:ModelList"
            + " xmlns:ns4='inubit.com/ibis/external/model'/>")).isEmpty();
    }

    @Test
    void aMissingTypeOrGroupBecomesEmptyText() {
        assertThat(parse("<ModelList><Model name='W1'/></ModelList>"))
            .containsExactly(InventoryItem.diagram(DEV, "W1", "", "", "OWNERS"));
    }

    @Test
    void anotherDocumentOrAModelWithoutNameIsAnUnexpectedResponseOfTheServer() {
        assertThatThrownBy(() -> parse("<html><body>login</body></html>"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
            });
        assertThatThrownBy(() -> parse("<ModelList><Model type='technical'/></ModelList>"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
            });
        assertThatThrownBy(() -> parse("not xml"))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().node()).contains(DEV));
    }
}
