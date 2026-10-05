package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class TargetResolverTest {

    private static final NodeId QA2 = NodeId.parse("qa/node2");
    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId QA1 = NodeId.parse("qa/node1");
    private static final NodeId STAGING = NodeId.parse("staging/inubit01");

    // config order: qa (node2 before node1), then dev, then staging
    private final TargetResolver resolver = new TargetResolver(List.of(QA2, QA1, DEV, STAGING));

    private static ToolError errorOf(Executable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class, () -> {
            try {
                call.execute();
            } catch (ToolErrorException e) {
                throw e;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    @Test
    void stageIdResolvesToAllItsServersInConfigOrder() {
        assertThat(resolver.resolve("qa")).containsExactly(QA2, QA1);
        assertThat(resolver.resolve("dev")).containsExactly(DEV);
    }

    @Test
    void serverIdResolvesToOneServer() {
        assertThat(resolver.resolve("qa/node1")).containsExactly(QA1);
    }

    @Test
    void allServersAreInConfigOrder() {
        assertThat(resolver.all()).containsExactly(QA2, QA1, DEV, STAGING);
    }

    @Test
    void knownIdsListStagesAndServersInConfigOrder() {
        assertThat(resolver.knownIds()).containsExactly("qa", "qa/node2", "qa/node1", "dev",
            "dev/node1", "staging", "staging/inubit01");
    }

    @Test
    void unknownStageGivesEnvironmentUnknownListingAllIds() {
        ToolError error = errorOf(() -> resolver.resolve("prod"));

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(error.message()).contains("'prod'",
            "qa, qa/node2, qa/node1, dev, dev/node1, staging, staging/inubit01");
        assertThat(error.nextStep()).contains("list_nodes");
    }

    @Test
    void unknownServerGivesEnvironmentUnknownListingAllIds() {
        ToolError error = errorOf(() -> resolver.resolve("qa/inubit3"));

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(error.message()).contains("'qa/inubit3'", "qa/node1", "dev/node1");
    }

    @Test
    void longInputIsShortenedInTheMessage() {
        ToolError error = errorOf(() -> resolver.resolve("X".repeat(200)));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("…").doesNotContain("X".repeat(100));
    }

    @Test
    void malformedTargetGivesInvalidInput() {
        ToolError error = errorOf(() -> resolver.resolve("QA/x"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("the id of one group", "of one node (<group>/<node>");
    }

    @Test
    void resolveSingleServerAcceptsANodeId() {
        assertThat(resolver.resolveSingleServer("dev/node1")).isEqualTo(DEV);
    }

    @Test
    void resolveSingleServerRejectsAGroupIdWithInvalidInput() {
        ToolError error = errorOf(() -> resolver.resolveSingleServer("qa"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("'qa'", "is the id of one group", "exactly one node");
        assertThat(error.nextStep()).contains("qa/node2", "qa/node1");
    }

    @Test
    void resolveSingleServerRejectsUnknownServersAndMalformedIds() {
        assertThat(errorOf(() -> resolver.resolveSingleServer("qa/nope")).code())
            .isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(errorOf(() -> resolver.resolveSingleServer("prod")).code())
            .isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(errorOf(() -> resolver.resolveSingleServer("a//b")).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(errorOf(() -> resolver.resolveSingleServer(null)).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void duplicateServersAreRejected() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new TargetResolver(List.of(DEV, DEV)));
    }
}
