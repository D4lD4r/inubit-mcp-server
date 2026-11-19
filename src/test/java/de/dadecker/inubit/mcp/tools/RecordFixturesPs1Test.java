package de.dadecker.inubit.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.StartCliEnvironment;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * T030 (feature 013, FR-014, research R-13): {@code tools/record-fixtures.ps1} records the
 * read-only Windows cases with the server's rules: the password only through the child's standard
 * input (never {@code -p}, an argument or a variable), the server's environment allowlist, the
 * interpreter of the system directory, the group allowlist checked before each case, raw output
 * only in a private directory. {@code .gitattributes} keeps the Windows recordings byte for byte.
 */
class RecordFixturesPs1Test {

    private static final Path SCRIPT = Path.of("tools/record-fixtures.ps1");
    private static final List<String> CASES = List.of("win_version", "win_help", "win_modules",
        "win_failure", "win_argv", "win_backslash");
    private static final String SESSION_PASSWORD = "session-test-password";
    /**
     * Spec FR-016 and the FR-014 table: each 7.4 case with its kind and the fixture name it
     * writes; the research cases keep their {@code v74_} name.
     */
    private static final List<List<String>> V74_CASES = List.of(
        List.of("v74_help", "local", "help"),
        List.of("v74_version", "local", "v74_version"),
        List.of("v74_exec_help", "exec", "v74_exec_help"),
        List.of("v74_exec_ps_help", "exec", "v74_exec_ps_help"),
        List.of("v74_direct_health", "direct", "v74_direct_health"),
        List.of("v74_health_session", "session", "health_session_ok"),
        List.of("v74_ps_all", "session", "ps_session_all"),
        List.of("v74_ps_error", "session", "ps_session_error"),
        List.of("v74_ps_waiting", "session", "ps_session_waiting"),
        List.of("v74_ps_queued", "session", "ps_session_queued"),
        List.of("v74_ps_limit5", "session", "ps_session_limit5"),
        List.of("v74_login_failed", "failure", "login_failed"),
        List.of("v74_failure", "failure", "unreachable"));
    private static final Path V74_FIXTURES = Path.of("src/test/resources/fixtures/v7_4/cli");

    @TempDir
    Path temp;

    private static String script() throws IOException {
        return Files.readString(SCRIPT, StandardCharsets.UTF_8);
    }

    /** The script without comment lines and comment blocks, so that only code is checked. */
    private static String code() throws IOException {
        return script().replaceAll("(?s)<#.*?#>", "").replaceAll("(?m)^\\s*#.*$", "");
    }

    /** The body of {@code function <name> {…}}: up to the next line that is a lone '}'. */
    private static String function(String name) throws IOException {
        String code = code();
        int start = code.indexOf("function " + name + " {");
        assertThat(start).as("function " + name).isNotNegative();
        int end = code.indexOf("\n}", start);
        return code.substring(start, end);
    }

    /** The case table {@code $Cases = @( … )}: up to the next line that is a lone ')'. */
    private static String table() throws IOException {
        String code = code();
        int start = code.indexOf("$Cases = @(");
        assertThat(start).as("the case table $Cases").isNotNegative();
        int end = code.indexOf("\n)", start);
        return code.substring(start, end + 2);
    }

    /** Feature 025, research D-12, data-model "Recorder case": one object per case. */
    private static List<String> rows() throws IOException {
        return Pattern.compile("(?s)\\[pscustomobject\\]@\\{.*?\\n\\s*\\}").matcher(table())
            .results().map(MatchResult::group).toList();
    }

    private static String field(String row, String name) {
        Matcher value = Pattern.compile("(?m)^\\s*" + name + "\\s*=\\s*(.*?)\\s*$").matcher(row);
        assertThat(value.find()).as(name + " in " + row).isTrue();
        return value.group(1);
    }

    private static String row(String name) throws IOException {
        return rows().stream().filter(r -> field(r, "Name").equals("'" + name + "'")).findFirst()
            .orElseThrow(() -> new AssertionError("no case " + name + " in the table"));
    }

    /** The string literals of an {@code @( … )} field, without their quotes. */
    private static List<String> literals(String field) {
        return Pattern.compile("\"([^\"]*)\"|'([^']*)'").matcher(field).results()
            .map(match -> match.group(1) != null ? match.group(1) : match.group(2)).toList();
    }

    @Test
    void listsEveryCaseOfTheTable() throws IOException {
        List<String> rows = rows();
        assertThat(rows).hasSize(table().split("\\[pscustomobject\\]", -1).length - 1);
        assertThat(rows).allSatisfy(row -> {
            for (String name : List.of("Name", "Description", "Kind", "Arguments", "Commands",
                "Credentials", "Contacts", "Folder", "FixtureName")) {
                field(row, name);
            }
        });
        assertThat(rows.stream().map(row -> field(row, "Name")))
            .containsSubsequence(CASES.stream().map(name -> "'" + name + "'").toList());
        for (String name : CASES) {
            String row = rows.stream().filter(r -> field(r, "Name").equals("'" + name + "'"))
                .findFirst().orElseThrow();
            assertThat(field(row, "FixtureName")).as(name).isEqualTo("'" + name + "'");
            assertThat(field(row, "Folder")).as(name)
                .isEqualTo("'src\\test\\resources\\fixtures\\windows'");
        }
        assertThat(code()).containsPattern("foreach \\(\\$entry in \\$Cases\\) \\{\\s*"
            + "'\\{0,-18\\} \\{1\\}' -f \\$entry\\.Name, \\$entry\\.Description");
    }

    /**
     * Feature 025, spec FR-014, FR-016, research D-12: the 7.4 cases with a one-line description,
     * their kind, and their files in {@code fixtures/v7_4/cli/} under the fixture names of the
     * FR-014 table; a session ends with its end-of-session command, one {@code ps} listing each
     * for the {@code v74_ps_*} cases.
     */
    @Test
    void lists74CasesWithTheirKindAndFixtureNameInThe74Folder() throws IOException {
        for (List<String> v74 : V74_CASES) {
            String name = v74.get(0);
            String row = row(name);
            assertThat(field(row, "Description")).as(name).matches("'[^']{10,}'");
            assertThat(field(row, "Kind")).as(name).isEqualTo("'" + v74.get(1) + "'");
            assertThat(field(row, "Folder")).as(name)
                .isEqualTo("'src\\test\\resources\\fixtures\\v7_4\\cli'");
            assertThat(field(row, "FixtureName")).as(name).isEqualTo("'" + v74.get(2) + "'");
            List<String> arguments = literals(field(row, "Arguments"));
            List<String> commands = literals(field(row, "Commands"));
            if (v74.get(1).equals("session")) {
                assertThat(arguments).as(name).isEmpty();
                assertThat(commands).as(name).hasSizeGreaterThan(1).endsWith("exit");
            } else {
                assertThat(arguments).as(name).isNotEmpty();
                assertThat(commands).as(name).isEmpty();
            }
            if (name.startsWith("v74_ps_")) {
                assertThat(commands).as(name).hasSize(2);
                assertThat(commands.getFirst()).as(name).startsWith("ps ");
            }
            if (!v74.get(2).startsWith("v74_")) {
                assertThat(V74_FIXTURES.resolve(v74.get(2) + ".stdout"))
                    .as("the FR-014 case " + name + " replaces").exists();
            }
        }
        assertThat(field(row("v74_login_failed"), "Contacts")).isEqualTo("$true");
        assertThat(field(row("v74_failure"), "Contacts")).isEqualTo("$false");
    }

    /** Spec FR-018: the arguments and commands of every case are edited in one place. */
    @Test
    void definesTheArgumentsAndCommandsOfEveryCaseOnlyInTheTable() throws IOException {
        String outside = code().replace(table(), "");
        List<String> literals = rows().stream().flatMap(row -> Stream.concat(
            literals(field(row, "Arguments")).stream(), literals(field(row, "Commands")).stream()))
            .distinct().toList();
        assertThat(literals).contains("-v", "-h", "--execCommand", "uptime", "exit",
            "export --exportModule '' --exportModuleGroup '' --exportModuleUser '<owner>'"
                + " --exportFile <file>");
        assertThat(literals).allSatisfy(literal -> assertThat(outside)
            .as("defined outside the table").doesNotContain("'" + literal + "'")
            .doesNotContain("\"" + literal + "\""));
        assertThat(outside).doesNotContain("--execCommand", "--exportModule", "Commands = ");
        assertThat(Pattern.compile("(?s)Invoke-StartCli -Arguments (.*?) -Stdin").matcher(outside)
            .results().map(call -> call.group(1)).toList())
            .as("every launch takes the case's arguments from its table entry").isNotEmpty()
            .allSatisfy(arguments -> assertThat(arguments).contains("$Entry.Arguments"));
    }

    @Test
    void neverPassesAPasswordOnTheCommandLine() throws IOException {
        String code = code();
        assertThat(code).doesNotContainPattern("['\"]-p['\"]")
            .doesNotContainPattern("(?i)--password")
            .doesNotContainPattern("(?i)\\$env:[A-Z_]*PASSWORD");
        Matcher uses = Pattern.compile("(?m)^.*\\$passwordBytes.*$").matcher(code);
        List<String> lines = new ArrayList<>();
        while (uses.find()) {
            lines.add(uses.group().trim());
        }
        assertThat(lines).isNotEmpty().allSatisfy(line -> assertThat(line)
            .containsAnyOf("$passwordBytes =", "[Array]::Clear", "-Stdin $passwordBytes",
                "-PasswordLine $passwordBytes", "Assert-NoSecret $prefix $passwordBytes"));
        assertThat(code).contains("Read-Host -AsSecureString", "RedirectStandardInput = $true",
            "StandardInput.BaseStream.Write($Stdin");
    }

    /**
     * Feature 025, spec FR-017, research D-12: a session case is one StartCLI process whose
     * standard input is the password line, then the case's commands (the end-of-session command
     * last), one per line; the password is never an argument, and the secret check runs on the
     * session's output.
     */
    @Test
    void runsASessionAsOneStartCliProcessWithItsInputOnStandardInputOnly() throws IOException {
        assertThat(code()).doesNotContainPattern("(?i)--?encryptString");
        String session = function("Invoke-StartCliSession");
        List<String> launches = Pattern
            .compile("(?s)Invoke-StartCli -Arguments (.*?) -Stdin (\\S+)").matcher(session)
            .results().map(launch -> launch.group(1) + " | " + launch.group(2)).toList();
        assertThat(launches).as("one startcli.bat process per session").hasSize(1);
        assertThat(launches.getFirst()).isEqualTo(
            "(@('-u', $UserName) + $Entry.Arguments + @($CliUrl)) | $stdin");
        assertThat(session).contains("[Array]::Copy($PasswordLine, $stdin, $PasswordLine.Length)",
            "@($Entry.Commands)", "\"`n\"", "[Array]::Clear($stdin, 0, $stdin.Length)");

        assertThat(function("Invoke-RecordCase"))
            .contains("'session'", "Invoke-CaseSession $Entry");
        String caseSession = function("Invoke-CaseSession");
        int launch = caseSession.indexOf("Invoke-StartCliSession");
        assertThat(caseSession.indexOf("$passwordBytes = Read-PasswordBytes")).isNotNegative()
            .isLessThan(launch);
        assertThat(caseSession).contains("-PasswordLine $passwordBytes",
            "[Array]::Clear($passwordBytes, 0, $passwordBytes.Length)");
        assertThat(caseSession.indexOf("Assert-NoSecret $prefix $passwordBytes"))
            .as("the secret check on the session's output").isGreaterThan(launch);
    }

    /**
     * Feature 025, spec FR-016, FR-017: a script-mode ({@code exec}) or direct-option
     * ({@code direct}) 7.4 case logs in with the password on standard input only, its arguments
     * from the table before the URL, and the secret check runs on its output.
     */
    @Test
    void runsAnExecOrDirectCaseWithThePasswordOnStandardInputOnly() throws IOException {
        assertThat(function("Invoke-RecordCase")).contains("'exec'", "'direct'",
            "Invoke-CaseExec $Entry");
        String exec = function("Invoke-CaseExec");
        List<String> launches = Pattern
            .compile("(?s)Invoke-StartCli -Arguments (.*?) -Stdin (\\S+)").matcher(exec)
            .results().map(call -> call.group(1) + " | " + call.group(2)).toList();
        assertThat(launches).containsExactly(
            "(@('-u', $name) + $Entry.Arguments + @($CliUrl)) | $passwordBytes");
        int launch = exec.indexOf("Invoke-StartCli ");
        assertThat(exec.indexOf("$passwordBytes = Read-PasswordBytes")).isNotNegative()
            .isLessThan(launch);
        assertThat(exec.indexOf("Assert-NoSecret $prefix $passwordBytes")).isGreaterThan(launch);
        assertThat(exec).contains("[Array]::Clear($passwordBytes, 0, $passwordBytes.Length)");
    }

    /** Feature 025, spec FR-016, US4 scenario 1: StartCLI only, no REST request. */
    @Test
    void contactsServersThroughStartCliOnly() throws IOException {
        assertThat(code()).doesNotContainPattern(
            "(?i)Invoke-WebRequest|Invoke-RestMethod|\\bcurl\\b|\\bwget\\b|WebClient|HttpClient"
                + "|Net\\.Http|\\biwr\\b|\\birm\\b");
        assertThat(Pattern.compile("Process\\]::Start\\(").matcher(code()).results().count())
            .as("one process launch, the one of Invoke-StartCli").isEqualTo(1);
        assertThat(function("Invoke-StartCli")).contains("[Diagnostics.Process]::Start($info)");
    }

    /**
     * Feature 025, spec FR-017, US4 scenario 7: a case that contacts the server of -CliUrl checks
     * the group allowlist and then the host allowlist before its kind runs, so before any launch;
     * every kind that logs in to -CliUrl is marked as contacting it, and a failure case reaches
     * -CliUrl only when it is marked.
     */
    @Test
    void checksBothAllowlistsBeforeAnyLaunchOfACaseThatContactsAServer() throws IOException {
        String record = function("Invoke-RecordCase");
        int group = record.indexOf("Assert-AllowedGroup");
        int host = record.indexOf("if ($Entry.Contacts) { Assert-CliUrl }");
        int dispatch = record.indexOf("switch ($Entry.Kind)");
        assertThat(group).isNotNegative().isLessThan(host);
        assertThat(host).isNotNegative().isLessThan(dispatch);
        assertThat(function("Assert-CliUrl")).contains("Assert-Passable 'CliUrl' $CliUrl",
            "Assert-AllowedHost");
        assertThat(rows()).allSatisfy(row -> {
            if (List.of("'export'", "'export-backslash'", "'exec'", "'direct'", "'session'")
                .contains(field(row, "Kind"))) {
                assertThat(field(row, "Contacts")).as(field(row, "Name")).isEqualTo("$true");
            }
        });
        for (String kind : List.of("Invoke-CaseLocal", "Invoke-CaseArgv")) {
            assertThat(function(kind)).doesNotContain("$CliUrl");
        }
        assertThat(function("Invoke-CaseFailure").replaceFirst(
            "(?s)if \\(\\$Entry\\.Contacts\\) \\{[^}]*\\}", "")).doesNotContain("$CliUrl");
    }

    /**
     * Feature 025, spec FR-017, US4 scenario 8: the 7.4 client lists neither a trust-store nor a
     * host-name option; a v74_* case never gets them, its trust store goes to the client's Java
     * runtime settings, and -DisableHostNameVerification with a 7.4 case stops the run before the
     * work directory exists and before any case runs.
     */
    @Test
    void neverPassesTheClientTlsOptionsToA74Case() throws IOException {
        String code = code();
        for (String option : List.of("--trustStoreFilePath", "--disableHostNameVerification")) {
            assertThat(Pattern.compile(Pattern.quote(option)).matcher(code).results()
                .map(MatchResult::start).toList()).as(option).isNotEmpty()
                .allSatisfy(at -> assertThat(enclosingFunction(code, at))
                    .isIn("Get-TlsArguments", "Invoke-CaseArgv"));
        }
        assertThat(Pattern.compile("(?<!function )Get-TlsArguments").matcher(code).results()
            .map(call -> enclosingFunction(code, call.start())).toList()).isNotEmpty()
            .allSatisfy(caller -> assertThat(caller)
                .isIn("Invoke-CaseExport", "Invoke-CaseFailure"));
        assertThat(function("Invoke-CaseFailure")).containsPattern(
            "if \\(\\$Entry\\.Name -notlike 'v74_\\*'\\) \\{[^}]*Get-TlsArguments");
        assertThat(function("Get-TrustStoreToolOptions")).contains(
            "-Djavax.net.ssl.trustStore=$TrustStore", "-Djavax.net.ssl.trustStoreType=$type");
        assertThat(function("Invoke-StartCli"))
            .contains("['JAVA_TOOL_OPTIONS'] = $JavaToolOptions + $ToolOptions");
        assertThat(function("Invoke-CaseExec")).contains("-ToolOptions $TrustStoreOptions");
        assertThat(function("Invoke-StartCliSession")).contains("-ToolOptions $TrustStoreOptions");

        Matcher stop = Pattern.compile("if \\(\\$DisableHostNameVerification\\) \\{\\s*"
            + "Stop-Recording").matcher(code);
        assertThat(stop.find()).isTrue();
        assertThat(code.substring(code.lastIndexOf("\nif (", stop.start()), stop.start()))
            .contains("$_.Name -like 'v74_*'");
        assertThat(stop.start()).isGreaterThan(code.indexOf("$Selected = @("))
            .isLessThan(code.indexOf("$private = New-PrivateDirectory"));
    }

    /** The name of the function whose body holds the offset, or "" outside any function. */
    private static String enclosingFunction(String code, int at) {
        Matcher start = Pattern.compile("(?m)^function ([\\w-]+) \\{").matcher(code);
        String name = "";
        while (start.find() && start.start() < at) {
            name = code.indexOf("\n}", start.start()) > at ? start.group(1) : "";
        }
        return name;
    }

    @Test
    void startsTheBatchFileThroughTheInterpreterOfTheSystemDirectory() throws IOException {
        String launch = function("Invoke-StartCli");
        assertThat(launch).contains("[Environment]::SystemDirectory", "'cmd.exe'",
            "'/d /v:off /s /c '", "'bin\\startcli.bat'", "UseShellExecute = $false");
    }

    @Test
    void givesStartCliTheServersEnvironmentAllowlistOnly() throws Exception {
        String launch = function("Invoke-StartCli");
        assertThat(launch).contains("EnvironmentVariables.Clear()",
            "'NoDefaultCurrentDirectoryInExePath'", "'ComSpec'", "'JAVA_HOME'",
            "'JAVA_TOOL_OPTIONS'");
        Field field = StartCliEnvironment.class.getDeclaredField("WINDOWS_VARIABLES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> variables = (List<String>) field.get(null);
        String expected = String.join(", ", variables.stream().map(v -> "'" + v + "'").toList());
        assertThat(code()).contains("$AllowedVariables = @(" + expected + ")")
            .contains("$JavaToolOptions = '" + StartCliEnvironment.WINDOWS_JAVA_TOOL_OPTIONS
                + "'");
    }

    @Test
    void refusesTheCharactersTheServerRefusesAndQuotesLikeTheServer() throws IOException {
        assertThat(function("Assert-Passable")).contains("0x7f", "IsControl", "'\"%!^&|<>'");
        assertThat(function("ConvertTo-Quoted")).contains("TrimEnd('\\')");
    }

    @Test
    void checksTheGroupAllowlistBeforeEachCase() throws IOException {
        String record = function("Invoke-RecordCase");
        String body = record.substring(record.indexOf('{') + 1).strip()
            .replaceFirst("^param\\([^)]*\\)\\s*", "");
        assertThat(body).as("first statement").startsWith("Assert-AllowedGroup");
        assertThat(function("Assert-AllowedGroup")).contains("$env:RECORD_ALLOWED_GROUPS");
        String code = code();
        int recordStart = code.indexOf("function Invoke-RecordCase {");
        int recordEnd = code.indexOf("\n}", recordStart);
        assertThat(Pattern.compile("(?<!function )Invoke-Case\\w+").matcher(code).results()
            .map(call -> call.start()))
            .as("each case runs only inside Invoke-RecordCase").isNotEmpty()
            .allSatisfy(at -> assertThat(at).isBetween(recordStart, recordEnd));
    }

    @Test
    void contactsOnlyAllowlistedHostsAndRunsInTheClientDirectoryLikeTheServer()
        throws IOException {
        assertThat(function("Assert-CliUrl")).contains("Assert-AllowedHost");
        assertThat(function("Assert-AllowedHost")).contains("$env:RECORD_ALLOWED_HOSTS");
        assertThat(Pattern.compile("-WorkingDirectory (\\S+)").matcher(code()).results()
            .map(match -> match.group(1)).toList()).isNotEmpty().containsOnly("$CliHome");
    }

    @Test
    void keepsRawOutputInAPrivateDirectoryAndNeutralizesIt() throws IOException {
        assertThat(code()).contains("/inheritance:r", "*S-1-5-18:(OI)(CI)F", "neutralize.py",
            "Remove-Item -LiteralPath $private -Recurse -Force",
            "src\\test\\resources\\fixtures\\windows");
        assertThat(code()).as("export ZIPs never leave the private directory")
            .doesNotContain("KeepZip").doesNotContainPattern("Copy-Item[^\\n]*\\$exportFile");
    }

    @Test
    void gitKeepsTheWindowsRecordingsByteForByte() throws IOException {
        assertThat(Files.readAllLines(Path.of(".gitattributes")))
            .contains("src/test/resources/fixtures/windows/** -text");
    }

    /** Feature 025, spec FR-019: the 7.4 cases (the attachment now, the recordings later). */
    @Test
    void gitKeepsThe74RecordingsByteForByte() throws IOException {
        assertThat(Files.readAllLines(Path.of(".gitattributes")))
            .contains("src/test/resources/fixtures/v7_4/** -text");
    }

    private Process record(Path cliHome, String group, Path out, Path log, String... more)
        throws IOException {
        return record(SCRIPT, "win_help,win_version", cliHome, group, out, log, more);
    }

    /** Without {@code out}, the script writes into the folders of its case table. */
    private Process record(Path script, String cases, Path cliHome, String group, Path out,
        Path log, String... more) throws IOException {
        List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile",
            "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
            "-Group", group, "-Node", "node1", "-Case", cases, "-CliHome",
            cliHome.toString(), "-JavaHome", System.getProperty("java.home"),
            "-WorkRoot", temp.toRealPath().toString()));
        if (out != null) {
            command.addAll(List.of("-OutputDirectory", out.toString()));
        }
        command.addAll(List.of(more));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(log.toFile());
        builder.environment().put("RECORD_ALLOWED_GROUPS", "dev test");
        builder.environment().put("RECORD_ALLOWED_HOSTS", "node1.example.test");
        return builder.start();
    }

    /**
     * The recorder with two test-only edits, so that session and login cases run without a
     * console: one extra session case with the test's own commands in {@code $Cases}, and a
     * {@code Read-PasswordBytes} that returns a fixed password line ({@code Read-Host
     * -AsSecureString} reads the console, which a test process has not). It lives in a copy of
     * the repository's {@code tools} directory, next to {@code neutralize.py}.
     */
    private Path sessionScript() throws IOException {
        Path tools = Files.createDirectories(temp.resolve("repository").resolve("tools"));
        Files.copy(Path.of("tools/neutralize.py"), tools.resolve("neutralize.py"));
        String script = script();
        int tableEnd = script.indexOf("\n)", script.indexOf("$Cases = @(")) + 1;
        script = script.substring(0, tableEnd) + String.join("\n",
            "    [pscustomobject]@{",
            "        Name        = 'test_session'",
            "        Description = 'test-only session case'",
            "        Kind        = 'session'",
            "        Arguments   = @()",
            "        Commands    = @('uname', 'ps -c -e', 'exit')",
            "        Credentials = $true",
            "        Contacts    = $true",
            "        Folder      = 'out'",
            "        FixtureName = 'test_session'",
            "    }", "") + script.substring(tableEnd);
        int readStart = script.indexOf("function Read-PasswordBytes {");
        int readEnd = script.indexOf("\n}", readStart) + 2;
        script = script.substring(0, readStart) + "function Read-PasswordBytes {\n"
            + "    return , $Utf8.GetBytes('" + SESSION_PASSWORD + "' + \"`n\")\n}"
            + script.substring(readEnd);
        Path copy = tools.resolve("record-fixtures.ps1");
        Files.writeString(copy, script, StandardCharsets.US_ASCII);
        return copy;
    }

    /**
     * The script's console log: Windows PowerShell 5.1 writes it in the console code page, which
     * on a non-English Windows has non-ASCII messages that are no UTF-8; read leniently, so that a
     * failing run shows its message instead of a MalformedInputException.
     */
    private static String log(Path log) throws IOException {
        return new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(180)
    void recordsTheClientVersionThroughTheSubstitute() throws Exception {
        Path cliHome = temp.resolve("client");
        FakeStartCli.replaying(cliHome, "version");
        Path out = Files.createDirectories(temp.resolve("out"));
        Path map = temp.resolve("test.map");
        Files.writeString(map, "NeverPresentInTheOutput\tneutral\n");
        Path log = temp.resolve("log.txt");

        Process process = record(cliHome, "dev", out, log, "-NeutralizeMap", map.toString());

        assertThat(process.waitFor(150, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(log(log)).isZero();
        assertThat(out.resolve("win_help.stdout")).exists();
        assertThat(Files.readString(out.resolve("win_version.stdout")))
            .contains("Supported inubit Process Engine versions");
        assertThat(Files.readString(out.resolve("win_version.exit")).strip()).isEqualTo("0");
        assertThat(FakeStartCli.read(cliHome.resolve("argv-record.json")).args())
            .containsExactly("-v");
        try (var left = Files.list(temp)) {
            assertThat(left.map(path -> path.getFileName().toString()))
                .noneMatch(name -> name.startsWith("inubit-record-"));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(180)
    void recordsASessionCaseThroughTheSubstitute() throws Exception {
        Path cliHome = temp.resolve("client");
        FakeStartCli.replaying(cliHome, "version");
        Path out = Files.createDirectories(temp.resolve("out"));
        Path map = temp.resolve("test.map");
        Files.writeString(map, "NeverPresentInTheOutput\tneutral\n");
        Path log = temp.resolve("log.txt");
        String url = "https://node1.example.test:8443/ibis/servlet/IBISSoapServlet";

        Process process = record(sessionScript(), "test_session", cliHome, "dev", out, log,
            "-NeutralizeMap", map.toString(), "-CliUrl", url, "-Username", "tester");

        assertThat(process.waitFor(150, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(log(log)).isZero();
        FakeStartCli.Recorded session = FakeStartCli.read(cliHome.resolve("argv-record.json"));
        assertThat(session.args()).containsExactly("-u", "tester", url);
        assertThat(new String(session.stdin(), StandardCharsets.UTF_8))
            .isEqualTo(SESSION_PASSWORD + "\nuname\nps -c -e\nexit\n");
        assertThat(Files.readString(out.resolve("test_session.stdout")))
            .contains("Supported inubit Process Engine versions");
        assertThat(out.resolve("test_session.stderr")).exists();
        assertThat(Files.readString(out.resolve("test_session.exit")).strip()).isEqualTo("0");
        try (var left = Files.list(temp)) {
            assertThat(left.map(path -> path.getFileName().toString()))
                .noneMatch(name -> name.startsWith("inubit-record-"));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(120)
    void refusesAGroupOutsideTheAllowlistBeforeAnyLaunch() throws Exception {
        Path cliHome = temp.resolve("client");
        FakeStartCli.replaying(cliHome, "version");
        Path log = temp.resolve("log.txt");

        Process process = record(cliHome, "prod", temp.resolve("out"), log);

        assertThat(process.waitFor(100, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(log(log)).contains("RECORD_ALLOWED_GROUPS");
        assertThat(cliHome.resolve("argv-record.json")).doesNotExist();
    }

    /**
     * Feature 025, spec FR-016 – FR-017, research D-12: an exec and a session case of 7.4 log in
     * with the password on standard input, get no client TLS option, get the trust store through
     * JAVA_TOOL_OPTIONS, and write into {@code fixtures/v7_4/cli/} under their fixture names.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(240)
    void records74CasesIntoThe74FolderWithTheTrustStoreInTheJavaRuntimeSettings()
        throws Exception {
        Path cliHome = temp.resolve("client");
        FakeStartCli.replaying(cliHome, "version");
        Path map = temp.resolve("test.map");
        Files.writeString(map, "NeverPresentInTheOutput\tneutral\n");
        Path trustStore = temp.toRealPath().resolve("trust.jks");
        Files.writeString(trustStore, "not a real trust store");
        Path log = temp.resolve("log.txt");
        String url = "https://node1.example.test:8443/ibis/servlet/IBISSoapServlet";
        Path script = sessionScript();

        Process process = record(script, "v74_ps_all,v74_exec_help", cliHome, "dev", null, log,
            "-NeutralizeMap", map.toString(), "-CliUrl", url, "-Username", "tester",
            "-TrustStore", trustStore.toString());

        assertThat(process.waitFor(220, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(log(log)).isZero();
        FakeStartCli.Recorded exec = FakeStartCli.read(cliHome.resolve("argv-record.json"));
        assertThat(exec.args()).containsExactly("-u", "tester", "--execCommand", "help", url);
        assertThat(new String(exec.stdin(), StandardCharsets.UTF_8))
            .isEqualTo(SESSION_PASSWORD + "\n");
        assertThat(exec.environment().entrySet().stream()
            .filter(variable -> variable.getKey().equalsIgnoreCase("JAVA_TOOL_OPTIONS"))
            .map(variable -> variable.getValue()).toList())
            .containsExactly(StartCliEnvironment.WINDOWS_JAVA_TOOL_OPTIONS
                + " -Djavax.net.ssl.trustStore=" + trustStore
                + " -Djavax.net.ssl.trustStoreType=JKS");
        Path folder = script.getParent().getParent()
            .resolve("src/test/resources/fixtures/v7_4/cli");
        for (String fixture : List.of("ps_session_all", "v74_exec_help")) {
            for (String extension : List.of(".stdout", ".stderr", ".exit")) {
                assertThat(folder.resolve(fixture + extension)).exists();
            }
        }
    }

    /** Feature 025, spec FR-017, US4 scenario 8: the whole run stops before any launch. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(120)
    void stopsA74RunOnDisableHostNameVerificationBeforeAnyLaunch() throws Exception {
        Path cliHome = temp.resolve("client");
        FakeStartCli.replaying(cliHome, "version");
        Path log = temp.resolve("log.txt");

        Process process = record(SCRIPT, "win_help,v74_help", cliHome, "dev",
            temp.resolve("out"), log, "-DisableHostNameVerification");

        assertThat(process.waitFor(100, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(log(log)).contains("-DisableHostNameVerification");
        assertThat(cliHome.resolve("argv-record.json")).doesNotExist();
    }

    /** Feature 025, spec FR-017, US4 scenario 7: a host outside the allowlist, no launch. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(120)
    void refusesA74CaseForAHostOutsideTheAllowlistBeforeAnyLaunch() throws Exception {
        Path cliHome = temp.resolve("client");
        FakeStartCli.replaying(cliHome, "version");
        Path log = temp.resolve("log.txt");

        Process process = record(SCRIPT, "v74_ps_all", cliHome, "dev", temp.resolve("out"), log,
            "-CliUrl", "https://prod-node1.example.test:8443/ibis/servlet/IBISSoapServlet",
            "-Username", "tester");

        assertThat(process.waitFor(100, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(log(log)).contains("RECORD_ALLOWED_HOSTS");
        assertThat(cliHome.resolve("argv-record.json")).doesNotExist();
    }
}
