package de.dadecker.inubit.mcp.testing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** The host platform for tests (research R-12): tests never hard-wire {@code windows = false}. */
public final class Platform {

    private Platform() {
    }

    /** True on Windows; decided like {@code Launcher} does from {@code os.name}. */
    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * The PID of a process that has already exited: a short {@code java -version} of the test
     * runtime, which exists on every platform (unlike {@code /bin/sh}).
     */
    public static long deadPid() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(javaExecutable().toString(), "-version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        process.waitFor();
        return process.pid();
    }

    /** A {@code PATH} of the host's syntax for a test's parent environment. */
    public static String searchPath() {
        return windows() ? "C:\\Windows\\System32;C:\\Windows" : "/usr/bin:/bin";
    }

    /**
     * {@code path} as it appears inside a StartCLI command on the host: unchanged on POSIX, with
     * {@code /} separators on Windows (research R-3).
     */
    public static String commandPath(Path path) {
        return windows() ? path.toString().replace('\\', '/') : path.toString();
    }

    /**
     * The fictitious absolute path {@code posixPath} (e.g. a fake home {@code /home/test}) in a
     * form that is absolute on the host: unchanged on POSIX, on drive {@code C:} on Windows, where
     * a path without a drive is not absolute.
     */
    public static Path absolutePath(String posixPath) {
        return Path.of(windows() ? "C:" + posixPath : posixPath);
    }

    /**
     * The absolute POSIX path {@code posixPath} with {@code /} as its separator on every host, for
     * code told by a flag to apply the POSIX rules (e.g. the {@code java.io.tmpdir} check with
     * {@code windows = false}): the default file system on POSIX; on Windows, whose default file
     * system turns {@code /} into {@code \}, a path of the {@code jrt} file system, which is a
     * name only and never opened.
     */
    public static Path posixPath(String posixPath) {
        return windows() ? java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"))
            .getPath(posixPath) : Path.of(posixPath);
    }

    /** The {@code java} executable of the runtime running the tests. */
    static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
            windows() ? "java.exe" : "java");
    }
}
