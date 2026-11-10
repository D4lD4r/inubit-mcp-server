package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.PartnerService.CloudCount;
import de.dadecker.inubit.mcp.application.PartnerService.CloudWorkflows;
import de.dadecker.inubit.mcp.application.PartnerService.DiagramRead;
import de.dadecker.inubit.mcp.application.PartnerService.PartnerView;
import de.dadecker.inubit.mcp.application.PartnerService.ServerView;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The system diagram lists of {@link PartnerBounds#servers} (phase 4 code review): kept by
 * position, the diagrams of the page first in page order, the first partner's always, and the
 * page cut to the partners whose system diagram is listed.
 */
class PartnerBoundsTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");
    private static final String GROUP = "Group-01";

    @Test
    void equalElementsAreKeptByPositionNotByValue() {
        List<DiagramRef> without = Collections.nCopies(10, new DiagramRef("SystemDiagram-01",
            GROUP));
        int share = ResultJson.size(without.subList(0, 5));
        int budget = (int) (share / PartnerBounds.DIAGRAM_SHARE);

        PartnerBounds.Servers bounded = PartnerBounds.servers(List.of(server(List.of(), without)),
            List.of(0), List.of(), budget, ResultJson::size);

        List<DiagramRef> kept = bounded.value().get(0).withoutPartnerManagement();
        assertThat(kept).as("a prefix, not every element equal to a kept one")
            .isNotEmpty().hasSizeLessThan(10);
        assertThat(ResultJson.size(kept)).isLessThanOrEqualTo(share);
        assertThat(bounded.cut()).isTrue();
    }

    @Test
    void theFirstPartnersSystemDiagramIsAlwaysListedAndThePageEndsBeforeAnUnlistedOne() {
        DiagramRead first = diagram("SystemDiagram-01");
        DiagramRead second = diagram("SystemDiagram-02");
        // the page shows a partner of the second system diagram before one of the first
        List<PartnerBounds.Item> page = List.of(item("SystemDiagram-02"),
            item("SystemDiagram-01"));
        int budget = (int) (ResultJson.size(List.of(first)) / PartnerBounds.DIAGRAM_SHARE);

        PartnerBounds.Servers bounded = PartnerBounds.servers(
            List.of(server(List.of(first, second), List.of())), List.of(0),
            page.stream().map(PartnerBounds.Item::sources).toList(), budget,
            ResultJson::size);

        assertThat(bounded.value().get(0).systemDiagramsRead())
            .as("the first partner's, though it does not fit beside the empty lists")
            .containsExactly(second);
        assertThat(bounded.shown()).isEqualTo(1);
        assertThat(bounded.cut()).isTrue();
    }

    private static ServerView server(List<DiagramRead> read, List<DiagramRef> without) {
        return new ServerView(NODE, NODE.group(), Optional.of("owner-01"), Optional.empty(),
            Optional.empty(), read, without, List.of(), List.of(), Optional.empty());
    }

    /** A system diagram read with enough clouds that only one fits the budget of the test. */
    private static DiagramRead diagram(String name) {
        return new DiagramRead(name, GROUP, IntStream.rangeClosed(1, 20)
            .mapToObj(n -> new CloudCount("Module-%04d".formatted(n), Optional.empty(), 1))
            .toList());
    }

    private static PartnerBounds.Item item(String diagram) {
        DiagramRef ref = new DiagramRef(diagram, GROUP);
        return new PartnerBounds.Item(NODE, new PartnerView(ref, "Module-0001", Optional.empty(),
            List.of(), 0, List.of()), new CloudWorkflows(ref, "Module-0001", Optional.empty(),
            List.of()));
    }
}
