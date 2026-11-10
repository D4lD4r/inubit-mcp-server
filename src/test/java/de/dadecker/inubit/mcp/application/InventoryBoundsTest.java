package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.InventoryBounds.Bounded;
import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryDetail;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerUsage;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerUsage.CloudUse;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.api.Timeout;

/**
 * Follow-up N2/N3: the size bounds of module usage lists ({@code workflows}) in list items and
 * details (T126); feature 018 (T029): the bounds of a technical workflow's {@code partnerUsage}.
 */
@Timeout(value = 20, threadMode = ThreadMode.SEPARATE_THREAD)
class InventoryBoundsTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final int BUDGET = 20_000;

    private static List<String> workflows(int count, String suffix) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(String.format("Workflow-%04d-%s", i, suffix));
        }
        return names;
    }

    private static InventoryDetail moduleDetail(List<String> workflows,
        Optional<List<VersionEntry>> versions) {
        return new InventoryDetail(DEV, InventoryKind.MODULE, "Mapping", Optional.of("XSLT"),
            Optional.of("XSLT"), "OWNERS", Optional.of(true), Optional.empty(),
            Optional.of(workflows), Optional.of(workflows.size()), Optional.of(true),
            Optional.of("comment"), Optional.empty(), versions, Optional.empty(),
            Optional.of(new ConnectorFlags(false, false, false)), Optional.empty(),
            Optional.empty(), List.of(), false);
    }

    private static InventoryDetail workflowDetail(PartnerUsage usage,
        Optional<List<VersionEntry>> versions) {
        return new InventoryDetail(DEV, InventoryKind.DIAGRAM, "Workflow-0101",
            Optional.of("technical"), Optional.of("GRP-41"), "OWNERS", Optional.of(true),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of("comment"), Optional.empty(), versions, Optional.of(List.of()),
            Optional.empty(), Optional.empty(), Optional.of(usage), List.of(), false);
    }

    private static List<VersionEntry> versions(int count) {
        List<VersionEntry> versions = new ArrayList<>();
        for (int i = count; i >= 1; i--) {
            versions.add(new VersionEntry(i, Optional.of("user1"),
                Optional.of(Instant.parse("2026-01-01T00:00:00Z").minusSeconds(i)),
                Optional.of("c".repeat(150)), Optional.empty(), List.of("T-" + i)));
        }
        return versions;
    }

    @Test
    void aListItemDropsListedWorkflowsButKeepsItsNameAndTheCount() {
        // N3 (a): escape-heavy names: 5 × 200 chars × 6 per escaped char exceed MAX_ITEM_CHARS
        List<String> heavy = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            heavy.add(i + "\u0001".repeat(250));
        }
        InventoryItem item = InventoryItem.module(DEV, "short", "XSLT", "XSLT", "OWNERS",
            Optional.of(true), Optional.empty()).withUsage(heavy, 5);

        Bounded<InventoryItem> bounded = InventoryBounds.item(item, ResultJson::size);

        assertThat(ResultJson.size(bounded.value())).isLessThanOrEqualTo(
            ItemBounds.MAX_ITEM_CHARS);
        assertThat(bounded.cut()).isTrue();
        assertThat(bounded.value().name()).isEqualTo("short");
        assertThat(bounded.value().workflowCount()).contains(5);
        assertThat(bounded.value().workflows().orElseThrow()).isNotEmpty().hasSizeLessThan(5);
    }

    @Test
    void aDetailWithTooManyWorkflowsKeepsAPrefixWithinTheBudgetAndTheFullCount() {
        // N3 (b)
        InventoryDetail detail = moduleDetail(workflows(2_000, "x".repeat(10)),
            Optional.empty());

        InventoryDetail bounded = InventoryBounds.detail(detail, BUDGET, ResultJson::size);

        assertThat(ResultJson.size(bounded)).isLessThanOrEqualTo(BUDGET);
        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.workflowCount()).contains(2_000);
        assertThat(bounded.workflows().orElseThrow()).isNotEmpty().hasSizeLessThan(2_000)
            .startsWith("Workflow-0000-xxxxxxxxxx");
        assertThat(bounded.name()).isEqualTo("Mapping");
    }

    @Test
    void workflowsAreCutBeforeAnyVersionIsDropped() {
        // N2: 500 workflows (~30,000 chars) and 30 versions (~9,000 chars), budget 20,000
        InventoryDetail detail = moduleDetail(workflows(500, "y".repeat(40)),
            Optional.of(versions(30)));

        InventoryDetail bounded = InventoryBounds.detail(detail, BUDGET, ResultJson::size);

        assertThat(ResultJson.size(bounded)).isLessThanOrEqualTo(BUDGET);
        assertThat(bounded.versions().orElseThrow()).as("every version is kept").hasSize(30);
        assertThat(bounded.workflows().orElseThrow()).isNotEmpty().hasSizeLessThan(500);
        assertThat(bounded.workflowCount()).contains(500);
        assertThat(bounded.truncated()).isTrue();
    }

    @Test
    void aPartnerUsageIsKeptAndItsTextsAreCut() {
        // feature 018 (T029): every name to MAX_FIELD_CHARS, the reason to the excerpt length
        String longName = "ISPartnerProtocol." + "v".repeat(300);
        PartnerUsage usage = new PartnerUsage(List.of("ISPartnerProtocol.ServerName", longName),
            true, Optional.of(List.of(new CloudUse(new DiagramRef("d".repeat(300),
                "g".repeat(300)), "m".repeat(300)))),
            Optional.of(new PartnerUsage.Unavailable(ErrorCode.UNEXPECTED_RESPONSE,
                "x".repeat(1_000), "y".repeat(1_000))));

        InventoryDetail bounded = InventoryBounds.detail(workflowDetail(usage, Optional.empty()),
            BUDGET, ResultJson::size);

        PartnerUsage kept = bounded.partnerUsage().orElseThrow();
        assertThat(bounded.truncated()).isTrue();
        assertThat(kept.partnerManagementNode()).isTrue();
        assertThat(kept.variables()).hasSize(2).first().isEqualTo("ISPartnerProtocol.ServerName");
        assertThat(kept.variables().get(1)).hasSize(ItemBounds.MAX_FIELD_CHARS)
            .endsWith(ItemBounds.TRUNCATION_MARKER);
        CloudUse use = kept.systemDiagrams().orElseThrow().get(0);
        assertThat(List.of(use.systemDiagram().name(), use.systemDiagram().diagramGroup(),
            use.cloud())).allSatisfy(text -> assertThat(text)
                .hasSize(ItemBounds.MAX_FIELD_CHARS).endsWith(ItemBounds.TRUNCATION_MARKER));
        PartnerUsage.Unavailable unavailable = kept.unavailable().orElseThrow();
        assertThat(unavailable.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(List.of(unavailable.message(), unavailable.nextStep())).allSatisfy(text ->
            assertThat(text).hasSize(ToolError.MAX_EXCERPT_LENGTH));
    }

    @Test
    void aPartnerUsageTooLargeForTheBudgetKeepsPrefixesOfItsListsAndEveryVersion() {
        // its lists are capped to PARTNER_SHARE of the budget, like the workflows of a module
        List<String> variables = new ArrayList<>();
        List<CloudUse> uses = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            variables.add(String.format("ISPartnerProtocol.Field_%04d_%s", i, "v".repeat(40)));
            uses.add(new CloudUse(new DiagramRef(String.format("System-%04d", i), "GRP-PM"),
                "Module-" + "c".repeat(40)));
        }
        PartnerUsage usage = new PartnerUsage(variables, false, Optional.of(uses),
            Optional.empty());

        InventoryDetail bounded = InventoryBounds.detail(workflowDetail(usage,
            Optional.of(versions(30))), BUDGET, ResultJson::size);

        assertThat(ResultJson.size(bounded)).isLessThanOrEqualTo(BUDGET);
        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.versions().orElseThrow()).as("every version is kept").hasSize(30);
        PartnerUsage kept = bounded.partnerUsage().orElseThrow();
        assertThat(kept.variables()).isNotEmpty().hasSizeLessThan(2_000)
            .startsWith(variables.get(0));
        assertThat(kept.systemDiagrams().orElseThrow()).isNotEmpty().hasSizeLessThan(2_000)
            .startsWith(uses.get(0));
        assertThat(ResultJson.size(kept)).as("within its share")
            .isLessThanOrEqualTo((int) (BUDGET * InventoryBounds.PARTNER_SHARE) + 100);
    }
}
