package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.PrivilegedWorkflows;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * T018 (feature 022, US1): the answers of {@code privilegedWorkflows showThreadsPercentage} and
 * {@code privilegedWorkflows showWorkflowList}. The cases
 * {@code admin-settings-privileged-percentage-reconstructed} and
 * {@code admin-settings-privileged-list-reconstructed} are SYNTHETIC until T014 records the real
 * ones.
 */
class PrivilegedWorkflowsParserTest {

    private static final List<String> PERCENTAGE =
        List.of("Percentage of threads reserved for privileged workflows: 20%");
    private static final String HEADER = "Privileged workflows:";

    @Test
    void theReconstructedAnswersGiveThePercentageAndTheWorkflows() {
        PrivilegedWorkflows privileged = PrivilegedWorkflowsParser.parse(
            block("admin-settings-privileged-percentage-reconstructed"),
            block("admin-settings-privileged-list-reconstructed"));

        assertThat(privileged).isEqualTo(new PrivilegedWorkflows(20,
            List.of("Fictitious-Workflow-0001", "Fictitious Workflow 0002"), false));
    }

    @Test
    void aHeaderWithoutNamesIsAnEmptyList() {
        assertThat(PrivilegedWorkflowsParser.parse(PERCENTAGE, List.of(HEADER)).workflows())
            .isEmpty();
    }

    @Test
    void namesKeepInnerSpacesAndLoseSurroundingOnes() {
        assertThat(PrivilegedWorkflowsParser.parse(
            List.of("  Percentage of threads reserved for privileged workflows: 0%  "),
            List.of("  " + HEADER, "   Order Import Workflow  ")))
            .isEqualTo(new PrivilegedWorkflows(0, List.of("Order Import Workflow"), false));
    }

    @Test
    void moreThan200NamesAreCutAndMarked() {
        List<String> list = new ArrayList<>(List.of(HEADER));
        IntStream.rangeClosed(1, 201).forEach(i -> list.add("Fictitious-Workflow-" + i));

        PrivilegedWorkflows privileged = PrivilegedWorkflowsParser.parse(PERCENTAGE, list);

        assertThat(privileged.workflows()).hasSize(PrivilegedWorkflows.MAX_WORKFLOWS)
            .endsWith("Fictitious-Workflow-200");
        assertThat(privileged.truncated()).isTrue();
    }

    @Test
    void anUnknownPercentageIsUnexpectedAndNeverGuessed() {
        List<String> list = List.of(HEADER);
        assertUnknown(() -> PrivilegedWorkflowsParser.parse(List.of(), list));
        assertUnknown(() -> PrivilegedWorkflowsParser.parse(List.of("20%"), list));
        assertUnknown(() -> PrivilegedWorkflowsParser.parse(
            List.of("Percentage of threads reserved for privileged workflows: 20"), list));
        assertUnknown(() -> PrivilegedWorkflowsParser.parse(
            List.of("Percentage of threads reserved for privileged workflows: 101%"), list));
        assertUnknown(() -> PrivilegedWorkflowsParser.parse(
            List.of("Percentage of threads reserved for privileged workflows: 20%",
                "Percentage of threads reserved for privileged workflows: 30%"), list));
    }

    @Test
    void anUnknownListIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> PrivilegedWorkflowsParser.parse(PERCENTAGE, List.of()));
        assertUnknown(() -> PrivilegedWorkflowsParser.parse(PERCENTAGE,
            List.of("Unknown command: privilegedWorkflows")));
        assertUnknown(() -> PrivilegedWorkflowsParser.parse(PERCENTAGE,
            List.of("Fictitious-Workflow-0001", HEADER)));
    }

    @Test
    void theExcerptShowsTheUnknownBlock() {
        assertThat(assertUnknown(() -> PrivilegedWorkflowsParser.parse(PERCENTAGE,
            List.of("1-NOK: Access denied"))).excerpt()).contains("1-NOK: Access denied");
    }
}
