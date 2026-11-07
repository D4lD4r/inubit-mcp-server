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

    @Test
    void listsAllSixReadOnlyCases() throws IOException {
        String code = code();
        for (String name : CASES) {
            assertThat(code).containsPattern("(?m)^\\s*" + name + "\\s*=");
        }
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
                "Assert-NoSecret $prefix $passwordBytes"));
        assertThat(code).contains("Read-Host -AsSecureString", "RedirectStandardInput = $true",
            "StandardInput.BaseStream.Write($Stdin");
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

    private Process record(Path cliHome, String group, Path out, Path log, String... more)
        throws IOException {
        List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile",
            "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", SCRIPT.toString(),
            "-Group", group, "-Node", "node1", "-Case", "win_help,win_version", "-CliHome",
            cliHome.toString(), "-JavaHome", System.getProperty("java.home"),
            "-OutputDirectory", out.toString(), "-WorkRoot", temp.toRealPath().toString()));
        command.addAll(List.of(more));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(log.toFile());
        builder.environment().put("RECORD_ALLOWED_GROUPS", "dev test");
        return builder.start();
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
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
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
    @Timeout(120)
    void refusesAGroupOutsideTheAllowlistBeforeAnyLaunch() throws Exception {
        Path cliHome = temp.resolve("client");
        FakeStartCli.replaying(cliHome, "version");
        Path log = temp.resolve("log.txt");

        Process process = record(cliHome, "prod", temp.resolve("out"), log);

        assertThat(process.waitFor(100, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(Files.readString(log)).contains("RECORD_ALLOWED_GROUPS");
        assertThat(cliHome.resolve("argv-record.json")).doesNotExist();
    }
}
