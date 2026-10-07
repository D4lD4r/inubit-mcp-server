package de.dadecker.inubit.mcp.adapter.cli.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.VersionHistory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * T083: {@code versionHistory.xml} of the CLI export with history (research R-11, S-6b; fixture
 * {@code cli/export_history_sample.zip}, group {@code GRP-41}).
 */
class VersionHistoryParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    private static VersionHistory recorded() {
        return VersionHistoryParser.parse(DEV,
            ExportFixtures.entry("export_history_sample.zip", "versionHistory.xml"));
    }

    private static VersionHistory parse(String xml) {
        return VersionHistoryParser.parse(DEV, xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void everyWorkflowOfTheGroupHasItsVersionsNewestFirst() {
        VersionHistory history = recorded();

        assertThat(history.workflows()).containsOnlyKeys("Workflow-0101",
            "Workflow-0110", "Workflow-0112");
        List<VersionEntry> alerting = history.workflows().get("Workflow-0101");
        assertThat(alerting).extracting(VersionEntry::version)
            .containsExactly(6, 5, 4, 3, 2, 1);
        assertThat(alerting.get(0)).isEqualTo(new VersionEntry(6, Optional.of("user1"),
            Optional.of(Instant.parse("2026-01-23T06:54:27Z")), Optional.of("[message 111]"),
            Optional.empty(), List.of()));
        assertThat(history.workflows().get("Workflow-0110")).hasSize(21);
        assertThat(history.workflows().get("Workflow-0112")).hasSize(3);
    }

    @Test
    void dateTimeIsLocalBerlinTimeWithoutOffsetInWinterAndSummer() {
        List<VersionEntry> logging = recorded().workflows().get("Workflow-0110");

        VersionEntry summer = logging.stream().filter(v -> v.version() == 16).findFirst()
            .orElseThrow();
        assertThat(summer.checkinAt()).as("15.04.2026 15:35:20 CEST")
            .contains(Instant.parse("2026-04-15T13:35:20Z"));
        VersionEntry winter = recorded().workflows().get("Workflow-0101").get(3);
        assertThat(winter.checkinAt()).as("05.12.2025 09:26:24 CET")
            .contains(Instant.parse("2025-12-05T08:26:24Z"));
    }

    @Test
    void theModulesSectionHasTheModuleHistoriesWithUserCommentsAndCurrentTags() {
        VersionHistory history = recorded();

        assertThat(history.modules()).containsOnlyKeys("Module-0030",
            "Module-0031", "Module-0028");
        assertThat(history.modules().get("Module-0030")).hasSize(56);
        assertThat(history.modules().get("Module-0031")).hasSize(5);
        assertThat(history.modules().get("Module-0028")).containsExactly(
            new VersionEntry(2, Optional.of("user1"),
                Optional.of(Instant.parse("2025-11-26T15:20:41Z")),
                Optional.of("[message 166]"), Optional.of("[message 122]"),
                List.of("TAG-01")),
            new VersionEntry(1, Optional.of("user1"),
                Optional.of(Instant.parse("2025-11-26T15:16:55Z")),
                Optional.of("[message 122]"), Optional.empty(), List.of()));
    }

    @Test
    void versionsAreSortedByVersionNumberWhateverTheFileOrder() {
        VersionHistory history = parse("<VersionInformation><Workflows>"
            + "<WorkflowGroup Name='G'><Workflow Name='W' Type='technical'>"
            + "<Version><versionNode>10</versionNode></Version>"
            + "<Version><versionNode>2</versionNode><Tags><Tag> A </Tag><Tag>B</Tag></Tags>"
            + "</Version>"
            + "<Version><versionNode>9</versionNode><DateTime>31.02.2026 25:00:00</DateTime>"
            + "</Version>"
            + "</Workflow></WorkflowGroup></Workflows></VersionInformation>");

        List<VersionEntry> versions = history.workflows().get("W");
        assertThat(versions).extracting(VersionEntry::version).containsExactly(10, 9, 2);
        assertThat(versions.get(1).checkinAt()).as("unparseable DateTime").isEmpty();
        assertThat(versions.get(1).checkinUser()).isEmpty();
        assertThat(versions.get(2).tags()).containsExactly("A", "B");
        assertThat(history.modules()).isEmpty();
    }

    @Test
    void anotherDocumentOrAVersionWithoutNumberIsAnUnexpectedResponse() {
        assertThatThrownBy(() -> parse("<IBISWorkflow/>"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
            });
        assertThatThrownBy(() -> parse("<VersionInformation><Modules><Module Name='M'>"
            + "<Version><versionNode>x</versionNode></Version></Module></Modules>"
            + "</VersionInformation>"))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    @Test
    void theHistoryWithGroupsAndTypesServesTheTagCheck() {
        // T021 (research D-16): which diagram group each diagram belongs to, and its tags
        String xml = """
            <VersionInformation><Workflows>
              <WorkflowGroup Name="GRP-01">
                <Workflow Name="W-1" Type="technical">
                  <Version><versionNode>1</versionNode></Version>
                  <Version><versionNode>2</versionNode><Tags><Tag>REL-1</Tag></Tags></Version>
                </Workflow>
              </WorkflowGroup>
              <WorkflowGroup Name="GRP-02">
                <Workflow Name="W-2" Type="bpd">
                  <Version><versionNode>3</versionNode></Version>
                </Workflow>
              </WorkflowGroup>
            </Workflows><Modules>
              <Module Name="M-1"><Version><versionNode>4</versionNode><Tags><Tag>REL-1</Tag>
                <Tag>OLD</Tag></Tags></Version></Module>
            </Modules></VersionInformation>""";

        var history = VersionHistoryParser.parseHistory(NodeId.parse("dev/node1"),
            xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(history.diagrams()).containsOnlyKeys("W-1", "W-2");
        assertThat(history.diagrams().get("W-1").diagramGroup()).isEqualTo("GRP-01");
        assertThat(history.diagrams().get("W-1").type()).isEqualTo("technical");
        assertThat(history.diagrams().get("W-1").versions().get(0).version()).isEqualTo(2);
        assertThat(history.diagrams().get("W-1").versions().get(0).tags())
            .containsExactly("REL-1");
        assertThat(history.diagrams().get("W-2").diagramGroup()).isEqualTo("GRP-02");
        assertThat(history.modules().get("M-1").get(0).tags()).containsExactly("REL-1", "OLD");
    }
}
