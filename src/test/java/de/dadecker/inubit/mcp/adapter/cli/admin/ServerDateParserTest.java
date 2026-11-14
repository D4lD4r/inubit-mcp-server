package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T018, T025 (feature 022, US1): the answer of {@code date}, against the recorded case
 * {@code admin-settings-date} (T014).
 */
class ServerDateParserTest {

    @Test
    void theRecordedAnswerIsTheServerTimeAsTheServerShowsIt() {
        assertThat(ServerDateParser.parse(AdminCliFixtures.answer("admin-settings-date")))
            .isEqualTo("Fri Nov 13 05:52:06 CET 2026");
    }

    @Test
    void anOffsetZoneAndSurroundingSpacesAreAccepted() {
        assertThat(ServerDateParser.parse(List.of("  Mon Jan 04 09:00:00 GMT+01:00 2027 ")))
            .isEqualTo("Mon Jan 04 09:00:00 GMT+01:00 2027");
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> ServerDateParser.parse(List.of()));
        assertUnknown(() -> ServerDateParser.parse(List.of("Unknown command: date")));
        assertUnknown(() -> ServerDateParser.parse(List.of("2026-11-12T14:03:27+01:00")));
        assertUnknown(() -> ServerDateParser.parse(List.of("Thu Nov 12 14:03 CET 2026")));
        assertUnknown(() -> ServerDateParser.parse(
            List.of("Thu Nov 12 14:03:27 CET 2026", "Thu Nov 12 14:03:28 CET 2026")));
    }

    @Test
    void theExcerptShowsTheUnknownAnswer() {
        assertThat(assertUnknown(() -> ServerDateParser.parse(List.of("1-NOK: Access denied")))
            .excerpt()).contains("1-NOK: Access denied");
    }
}
