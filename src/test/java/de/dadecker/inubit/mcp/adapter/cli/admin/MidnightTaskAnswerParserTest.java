package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T057 (feature 022, US4, research D-22): the answer of {@code triggerMidnightTask}.
 * RECONSTRUCTED: {@code admin-operate-midnight-task-reconstructed} is SYNTHETIC until the live
 * check T054 records the real answer.
 */
class MidnightTaskAnswerParserTest {

    @Test
    void theReconstructedAnswerIsInubitsAnswer() {
        assertThat(MidnightTaskAnswerParser.parse(
            block("admin-operate-midnight-task-reconstructed")))
            .isEqualTo("Midnight task triggered.");
        assertThat(MidnightTaskAnswerParser.parse(List.of("  Midnight task triggered. \r")))
            .isEqualTo("Midnight task triggered.");
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> MidnightTaskAnswerParser.parse(List.of()));
        assertUnknown(() -> MidnightTaskAnswerParser.parse(List.of("Midnight task started.")));
        assertUnknown(() -> MidnightTaskAnswerParser.parse(List.of("Command not found.")));
        assertUnknown(() -> MidnightTaskAnswerParser.parse(
            List.of("Midnight task triggered.", "Midnight task triggered.")));
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
