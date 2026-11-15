package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Feature 024 (T006, research D-7): the status of a cluster member. */
class NodeStatusTest {

    @Test
    void knownStatusesMapByName() {
        assertThat(NodeStatus.of(Optional.of("ACTIVE"))).isEqualTo(NodeStatus.ACTIVE);
        assertThat(NodeStatus.of(Optional.of("MAINTENANCE"))).isEqualTo(NodeStatus.MAINTENANCE);
        assertThat(NodeStatus.of(Optional.of("OFFLINE"))).isEqualTo(NodeStatus.OFFLINE);
    }

    @Test
    void noStatusIsUnknown() {
        assertThat(NodeStatus.of(Optional.empty())).isEqualTo(NodeStatus.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "Offline", "STARTING", "UNKNOWN", "", "ACTIVE "})
    void anyOtherTextIsUnknown(String raw) {
        assertThat(NodeStatus.of(Optional.of(raw))).isEqualTo(NodeStatus.UNKNOWN);
    }
}
