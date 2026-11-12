package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Feature 024 (T006, research D-7, data-model "Domain"): one cluster member. */
class ClusterNodeTest {

    private static ClusterNode node(String name) {
        return new ClusterNode(name, NodeStatus.ACTIVE, Optional.empty(), Optional.of("8.1.12"),
            Optional.of("zone1"), Optional.of("node-id-0001"), Optional.of(Duration.ofSeconds(90)));
    }

    private static ClusterNode unknown(Optional<String> rawStatus) {
        return new ClusterNode("node1", NodeStatus.UNKNOWN, rawStatus, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ClusterNode texts(String version, String zone, String nodeId) {
        return new ClusterNode("node1", NodeStatus.ACTIVE, Optional.empty(), Optional.of(version),
            Optional.of(zone), Optional.of(nodeId), Optional.empty());
    }

    @Test
    void holdsAllFields() {
        ClusterNode node = node("node1");

        assertThat(node.name()).isEqualTo("node1");
        assertThat(node.status()).isEqualTo(NodeStatus.ACTIVE);
        assertThat(node.rawStatus()).isEmpty();
        assertThat(node.version()).contains("8.1.12");
        assertThat(node.zone()).contains("zone1");
        assertThat(node.nodeId()).contains("node-id-0001");
        assertThat(node.uptime()).contains(Duration.ofSeconds(90));
    }

    @ParameterizedTest
    @ValueSource(strings = {"n", "node1", "node-2.acme.example.test", "acme_node:8000",
        "A.b-C_d:9"})
    void acceptsNamesOfTheRule(String name) {
        assertThat(node(name).name()).isEqualTo(name);
        assertThat(ClusterNode.isValidName(name)).isTrue();
    }

    @Test
    void acceptsANameOf128Characters() {
        assertThat(node("n".repeat(128)).name()).hasSize(128);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "node 1", "node/1", "node1\n", "nöde", "node'1", "node@1"})
    void refusesNamesOutsideTheRule(String name) {
        assertThat(ClusterNode.isValidName(name)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> node(name))
            .withMessageContaining("node name");
    }

    @Test
    void refusesANameOf129CharactersAndNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> node("n".repeat(129)));
        assertThat(ClusterNode.isValidName(null)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> node(null));
    }

    @Test
    void unknownStatusMayCarryARawStatus() {
        assertThat(unknown(Optional.of("STARTING")).rawStatus()).contains("STARTING");
        assertThat(unknown(Optional.of("a.B_c-9")).rawStatus()).contains("a.B_c-9");
        assertThat(unknown(Optional.of("S".repeat(64))).rawStatus()).hasValue("S".repeat(64));
        assertThat(unknown(Optional.empty()).rawStatus()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "NOT READY", "starting!", "STATUS\t", "zustand-ä"})
    void refusesARawStatusOutsideTheRule(String raw) {
        assertThatIllegalArgumentException().isThrownBy(() -> unknown(Optional.of(raw)))
            .withMessageContaining("raw status");
    }

    @Test
    void refusesARawStatusOf65Characters() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> unknown(Optional.of("S".repeat(65))));
    }

    @Test
    void refusesARawStatusBesideAKnownStatus() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClusterNode("node1",
            NodeStatus.ACTIVE, Optional.of("STARTING"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty()))
            .withMessageContaining("UNKNOWN");
    }

    @Test
    void textsMayHave200Characters() {
        String text = "t".repeat(200);

        ClusterNode node = texts(text, text, text);

        assertThat(node.version()).hasValue(text);
        assertThat(node.zone()).hasValue(text);
        assertThat(node.nodeId()).hasValue(text);
    }

    @Test
    void refusesTextsOver200Characters() {
        String text = "t".repeat(201);

        assertThatIllegalArgumentException().isThrownBy(() -> texts(text, "z", "i"))
            .withMessageContaining("version");
        assertThatIllegalArgumentException().isThrownBy(() -> texts("v", text, "i"))
            .withMessageContaining("zone");
        assertThatIllegalArgumentException().isThrownBy(() -> texts("v", "z", text))
            .withMessageContaining("nodeId");
    }

    @Test
    void refusesANegativeUptime() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClusterNode("node1",
            NodeStatus.ACTIVE, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(Duration.ofSeconds(-1))))
            .withMessageContaining("uptime");
    }

    @Test
    void zeroUptimeIsKept() {
        assertThat(new ClusterNode("node1", NodeStatus.ACTIVE, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(Duration.ZERO)).uptime())
            .contains(Duration.ZERO);
    }
}
