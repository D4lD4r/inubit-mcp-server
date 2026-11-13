package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * T031 (feature 022, US2 AS4): the answers of the client runs {@code --who} and {@code --nwho},
 * against the recorded cases {@code admin-users-who} and {@code admin-users-nwho} (T028a: a system
 * table with the columns {@code SYSTEM-ID} 64, {@code SYSTEM TYPE} 30, {@code STATE} 10,
 * {@code VERSION} 15 and {@code IDLE}; only the {@code Toolset} row is a login; {@code --nwho}
 * prints the number of rows).
 */
class WhoParserTest {

    private static final String HEADER = row("SYSTEM-ID", "SYSTEM TYPE", "STATE", "VERSION",
        "IDLE");

    private static String row(String id, String type, String state, String version, String idle) {
        return String.format("%-64s%-30s%-10s%-15s%s", id, type, state, version, idle);
    }

    private static List<String> table(String... rows) {
        List<String> lines = new ArrayList<>(List.of(HEADER));
        lines.addAll(List.of(rows));
        return lines;
    }

    @Test
    void theRecordedTableGivesOnlyTheToolsetRowAsALoginWithoutATime() {
        assertThat(WhoParser.parse(AdminCliFixtures.answer("admin-users-who")))
            .containsExactly(new LoginEntry("jdoe", Optional.empty()));
    }

    @Test
    void everyToolsetRowIsALoginInTheTableOrder() {
        // reconstructed: T028a recorded only the run's own connection
        assertThat(WhoParser.parse(table(
            row("workstation-2.example.test (qa_robot)", "Toolset", "OK", "8.1.17", "0:12:00s"),
            row("http://localhost:8181/", "Portal (BPC)", "", "", "271:16:42s"),
            row("workstation-1.example.test (jdoe)", "Toolset", "OK", "8.1.17", "0:00:01s"))))
            .containsExactly(new LoginEntry("qa_robot", Optional.empty()),
                new LoginEntry("jdoe", Optional.empty()));
    }

    @Test
    void aTableWithoutToolsetRowsHasNoLogin() {
        assertThat(WhoParser.parse(table(
            row("http://localhost:8181/", "Portal (BPC)", "", "", "271:16:42s")))).isEmpty();
        assertThat(WhoParser.parse(table())).isEmpty();
    }

    @Test
    void anUnknownTableIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> WhoParser.parse(List.of()));
        assertUnknown(() -> WhoParser.parse(List.of("Command not found.")));
        assertUnknown(() -> WhoParser.parse(
            AdminCliFixtures.block("admin-users-who-missing-argument")));
        // another header: a column renamed, added or of another width
        assertUnknown(() -> WhoParser.parse(List.of(HEADER.replace("IDLE", "LOGIN TIME"))));
        assertUnknown(() -> WhoParser.parse(List.of(HEADER + "  SINCE")));
        assertUnknown(() -> WhoParser.parse(List.of(HEADER.replaceFirst("  SYSTEM TYPE",
            "SYSTEM TYPE  "))));
        // a row that does not fit the columns
        assertUnknown(() -> WhoParser.parse(table("workstation-1.example.test (jdoe) Toolset")));
        assertUnknown(() -> WhoParser.parse(table(
            row("jdbc:postgresql://inubit-db-01.example.test:5432/inubitlogdb?ssl=true&x=1",
                "Log DB (PostgreSQL)", "", "42.7.7", "271:16:42s"))));
        assertUnknown(() -> WhoParser.parse(table(
            row("workstation-1.example.test (jdoe)", "Toolset", "OK", "8.1.17", "1 second"))));
        // a Toolset row without a login in parentheses
        assertUnknown(() -> WhoParser.parse(table(
            row("workstation-1.example.test", "Toolset", "OK", "8.1.17", "0:00:01s"))));
        assertUnknown(() -> WhoParser.parse(table(
            row("workstation-1.example.test ()", "Toolset", "OK", "8.1.17", "0:00:01s"))));
    }

    @Test
    void theRecordedCountIsTheNumberOfTableRows() {
        assertThat(WhoParser.count(AdminCliFixtures.answer("admin-users-nwho"))).isEqualTo(6);
        assertThat(AdminCliFixtures.answer("admin-users-who")).hasSize(1 + 6);
    }

    @Test
    void anUnknownCountIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> WhoParser.count(List.of()));
        assertUnknown(() -> WhoParser.count(List.of("6", "7")));
        assertUnknown(() -> WhoParser.count(List.of("6 users")));
        assertUnknown(() -> WhoParser.count(List.of("-1")));
        assertUnknown(() -> WhoParser.count(List.of("99999999999")));
        assertUnknown(() -> WhoParser.count(
            AdminCliFixtures.block("admin-users-nwho-missing-argument")));
    }

    @Test
    void theExcerptShowsTheUnknownAnswer() {
        assertThat(assertUnknown(() -> WhoParser.count(List.of("Command not found.")))
            .excerpt()).contains("Command not found.");
    }
}
