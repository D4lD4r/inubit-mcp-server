package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Feature 011 T015 (FR-008, research R-8, quickstart Q9): the fan-out allowance per node. */
@Tag("serial")
class SecretTimingTest {

    private static final NodeId AWS = NodeId.parse("prod/node1");
    private static final NodeId STORE = NodeId.parse("dev/node1");
    private static final NodeId OTHER = NodeId.parse("qa/node1");

    @Test
    void eachNodeWaitsAsLongAsItsStoreWhileItsCredentialsAreNotHeld() {
        Set<NodeId> held = new HashSet<>();
        SecretTiming timing = new SecretTiming(Map.of(AWS, Duration.ofSeconds(10),
            STORE, Duration.ofSeconds(60)), held::contains);

        assertThat(timing.allowance(AWS)).isEqualTo(Duration.ofSeconds(11));
        assertThat(timing.allowance(STORE)).isEqualTo(Duration.ofSeconds(61));
        assertThat(timing.allowance(OTHER)).isZero();

        held.add(STORE);
        held.add(AWS);

        assertThat(timing.allowance(AWS)).isZero();
        assertThat(timing.allowance(STORE)).isZero();
    }

    @Test
    void noneGivesNoAllowance() {
        assertThat(SecretTiming.NONE.allowance(STORE)).isZero();
    }

    @Test
    void aTimeoutWhileAStoreReadWasPendingIsExplainedOthersAreUnchanged() {
        Set<NodeId> held = new HashSet<>();
        SecretTiming timing = new SecretTiming(Map.of(STORE, Duration.ofSeconds(60),
            AWS, Duration.ofSeconds(10)), held::contains, Map.of(STORE, "macOS Keychain"));
        ToolError timeout = ToolError.of(ErrorCode.TIMEOUT, "No response from x within 16 s",
            "cause", "next");
        Set<NodeId> pending = timing.pending(List.of(STORE, AWS, OTHER));

        List<NodeResult<String>> results = timing.explainTimeouts(List.of(
            NodeResult.failure(STORE, timeout.withNode(STORE)),
            NodeResult.failure(AWS, timeout.withNode(AWS)),
            NodeResult.success(OTHER, "ok")), pending);

        assertThat(pending).containsExactly(STORE);
        assertThat(results.get(0).error().orElseThrow().likelyCause())
            .contains("macOS Keychain", "access dialog");
        assertThat(results.get(0).error().orElseThrow().message())
            .isEqualTo("No response from x within 16 s");
        assertThat(results.get(1).error().orElseThrow()).isEqualTo(timeout.withNode(AWS));
        assertThat(results.get(2)).isEqualTo(NodeResult.success(OTHER, "ok"));

        held.add(STORE);
        assertThat(timing.explainTimeouts(List.of(NodeResult.<String>failure(STORE,
            timeout.withNode(STORE))), pending).get(0).error().orElseThrow().likelyCause())
            .isEqualTo("cause");
    }

    @Test
    void anExplainedTimeoutKeepsItsWarnings() {
        // feature 025 T044 (FR-030 (b)): the unverified warning of an admitted node
        SecretTiming timing = new SecretTiming(Map.of(STORE, Duration.ofSeconds(60)),
            node -> false, Map.of(STORE, "macOS Keychain"));
        ToolError timeout = ToolError.of(ErrorCode.TIMEOUT, "No response", "cause", "next")
            .withNode(STORE);

        NodeResult<String> explained = timing.explainTimeouts(List.of(new NodeResult<String>(
            STORE, Optional.empty(), Optional.of(timeout), List.of("not verified"))),
            Set.of(STORE)).get(0);

        assertThat(explained.error().orElseThrow().likelyCause()).contains("macOS Keychain");
        assertThat(explained.warnings()).containsExactly("not verified");
    }
}
