package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T030: zone-less StartCLI timestamps, read as {@code Europe/Berlin} (research D-10). */
class CliDatesTest {

    @ParameterizedTest
    @CsvSource({
        // German form (8.1 exports, 6.1 German ps listing)
        "'23.01.2026 07:54:27', 2026-01-23T06:54:27Z",
        "'15.04.2026 15:35:20', 2026-04-15T13:35:20Z",
        "' 30.10.2026 08:15:42 ', 2026-10-30T07:15:42Z",
        // ISO local form (6.1 English ps listing)
        "2026-01-23T07:54:27, 2026-01-23T06:54:27Z",
        "2026-04-15T15:35:20, 2026-04-15T13:35:20Z",
        "' 2026-10-30T08:15:42 ', 2026-10-30T07:15:42Z",
    })
    void bothFormsAreLocalTimeInBerlin(String text, String expected) {
        assertThat(CliDates.parse(text)).contains(Instant.parse(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"25.10.2026 02:30:00", "2026-10-25T02:30:00"})
    void theHourThatOccursTwiceTakesTheEarlierInstant(String text) {
        // 02:30 exists in CEST (+02:00) and CET (+01:00); the CEST instant is earlier.
        assertThat(CliDates.parse(text)).contains(Instant.parse("2026-10-25T00:30:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"29.03.2026 02:30:00", "2026-03-29T02:30:00"})
    void theSkippedHourIsShiftedForwardByTheGap(String text) {
        // 02:30 does not exist on 29.03.2026; it is read as 03:30 CEST.
        assertThat(CliDates.parse(text)).contains(Instant.parse("2026-03-29T01:30:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "31.02.2026 10:00:00", "2026-02-31T10:00:00",
        "30.10.2026 08:15", "2026-10-30 08:15:42", "2026-10-30T08:15:42Z",
        "2026-10-30T08:15:42.123", "2026-10-30T08:15:42+01:00", "30.10.26 08:15:42",
        "30.10.2026 24:00:00", "Error"})
    void otherTextsAreAbsent(String text) {
        assertThat(CliDates.parse(text)).isEmpty();
    }
}
