package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import org.junit.jupiter.api.Test;

/** T004: a reason has a code, a bounded message and a bounded next step. */
class ReasonTest {

    @Test
    void aReasonKeepsCodeMessageAndNextStep() {
        Reason reason = new Reason(ErrorCode.TIMEOUT, "budget reached",
            "the read goes on and fills the cache");

        assertThat(reason.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(reason.message()).isEqualTo("budget reached");
        assertThat(reason.nextStep()).isEqualTo("the read goes on and fills the cache");
    }

    @Test
    void messageAndNextStepMayHaveTheMessageBound() {
        String bound = "m".repeat(ItemBounds.MAX_MESSAGE_CHARS);

        assertThat(new Reason(ErrorCode.INTERNAL, bound, bound).message()).hasSize(
            ItemBounds.MAX_MESSAGE_CHARS);
    }

    @Test
    void aMessageLongerThanTheBoundIsRefused() {
        String tooLong = "m".repeat(ItemBounds.MAX_MESSAGE_CHARS + 1);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> new Reason(ErrorCode.INTERNAL, tooLong, "retry"))
            .withMessageContaining("message");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new Reason(ErrorCode.INTERNAL, "failed", tooLong))
            .withMessageContaining("nextStep");
    }

    @Test
    void everyFieldIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new Reason(null, "failed", "retry"))
            .withMessage("code");
        assertThatNullPointerException()
            .isThrownBy(() -> new Reason(ErrorCode.INTERNAL, null, "retry"))
            .withMessage("message");
        assertThatNullPointerException()
            .isThrownBy(() -> new Reason(ErrorCode.INTERNAL, "failed", null))
            .withMessage("nextStep");
    }
}
