package de.dadecker.inubit.mcp.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The rule for file paths inside StartCLI's {@code --execCommand} (research R-11): absolute,
 * only letters, digits, {@code _ . - /} and spaces, no {@code .}/{@code ..} segment. Shared by the
 * command builder and the configuration check of the export directory ({@code java.io.tmpdir}).
 *
 * <p>On Windows (feature 013, research R-3) a path is passed with {@code /} separators and must
 * match {@link #WINDOWS_PATH_VALUE}: a drive letter, ASCII only, at most 259 characters; a
 * network path ({@code \\server\share}) never does. The server's temporary directory is given
 * to StartCLI as its real path, so an 8.3 short name ({@code RUNNER~1}) is expanded first
 * ({@link #forCommand}).
 */
public final class CliPaths {

    public static final Pattern PATH_VALUE = Pattern.compile("^/[A-Za-z0-9_.\\-/ ]{1,1023}$");
    /** The Windows rule, checked on the path with {@code /} separators ({@link #render}). */
    public static final Pattern WINDOWS_PATH_VALUE =
        Pattern.compile("^[A-Za-z]:/[A-Za-z0-9_.\\- /]{1,256}$");
    /**
     * The longest export file below a temporary directory, as the exports create it:
     * {@code inubit-mcp-export-<profile>-<pid>-<random>/repository.zip} (the longest file name
     * an export or import writes) with a profile name of the maximum length (32), a 19-digit pid
     * and a 20-digit random number (002 research D-8): 106 characters.
     */
    private static final String SAMPLE_EXPORT_FILE = "inubit-mcp-export-" + "p".repeat(32)
        + "-" + "9".repeat(19) + "-" + "9".repeat(20) + "/repository.zip";

    /** Resolves a path to its real path, e.g. {@link Path#toRealPath}. */
    @FunctionalInterface
    public interface RealPath {
        Path resolve(Path path) throws IOException;
    }

    private CliPaths() {
    }

    /** The rule of the platform. */
    public static Pattern rule(boolean windows) {
        return windows ? WINDOWS_PATH_VALUE : PATH_VALUE;
    }

    /**
     * What {@code -Djava.io.tmpdir=<…>} must name on the platform, for the next step of a
     * temporary directory that breaks the rule.
     */
    public static String tmpdirHint(boolean windows) {
        return windows ? "local drive path such as C:\\mcp-temp, of letters, digits, _ . - \\"
            + " and spaces" : "absolute path of letters, digits, _ . - / and spaces";
    }

    /** {@code path} as StartCLI is given it: with {@code /} separators on Windows. */
    public static String render(Path path, boolean windows) {
        return windows ? path.toString().replace('\\', '/') : path.toString();
    }

    /** True if {@code path} may be passed to StartCLI on the platform. */
    public static boolean passable(Path path, boolean windows) {
        if (path == null || !windows && !path.isAbsolute()) {
            return false;
        }
        String text = render(path, windows);
        if (!rule(windows).matcher(text).matches()) {
            return false;
        }
        for (String name : text.split("/")) {
            if (name.equals(".") || name.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** True if export files below {@code temporaryDirectory} may be passed to StartCLI. */
    public static boolean exportRootUsable(Path temporaryDirectory, boolean windows) {
        return temporaryDirectory != null
            && passable(temporaryDirectory.resolve(SAMPLE_EXPORT_FILE), windows);
    }

    /** {@link #forCommand(Path, boolean, RealPath)} with {@link Path#toRealPath}. */
    public static Path forCommand(Path directory, boolean windows) {
        return forCommand(directory, windows, path -> path.toRealPath());
    }

    /**
     * The server's temporary {@code directory} as StartCLI is given it: on Windows its real path
     * (8.3 short names expanded), unless it is a network path, which is never resolved and
     * fails the rule; unchanged on other platforms or if it cannot be resolved.
     */
    public static Path forCommand(Path directory, boolean windows, RealPath real) {
        if (!windows || directory == null || render(directory, true).startsWith("//")) {
            return directory;
        }
        try {
            return real.resolve(directory);
        } catch (IOException e) {
            return directory;
        }
    }
}
