package de.dadecker.inubit.mcp.adapter.cli.v61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MemoryFigures;
import de.dadecker.inubit.mcp.domain.model.ProcessCounts;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T024 (feature 011, FR-017, research D-5, D-6): the health session of StartCLI 6.1, split at
 * the prompt and parsed, against the reconstructed T022 fixtures and malformed variants of them.
 */
class V61HealthParserTest {

    private static final MemoryFigures MEMORY = new MemoryFigures(1210, 838, 2048);
    private static final ProcessCounts COUNTS = new ProcessCounts(3, 0, 12, 0);

    private static String stdout(String fixtureCase) {
        return FakeProcessLauncher.fixtureText("v6_1", fixtureCase + ".stdout");
    }

    private static V61HealthParser.Health parse(String fixtureCase) {
        return V61HealthParser.parse(stdout(fixtureCase));
    }

    private static void assertUnexpected(String stdout) {
        assertThatThrownBy(() -> V61HealthParser.parse(stdout))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    @Test
    void theHealthSessionSendsTheCommandsOfTheFixtureLayout() {
        assertThat(V61HealthParser.COMMANDS).extracting(CliSessionCommand::text)
            .containsExactly("uname", "uptime", "maintenance", "free -m", "ps -c -e",
                "ps -c -w", "ps -c -q", "ps -c -a");
    }

    @Test
    void anOkSessionIsParsedCompletely() {
        assertThat(parse("health_session_ok")).isEqualTo(
            new V61HealthParser.Health("6.1.0.89", true, false, MEMORY, COUNTS));
    }

    @Test
    void maintenanceOnIsRead() {
        assertThat(parse("health_session_maintenance")).isEqualTo(
            new V61HealthParser.Health("6.1.0.89", true, true, MEMORY, COUNTS));
    }

    @Test
    void germanLabelsGiveTheSameValues() {
        assertThat(parse("health_session_de")).isEqualTo(
            new V61HealthParser.Health("6.1.0.89", true, false, MEMORY, COUNTS));
    }

    @Test
    void aNegativeAliveCheckIsReadWithTheOtherParts() {
        assertThat(parse("health_session_alive_failed")).isEqualTo(
            new V61HealthParser.Health("6.1.0.89", false, false, MEMORY, COUNTS));
    }

    @Test
    void germanNotAliveAndMaintenanceOnAreRead() {
        V61HealthParser.Health health = V61HealthParser.parse(stdout("health_session_de")
            .replace("Process Engine ist aktiv (läuft seit 2 Tagen, 03:14:07)",
                "Process Engine ist nicht aktiv")
            .replace("Wartungsmodus: aus", "Wartungsmodus: ein"));

        assertThat(health.alive()).isFalse();
        assertThat(health.maintenance()).isTrue();
    }

    @Test
    void windowsLineEndingsAreAccepted() {
        assertThat(V61HealthParser.parse(stdout("health_session_ok").replace("\n", "\r\n")))
            .isEqualTo(parse("health_session_ok"));
    }

    @Test
    void processingIsAllMinusErrorWaitingAndQueued() {
        V61HealthParser.Health health = V61HealthParser.parse(stdout("health_session_ok")
            .replace("cli> Total: 0\ncli> Total: 15\n", "cli> Total: 1\ncli> Total: 20\n"));

        assertThat(health.counts()).isEqualTo(new ProcessCounts(3, 4, 12, 1));
    }

    @Test
    void aNegativeProcessingCountIsUnexpected() {
        assertUnexpected(stdout("health_session_ok").replace("cli> Total: 15\n",
            "cli> Total: 14\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"login_failed", "unreachable"})
    void outputWithoutPromptsIsUnexpected(String fixtureCase) {
        assertUnexpected(stdout(fixtureCase));
    }

    @Test
    void emptyOutputIsUnexpected() {
        assertUnexpected("");
    }

    @Test
    void aMissingExitPromptIsUnexpected() {
        String ok = stdout("health_session_ok");
        assertUnexpected(ok.substring(0, ok.lastIndexOf("cli> ")));
    }

    @Test
    void anExtraPromptIsUnexpected() {
        assertUnexpected(stdout("health_session_ok") + "cli> \n");
    }

    @Test
    void outputAfterExitIsUnexpected() {
        assertUnexpected(stdout("health_session_ok") + "Bye\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "INUBIT Process Engine 6.1.0.89|Unknown command",
        "INUBIT Process Engine 6.1.0.89|INUBIT Process Engine",
        "INUBIT Process Engine 6.1.0.89|INUBIT Process Engine 6.1.0.89\nsecond line",
        "Process Engine is alive (up 2 days, 03:14:07)|Process Engine is alive and well",
        "Process Engine is alive (up 2 days, 03:14:07)|Prozess-Engine ist bereit",
        "Maintenance mode: off|Maintenance mode: maybe",
        "Maintenance mode: off|Maintenance: off",
        "Used memory: 1210 MB|Used memory: many MB",
        "Used memory: 1210 MB|Heap memory: 1210 MB",
        "Free memory: 838 MB\n|",
        "Max memory: 2048 MB|Max memory: 2048 MB\nPermGen memory: 256 MB",
        "Max memory: 2048 MB|Max memory: 2 GB",
        "cli> Total: 3|cli> Total: x",
        "cli> Total: 3|cli> Summe: 3",
        "cli> Total: 3|cli> Total: 3\nTotal: 3",
        "cli> Total: 3|cli> Total: 99999999999999999999",
        "cli> Total: 3|cli> Total: -3",
        "cli> Total: 3|cli> ",
    })
    void anUnknownOrIncompleteBlockIsUnexpected(String replacement) {
        String[] parts = replacement.split("\\|", -1);
        String ok = stdout("health_session_ok");
        assertThat(ok).contains(parts[0]);

        assertUnexpected(ok.replace(parts[0], parts[1]));
    }
}
