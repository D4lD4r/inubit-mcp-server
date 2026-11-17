package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T057 (feature 022, US4, research D-22): the answer of {@code maintenance -s} and of
 * {@code maintenance on|off}. RECONSTRUCTED: {@code admin-operate-maintenance-state-reconstructed}
 * is SYNTHETIC until the live check T054 (L-2) records the real answers.
 */
class MaintenanceStateParserTest {

    @Test
    void theReconstructedAnswerGivesTheState() {
        assertThat(MaintenanceStateParser.parse(
            block("admin-operate-maintenance-state-reconstructed"))).isTrue();
        assertThat(MaintenanceStateParser.parse(List.of("Maintenance mode: off"))).isFalse();
    }

    @Test
    void surroundingSpacesAreIgnored() {
        assertThat(MaintenanceStateParser.parse(List.of("  Maintenance mode: on \r"))).isTrue();
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> MaintenanceStateParser.parse(List.of()));
        assertUnknown(() -> MaintenanceStateParser.parse(List.of("on")));
        assertUnknown(() -> MaintenanceStateParser.parse(List.of("Maintenance mode: ON")));
        assertUnknown(() -> MaintenanceStateParser.parse(List.of("Maintenance mode: true")));
        assertUnknown(() -> MaintenanceStateParser.parse(List.of("Command not found.")));
        assertUnknown(() -> MaintenanceStateParser.parse(
            List.of("Maintenance mode: on", "Maintenance mode: off")));
    }

    /** Leak check: a secret in an unknown answer is masked in the excerpt. */
    @Test
    void theExcerptShowsTheUnknownAnswerWithSecretsMasked() {
        String excerpt = assertUnknown(() -> MaintenanceStateParser.parse(
            List.of("1-NOK: Access denied " + TestSecrets.FREE_TEXT_SECRETS))).toString();

        assertThat(excerpt).contains("1-NOK: Access denied");
        TestSecrets.assertNoLeak(excerpt);
    }
}
