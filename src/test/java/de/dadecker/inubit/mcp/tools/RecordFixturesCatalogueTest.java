package de.dadecker.inubit.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand.Listing;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61HealthParser;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T035 (feature 012, research D-16): the INUBIT 6.1 catalogue cases of
 * {@code tools/record-fixtures.sh} are recorded with StartCLI only. The test reads the script and
 * checks for every {@code v61_*} case: no curl/REST step, no {@code -p} password option, the
 * password only on StartCLI's stdin under the allowlisted environment, the group allowlist check
 * before the recording, and session commands taken from {@link CliSessionCommand} exactly as the
 * 6.1 adapters send them.
 *
 * <p>T034 (feature 017, research D-10, D-11): {@code RECORD_INUBIT_LINE=9.0} records the 9.0
 * catalogue into {@code fixtures/v9_0/}, StartCLI against {@code <baseUrl>}, every REST case with
 * the {@code Accept} of its production adapter, and a {@code <case>.origin} of
 * {@code recorded <date> <INUBIT version>}; the 8.1 default stays unchanged. The recording runs
 * against a copy of the script with fake {@code curl} and StartCLI (POSIX only).
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

    /** The INUBIT 9.0 catalogue: REST and POSIX StartCLI, all read-only (research D-10). */
    static final List<String> V90_CATALOGUE = List.of("healthcheck", "ready", "system_info",
        "metrics", "log_systemLog", "log_systemLog_filtered", "model_models_owner",
        "modelByName_sample", "model_export_sample", "rest_auth_failed", "rest_not_found",
        "version", "help", "processErrorStart_unknown", "kill_unknown", "unknown_command",
        "login_failed", "unreachable", "ps_all", "export_history_sample",
        "export_modules_sample");

    /** The 9.0 REST cases and the media range their production adapter sends (D-11). */
    static final Map<String, MediaRange> V90_ACCEPT = v90Accept();

    /** The cases {@code tools/record-fixtures.sh --list} printed before feature 017. */
    private static final List<String> V81_LIST = List.of("log_processLog", "log_systemLog",
        "log_queueLog", "log_connectionLog", "log_schedulerLog", "log_auditLog",
        "log_keyManagerLog", "log_webserviceManager", "log_systemLog_filtered",
        "log_queueLog_error", "metrics", "healthcheck", "ready", "system_info", "version",
        "model_models_owner", "modelByName_sample", "model_export_sample",
        "processErrorStart_unknown", "processErrorStart_unknown_default_locale", "kill_unknown",
        "unknown_command", "login_failed", "unreachable", "ps_error", "ps_waiting", "ps_all",
        "ps_queued", "ps_no_selector", "ps_filter_workflow", "ps_filter_space", "v61_help",
        "v61_version", "v61_health_session", "v61_ps_all", "v61_ps_error", "v61_ps_waiting",
        "v61_ps_queued", "v61_ps_limit5", "export_history_sample", "export_modules_sample");

    private static Map<String, MediaRange> v90Accept() {
        Map<String, MediaRange> accept = new LinkedHashMap<>();
        // V81MonitoringAdapter, LogEndpoint
        for (String json : List.of("healthcheck", "ready", "metrics", "log_systemLog",
            "log_systemLog_filtered")) {
            accept.put(json, MediaRange.JSON);
        }
        // V81VersionDetector, V81InventoryAdapter (and their 401/404 bodies)
        for (String xml : List.of("system_info", "model_models_owner", "modelByName_sample",
            "rest_auth_failed", "rest_not_found")) {
            accept.put(xml, MediaRange.XML);
        }
        accept.put("model_export_sample", MediaRange.ANY);
        return Map.copyOf(accept);
    }

    /** The cases of {@code V90_CASES=(...)} in the script. */
    static List<String> v90CasesOfTheScript() throws IOException {
        Matcher array = Pattern.compile("(?m)^readonly V90_CASES=\\(([^)]*)\\)").matcher(script());
        assertThat(array.find()).as("readonly V90_CASES=(...) in " + SCRIPT).isTrue();
        return Arrays.stream(array.group(1).strip().split("\\s+")).toList();
    }

    @Test
    void theNinePointZeroCatalogueHasTheReadOnlyCasesOfD10() throws IOException {
        List<String> cases = v90CasesOfTheScript();

        assertThat(cases).containsExactlyElementsOf(V90_CATALOGUE);
        assertThat(V90_ACCEPT.keySet()).allMatch(cases::contains);
        for (String name : cases) {
            String arm = arm(name);
            assertThat(arm).as(name).doesNotContain("import", "--tag", "command='tag");
            if (arm.contains("processErrorStart") || arm.contains("kill ")) {
                assertThat(arm).as(name).contains("${UNKNOWN_PID}", "preflight_unknown_pid=yes");
            }
            if (kind(arm).equals("rest")) {
                assertThat(V90_ACCEPT).as("Accept of " + name).containsKey(name);
            }
        }
    }

    @Test
    void theNewNinePointZeroCasesAreReadOnlyRuns() throws IOException {
        assertThat(kind(arm("help"))).isEqualTo("cli_local");
        assertThat(arm("help")).contains("local_option=-h");
        assertThat(kind(arm("rest_auth_failed"))).isEqualTo("rest");
        assertThat(arm("rest_auth_failed")).contains("path='/ibis/rest/system/info'",
            "auth=dummy").doesNotContain("method=");
        assertThat(kind(arm("rest_not_found"))).isEqualTo("rest");
        assertThat(arm("rest_not_found")).contains("/ibis/rest/model/modelByName/")
            .doesNotContain("method=", "SAMPLE_DIAGRAM");
        // the dummy password reaches curl like the real one: through stdin (-K -)
        assertThat(function("rest_call")).contains("curl --config - \"$@\" \"${url}\"")
            .contains("DUMMY_PASSWORD");
    }

    /** Runs a copy of the script against fake curl and StartCLI (research D-10, T034). */
    @Nested
    @DisabledOnOs(OS.WINDOWS)
    class Recording {

        private static final String BASE_URL = "https://dev.example.test:8443/";
        private static final String DATE = "\\d{4}-\\d{2}-\\d{2}";
        private static final String V81_ACCEPT =
            "application/json, application/xml;q=0.9, */*;q=0.8";

        @TempDir
        Path temp;

        private Path project;
        private Path client81;
        private Path client90;

        @BeforeEach
        void copyTheScriptAndInstallTheFakes() throws IOException {
            project = temp.resolve("project");
            Path tools = Files.createDirectories(project.resolve("tools"));
            Files.copy(SCRIPT, tools.resolve("record-fixtures.sh"));
            Files.copy(Path.of("tools/anonymize.py"), tools.resolve("anonymize.py"));
            Path bin = Files.createDirectories(temp.resolve("bin"));
            executable(bin.resolve("curl"), FAKE_CURL);
            client81 = client("client-8.1", "8.1.17");
            client90 = client("client-9.0", "9.0.7");
            for (String jdk : List.of("jdk-17", "jdk-21", "jdk-override")) {
                Files.createDirectories(temp.resolve(jdk));
            }
            // the recommended profile of research D-1: the 8.1 client is the version-less one
            writeDefaults("""
                  cliHome: %1$s/client-8.1   # version-less client
                  cliJavaHome: %1$s/jdk-17
                  cliClients:
                    "8.1": { home: %1$s/client-8.1, javaHome: %1$s/jdk-17 }
                    "9.0": { home: %1$s/client-9.0, javaHome: %1$s/jdk-21 }
                """.formatted(temp));
        }

        private Path client(String name, String version) throws IOException {
            Path home = temp.resolve(name);
            executable(Files.createDirectories(home.resolve("bin")).resolve("startcli.sh"),
                FAKE_STARTCLI);
            Files.writeString(home.resolve("client-version"), version + "\n");
            return home;
        }

        private void writeDefaults(String defaults) throws IOException {
            Files.writeString(temp.resolve("config.yaml"), "defaults:\n" + defaults + """
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: %s
                """.formatted(BASE_URL));
        }

        @Test
        void listPrintsTheNinePointZeroCatalogue() throws Exception {
            Run run = run(Map.of("RECORD_INUBIT_LINE", "9.0"), "--list");

            assertThat(run.exit()).as(run.output()).isZero();
            assertThat(firstWords(run.stdout())).containsExactlyElementsOf(
                v90CasesOfTheScript());
        }

        @Test
        void theDefaultListIsTheCatalogueOf81() throws Exception {
            Run byDefault = run(Map.of(), "--list");
            Run explicit = run(Map.of("RECORD_INUBIT_LINE", "8.1"), "--list");

            assertThat(firstWords(byDefault.stdout())).containsExactlyElementsOf(V81_LIST);
            assertThat(explicit.stdout()).isEqualTo(byDefault.stdout());
        }

        @Test
        void anUnknownLineIsRefused() throws Exception {
            Run run = run(Map.of("RECORD_INUBIT_LINE", "9.1"), "--list");

            assertThat(run.exit()).isNotZero();
            assertThat(run.stderr()).contains("RECORD_INUBIT_LINE");
            assertThat(run.stdout()).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("de.dadecker.inubit.mcp.tools.RecordFixturesCatalogueTest#v90RestCases")
        void aNinePointZeroRestCaseSendsTheAcceptOfItsAdapterAndWritesItsOrigin(String name)
            throws Exception {
            Run run = record("9.0", name);

            assertThat(run.exit()).as(run.output()).isZero();
            List<Map<String, String>> calls = curlCalls();
            // first the version of the node (for the origin), then the case
            assertThat(calls).hasSize(2);
            assertThat(calls.get(0).get("url"))
                .isEqualTo("https://dev.example.test:8443/ibis/rest/system/info");
            assertThat(calls.get(0).get("accept")).isEqualTo(MediaRange.XML.header());
            assertThat(calls.get(1).get("accept")).isEqualTo(V90_ACCEPT.get(name).header());
            assertThat(calls.get(1).get("auth"))
                .isEqualTo(switch (name) {
                    case "healthcheck", "ready" -> "none";
                    case "rest_auth_failed" -> "dummy";
                    default -> "basic";
                });
            Path rest = project.resolve("src/test/resources/fixtures/v9_0/rest");
            assertThat(rest.resolve(name + ".http")).exists();
            assertThat(Files.readString(rest.resolve(name + ".origin")))
                .matches("recorded " + DATE + " 9\\.0\\.7\\n");
            assertThat(project.resolve("src/test/resources/fixtures/v8_1")).doesNotExist();
        }

        @Test
        void theErrorCasesRecordTheStatusOfTheServer() throws Exception {
            assertThat(record("9.0", "rest_auth_failed").exit()).isZero();
            assertThat(record("9.0", "rest_not_found").exit()).isZero();

            Path rest = project.resolve("src/test/resources/fixtures/v9_0/rest");
            assertThat(Files.readString(rest.resolve("rest_auth_failed.http")))
                .startsWith("status: 401\n");
            assertThat(Files.readString(rest.resolve("rest_not_found.http")))
                .startsWith("status: 404\n");
            assertThat(curlCalls().get(3).get("url"))
                .contains("/ibis/rest/model/modelByName/NoSuch");
        }

        @Test
        void aNinePointZeroCliCaseRunsAgainstTheBaseUrl() throws Exception {
            Run run = record("9.0", "ps_all");

            assertThat(run.exit()).as(run.output()).isZero();
            // the 9.0 entry of cliClients, checked with -v before the run
            List<String> calls = startcliCalls(client90);
            assertThat(calls).hasSize(2);
            assertThat(calls.get(0)).isEqualTo("-v");
            assertThat(calls.get(1)).contains("--execCommand\tps -csv -a -l 5\t")
                .endsWith("\thttps://dev.example.test:8443").doesNotContain("servlet");
            assertThat(javaHomes(client90)).containsOnly(temp.resolve("jdk-21").toString());
            assertThat(startcliCalls(client81)).isEmpty();
            Path cli = project.resolve("src/test/resources/fixtures/v9_0/cli");
            assertThat(cli.resolve("ps_all.stdout")).exists();
            assertThat(Files.readString(cli.resolve("ps_all.origin")))
                .matches("recorded " + DATE + " 9\\.0\\.7\\n");
        }

        @Test
        void theUnreachableNinePointZeroCaseUsesABaseUrlWithoutServletPath() throws Exception {
            Run run = record("9.0", "unreachable");

            assertThat(run.exit()).as(run.output()).isZero();
            List<String> calls = startcliCalls(client90);
            assertThat(calls).hasSize(2).first().isEqualTo("-v");
            assertThat(calls.get(1)).endsWith("\thttps://127.0.0.1:9");
        }

        @Test
        void theHelpCaseRunsTheLocalClientAndTakesItsVersion() throws Exception {
            Files.writeString(client90.resolve("client-version"), "9.0.5\n");

            Run run = record("9.0", "help");

            assertThat(run.exit()).as(run.output()).isZero();
            assertThat(curlCalls()).isEmpty();
            assertThat(startcliCalls(client90)).containsExactly("-v", "-h");
            assertThat(startcliCalls(client81)).isEmpty();
            Path cli = project.resolve("src/test/resources/fixtures/v9_0/cli");
            assertThat(Files.readString(cli.resolve("help.stdout"))).contains("usage");
            assertThat(Files.readString(cli.resolve("help.origin")))
                .matches("recorded " + DATE + " 9\\.0\\.5\\n");
        }

        @Test
        void aNodeOfAnotherLineIsRefusedBeforeAnythingIsRecorded() throws Exception {
            Run run = record("9.0", "ps_all", Map.of("FAKE_INUBIT_VERSION", "8.1.17"));

            assertThat(run.exit()).isNotZero();
            assertThat(run.stderr()).contains("node", "8.1.17", "not 9.0");
            assertThat(startcliCalls(client90)).containsExactly("-v");
            assertThat(project.resolve("src/test/resources/fixtures")).doesNotExist();
        }

        @Test
        void aClientOfAnotherLineIsRefusedForANodeBackedCase() throws Exception {
            Files.writeString(client90.resolve("client-version"), "8.1.17\n");

            Run run = record("9.0", "ps_all");

            assertThat(run.exit()).isNotZero();
            assertThat(run.stderr()).contains("client", "8.1.17", "not 9.0");
            assertThat(startcliCalls(client90)).containsExactly("-v");
            assertThat(curlCalls()).isEmpty();
            assertThat(project.resolve("src/test/resources/fixtures")).doesNotExist();
        }

        @Test
        void theOverrideWinsOverTheEntry() throws Exception {
            Path override = client("client-override", "9.0.3");

            Run run = record("9.0", "ps_all", Map.of("RECORD_CLI_HOME", override.toString(),
                "RECORD_CLI_JAVA_HOME", temp.resolve("jdk-override").toString()));

            assertThat(run.exit()).as(run.output()).isZero();
            assertThat(startcliCalls(override)).hasSize(2).first().isEqualTo("-v");
            assertThat(javaHomes(override)).containsOnly(
                temp.resolve("jdk-override").toString());
            assertThat(startcliCalls(client90)).isEmpty();
            assertThat(startcliCalls(client81)).isEmpty();
            // the origin names the node's version
            assertThat(Files.readString(project.resolve(
                "src/test/resources/fixtures/v9_0/cli/ps_all.origin")))
                .matches("recorded " + DATE + " 9\\.0\\.7\\n");
        }

        @Test
        void aBlockMappingEntryWithAnUnquotedKeyIsUsed() throws Exception {
            writeDefaults("""
                  cliHome: %1$s/client-8.1
                  cliJavaHome: %1$s/jdk-17
                  cliClients:
                    8.1:
                      home: %1$s/client-8.1
                    9.0:
                      home: %1$s/client-9.0
                      javaHome: %1$s/jdk-21
                """.formatted(temp));

            Run run = record("9.0", "version");

            assertThat(run.exit()).as(run.output()).isZero();
            assertThat(startcliCalls(client90)).containsExactly("-v", "-v");
            assertThat(javaHomes(client90)).containsOnly(temp.resolve("jdk-21").toString());
            assertThat(startcliCalls(client81)).isEmpty();
        }

        @Test
        void withoutA90EntryTheDefaultClientIsUsed() throws Exception {
            writeDefaults("""
                  cliHome: %1$s/client-9.0
                  cliJavaHome: %1$s/jdk-21
                  cliClients:
                    "8.1": { home: %1$s/client-8.1, javaHome: %1$s/jdk-17 }
                """.formatted(temp));

            Run run = record("9.0", "ps_all");

            assertThat(run.exit()).as(run.output()).isZero();
            assertThat(startcliCalls(client90)).hasSize(2);
            assertThat(javaHomes(client90)).containsOnly(temp.resolve("jdk-21").toString());
            assertThat(startcliCalls(client81)).isEmpty();
        }

        @Test
        void casesOfOneLineAreRefusedOnTheOther() throws Exception {
            for (String name : List.of("v61_help", "ps_error", "log_queueLog")) {
                Run run = record("9.0", name);
                assertThat(run.exit()).as(name).isNotZero();
                assertThat(run.stderr()).as(name).contains("9.0 catalogue");
            }
            for (String name : List.of("help", "rest_auth_failed", "rest_not_found")) {
                Run run = record("8.1", name);
                assertThat(run.exit()).as(name).isNotZero();
                assertThat(run.stderr()).as(name).contains("RECORD_INUBIT_LINE=9.0");
            }
            assertThat(curlCalls()).isEmpty();
            assertThat(startcliCalls(client81)).isEmpty();
            assertThat(startcliCalls(client90)).isEmpty();
        }

        @Test
        void the81DefaultIsUnchanged() throws Exception {
            Run rest = record(null, "system_info");
            // the override and the cliClients entries are 9.0-only
            Run cli = record(null, "ps_all", Map.of("RECORD_CLI_HOME",
                client90.toString(), "RECORD_CLI_JAVA_HOME", temp.resolve("jdk-21").toString()));

            assertThat(rest.exit()).as(rest.output()).isZero();
            assertThat(cli.exit()).as(cli.output()).isZero();
            assertThat(curlCalls()).singleElement().satisfies(call -> assertThat(
                call.get("accept")).isEqualTo(V81_ACCEPT));
            assertThat(startcliCalls(client81)).singleElement().asString()
                .contains("\thttps://dev.example.test:8443/")
                .endsWith("/ibis/servlet/IBISSoapServlet");
            assertThat(javaHomes(client81)).containsOnly(temp.resolve("jdk-17").toString());
            assertThat(startcliCalls(client90)).isEmpty();
            Path fixtures = project.resolve("src/test/resources/fixtures");
            assertThat(fixtures.resolve("v8_1/rest/system_info.xml")).exists();
            assertThat(fixtures.resolve("v8_1/cli/ps_all.stdout")).exists();
            assertThat(fixtures.resolve("v9_0")).doesNotExist();
            try (Stream<Path> files = Files.walk(fixtures)) {
                assertThat(files.map(Path::toString)).noneMatch(file -> file.endsWith(".origin"));
            }
        }

        private Run record(String line, String name) throws Exception {
            return record(line, name, Map.of());
        }

        private Run record(String line, String name, Map<String, String> more) throws Exception {
            Map<String, String> environment = new LinkedHashMap<>(more);
            if (line != null) {
                environment.put("RECORD_INUBIT_LINE", line);
            }
            return run(environment, "dev", "node1", name);
        }

        private Run run(Map<String, String> more, String... arguments) throws Exception {
            List<String> command = new ArrayList<>(List.of("bash",
                project.resolve("tools/record-fixtures.sh").toString()));
            command.addAll(List.of(arguments));
            ProcessBuilder builder = new ProcessBuilder(command).directory(temp.toFile())
                .redirectOutput(temp.resolve("out.log").toFile())
                .redirectError(temp.resolve("err.log").toFile());
            Map<String, String> environment = builder.environment();
            environment.keySet().removeIf(key -> key.startsWith("RECORD_")
                || key.startsWith("INUBIT_"));
            environment.put("PATH", temp.resolve("bin") + ":" + System.getenv("PATH"));
            environment.put("HOME", temp.toString());
            environment.put("TMPDIR", temp.toString());
            environment.put("INUBIT_MCP_CONFIG", temp.resolve("config.yaml").toString());
            environment.put("RECORD_ALLOWED_GROUPS", "dev");
            environment.put("RECORD_PINNED_PUBKEY", "sha256//AAAA");
            environment.put("RECORD_OWNER", "acme-owner");
            environment.put("RECORD_SAMPLE_DIAGRAM", "Sample_Diagram");
            environment.put("INUBIT_DEV_USERNAME", "recorder");
            environment.put("INUBIT_DEV_PASSWORD", "s3cret-recorder-pw");
            environment.put("FAKE_LOG", temp.resolve("curl.log").toString());
            environment.putAll(more);
            Process process = builder.start();
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
            return new Run(process.exitValue(), Files.readString(temp.resolve("out.log")),
                Files.readString(temp.resolve("err.log")));
        }

        private List<Map<String, String>> curlCalls() throws IOException {
            Path log = temp.resolve("curl.log");
            if (!Files.exists(log)) {
                return List.of();
            }
            return Files.readAllLines(log).stream().map(line -> {
                Map<String, String> call = new LinkedHashMap<>();
                for (String field : line.split("\\t")) {
                    int equals = field.indexOf('=');
                    call.put(field.substring(0, equals), field.substring(equals + 1));
                }
                return call;
            }).toList();
        }

        /** Every StartCLI run: its arguments, tab-separated. */
        private List<String> startcliCalls(Path client) throws IOException {
            Path log = client.resolve("calls.log");
            return Files.exists(log) ? Files.readAllLines(log) : List.of();
        }

        /** The {@code JAVA_HOME} of every StartCLI run of {@code client}. */
        private List<String> javaHomes(Path client) throws IOException {
            Path log = client.resolve("java-homes.log");
            return Files.exists(log) ? Files.readAllLines(log) : List.of();
        }
    }

    static Stream<String> v90RestCases() {
        return V90_ACCEPT.keySet().stream().sorted();
    }

    private record Run(int exit, String stdout, String stderr) {
        String output() {
            return stdout + stderr;
        }
    }

    private static List<String> firstWords(String listing) {
        return listing.lines().map(line -> line.split("\\s+", 2)[0]).toList();
    }

    private static void executable(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    /**
     * A curl that answers like an INUBIT 9.0 node (version {@code FAKE_INUBIT_VERSION}) and logs
     * url, Accept and the kind of credentials (never their values) to {@code FAKE_LOG}.
     */
    private static final String FAKE_CURL = """
        #!/usr/bin/env python3
        import io, os, sys, zipfile
        args, headers, values = sys.argv[1:], [], {}
        with_value = {"--pinnedpubkey", "--request", "--header", "--max-time", "--output",
                      "--write-out", "--data-binary", "--config"}
        url, i = None, 0
        while i < len(args):
            if args[i] in with_value:
                if args[i] == "--header":
                    headers.append(args[i + 1])
                values[args[i]] = args[i + 1]
                i += 2
            elif args[i].startswith("--"):
                i += 1
            else:
                url, i = args[i], i + 1
        auth = "none"
        if values.get("--config") == "-":
            auth = "dummy" if "dummy-wrong-password" in sys.stdin.read() else "basic"
        accept = next((h[len("Accept: "):] for h in headers if h.startswith("Accept: ")), "")
        with open(os.environ["FAKE_LOG"], "a") as log:
            log.write(f"url={url}\\taccept={accept}\\tauth={auth}\\n")
        version = os.environ.get("FAKE_INUBIT_VERSION", "9.0.7")
        path = url.split("8443", 1)[1]
        status, ctype = 200, "application/json"
        body = b'{"status":"UP"}'
        if path.startswith("/ibis/rest/system/info") and auth == "dummy":
            status, body = 401, b'{"statusCode":401,"message":"Unauthorized"}'
        elif path.startswith("/ibis/rest/system/info"):
            ctype = "application/xml"
            body = ('<SystemInformationList><SystemInformation name="Version" value="'
                    + version + '"/></SystemInformationList>').encode()
        elif path.startswith("/ibis/rest/model/modelByName/NoSuch"):
            status, body = 404, b'{"statusCode":404,"message":"Not Found"}'
        elif path.startswith("/ibis/rest/model/modelByName/"):
            ctype, body = "application/xml", b'<Model name="Sample_Diagram"/>'
        elif path.startswith("/ibis/rest/model/models"):
            ctype, body = "application/xml", b'<Models><Model name="Sample_Diagram"/></Models>'
        elif path.startswith("/ibis/rest/model/export/"):
            ctype, buffer = "application/zip", io.BytesIO()
            with zipfile.ZipFile(buffer, "w") as archive:
                archive.writestr("workflow/workflow.xml", "<Workflow/>")
                archive.writestr("archive.properties", "version=1")
            body = buffer.getvalue()
        elif path.startswith("/ibis/rest/log/"):
            body = b'{"total":0,"items":[]}'
        if "--output" in values:
            with open(values["--output"], "wb") as out:
                out.write(body)
            sys.stdout.write(f"{status} {ctype}")
        else:
            sys.stdout.write(body.decode())
        """;

    /** A StartCLI that logs its arguments next to itself (StartCLI gets no FAKE_* variables). */
    private static final String FAKE_STARTCLI = """
        #!/usr/bin/env bash
        home="$(cd "$(dirname "$0")/.." && pwd)"
        (IFS=$'\\t'; printf '%s\\n' "$*") >> "${home}/calls.log"
        printf '%s\\n' "${JAVA_HOME}" >> "${home}/java-homes.log"
        cat > /dev/null
        case "$1" in
            -v) printf 'CLI %s\\n' "$(cat "${home}/client-version")" ;;
            -h) printf 'usage: startcli.sh [options] <url>\\n' ;;
            *) printf 'Process list\\n' ;;
        esac
        """;

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

    /** The arm of {@code define_case} that handles {@code name}: the first match, as in bash. */
    static String arm(String name) throws IOException {
        Matcher arm = ARM.matcher(function("define_case"));
        while (arm.find()) {
            if (Arrays.stream(arm.group(1).split("\\|")).anyMatch(label -> name.matches(
                Pattern.quote(label).replace("*", "\\E.*\\Q")))) {
                return arm.group(2);
            }
        }
        throw new AssertionError("no case arm for " + name);
    }

    static String kind(String arm) {
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
