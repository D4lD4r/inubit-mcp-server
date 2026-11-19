package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.answer;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.dadecker.inubit.mcp.domain.model.TraceLevel;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * T057, T054 (feature 022, US4, research D-22): the answers of {@code trace on|off} and
 * {@code trace -l <level>}, as recorded on 8.1.17 ({@code admin-operate-trace-*}).
 */
class TraceStateParserTest {

    @Test
    void theRecordedSwitchAnswersHaveNoText() {
        assertThat(answer("admin-operate-trace-on")).isEmpty();
        assertThat(answer("admin-operate-trace-off")).isEmpty();
        assertThatCode(() -> TraceStateParser.parseSwitch(answer("admin-operate-trace-on")))
            .doesNotThrowAnyException();
        assertThatCode(() -> TraceStateParser.parseSwitch(answer("admin-operate-trace-off")))
            .doesNotThrowAnyException();
    }

    @Test
    void theRecordedLevelAnswersGiveTheLevel() {
        assertThat(TraceStateParser.parseLevel(answer("admin-operate-trace-level-debug")))
            .isEqualTo(TraceLevel.DEBUG);
        assertThat(TraceStateParser.parseLevel(answer("admin-operate-trace-level-info")))
            .isEqualTo(TraceLevel.INFO);
    }

    @ParameterizedTest
    @EnumSource(TraceLevel.class)
    void everyLevelOfTheListIsReadInUpperCase(TraceLevel level) {
        assertThat(TraceStateParser.parseLevel(
            List.of(" Successfully changed level to " + level.name() + " \r")))
            .isEqualTo(level);
    }

    @Test
    void anUnknownSwitchAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> TraceStateParser.parseSwitch(List.of("on")));
        assertUnknown(() -> TraceStateParser.parseSwitch(List.of("Trace is currently on.")));
        assertUnknown(() -> TraceStateParser.parseSwitch(
            List.of("Successfully changed level to DEBUG")));
    }

    @Test
    void anUnknownLevelAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> TraceStateParser.parseLevel(List.of()));
        assertUnknown(() -> TraceStateParser.parseLevel(List.of("DEBUG")));
        assertUnknown(() -> TraceStateParser.parseLevel(
            List.of("Successfully changed level to debug")));
        assertUnknown(() -> TraceStateParser.parseLevel(
            List.of("Successfully changed level to VERBOSE")));
        assertUnknown(() -> TraceStateParser.parseLevel(List.of("Trace is currently on.")));
        assertUnknown(() -> TraceStateParser.parseLevel(List.of(
            "Successfully changed level to DEBUG", "Successfully changed level to INFO")));
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
