package de.dadecker.inubit.mcp.testing;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import tools.jackson.databind.json.JsonMapper;

/**
 * A substitute StartCLI for the host platform (research R-12): {@code <cliHome>/bin/startcli.sh}
 * on POSIX, {@code <cliHome>\bin\startcli.bat} on Windows. The script runs {@link ArgvRecorder}
 * with the test JVM and the arguments it received ({@code "$@"} / {@code %*}); the Windows script
 * first writes {@code %CMDCMDLINE%}, the line the interpreter received, to
 * {@link #commandLineFile()}. Each run overwrites the record of the previous one.
 * {@link #installCallingJavaUnqualified} writes a script that calls {@code java} by name, as
 * {@code startcli.bat} does, so the script's {@code PATH} decides which runtime runs.
 */
public final class FakeStartCli {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path script;
    private final Path recordFile;
    private final Path commandLineFile;
    private final List<String> recorderCommand;

    private FakeStartCli(Path script, Path recordFile, Path commandLineFile,
        List<String> recorderCommand) {
        this.script = script;
        this.recordFile = recordFile;
        this.commandLineFile = commandLineFile;
        this.recorderCommand = List.copyOf(recorderCommand);
    }

    /**
     * What one run of the substitute received; {@code workingDirectory} is its {@code user.dir},
     * {@code javaHome} the {@code java.home} of the runtime that ran the recorder.
     */
    public record Recorded(long pid, Long childPid, List<String> args,
        Map<String, String> environment, byte[] stdin, String workingDirectory, String javaHome) {
    }

    /** Installs a substitute that only records. */
    public static FakeStartCli install(Path cliHome) throws IOException {
        return install(cliHome, Map.of());
    }

    /**
     * Installs a substitute whose {@link ArgvRecorder} also gets {@code options}, keyed by the
     * {@code ArgvRecorder} property names (e.g. {@link ArgvRecorder#SLEEP_MILLIS}).
     */
    public static FakeStartCli install(Path cliHome, Map<String, String> options)
        throws IOException {
        return install(cliHome, options, false);
    }

    /**
     * Installs a substitute that only records and calls {@code java} unqualified, so the
     * interpreter or shell finds it through the search path (research R-5).
     * {@link #recorderCommand()} still names the test JVM's {@code java} in full.
     */
    public static FakeStartCli installCallingJavaUnqualified(Path cliHome) throws IOException {
        return install(cliHome, Map.of(), true);
    }

    private static FakeStartCli install(Path cliHome, Map<String, String> options,
        boolean unqualifiedJava) throws IOException {
        boolean windows = Platform.windows();
        Path bin = Files.createDirectories(cliHome.resolve("bin"));
        Path recordFile = cliHome.resolve("argv-record.json");
        Map<String, String> properties = new TreeMap<>(options);
        properties.put(ArgvRecorder.RECORD, recordFile.toString());
        List<String> java = new ArrayList<>(List.of(Platform.javaExecutable().toString(), "-cp",
            testClasses().toString()));
        properties.forEach((name, value) -> java.add("-D" + name + "=" + value));
        java.add(ArgvRecorder.class.getName());
        FakeStartCli cli = new FakeStartCli(bin.resolve(windows ? "startcli.bat" : "startcli.sh"),
            recordFile, cliHome.resolve("cmdcmdline.txt"), java);
        if (windows) {
            Files.writeString(cli.script, String.join("\r\n",
                "@echo off",
                "setlocal EnableDelayedExpansion",
                ">" + batchQuote(cli.commandLineFile.toString()) + " echo(!CMDCMDLINE!",
                "endlocal",
                line(unqualifiedJava, java, FakeStartCli::batchQuote) + " %*",
                "exit /b %ERRORLEVEL%", ""), StandardCharsets.US_ASCII);
        } else {
            Files.writeString(cli.script, "#!/bin/sh\n"
                + line(unqualifiedJava, java, FakeStartCli::shellQuote) + " \"$@\"\n",
                StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(cli.script,
                PosixFilePermissions.fromString("rwx------"));
        }
        return cli;
    }

    /**
     * Installs a substitute that records and then prints the recorded StartCLI run
     * {@code fixtures/v8_1/cli/<fixtureCase>.{stdout,stderr}} and exits with its {@code .exit}.
     */
    public static FakeStartCli replaying(Path cliHome, String fixtureCase) throws IOException {
        return install(cliHome, Map.of(ArgvRecorder.REPLAY, fixtureCase));
    }

    public Path script() {
        return script;
    }

    /**
     * The program the script runs, without the script and without arguments: launched directly,
     * it records into {@link #recordFile()} like a run of the script, with no interpreter or
     * shell in between.
     */
    public List<String> recorderCommand() {
        return recorderCommand;
    }

    /** Where the last run's record is written; absent until a run got that far. */
    public Path recordFile() {
        return recordFile;
    }

    /** Windows only: where the script writes {@code %CMDCMDLINE%}. */
    public Path commandLineFile() {
        return commandLineFile;
    }

    /** The record of the last run. */
    public Recorded record() throws IOException {
        return JSON.readValue(Files.readAllBytes(recordFile), Recorded.class);
    }

    /**
     * Windows only: the interpreter's command line of the last run. ISO-8859-1 maps the bytes
     * one to one; the values the server passes on Windows are ASCII (research R-2).
     */
    public String commandLine() throws IOException {
        return Files.readString(commandLineFile, StandardCharsets.ISO_8859_1).strip();
    }

    /**
     * Only the directory holding the test classes (and fixtures): the full test class path could
     * exceed the interpreter's line limit on Windows, and {@link ArgvRecorder} needs no library.
     */
    private static Path testClasses() {
        try {
            return Path.of(ArgvRecorder.class.getProtectionDomain().getCodeSource().getLocation()
                .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code java} quoted; its first element, the program, is just {@code java} if requested. */
    private static String line(boolean unqualifiedJava, List<String> java,
        UnaryOperator<String> quote) {
        List<String> words = new ArrayList<>(java.stream().map(quote).toList());
        if (unqualifiedJava) {
            words.set(0, "java");
        }
        return String.join(" ", words);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Test paths contain no {@code "}; {@code %} is doubled so the batch file keeps it. */
    private static String batchQuote(String value) {
        return "\"" + value.replace("%", "%%") + "\"";
    }
}
