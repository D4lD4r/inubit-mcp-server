package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.adapter.cli.WindowsCommandLine.Built;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 013 (T009, FR-004, research R-5): the environment StartCLI is started with. The POSIX
 * allowlist is today's (moved out of {@link CliRunner} unchanged, FR-017); the Windows allowlist
 * is pure as well, so both run on every platform. On Windows, decoy {@code java.exe} files in
 * {@code cli.home} and in the server's {@code PATH} show that an unqualified {@code java} in
 * {@code startcli.bat} reaches only the configured runtime.
 */
@Timeout(60)
class StartCliEnvironmentTest {

    private static final String SYSTEM = "C:\\Windows\\system32";
    private static final String JAVA_HOME = "C:\\Program Files\\Java\\jdk-17";

    @TempDir
    Path directory;

    // --- POSIX: today's allowlist ----------------------------------------------------------------

    private static Map<String, String> posixParent() {
        Map<String, String> parent = new HashMap<>();
        parent.put("PATH", "/usr/bin:/bin");
        parent.put("HOME", "/home/svc");
        parent.put("TMPDIR", "/tmp/svc");
        parent.put("LANG", "de_DE.UTF-8");
        parent.put("LC_ALL", "de_DE.UTF-8");
        parent.put("USER", "svc");
        parent.put("JAVA_HOME", "/opt/server-jdk");
        parent.put("JAVA_TOOL_OPTIONS", "-Duser.language=de -javaagent:/evil.jar");
        parent.put("INUBIT_DEV_PASSWORD", "dev-secret");
        parent.put("GITHUB_TOKEN", "ghp_token");
        parent.put("CLASSPATH", "/evil");
        parent.put("SHELL", "/bin/zsh");
        parent.put("DYLD_INSERT_LIBRARIES", "/evil.dylib");
        parent.put("_JAVA_OPTIONS", "-javaagent:/evil.jar");
        parent.put("JDK_JAVA_OPTIONS", "-javaagent:/evil.jar");
        return parent;
    }

    @Test
    void onPosixTheEnvironmentIsTodaysAllowlistInTodaysOrder() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("PATH", "/usr/bin:/bin");
        expected.put("HOME", "/home/svc");
        expected.put("TMPDIR", "/tmp/svc");
        expected.put("LANG", "de_DE.UTF-8");
        expected.put("LC_ALL", "de_DE.UTF-8");
        expected.put("USER", "svc");
        expected.put("JAVA_HOME", "/opt/cli-jdk");
        expected.put("JAVA_TOOL_OPTIONS", "-Duser.language=en -Duser.country=US");

        assertThat(StartCliEnvironment.posix(posixParent(), "/opt/cli-jdk"))
            .containsExactlyEntriesOf(expected);
    }

    @Test
    void onPosixUnsetVariablesAreNotInventedAndNamesAreCaseSensitive() {
        Map<String, String> parent = new HashMap<>(Map.of("Path", "/evil", "home", "/evil",
            "USER", "svc"));

        assertThat(StartCliEnvironment.posix(parent, "/opt/cli-jdk"))
            .containsExactlyInAnyOrderEntriesOf(Map.of("USER", "svc", "JAVA_HOME", "/opt/cli-jdk",
                "JAVA_TOOL_OPTIONS", "-Duser.language=en -Duser.country=US"));
    }

    // --- Windows: the allowlist of research R-5 ------------------------------------------------

    /** A server environment as Windows spells it, with values the child must not get. */
    private static Map<String, String> windowsParent() {
        Map<String, String> parent = new HashMap<>();
        parent.put("SystemRoot", "C:\\Windows");
        parent.put("windir", "C:\\Windows");
        parent.put("SystemDrive", "C:");
        parent.put("ComSpec", "C:\\evil\\cmd.exe");
        parent.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        parent.put("TEMP", "C:\\Users\\svc\\AppData\\Local\\Temp");
        parent.put("TMP", "C:\\Users\\svc\\AppData\\Local\\Temp");
        parent.put("USERPROFILE", "C:\\Users\\svc");
        parent.put("USERNAME", "svc");
        parent.put("USERDOMAIN", "CORP");
        parent.put("APPDATA", "C:\\Users\\svc\\AppData\\Roaming");
        parent.put("LOCALAPPDATA", "C:\\Users\\svc\\AppData\\Local");
        parent.put("ProgramData", "C:\\ProgramData");
        parent.put("OS", "Windows_NT");
        parent.put("PROCESSOR_ARCHITECTURE", "AMD64");
        parent.put("NUMBER_OF_PROCESSORS", "8");
        parent.put("Path", "C:\\decoy;C:\\Windows\\system32");
        parent.put("JAVA_HOME", "C:\\server-jdk");
        parent.put("JAVA_TOOL_OPTIONS", "-javaagent:C:\\evil.jar");
        parent.put("NoDefaultCurrentDirectoryInExePath", "");
        parent.put("INUBIT_DEV_PASSWORD", "dev-secret");
        parent.put("GITHUB_TOKEN", "ghp_token");
        parent.put("CLASSPATH", "C:\\evil");
        parent.put("_JAVA_OPTIONS", "-javaagent:C:\\evil.jar");
        parent.put("JDK_JAVA_OPTIONS", "-javaagent:C:\\evil.jar");
        parent.put("HOME", "C:\\Users\\svc");
        parent.put("HOMEDRIVE", "C:");
        parent.put("PSModulePath", "C:\\evil");
        parent.put("PROMPT", "$P$G");
        return parent;
    }

    @Test
    void onWindowsTheEnvironmentIsTheAllowlistWithTheValuesTheServerSets() {
        Map<String, String> expected = new HashMap<>();
        expected.put("SystemRoot", "C:\\Windows");
        expected.put("windir", "C:\\Windows");
        expected.put("SystemDrive", "C:");
        expected.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        expected.put("TEMP", "C:\\Users\\svc\\AppData\\Local\\Temp");
        expected.put("TMP", "C:\\Users\\svc\\AppData\\Local\\Temp");
        expected.put("USERPROFILE", "C:\\Users\\svc");
        expected.put("USERNAME", "svc");
        expected.put("USERDOMAIN", "CORP");
        expected.put("APPDATA", "C:\\Users\\svc\\AppData\\Roaming");
        expected.put("LOCALAPPDATA", "C:\\Users\\svc\\AppData\\Local");
        expected.put("ProgramData", "C:\\ProgramData");
        expected.put("OS", "Windows_NT");
        expected.put("PROCESSOR_ARCHITECTURE", "AMD64");
        expected.put("NUMBER_OF_PROCESSORS", "8");
        expected.put("ComSpec", "C:\\Windows\\system32\\cmd.exe");
        expected.put("PATH", JAVA_HOME + "\\bin;" + SYSTEM);
        expected.put("NoDefaultCurrentDirectoryInExePath", "1");
        expected.put("JAVA_HOME", JAVA_HOME);
        expected.put("JAVA_TOOL_OPTIONS", "-Duser.language=en -Duser.country=US"
            + " -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8");

        assertThat(StartCliEnvironment.windows(windowsParent(), JAVA_HOME, SYSTEM))
            .containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void onWindowsNamesAreMatchedCaseInsensitivelyAndWrittenInTheAllowlistsSpelling() {
        Map<String, String> parent = new HashMap<>(Map.of("SYSTEMROOT", "C:\\Windows",
            "WinDir", "C:\\Windows", "temp", "C:\\t", "Username", "svc",
            "path", "C:\\decoy", "comspec", "C:\\evil\\cmd.exe", "java_tool_options", "-Xevil",
            "nodefaultcurrentdirectoryinexepath", "0"));

        Map<String, String> environment = StartCliEnvironment.windows(parent, JAVA_HOME, SYSTEM);

        assertThat(environment).containsEntry("SystemRoot", "C:\\Windows")
            .containsEntry("windir", "C:\\Windows").containsEntry("TEMP", "C:\\t")
            .containsEntry("USERNAME", "svc")
            .containsEntry("PATH", JAVA_HOME + "\\bin;" + SYSTEM)
            .containsEntry("ComSpec", SYSTEM + "\\cmd.exe")
            .containsEntry("NoDefaultCurrentDirectoryInExePath", "1");
        assertThat(environment.keySet()).doesNotContain("SYSTEMROOT", "WinDir", "temp",
            "Username", "path", "comspec", "java_tool_options",
            "nodefaultcurrentdirectoryinexepath");
        assertThat(environment.values()).doesNotContain("C:\\decoy", "C:\\evil\\cmd.exe",
            "-Xevil", "0");
    }

    @Test
    void onWindowsTheExactSpellingWinsOverAnotherCaseOfTheSameName() {
        Map<String, String> parent = new HashMap<>(Map.of("TEMP", "C:\\exact", "Temp",
            "C:\\other", "tmp", "C:\\lower", "Tmp", "C:\\mixed"));

        Map<String, String> environment = StartCliEnvironment.windows(parent, JAVA_HOME, SYSTEM);

        assertThat(environment).containsEntry("TEMP", "C:\\exact")
            .as("without the exact spelling, the first spelling in String order: Tmp < tmp")
            .containsEntry("TMP", "C:\\mixed");
    }

    @Test
    void onWindowsUnsetVariablesAreNotInvented() {
        assertThat(StartCliEnvironment.windows(Map.of(), JAVA_HOME, SYSTEM).keySet())
            .containsExactlyInAnyOrder("ComSpec", "PATH", "NoDefaultCurrentDirectoryInExePath",
                "JAVA_HOME", "JAVA_TOOL_OPTIONS");
    }

    @Test
    void onWindowsTrailingBackslashesOfTheDirectoriesAreNotDoubled() {
        Map<String, String> environment = StartCliEnvironment.windows(Map.of(), "C:\\jdk\\",
            "C:\\");

        assertThat(environment).containsEntry("PATH", "C:\\jdk\\bin;C:\\")
            .containsEntry("ComSpec", "C:\\cmd.exe").containsEntry("JAVA_HOME", "C:\\jdk\\");
    }

    // --- Windows: decoys (research R-5) ---------------------------------------------------------

    private static final List<String> ARGUMENTS = List.of("-u", "jdoe", "--execCommand", "plain",
        "https://inubit-dev-1.example.test/ibis/servlet/IBISSoapServlet");

    /** The runtime configured for StartCLI: the one running the tests. */
    private static String configuredJavaHome() {
        return System.getProperty("java.home");
    }

    private static String systemDirectory() {
        return Path.of(System.getenv("SystemRoot"), "System32").toString();
    }

    /**
     * A {@code java.exe} in {@code target} that is a real program but not a Java runtime: a copy
     * of {@code whoami.exe}. Reached instead of the configured runtime, it fails on the Java
     * options and the substitute writes no record.
     */
    private static void plantDecoy(Path target) throws Exception {
        Files.copy(Path.of(systemDirectory(), "whoami.exe"), target.resolve("java.exe"));
    }

    /** The test JVM's environment with {@code first} at the front of its search path. */
    private static Map<String, String> serverEnvironment(Path first) {
        Map<String, String> server = new HashMap<>(System.getenv());
        server.keySet().removeIf(name -> name.equalsIgnoreCase("PATH"));
        server.put("Path", first + ";" + System.getenv("PATH"));
        return server;
    }

    /** Installed substitute and decoys: one in {@code cli.home}, one on the server's path. */
    private record Setup(Path cliHome, FakeStartCli cli, Path serverPathDirectory) {
    }

    private Setup decoys() throws Exception {
        Path cliHome = Files.createDirectory(directory.resolve("cli home"));
        Path serverPathDirectory = Files.createDirectory(directory.resolve("server path"));
        FakeStartCli cli = FakeStartCli.installCallingJavaUnqualified(cliHome);
        plantDecoy(cliHome);
        plantDecoy(serverPathDirectory);
        return new Setup(cliHome, cli, serverPathDirectory);
    }

    /** Starts the substitute as the server does (interpreter call, cli.home); exit and output. */
    private static String run(Setup setup, Map<String, String> environment) throws Exception {
        List<String> command = ((Built) WindowsCommandLine.build(systemDirectory(),
            setup.cli().script().toString(), ARGUMENTS)).command();
        LaunchedProcess process = new SystemProcessLauncher().launch(new LaunchSpec(command,
            environment, setup.cliHome()));
        process.stdin().close();
        String stdout = new String(process.stdout().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.stderr().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(Duration.ofSeconds(30))).isTrue();
        return "exit " + process.exitValue() + ", stdout: " + stdout + ", stderr: " + stderr;
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsAnUnqualifiedJavaReachesTheConfiguredRuntimeAndNoDecoy() throws Exception {
        Setup setup = decoys();
        Map<String, String> environment = StartCliEnvironment.windows(
            serverEnvironment(setup.serverPathDirectory()), configuredJavaHome(),
            systemDirectory());

        String outcome = run(setup, environment);

        assertThat(setup.cli().recordFile()).as("the Java runtime ran the recorder; %s", outcome)
            .exists();
        FakeStartCli.Recorded record = setup.cli().record();
        assertThat(Path.of(record.javaHome()).toRealPath())
            .isEqualTo(Path.of(configuredJavaHome()).toRealPath());
        assertThat(record.args()).containsExactlyElementsOf(ARGUMENTS);
    }

    /** Control: without {@code NoDefaultCurrentDirectoryInExePath} the decoy in cli.home runs. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsWithoutTheSettingTheInterpreterRunsTheDecoyInTheWorkingDirectory()
        throws Exception {
        Setup setup = decoys();
        Map<String, String> environment = new HashMap<>(StartCliEnvironment.windows(
            serverEnvironment(setup.serverPathDirectory()), configuredJavaHome(),
            systemDirectory()));
        environment.remove("NoDefaultCurrentDirectoryInExePath");

        String outcome = run(setup, environment);

        assertThat(setup.cli().recordFile()).as("the decoy ran instead; %s", outcome)
            .doesNotExist();
    }

    /** Control: with the server's directory in front of {@code PATH} its decoy runs. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsWithTheServersPathTheInterpreterRunsTheDecoyOnThePath() throws Exception {
        Setup setup = decoys();
        Map<String, String> environment = new HashMap<>(StartCliEnvironment.windows(
            serverEnvironment(setup.serverPathDirectory()), configuredJavaHome(),
            systemDirectory()));
        environment.put("PATH", setup.serverPathDirectory() + ";" + environment.get("PATH"));

        String outcome = run(setup, environment);

        assertThat(setup.cli().recordFile()).as("the decoy ran instead; %s", outcome)
            .doesNotExist();
    }
}
