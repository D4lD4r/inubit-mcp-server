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
            + "'\\{0,-14\\} \\{1\\}' -f \\$entry\\.Name, \\$entry\\.Description");
    }

    /** Spec FR-018: the arguments and commands of every case are edited in one place. */
    @Test
    void definesTheArgumentsAndCommandsOfEveryCaseOnlyInTheTable() throws IOException {
        String table = table();
        assertThat(table).contains("'-v'", "'-h'", "'--execCommand'", "'uptime'",
            "--exportModuleUser '<owner>' --exportFile <file>");
        String outside = code().replace(table, "");
        assertThat(outside).doesNotContain("'-v'", "'-h'", "--execCommand", "'uptime'",
            "--exportModule", "Commands = ");
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
        assertThat(caseSession.indexOf("Assert-AllowedHost")).as("host allowlist before the launch")
            .isNotNegative().isLessThan(launch);
        assertThat(caseSession.indexOf("$passwordBytes = Read-PasswordBytes")).isNotNegative()
            .isLessThan(launch);
        assertThat(caseSession).contains("-PasswordLine $passwordBytes",
            "[Array]::Clear($passwordBytes, 0, $passwordBytes.Length)");
        assertThat(caseSession.indexOf("Assert-NoSecret $prefix $passwordBytes"))
            .as("the secret check on the session's output").isGreaterThan(launch);
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
        assertThat(function("Assert-ExportInputs")).contains("Assert-AllowedHost");
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

    private Process record(Path script, String cases, Path cliHome, String group, Path out,
        Path log, String... more) throws IOException {
        List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile",
            "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
            "-Group", group, "-Node", "node1", "-Case", cases, "-CliHome",
            cliHome.toString(), "-JavaHome", System.getProperty("java.home"),
            "-OutputDirectory", out.toString(), "-WorkRoot", temp.toRealPath().toString()));
        command.addAll(List.of(more));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(log.toFile());
        builder.environment().put("RECORD_ALLOWED_GROUPS", "dev test");
        builder.environment().put("RECORD_ALLOWED_HOSTS", "node1.example.test");
        return builder.start();
    }

    /**
     * The recorder with two test-only edits, so that a session case runs without a console and
     * without a 7.4 case in the production table: one extra session case in {@code $Cases}, and a
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
}
