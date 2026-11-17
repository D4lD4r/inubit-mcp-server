package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T057 (feature 022, US4, research D-22, FR-017): the count INUBIT reports for
 * {@code processdelete error}. RECONSTRUCTED:
 * {@code admin-operate-processdelete-error-reconstructed} is SYNTHETIC until the live check T054
 * records the real answer.
 */
class ProcessDeleteAnswerParserTest {

    @Test
    void theReconstructedAnswerGivesTheReportedCount() {
        assertThat(ProcessDeleteAnswerParser.parse(
            block("admin-operate-processdelete-error-reconstructed"))).isEqualTo(40L);
    }

    @Test
    void zeroAndSurroundingSpacesAreRead() {
        assertThat(ProcessDeleteAnswerParser.parse(List.of("  Deleted processes: 0 \r")))
            .isZero();
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(List.of()));
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(List.of("40")));
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(List.of("Deleted processes: -1")));
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(List.of("Deleted processes: many")));
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(
            List.of("Deleted processes: 99999999999999999999")));
        assertUnknown(() -> ProcessDeleteAnswerParser.parse(
            List.of("Deleted processes: 40", "Deleted processes: 2")));
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
