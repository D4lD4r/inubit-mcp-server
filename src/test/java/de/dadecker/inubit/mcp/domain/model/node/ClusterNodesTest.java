package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Feature 024 (T006, research D-7, data-model "Domain"): the cluster members, their bounds and
 * the details of one member.
 */
class ClusterNodesTest {

    private static ClusterNode member(int i) {
        return new ClusterNode("node" + i, NodeStatus.ACTIVE, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static List<ClusterNode> members(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(ClusterNodesTest::member).toList();
    }

    private static List<NodeDetail> pairs(int count) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(i -> new NodeDetail("key" + i, "value" + i, false)).toList();
    }

    @Test
    void holdsMembersInTheGivenOrder() {
        ClusterNodes nodes = new ClusterNodes("node2", List.of(member(2), member(1)), 2, false,
            Optional.empty());

        assertThat(nodes.answeringNode()).isEqualTo("node2");
        assertThat(nodes.nodes()).extracting(ClusterNode::name).containsExactly("node2", "node1");
        assertThat(nodes.total()).isEqualTo(2);
        assertThat(nodes.truncated()).isFalse();
        assertThat(nodes.details()).isEmpty();
    }

    @Test
    void membersAreCopied() {
        List<ClusterNode> list = new ArrayList<>(List.of(member(1)));
        ClusterNodes nodes = new ClusterNodes("node1", list, 1, false, Optional.empty());

        list.add(member(2));

        assertThat(nodes.nodes()).hasSize(1);
    }

    @Test
    void holdsAtMost200MembersAndMarksTheRest() {
        ClusterNodes nodes = new ClusterNodes("node1", members(200), 250, true, Optional.empty());

        assertThat(nodes.nodes()).hasSize(ClusterNodes.MAX_NODES);
        assertThat(nodes.total()).isEqualTo(250);
        assertThat(nodes.truncated()).isTrue();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ClusterNodes("node1", members(201), 201, true, Optional.empty()))
            .withMessageContaining("200");
    }

    @Test
    void truncatedMustMatchTheCount() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ClusterNodes("node1", members(2), 3, false, Optional.empty()));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ClusterNodes("node1", members(2), 2, true, Optional.empty()));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ClusterNodes("node1", members(2), 1, false, Optional.empty()));
    }

    @Test
    void refusesAnAnsweringNodeOutsideTheNameRule() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ClusterNodes("", members(1), 1, false, Optional.empty()));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ClusterNodes("node 1", members(1), 1, false, Optional.empty()));
    }

    @Test
    void availableDetailsHoldPairsInTheGivenOrder() {
        ClusterNodes.Details details = new ClusterNodes.Available("node1",
            List.of(new NodeDetail("b", "2", false), new NodeDetail("a", "1", true)), false);
        ClusterNodes nodes = new ClusterNodes("node1", members(1), 1, false, Optional.of(details));

        assertThat(nodes.details()).containsInstanceOf(ClusterNodes.Available.class);
        ClusterNodes.Available available = (ClusterNodes.Available) nodes.details().orElseThrow();
        assertThat(available.node()).isEqualTo("node1");
        assertThat(available.pairs()).extracting(NodeDetail::key).containsExactly("b", "a");
        assertThat(available.pairs().get(1).cut()).isTrue();
        assertThat(available.truncated()).isFalse();
    }

    @Test
    void availableDetailsHoldAtMost500Pairs() {
        assertThat(new ClusterNodes.Available("node1", pairs(500), true).pairs())
            .hasSize(ClusterNodes.MAX_PAIRS);
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ClusterNodes.Available("node1", pairs(501), true))
            .withMessageContaining("500");
        assertThat(new ClusterNodes.Available("node1", Collections.emptyList(), false).pairs())
            .isEmpty();
    }

    @Test
    void unavailableDetailsCarryAReasonOfAtMost200Characters() {
        ClusterNodes.Unavailable unavailable =
            new ClusterNodes.Unavailable("node1", "r".repeat(200));

        assertThat(unavailable.node()).isEqualTo("node1");
        assertThat(unavailable.reason()).hasSize(200);
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ClusterNodes.Unavailable("node1", "r".repeat(201)))
            .withMessageContaining("reason");
    }

    @Test
    void detailsNameAListableNode() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ClusterNodes.Available("node 1", pairs(1), false));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ClusterNodes.Unavailable("node/1", "reason"));
    }

    @Test
    void detailKeyHasAtMost200AndValueAtMost2000Characters() {
        NodeDetail detail = new NodeDetail("k".repeat(200), "v".repeat(2_000), true);

        assertThat(detail.key()).hasSize(NodeDetail.MAX_KEY_CHARS);
        assertThat(detail.value()).hasSize(NodeDetail.MAX_VALUE_CHARS);
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NodeDetail("k".repeat(201), "v", false))
            .withMessageContaining("key");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NodeDetail("k", "v".repeat(2_001), false))
            .withMessageContaining("value");
    }
}
