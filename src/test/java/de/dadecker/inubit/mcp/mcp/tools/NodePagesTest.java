package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.DiagnosisService.NodePage;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Feature 025 T044 (FR-030 (b)): the entries of {@code find_processes} and {@code query_logs}. */
class NodePagesTest {

    private static final NodeId NODE = NodeId.parse("legacy74/node1");
    private static final ToolError ERROR =
        ToolError.of(ErrorCode.UNREACHABLE, "down", "cause", "next").withNode(NODE);

    @Test
    void aFailureWithWarningsRendersThemBesideTheError() {
        JsonNode entry = render(new NodeResult<NodePage<String>>(NODE, Optional.empty(),
            Optional.of(ERROR), List.of("not verified")));

        assertThat(entry.path("node").asString()).isEqualTo("legacy74/node1");
        assertThat(entry.has("page")).isFalse();
        assertThat(entry.path("error").path("code").asString()).isEqualTo("UNREACHABLE");
        assertThat(entry.path("warnings")).extracting(JsonNode::asString)
            .containsExactly("not verified");
    }

    @Test
    void aFailureWithoutWarningsHasOnlyTheError() {
        JsonNode entry = render(NodeResult.failure(NODE, ERROR));

        assertThat(entry.has("error")).isTrue();
        assertThat(entry.has("warnings")).isFalse();
    }

    @Test
    void aPageKeepsItsOwnWarnings() {
        Page<String> page = new Page<>(List.of(), 0, 10, OptionalLong.of(0), false, false,
            OptionalInt.empty());

        JsonNode entry = render(NodeResult.success(NODE,
            new NodePage<>(page, List.of("page warning"))));

        assertThat(entry.has("error")).isFalse();
        assertThat(entry.path("warnings")).extracting(JsonNode::asString)
            .containsExactly("page warning");
    }

    private static JsonNode render(NodeResult<NodePage<String>> result) {
        return ResultJson.mapper().valueToTree(NodePages.of(List.of(result)))
            .path("results").get(0);
    }
}
