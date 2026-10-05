package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class NodeResultTest {

    private static final NodeId SERVER = NodeId.parse("dev/node1");
    private static final ToolError ERROR = ToolError.of(ErrorCode.TIMEOUT, "m", "c", "n");

    @Test
    void successHasPayloadOnly() {
        NodeResult<String> result = NodeResult.success(SERVER, "ok");

        assertThat(result.node()).isEqualTo(SERVER);
        assertThat(result.payload()).contains("ok");
        assertThat(result.error()).isEmpty();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void failureHasErrorOnly() {
        NodeResult<String> result = NodeResult.failure(SERVER, ERROR);

        assertThat(result.payload()).isEmpty();
        assertThat(result.error()).contains(ERROR);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void payloadAndErrorMayCoexist() {
        // e.g. NOT_FOUND together with an item carrying similarNames (mcp-tools.md §6)
        NodeResult<String> result =
            new NodeResult<>(SERVER, Optional.of("similar"), Optional.of(ERROR));

        assertThat(result.payload()).contains("similar");
        assertThat(result.error()).contains(ERROR);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void requiresPayloadOrError() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NodeResult<>(SERVER, Optional.empty(), Optional.empty()));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NodeResult<String>(SERVER, null, null));
    }
}
