package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * T057 (feature 022, US4, research D-22): the answers of {@code trace on|off} and
 * {@code trace -l <level>}. RECONSTRUCTED: {@code admin-operate-trace-on-reconstructed} and
 * {@code admin-operate-trace-level-reconstructed} are SYNTHETIC until the live check T054 (L-3)
 * records the real answers.
 */
class TraceStateParserTest {

    @Test
    void theReconstructedSwitchAnswerGivesTheState() {
        assertThat(TraceStateParser.parseSwitch(block("admin-operate-trace-on-reconstructed")))
            .isTrue();
        assertThat(TraceStateParser.parseSwitch(List.of(" Trace: off \r"))).isFalse();
    }

    @Test
    void theReconstructedLevelAnswerGivesTheLevel() {
        assertThat(TraceStateParser.parseLevel(block("admin-operate-trace-level-reconstructed")))
            .isEqualTo(TraceLevel.DEBUG);
    }

    @ParameterizedTest
    @EnumSource(TraceLevel.class)
    void everyLevelOfTheListIsReadInLowerCase(TraceLevel level) {
        assertThat(TraceStateParser.parseLevel(
            List.of("Trace level: " + level.name().toLowerCase(Locale.ROOT))))
            .isEqualTo(level);
    }

    @Test
    void anUnknownSwitchAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> TraceStateParser.parseSwitch(List.of()));
        assertUnknown(() -> TraceStateParser.parseSwitch(List.of("on")));
        assertUnknown(() -> TraceStateParser.parseSwitch(List.of("Trace: ON")));
        assertUnknown(() -> TraceStateParser.parseSwitch(List.of("Trace level: debug")));
        assertUnknown(() -> TraceStateParser.parseSwitch(List.of("Trace: on", "Trace: off")));
    }

    @Test
    void anUnknownLevelAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> TraceStateParser.parseLevel(List.of()));
        assertUnknown(() -> TraceStateParser.parseLevel(List.of("debug")));
        assertUnknown(() -> TraceStateParser.parseLevel(List.of("Trace level: DEBUG")));
        assertUnknown(() -> TraceStateParser.parseLevel(List.of("Trace level: verbose")));
        assertUnknown(() -> TraceStateParser.parseLevel(List.of("Trace: on")));
        assertUnknown(() -> TraceStateParser.parseLevel(
            List.of("Trace level: debug", "Trace level: info")));
    }

    /** Leak check: a secret in an unknown answer is masked in the excerpt. */
    @Test
    void theExcerptShowsTheUnknownAnswerWithSecretsMasked() {
        String switchError = assertUnknown(() -> TraceStateParser.parseSwitch(
            List.of("1-NOK: Access denied " + TestSecrets.FREE_TEXT_SECRETS))).toString();
        String levelError = assertUnknown(() -> TraceStateParser.parseLevel(
            List.of("1-NOK: Access denied " + TestSecrets.FREE_TEXT_SECRETS))).toString();

        assertThat(switchError).contains("1-NOK: Access denied");
        assertThat(levelError).contains("1-NOK: Access denied");
        TestSecrets.assertNoLeak(switchError);
        TestSecrets.assertNoLeak(levelError);
    }
}
