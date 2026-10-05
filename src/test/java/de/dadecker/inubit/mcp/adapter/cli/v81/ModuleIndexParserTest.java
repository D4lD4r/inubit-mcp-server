package de.dadecker.inubit.mcp.adapter.cli.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.ModuleEntry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * T084: {@code module/module.xml} of the CLI module export (research R-11, S-6b; fixture
 * {@code cli/export_modules_sample.zip}, 20 modules of the module group {@code AS2 Connector}).
 */
class ModuleIndexParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    private static List<ModuleEntry> recorded() {
        return ModuleIndexParser.parse(DEV, "OWNERS",
            ExportFixtures.entry("export_modules_sample.zip", "module/module.xml"));
    }

    private static List<ModuleEntry> parse(String xml) {
        return ModuleIndexParser.parse(DEV, "OWNERS", xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void everyModuleBecomesAModuleItemWithPluginTypeModuleGroupAndLastChange() {
        List<ModuleEntry> modules = recorded();

        assertThat(modules).hasSize(20);
        assertThat(modules.get(0)).isEqualTo(new ModuleEntry(
            InventoryItem.module(DEV, "Module-0001", "AS2 Connector",
                "AS2 Connector", "OWNERS", Optional.of(true),
                Optional.of(Instant.parse("2013-01-18T12:13:25Z"))),
            Optional.of("[message 167]"), Optional.of("[message 168]"),
            new ConnectorFlags(false, true, false),
            Optional.of("Workflow-0283")));
        assertThat(modules).as("usage is added by the application layer (T126)")
            .allSatisfy(module -> {
                assertThat(module.item().workflows()).isEmpty();
                assertThat(module.item().workflowCount()).isEmpty();
            });
        assertThat(modules).allSatisfy(module -> {
            assertThat(module.item().kind()).isEqualTo(InventoryKind.MODULE);
            assertThat(module.item().group()).isEqualTo("AS2 Connector");
            assertThat(module.item().owner()).isEqualTo("OWNERS");
        });
    }

    @Test
    void onlyConnectorsNameTheirWorkflowAndInactiveModulesAreInactive() {
        // F1: WorkflowName is the workflow a connector is bound to, not a usage list
        List<ModuleEntry> modules = recorded();

        assertThat(modules).filteredOn(module -> module.connectorWorkflow().isEmpty())
            .extracting(module -> module.item().name())
            .containsExactly("Module-0022");
        ModuleEntry inactive = modules.get(3);
        assertThat(inactive.item().name()).isEqualTo("Module-0008");
        assertThat(inactive.item().active()).contains(false);
        assertThat(inactive.connector()).isEqualTo(new ConnectorFlags(true, false, false));
        ModuleEntry summer = modules.stream()
            .filter(module -> module.item().name().equals("Module-0017"))
            .findFirst().orElseThrow();
        assertThat(summer.item().lastChange()).as("14.06.2013 16:06:42 CEST")
            .contains(Instant.parse("2013-06-14T14:06:42Z"));
        assertThat(modules.get(6).userComment()).isEmpty();
    }

    @Test
    void exportUserModuleIdAndErrorSuppressionAreNotPassedThrough() {
        String text = recorded().toString();

        assertThat(text).doesNotContain("-12f884e4", "ExportUser", "ErrorSuppression");
    }

    @Test
    void theScheduledFlagAndAMissingPluginNameAreRead() {
        List<ModuleEntry> modules = parse("<IBISWorkflow><Modules><ModuleGroup>"
            + "<ModuleGroupName>Scheduler</ModuleGroupName>"
            + "<Module type='technical'><ModuleName>S1</ModuleName>"
            + "<IsScheduled>true</IsScheduled><LastUpdate>bad</LastUpdate></Module>"
            + "</ModuleGroup></Modules></IBISWorkflow>");

        assertThat(modules).containsExactly(new ModuleEntry(
            InventoryItem.module(DEV, "S1", "Scheduler", "Scheduler", "OWNERS", Optional.empty(),
                Optional.empty()),
            Optional.empty(), Optional.empty(), new ConnectorFlags(false, false, true),
            Optional.empty()));
    }

    @Test
    void anEmptyIndexIsEmptyAndAnotherDocumentOrAModuleWithoutNameIsUnexpected() {
        assertThat(parse("<IBISWorkflow version='5.3'><Modules/></IBISWorkflow>")).isEmpty();
        assertThatThrownBy(() -> parse("<VersionInformation/>"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
            });
        assertThatThrownBy(() -> parse("<IBISWorkflow><Modules><ModuleGroup><Module/>"
            + "</ModuleGroup></Modules></IBISWorkflow>"))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }
}
