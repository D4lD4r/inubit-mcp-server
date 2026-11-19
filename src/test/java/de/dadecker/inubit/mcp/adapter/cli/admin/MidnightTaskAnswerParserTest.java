package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.answer;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T057, T054 (feature 022, US4, research D-22): the answer of {@code triggerMidnightTask}, as
 * recorded on 8.1.17 ({@code admin-operate-midnight-task}): no text.
 */
class MidnightTaskAnswerParserTest {

    @Test
    void theRecordedAnswerHasNoText() {
        assertThat(answer("admin-operate-midnight-task")).isEmpty();
        assertThatCode(() -> MidnightTaskAnswerParser.parse(answer("admin-operate-midnight-task")))
            .doesNotThrowAnyException();
    }

    @Test
    void anyTextIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> MidnightTaskAnswerParser.parse(List.of("Midnight task triggered.")));
        assertUnknown(() -> MidnightTaskAnswerParser.parse(List.of("Command not found.")));
    }

    /** Leak check: a secret in an unknown answer is masked in the excerpt. */
    @Test
    void theExcerptShowsTheUnknownAnswerWithSecretsMasked() {
        String error = assertUnknown(() -> MidnightTaskAnswerParser.parse(
            List.of("1-NOK: Access denied " + TestSecrets.FREE_TEXT_SECRETS))).toString();

        assertThat(error).contains("1-NOK: Access denied");
        TestSecrets.assertNoLeak(error);
    }
}
