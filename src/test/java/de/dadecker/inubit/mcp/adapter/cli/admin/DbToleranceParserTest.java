package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.DbConnectionTolerance;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T018 (feature 022, US1): the answer of {@code dbconnectiontolerance show}. The case
 * {@code admin-settings-db-tolerance-reconstructed} is copied from the vendor example until T014
 * records the real one.
 */
class DbToleranceParserTest {

    private static List<String> answer(String errorLevel, String threshold, String rate,
        String interval) {
        return List.of("Error level: " + errorLevel, "Fault threshold: " + threshold,
            "Drop rate: " + rate, "Drop interval (s): " + interval);
    }

    @Test
    void theVendorExampleGivesThresholdRateAndInterval() {
        assertThat(DbToleranceParser.parse(block("admin-settings-db-tolerance-reconstructed")))
            .isEqualTo(new DbConnectionTolerance(25, 1, 180));
    }

    @Test
    void theErrorLevelIsNotASettingAndIsNotReturned() {
        assertThat(DbToleranceParser.parse(answer("100%", "0", "100%", "0 seconds")))
            .isEqualTo(new DbConnectionTolerance(0, 100, 0));
    }

    @Test
    void surroundingSpacesAreIgnored() {
        assertThat(DbToleranceParser.parse(List.of(" Error level: 0% ", " Fault threshold: 25 ",
            " Drop rate: 4% ", " Drop interval (s): 3600 seconds ")))
            .isEqualTo(new DbConnectionTolerance(25, 4, 3600));
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> DbToleranceParser.parse(List.of()));
        assertUnknown(() -> DbToleranceParser.parse(List.of("Unknown command")));
        // a line missing, an extra line, another order
        assertUnknown(() -> DbToleranceParser.parse(answer("0%", "25", "1%", "180 seconds")
            .subList(1, 4)));
        assertUnknown(() -> DbToleranceParser.parse(List.of("Error level: 0%",
            "Fault threshold: 25", "Drop rate: 1%", "Drop interval (s): 180 seconds",
            "Fault threshold: 30")));
        assertUnknown(() -> DbToleranceParser.parse(List.of("Fault threshold: 25",
            "Error level: 0%", "Drop rate: 1%", "Drop interval (s): 180 seconds")));
        // values out of form or range
        assertUnknown(() -> DbToleranceParser.parse(answer("0%", "-1", "1%", "180 seconds")));
        assertUnknown(() -> DbToleranceParser.parse(answer("0%", "25", "1", "180 seconds")));
        assertUnknown(() -> DbToleranceParser.parse(answer("0%", "25", "101%", "180 seconds")));
        assertUnknown(() -> DbToleranceParser.parse(answer("0%", "25", "1%", "180")));
        assertUnknown(() -> DbToleranceParser.parse(answer("0%", "25", "1%", "3 minutes")));
        assertUnknown(() -> DbToleranceParser.parse(answer("0%", "99999999999", "1%",
            "180 seconds")));
    }

    @Test
    void theExcerptShowsTheUnknownAnswer() {
        assertThat(assertUnknown(() -> DbToleranceParser.parse(List.of("1-NOK: Access denied")))
            .excerpt()).contains("1-NOK: Access denied");
    }
}
