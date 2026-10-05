package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.InventoryBounds.Bounded;
import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.InventoryDetail;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
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
 * details (T126).
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
            Optional.of(new ConnectorFlags(false, false, false)), Optional.empty(), List.of(),
            false);
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
        List<VersionEntry> versions = new ArrayList<>();
        for (int i = 30; i >= 1; i--) {
            versions.add(new VersionEntry(i, Optional.of("user1"),
                Optional.of(Instant.parse("2026-01-01T00:00:00Z").minusSeconds(i)),
                Optional.of("c".repeat(150)), Optional.empty(), List.of("T-" + i)));
        }
        InventoryDetail detail = moduleDetail(workflows(500, "y".repeat(40)),
            Optional.of(versions));

        InventoryDetail bounded = InventoryBounds.detail(detail, BUDGET, ResultJson::size);

        assertThat(ResultJson.size(bounded)).isLessThanOrEqualTo(BUDGET);
        assertThat(bounded.versions().orElseThrow()).as("every version is kept").hasSize(30);
        assertThat(bounded.workflows().orElseThrow()).isNotEmpty().hasSizeLessThan(500);
        assertThat(bounded.workflowCount()).contains(500);
        assertThat(bounded.truncated()).isTrue();
    }
}
