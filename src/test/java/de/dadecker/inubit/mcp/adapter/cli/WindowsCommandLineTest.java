package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.Built;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.Cause;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.Result;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.TooLong;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 013 (T007, research R-1, R-2, R-4, contracts/windows-invocation.md): the argument list
 * the JDK receives for a StartCLI call on Windows. Pure (Windows paths are plain strings), so it
 * runs on every platform; the JDK's handling of the list is pinned on Windows by T008.
 */
class WindowsCommandLineTest {

    private static final String SYSTEM = "C:\\Windows\\system32";
    private static final String SCRIPT = "C:\\inubit\\client 8.1\\bin\\startcli.bat";
    private static final String COMMAND = "export --exportModule 'Mod_1' --exportFile"
        + " 'C:/Users/svc/AppData/Local/Temp/inubit-mcp-1/out.zip'";
    private static final String URL = "https://cli.example.test/ibis/servlet/IBISSoapServlet";

    @Test
    void buildsTheInterpreterCallWithEveryArgumentQuotedInOneInnerArgument() {
        Result result = WindowsCommandLine.build(SYSTEM, SCRIPT,
            List.of("-u", "svc_user", "--trustStoreFilePath", "C:\\certs\\trust.p12",
                "--disableHostNameVerification", "--execCommand", COMMAND, URL));

        assertThat(result).isEqualTo(new Built(List.of(
            "C:\\Windows\\system32\\cmd.exe", "/d", "/v:off", "/s", "/c",
            "\"\"C:\\inubit\\client 8.1\\bin\\startcli.bat\" \"-u\" \"svc_user\""
                + " \"--trustStoreFilePath\" \"C:\\certs\\trust.p12\""
                + " \"--disableHostNameVerification\" \"--execCommand\" \"" + COMMAND + "\" \""
                + URL + "\"\"")));
    }

    @Test
    void theInnerArgumentStartsAndEndsWithADoubleQuoteSoTheJdkPassesItUnchanged() {
        List<String> arguments = built(WindowsCommandLine.build(SYSTEM, SCRIPT, List.of("-v")));

        assertThat(arguments.get(5)).isEqualTo("\"\"" + SCRIPT + "\" \"-v\"\"")
            .startsWith("\"").endsWith("\"");
    }

    @Test
    void theSystemDirectoryIsUsedAsReportedWithoutTheHostsSeparator() {
        assertThat(built(WindowsCommandLine.build("D:\\WINNT\\System32", SCRIPT, List.of("-v")))
            .get(0)).isEqualTo("D:\\WINNT\\System32\\cmd.exe");
        // GetSystemDirectoryW ends in a backslash only for a root directory
        assertThat(built(WindowsCommandLine.build("C:\\", SCRIPT, List.of("-v"))).get(0))
            .isEqualTo("C:\\cmd.exe");
    }

    /** The test values of contracts/windows-invocation.md that reach StartCLI unchanged. */
    @ParameterizedTest
    @ValueSource(strings = {"plain", "with space", "''", "a'b", "(x)", "a;b", "a,b", "a=b",
        "C:/dir/x", "*", "a?b", "C:\\dir\\x", "\\\\not-at-the-end\\x", ""})
    void quotesAValueWithoutChangingIt(String value) {
        assertThat(inner(List.of("-u", value)))
            .isEqualTo("\"\"" + SCRIPT + "\" \"-u\" \"" + value + "\"\"");
    }

    @Test
    void doublesTrailingBackslashesSoThatTheyDoNotEscapeTheClosingQuote() {
        assertThat(inner(List.of("--trustStoreFilePath", "C:\\dir\\")))
            .isEqualTo("\"\"" + SCRIPT + "\" \"--trustStoreFilePath\" \"C:\\dir\\\\\"\"");
        assertThat(inner(List.of("C:\\dir\\\\", "\\")))
            .isEqualTo("\"\"" + SCRIPT + "\" \"C:\\dir\\\\\\\\\" \"\\\\\"\"");
    }

    @Test
    void acceptsTheLongestAllowedLine() {
        List<String> arguments = argumentsWithInnerLength(WindowsCommandLine.MAX_INNER_LENGTH);

        List<String> command = built(WindowsCommandLine.build(SYSTEM, SCRIPT, arguments));

        assertThat(WindowsCommandLine.MAX_INNER_LENGTH).isEqualTo(8191 - 2048);
        assertThat(command.get(5)).hasSize(6143);
    }

    @Test
    void refusesOneCharacterOverTheLimitAsToolInputWhenTheCommandMakesItLong() {
        List<String> arguments = argumentsWithInnerLength(WindowsCommandLine.MAX_INNER_LENGTH + 1);

        assertThat(WindowsCommandLine.build(SYSTEM, SCRIPT, arguments))
            .isEqualTo(new TooLong(Cause.TOOL_INPUT, 6144));
    }

    @Test
    void refusesAsConfigurationWhenTheLineIsTooLongEvenWithAnEmptyCommand() {
        String trustStore = "C:\\" + "t".repeat(6200);

        Result result = WindowsCommandLine.build(SYSTEM, SCRIPT,
            List.of("-u", "svc_user", "--trustStoreFilePath", trustStore, "--execCommand",
                "export", URL));

        assertThat(result).isInstanceOfSatisfying(TooLong.class,
            tooLong -> assertThat(tooLong.cause()).isEqualTo(Cause.CONFIGURATION));
    }

    @Test
    void theConfiguredPartsAtTheLimitWithAnEmptyCommandStillCountAsToolInput() {
        // configured parts plus "--execCommand" "" exactly at the limit: any command is too long
        List<String> arguments = argumentsWithInnerLength(WindowsCommandLine.MAX_INNER_LENGTH);
        String filler = arguments.get(1);
        int commandLength = arguments.get(3).length();
        arguments.set(1, filler + "u".repeat(commandLength));

        assertThat(WindowsCommandLine.build(SYSTEM, SCRIPT, arguments))
            .isEqualTo(new TooLong(Cause.TOOL_INPUT,
                WindowsCommandLine.MAX_INNER_LENGTH + commandLength));
    }

    @Test
    void aTooLongLineWithoutCommandIsConfiguration() {
        String home = "C:\\" + "h".repeat(6200);

        assertThat(WindowsCommandLine.build(SYSTEM, home + "\\bin\\startcli.bat", List.of("-v")))
            .isInstanceOfSatisfying(TooLong.class,
                tooLong -> assertThat(tooLong.cause()).isEqualTo(Cause.CONFIGURATION));
    }

    /** A value that should have been refused earlier (T013, T015, T016) is a programming error. */
    @ParameterizedTest
    @ValueSource(strings = {"\"", "%PATH%", "!x!", "^", "&", "|", "<", ">", "\t", "\n", "\u00fc"})
    void refusesAnInterpretedCharacterAsAProgrammingErrorWithoutTheValue(String character) {
        String value = "secret-looking" + character + "value";

        assertThatThrownBy(() -> WindowsCommandLine.build(SYSTEM, SCRIPT,
                List.of("-u", value, "--execCommand", COMMAND, URL)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("argument 2")
            .hasMessageNotContaining("secret-looking");
    }

    @Test
    void refusesAnInterpretedCharacterInTheScriptPath() {
        assertThatThrownBy(() -> WindowsCommandLine.build(SYSTEM,
                "C:\\inubit%x%\\bin\\startcli.bat", List.of("-v")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("script")
            .hasMessageContaining("percent sign")
            .hasMessageNotContaining("inubit%x%");
    }

    /**
     * {@code -u <filler> --execCommand <command> <url>} whose inner line has exactly
     * {@code length} characters: the opening {@code "}, {@code "<script>"}, {@code  "<arg>"} per
     * argument and the closing {@code "}.
     */
    private static List<String> argumentsWithInnerLength(int length) {
        List<String> arguments = new ArrayList<>(List.of("-u", "svc_user", "--execCommand", "",
            URL));
        int fixed = 4 + SCRIPT.length()
            + arguments.stream().mapToInt(argument -> 3 + argument.length()).sum();
        arguments.set(3, "export --exportModule 'M" + "x".repeat(length - fixed - 25) + "'");
        assertThat(4 + SCRIPT.length()
            + arguments.stream().mapToInt(argument -> 3 + argument.length()).sum())
            .isEqualTo(length);
        return arguments;
    }

    private static String inner(List<String> arguments) {
        return built(WindowsCommandLine.build(SYSTEM, SCRIPT, arguments)).get(5);
    }

    private static List<String> built(Result result) {
        assertThat(result).isInstanceOf(Built.class);
        return ((Built) result).command();
    }
}
