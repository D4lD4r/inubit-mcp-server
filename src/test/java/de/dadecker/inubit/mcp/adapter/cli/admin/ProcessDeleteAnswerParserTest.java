package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.answer;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T057, T054 (feature 022, US4, research D-22, FR-017): the answer of {@code processdelete error},
 * as recorded on 8.1.17 ({@code admin-operate-processdelete-error}): one sentence without a count.
 */
class ProcessDeleteAnswerParserTest {

    private static final String RECORDED = "Process with status [error] has been deleted.";

    @Test
    void theRecordedAnswerIsInubitsAnswer() {
        assertThat(ProcessDeleteAnswerParser.parse(answer("admin-operate-processdelete-error")))
            .isEqualTo(RECORDED);
        assertThat(ProcessDeleteAnswerParser.parse(List.of("  " + RECORDED + " \r")))
            .isEqualTo(RECORDED);
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(List.of()));
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(List.of("40")));
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(List.of("Deleted processes: 40")));
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(
            List.of("Process with status [hanging] has been deleted.")));
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(List.of(RECORDED, RECORDED)));
    }

    /** Leak check: a secret in an unknown answer is masked in the excerpt. */
    @Test
    void theExcerptShowsTheUnknownAnswerWithSecretsMasked() {
        String error = assertUnknown(() -> ProcessDeleteAnswerParser.parse(
            List.of("1-NOK: Access denied " + TestSecrets.FREE_TEXT_SECRETS))).toString();

        assertThat(error).contains("1-NOK: Access denied");
        TestSecrets.assertNoLeak(error);
    }
}
