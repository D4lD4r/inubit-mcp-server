package de.dadecker.inubit.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand.Listing;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61HealthParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T035 (feature 012, research D-16): the INUBIT 6.1 catalogue cases of
 * {@code tools/record-fixtures.sh} are recorded with StartCLI only. The test reads the script and
 * checks for every {@code v61_*} case: no curl/REST step, no {@code -p} password option, the
 * password only on StartCLI's stdin under the allowlisted environment, the group allowlist check
 * before the recording, and session commands taken from {@link CliSessionCommand} exactly as the
 * 6.1 adapters send them.
 */
class RecordFixturesCatalogueTest {

    private static final Path SCRIPT = Path.of("tools/record-fixtures.sh");

    private static final Map<String, List<CliSessionCommand>> SESSIONS = Map.of(
        "v61_health_session", V61HealthParser.COMMANDS,
        "v61_ps_all", List.of(CliSessionCommand.psList(Listing.ALL, CliSessionCommand.MAX_ROWS)),
        "v61_ps_error",
        List.of(CliSessionCommand.psList(Listing.ERROR, CliSessionCommand.MAX_ROWS)),
        "v61_ps_waiting",
        List.of(CliSessionCommand.psList(Listing.WAITING, CliSessionCommand.MAX_ROWS)),
        "v61_ps_queued",
        List.of(CliSessionCommand.psList(Listing.QUEUED, CliSessionCommand.MAX_ROWS)),
        "v61_ps_limit5", List.of(CliSessionCommand.psList(Listing.ALL, 5)));

    /** The local runs without server, credentials or stdin, by their StartCLI option. */
    private static final Map<String, String> LOCAL_RUNS = Map.of("v61_help", "-h",
        "v61_version", "-v");

    private static final Pattern ARM =
        Pattern.compile("(?ms)^ {8}([\\w|*]+)\\)\\n(.*?);;");

    static Stream<String> v61Cases() {
        return Stream.concat(LOCAL_RUNS.keySet().stream(), SESSIONS.keySet().stream()).sorted();
    }

    @Test
    void theCatalogueHasExactlyTheCasesOfD16AndListsThem() throws IOException {
        List<String> labels = new ArrayList<>();
        Matcher arm = ARM.matcher(function("define_case"));
        while (arm.find()) {
            Arrays.stream(arm.group(1).split("\\|")).filter(label -> label.startsWith("v61_"))
                .forEach(labels::add);
        }

        assertThat(labels).containsExactlyInAnyOrderElementsOf(v61Cases().toList());
        String list = function("list_cases");
        v61Cases().forEach(name -> assertThat(list).contains(name));
    }

    @ParameterizedTest
    @MethodSource("v61Cases")
    void aCaseHasNoCurlOrRestStep(String name) throws IOException {
        String arm = arm(name);
        String record = function(recordFunction(arm));

        assertThat(kind(arm)).isNotEqualTo("rest");
        assertThat(arm).doesNotContain("curl", "preflight_unknown_pid=yes");
        assertThat(record).doesNotContain("curl", "rest_call", "require_unknown_pid_absent",
            "--execCommand");
    }

    @ParameterizedTest
    @MethodSource("v61Cases")
    void aCaseNeverPassesThePasswordAsAnOption(String name) throws IOException {
        String arm = arm(name);

        assertThat(tokens(arm)).doesNotContain("-p").noneMatch(token -> token.contains("--pass"));
        for (String call : startcliCalls(function(recordFunction(arm)))) {
            assertThat(tokens(call)).as(call).doesNotContain("-p")
                .noneMatch(token -> token.contains("--pass"));
        }
    }

    @ParameterizedTest
    @MethodSource("v61Cases")
    void thePasswordReachesStartcliOnlyThroughStdin(String name) throws IOException {
        String arm = arm(name);
        String record = function(recordFunction(arm));
        List<String> calls = startcliCalls(record);

        assertThat(calls).isNotEmpty().allSatisfy(call -> {
            // the allowlisted environment of build_cli_env, nothing else
            assertThat(call).contains("\"${child_env[@]}\" ./bin/startcli.sh");
            String arguments = call.substring(call.indexOf("./bin/startcli.sh"));
            assertThat(arguments).doesNotContain("password");
        });
        String passwordLines = String.join("\n", logicalLines(record).stream()
            .filter(line -> !line.startsWith("#") && line.contains("password")).toList());
        if (LOCAL_RUNS.containsKey(name)) {
            assertThat(passwordLines).isEmpty();
            assertThat(calls).allSatisfy(call -> assertThat(call).contains("< /dev/null"));
        } else {
            assertThat(passwordLines).matches("\\(cd \"\\$\\{cli_home}\" && printf '%s\\\\n'"
                + " \"\\$\\{password}\" [^|]*\\| \"\\$\\{child_env\\[@]}\" \\./bin/startcli\\.sh"
                + " .*");
        }
        String environment = function("build_cli_env");
        assertThat(environment).contains("child_env=(env -i)",
            "for name in PATH HOME TMPDIR LANG LC_ALL USER; do");
        assertThat(environment).doesNotContain("password", "USERNAME", "PASSWORD");
    }

    @ParameterizedTest
    @MethodSource("v61Cases")
    void theGroupAllowlistIsCheckedBeforeTheCaseIsRecorded(String name) throws IOException {
        String main = function("main");
        int listExit = main.indexOf("exit 0");
        int allowlist = main.indexOf("[[ \" ${RECORD_ALLOWED_GROUPS} \" == *\" ${stage} \"* ]]");
        int define = main.indexOf("define_case \"${case_name}\"");
        int record = main.indexOf("\"record_${kind}\"");

        assertThat(listExit).isPositive().isLessThan(allowlist);
        assertThat(main.substring(listExit + "exit 0".length(), record))
            .doesNotContainPattern("\\b(exit|return)\\b");
        assertThat(allowlist).isPositive().isLessThan(define);
        assertThat(define).isLessThan(record);
        assertThat(function(recordFunction(arm(name)))).doesNotContain("RECORD_ALLOWED_GROUPS");
    }

    @ParameterizedTest
    @MethodSource("v61Cases")
    void aCaseRunsTheLocalOptionOrTheSessionOfThe61Adapters(String name) throws IOException {
        String arm = arm(name);

        if (LOCAL_RUNS.containsKey(name)) {
            assertThat(kind(arm)).isEqualTo("cli_local");
            assertThat(arm).contains("local_option=" + LOCAL_RUNS.get(name)).doesNotContain(
                "session=");
            return;
        }
        assertThat(kind(arm)).isEqualTo("cli_session");
        Matcher session = Pattern.compile("session=\\(([^)]*)\\)").matcher(arm);
        assertThat(session.find()).as("session=(...) in " + name).isTrue();
        List<String> commands = new ArrayList<>();
        Matcher item = Pattern.compile("'([^']*)'|(\\S+)").matcher(session.group(1));
        while (item.find()) {
            commands.add(item.group(1) != null ? item.group(1) : item.group(2));
        }
        assertThat(commands).containsExactlyElementsOf(
            SESSIONS.get(name).stream().map(CliSessionCommand::text).toList());
        // the session ends with the allow-listed exit, appended by the record function
        assertThat(function("record_cli_session"))
            .contains("\"${session[@]}\" " + CliSessionCommand.EXIT.text() + " |");
    }

    private static String script() throws IOException {
        return Files.readString(SCRIPT);
    }

    /** The body of {@code name() { ... }}. */
    private static String function(String name) throws IOException {
        Matcher body = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n(.*?)^}$")
            .matcher(script());
        assertThat(body.find()).as("function " + name).isTrue();
        return body.group(1);
    }

    /** The arm of {@code define_case} that handles {@code name}. */
    private static String arm(String name) throws IOException {
        Matcher arm = ARM.matcher(function("define_case"));
        while (arm.find()) {
            if (Arrays.asList(arm.group(1).split("\\|")).contains(name)) {
                return arm.group(2);
            }
        }
        throw new AssertionError("no case arm for " + name);
    }

    private static String kind(String arm) {
        Matcher kind = Pattern.compile("\\bkind=(\\w+)").matcher(arm);
        assertThat(kind.find()).as("kind= in " + arm).isTrue();
        return kind.group(1);
    }

    private static String recordFunction(String arm) {
        return "record_" + kind(arm);
    }

    /** Lines with backslash continuations folded. */
    private static List<String> logicalLines(String text) {
        return Arrays.stream(text.replaceAll("\\s*\\\\\\n\\s*", " ").split("\\n"))
            .map(String::strip).toList();
    }

    private static List<String> startcliCalls(String body) {
        return logicalLines(body).stream().filter(line -> line.contains("./bin/startcli.sh"))
            .toList();
    }

    private static List<String> tokens(String text) {
        return Arrays.asList(text.split("[\\s\"'()]+"));
    }
}
