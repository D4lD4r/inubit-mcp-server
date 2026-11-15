package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Feature 024 (T015, research D-10): the values one node answered, in request order. */
class NodeConfigValuesTest {

    @Test
    void keepsTheAnsweringNodeAndAnUnmodifiableCopyOfTheValuesInOrder() {
        List<NodeConfigValue> values = new ArrayList<>(List.of(
            new NodeConfigValue.Absent(NodeConfigName.NO_OF_RETRIES),
            new NodeConfigValue.Absent(NodeConfigName.CLUSTER_NAME)));

        NodeConfigValues answer = new NodeConfigValues("node-a.1", values);
        values.clear();

        assertThat(answer.answeringNode()).isEqualTo("node-a.1");
        assertThat(answer.values()).extracting(NodeConfigValue::name)
            .containsExactly(NodeConfigName.NO_OF_RETRIES, NodeConfigName.CLUSTER_NAME);
        assertThatThrownBy(() -> answer.values().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theAnsweringNodeFollowsTheNodeNameRule() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NodeConfigValues("", List.of()))
            .withMessageContaining("node name");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NodeConfigValues("node a", List.of()));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NodeConfigValues("n".repeat(129), List.of()));
        assertThat(new NodeConfigValues("n".repeat(128), List.of()).answeringNode()).hasSize(128);
    }
}
