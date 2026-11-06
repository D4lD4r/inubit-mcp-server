package de.dadecker.inubit.mcp.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The program behind the substitute StartCLI ({@link FakeStartCli}, research R-12). It runs in
 * its own JVM with only the test classes on its class path, so it uses nothing but the JDK (and
 * {@link Platform}).
 * Controlled by system properties, in this order:
 *
 * <ol>
 *   <li>{@link #RECORD} set: reads stdin to its end, starts a child if {@link #CHILD_SLEEP_MILLIS}
 *       is set, writes {@link #WRITTEN_FILE} into {@link #WRITE_INTO} if set, writes the
 *       fixture {@link #EXPORT_FIXTURE} to the {@code --exportFile} of its {@code --execCommand}
 *       ({@link #EXPORT_MODULE_FIXTURE} for a module export if set) as StartCLI writes the
 *       export, then writes
 *       {@code {"pid", "childPid", "args", "environment", "stdin" (Base64), "workingDirectory",
 *       "javaHome"}} as
 *       JSON to the file named by {@link #RECORD} (atomically, so a poller never reads half a
 *       record);
 *   <li>{@link #REPLAY} set: prints {@code fixtures/v8_1/cli/<case>.stdout} and {@code .stderr}
 *       (with CRLF line ends if {@link #CRLF} is set);
 *   <li>{@link #SLEEP_MILLIS} set: sleeps that long;
 *   <li>exits with the replayed case's {@code .exit} code, else 0.
 * </ol>
 *
 * <p>The child is this class again with only {@link #SLEEP_MILLIS}: it records nothing and just
 * sleeps, standing for the JVM {@code startcli} starts.
 */
public final class ArgvRecorder {

    public static final String RECORD = "argvRecorder.record";
    public static final String REPLAY = "argvRecorder.replay";
    public static final String SLEEP_MILLIS = "argvRecorder.sleepMillis";
    public static final String CHILD_SLEEP_MILLIS = "argvRecorder.childSleepMillis";
    public static final String WRITE_INTO = "argvRecorder.writeInto";
    public static final String EXPORT_FIXTURE = "argvRecorder.exportFixture";
    /** The export fixture instead of {@link #EXPORT_FIXTURE} for a module export. */
    public static final String EXPORT_MODULE_FIXTURE = "argvRecorder.exportModuleFixture";
    /** Set: the replayed output has CRLF line ends, as the client prints them on Windows. */
    public static final String CRLF = "argvRecorder.crlf";
    /** The name of the file written into {@link #WRITE_INTO}. */
    public static final String WRITTEN_FILE = "argv-recorder.txt";

    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");

    private ArgvRecorder() {
    }

    public static void main(String[] args) throws Exception {
        String record = System.getProperty(RECORD);
        if (record != null) {
            byte[] stdin = System.in.readAllBytes();
            Long childPid = null;
            String childSleep = System.getProperty(CHILD_SLEEP_MILLIS);
            if (childSleep != null) {
                childPid = startChild(childSleep);
            }
            String writeInto = System.getProperty(WRITE_INTO);
            if (writeInto != null) {
                Files.writeString(Path.of(writeInto, WRITTEN_FILE), "written");
            }
            String exportFixture = System.getProperty(EXPORT_FIXTURE);
            if (exportFixture != null) {
                String moduleFixture = System.getProperty(EXPORT_MODULE_FIXTURE);
                Files.write(exportFile(args), fixture(moduleFixture != null
                    && String.join(" ", args).contains("--exportModule")
                    ? moduleFixture : exportFixture));
            }
            writeRecord(Path.of(record), json(args, stdin, childPid));
        }
        int exitCode = 0;
        String replay = System.getProperty(REPLAY);
        if (replay != null) {
            System.out.write(output(replay + ".stdout"));
            System.out.flush();
            System.err.write(output(replay + ".stderr"));
            System.err.flush();
            exitCode = Integer.parseInt(
                new String(fixture(replay + ".exit"), StandardCharsets.UTF_8).strip());
        }
        String sleep = System.getProperty(SLEEP_MILLIS);
        if (sleep != null) {
            Thread.sleep(Long.parseLong(sleep));
        }
        System.exit(exitCode);
    }

    /** A replayed output, with CRLF line ends if {@link #CRLF} is set. */
    private static byte[] output(String name) throws IOException {
        byte[] recorded = fixture(name);
        if (System.getProperty(CRLF) == null) {
            return recorded;
        }
        return new String(recorded, StandardCharsets.UTF_8).replace("\r\n", "\n")
            .replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The {@code --exportFile} named in the {@code --execCommand} argument. */
    private static Path exportFile(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--execCommand")) {
                Matcher matcher = EXPORT_FILE.matcher(args[i + 1]);
                if (matcher.find()) {
                    return Path.of(matcher.group(1));
                }
            }
        }
        throw new IllegalArgumentException("no --exportFile in the --execCommand");
    }

    private static long startChild(String sleepMillis) throws IOException {
        Process child = new ProcessBuilder(Platform.javaExecutable().toString(), "-cp",
            System.getProperty("java.class.path"), "-D" + SLEEP_MILLIS + "=" + sleepMillis,
            ArgvRecorder.class.getName())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        return child.pid();
    }

    private static void writeRecord(Path target, String json) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
    }

    private static String json(String[] args, byte[] stdin, Long childPid) {
        List<String> quotedArgs = new ArrayList<>();
        for (String arg : args) {
            quotedArgs.add(quote(arg));
        }
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(System.getenv()).entrySet()) {
            entries.add(quote(entry.getKey()) + ":" + quote(entry.getValue()));
        }
        return "{\"pid\":" + ProcessHandle.current().pid()
            + ",\"childPid\":" + childPid
            + ",\"args\":[" + String.join(",", quotedArgs) + "]"
            + ",\"environment\":{" + String.join(",", entries) + "}"
            + ",\"stdin\":" + quote(Base64.getEncoder().encodeToString(stdin))
            + ",\"workingDirectory\":" + quote(System.getProperty("user.dir"))
            + ",\"javaHome\":" + quote(System.getProperty("java.home")) + "}";
    }

    /** A JSON string; everything outside printable ASCII as {@code \}{@code uXXXX}. */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static byte[] fixture(String name) {
        try (InputStream in = ArgvRecorder.class.getResourceAsStream(
            "/fixtures/v8_1/cli/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
