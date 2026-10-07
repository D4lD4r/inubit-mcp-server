package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol.Action;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol.Entry;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * T013 (feature 004, research D-8): the fixed-width import protocol of StartCLI
 * ({@code --returnProtocol}), against the recorded fixtures.
 */
class ImportProtocolParserTest {

    private static ImportProtocol parse(String fixtureCase) {
        return ImportProtocolParser.parse(FakeProcessLauncher.fixtureText(fixtureCase
            + ".stdout"));
    }

    @Test
    void createdArtifactsAreParsedWithKindNameAndOwner() {
        ImportProtocol protocol = parse("import_created");

        assertThat(protocol.total()).isEqualTo(5);
        assertThat(protocol.entries()).hasSize(5).allSatisfy(entry -> {
            assertThat(entry.type()).isEqualTo("INFORMATION");
            assertThat(entry.action()).contains(Action.CREATED);
            assertThat(entry.groupOrUser()).isEqualTo("jdoe");
        });
        assertThat(protocol.entries().get(0)).isEqualTo(new Entry("INFORMATION",
            "Module [SPIKE_C_XSLT-Converter-01] was created.",
            "SPIKE_Roundtrip_Claude/SPIKE_C_XSLT-Converter-01", "jdoe",
            Optional.of(ImportProtocol.Kind.MODULE), Optional.of("SPIKE_C_XSLT-Converter-01"),
            Optional.of(Action.CREATED)));
        assertThat(protocol.entries().get(4).kind()).contains(ImportProtocol.Kind.WORKFLOW);
        assertThat(protocol.created()).containsExactly("SPIKE_C_XSLT-Converter-01",
            "SPIKE_C_Assign-02", "SPIKE_C_Assign-01", "SPIKE_C_Demux-01",
            "SPIKE_Roundtrip_Claude");
        assertThat(protocol.modified()).isEmpty();
    }

    @Test
    void createdAndModifiedAreSeparated() {
        ImportProtocol protocol = parse("import_created_and_modified");

        assertThat(protocol.created()).containsExactly("SPIKE_C_Assign-03");
        assertThat(protocol.modified()).hasSize(5).contains("SPIKE_Roundtrip_Claude");
    }

    @Test
    void aModuleOnlyAndAWorkflowOnlyProtocolHaveOneRow() {
        assertThat(parse("import_module_only").entries()).singleElement().satisfies(entry -> {
            assertThat(entry.diagramOrModule()).isEqualTo("/SPIKE_C_XSLT-Converter-01");
            assertThat(entry.name()).contains("SPIKE_C_XSLT-Converter-01");
            assertThat(entry.action()).contains(Action.MODIFIED);
        });
        assertThat(parse("import_workflow_only").modified())
            .containsExactly("SPIKE_Roundtrip_Claude");
    }

    /** One fixed-width row as StartCLI pads it. */
    private static String row(String type, String description, String diagram, String user) {
        return String.format("%-12s%-32s%-15s%s\r\n", type, description, diagram, user);
    }

    @Test
    void namesWithSpacesKeepTheirColumns() {
        String stdout = row("TYPE", "DESCRIPTION", "DIAGRAM/MODULE", "GROUP/USER")
            + row("INFORMATION", "Module [My Module] was created.", "WF A/My Module", "OWNERS ")
            + "Total: 1\r\n";

        Entry entry = ImportProtocolParser.parse(stdout).entries().get(0);

        assertThat(entry.name()).contains("My Module");
        assertThat(entry.diagramOrModule()).isEqualTo("WF A/My Module");
        assertThat(entry.groupOrUser()).isEqualTo("OWNERS");
    }

    @Test
    void aProtocolWithoutTotalOrWithAWrongTotalIsUnexpected() {
        String header = row("TYPE", "DESCRIPTION", "DIAGRAM/MODULE", "GROUP/USER");
        String line = row("INFORMATION", "Diagram [W] was modified.", "W", "jdoe");
        for (String stdout : List.of(header + line, header + line + "Total: 2\r\n",
            "nothing")) {
            assertThatThrownBy(() -> ImportProtocolParser.parse(stdout))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                    e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
        }
    }

    @Test
    void anOtherRowKeepsItsDescriptionWithoutAction() {
        String stdout = row("TYPE", "DESCRIPTION", "DIAGRAM/MODULE", "GROUP/USER")
            + row("WARNING", "Something happened", "W", "jdoe") + "Total: 1\r\n";

        Entry entry = ImportProtocolParser.parse(stdout).entries().get(0);

        assertThat(entry.type()).isEqualTo("WARNING");
        assertThat(entry.action()).isEmpty();
        assertThat(entry.kind()).isEmpty();
        assertThat(entry.description()).isEqualTo("Something happened");
    }
}
