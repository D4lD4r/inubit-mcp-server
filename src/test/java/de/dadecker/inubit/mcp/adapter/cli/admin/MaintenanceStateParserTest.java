package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.answer;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T057, T054 (feature 022, US4, research D-22): the answers of {@code maintenance -s} and of
 * {@code maintenance on|off}, as recorded on 8.1.17 ({@code admin-operate-maintenance-*}).
 */
class MaintenanceStateParserTest {

    @Test
    void theRecordedStateAnswersGiveTheState() {
        assertThat(MaintenanceStateParser.parse(answer("admin-operate-maintenance-state")))
            .isFalse();
        assertThat(MaintenanceStateParser.parse(answer("admin-operate-maintenance-state-on")))
            .isTrue();
        assertThat(MaintenanceStateParser.parse(List.of("  on \r"))).isTrue();
    }

    @Test
    void theRecordedSwitchAnswersAreInubitsAnswer() {
        assertThat(MaintenanceStateParser.parseSwitch(
            answer("admin-operate-maintenance-on-eq-false"))).isEqualTo("Updated Successfully");
        assertThat(MaintenanceStateParser.parseSwitch(answer("admin-operate-maintenance-off")))
            .isEqualTo("Updated Successfully");
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> MaintenanceStateParser.parse(List.of()));
        assertUnknown(() -> MaintenanceStateParser.parse(List.of("ON")));
        assertUnknown(() -> MaintenanceStateParser.parse(List.of("true")));
        assertUnknown(() -> MaintenanceStateParser.parse(List.of("Updated Successfully")));
        assertUnknown(() -> MaintenanceStateParser.parse(
            List.of("Cluster maintenance mode is currently off.")));
        assertUnknown(() -> MaintenanceStateParser.parse(List.of("on", "off")));
        assertUnknown(() -> MaintenanceStateParser.parseSwitch(List.of()));
        assertUnknown(() -> MaintenanceStateParser.parseSwitch(List.of("on")));
        assertUnknown(() -> MaintenanceStateParser.parseSwitch(List.of("Command not found.")));
        assertUnknown(() -> MaintenanceStateParser.parseSwitch(
            List.of("Updated Successfully", "Updated Successfully")));
    }

    /** Leak check: a secret in an unknown answer is masked in the excerpt. */
    @Test
    void theExcerptShowsTheUnknownAnswerWithSecretsMasked() {
        String state = assertUnknown(() -> MaintenanceStateParser.parse(
            List.of("1-NOK: Access denied " + TestSecrets.FREE_TEXT_SECRETS))).toString();
        String change = assertUnknown(() -> MaintenanceStateParser.parseSwitch(
            List.of("1-NOK: Access denied " + TestSecrets.FREE_TEXT_SECRETS))).toString();

        assertThat(state).contains("1-NOK: Access denied");
        assertThat(change).contains("1-NOK: Access denied");
        TestSecrets.assertNoLeak(state);
        TestSecrets.assertNoLeak(change);
    }
}
