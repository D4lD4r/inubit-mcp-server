package de.dadecker.inubit.mcp.config;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The rule for file paths inside StartCLI's {@code --execCommand} (research R-11): absolute,
 * only letters, digits, {@code _ . - /} and spaces, no {@code .}/{@code ..} segment. Shared by the
 * command builder and the configuration check of the export directory ({@code java.io.tmpdir}).
 */
public final class CliPaths {

    public static final Pattern PATH_VALUE = Pattern.compile("^/[A-Za-z0-9_.\\-/ ]{1,1023}$");
    /**
     * The longest export file below a temporary directory, as the exports create it:
     * {@code inubit-mcp-export-<profile>-<pid>-<random>/history.zip} with a profile name of the
     * maximum length (32), a 19-digit pid and a 20-digit random number (002 research D-8).
     */
    private static final String SAMPLE_EXPORT_FILE = "inubit-mcp-export-" + "p".repeat(32)
        + "-" + "9".repeat(19) + "-" + "9".repeat(20) + "/history.zip";

    private CliPaths() {
    }

    /** True if {@code path} may be passed to StartCLI. */
    public static boolean passable(Path path) {
        if (path == null || !path.isAbsolute() || !PATH_VALUE.matcher(path.toString()).matches()) {
            return false;
        }
        for (Path segment : path) {
            String name = segment.toString();
            if (name.equals(".") || name.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** True if export files below {@code temporaryDirectory} may be passed to StartCLI. */
    public static boolean exportRootUsable(Path temporaryDirectory) {
        return temporaryDirectory != null
            && passable(temporaryDirectory.resolve(SAMPLE_EXPORT_FILE));
    }
}
