package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T057 (feature 022, US4, research D-22): the answer of {@code debug on|off}. RECONSTRUCTED:
 * {@code admin-operate-debug-on-reconstructed} is SYNTHETIC until the live check T054 (L-3)
 * records the real answers.
 */
class DebugStateParserTest {

    @Test
    void theReconstructedAnswerGivesTheState() {
        assertThat(DebugStateParser.parse(block("admin-operate-debug-on-reconstructed")))
            .isTrue();
        assertThat(DebugStateParser.parse(List.of(" Debug mode: off \r"))).isFalse();
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> DebugStateParser.parse(List.of()));
        assertUnknown(() -> DebugStateParser.parse(List.of("on")));
        assertUnknown(() -> DebugStateParser.parse(List.of("Debug mode: ON")));
        assertUnknown(() -> DebugStateParser.parse(List.of("Trace: on")));
        assertUnknown(() -> DebugStateParser.parse(List.of("Debug mode: on", "Debug mode: off")));
    }

    /** Leak check: a secret in an unknown answer is masked in the excerpt. */
    @Test
    void theExcerptShowsTheUnknownAnswerWithSecretsMasked() {
        String error = assertUnknown(() -> DebugStateParser.parse(
            List.of("1-NOK: Access denied " + TestSecrets.FREE_TEXT_SECRETS))).toString();

        assertThat(error).contains("1-NOK: Access denied");
        TestSecrets.assertNoLeak(error);
    }
}
