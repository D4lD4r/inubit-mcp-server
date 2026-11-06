package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.WindowsArguments;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The environment StartCLI is started with: an allowlist, so no credential and no other variable
 * of the server's environment reaches it (FR-004; Constitution II).
 *
 * <ul>
 *   <li>macOS and Linux ({@link #posix}, unchanged by feature 013, FR-017): {@code PATH}, {@code HOME},
 *       {@code TMPDIR}, {@code LANG}, {@code LC_ALL}, {@code USER} (copied if set),
 *       {@code JAVA_HOME} and {@link #JAVA_TOOL_OPTIONS}.
 *   <li>Windows ({@link #windows}, feature 013, research R-5): the variables Windows and a Java
 *       runtime need to start, copied if set and matched case-insensitively, and the values the
 *       server sets instead of copying: {@code ComSpec} (the interpreter of the system directory),
 *       {@code PATH} = {@code <JAVA_HOME>\bin;<system dir>}, so an unqualified {@code java} in
 *       {@code startcli.bat} finds only the configured runtime,
 *       {@code NoDefaultCurrentDirectoryInExePath=1}, so the interpreter does not search the
 *       working directory ({@code cli.home}) for programs, {@code JAVA_HOME} and
 *       {@link #WINDOWS_JAVA_TOOL_OPTIONS}.
 * </ul>
 *
 * <p>The Java home is used as given; refusing one that contains a character the interpreter
 * interprets or a {@code ;} (the {@code PATH} separator) is up to the caller.
 */
public final class StartCliEnvironment {

    /** Forces the English messages StartCLI's output is classified by. */
    public static final String JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US";

    /**
     * {@link #JAVA_TOOL_OPTIONS} plus UTF-8 for the client's default charset, {@code System.out}
     * and {@code System.err}, whatever the console code page (research R-6).
     */
    public static final String WINDOWS_JAVA_TOOL_OPTIONS = JAVA_TOOL_OPTIONS
        + " -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8";

    /** Copied from the server's environment on macOS and Linux, if set (names case-sensitive). */
    static final List<String> POSIX_VARIABLES =
        List.of("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "USER");

    /** Copied from the server's environment on Windows, if set, in this spelling (R-5). */
    static final List<String> WINDOWS_VARIABLES = List.of("SystemRoot", "windir", "SystemDrive",
        "PATHEXT", "TEMP", "TMP", "USERPROFILE", "USERNAME", "USERDOMAIN", "APPDATA",
        "LOCALAPPDATA", "ProgramData", "OS", "PROCESSOR_ARCHITECTURE", "NUMBER_OF_PROCESSORS");

    private StartCliEnvironment() {
    }

    /**
     * The environment on macOS and Linux.
     *
     * @param parent the server's environment
     * @param javaHome the effective Java home ({@code cli.javaHome}, else the server's
     *     {@code JAVA_HOME})
     */
    public static Map<String, String> posix(Map<String, String> parent, String javaHome) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : POSIX_VARIABLES) {
            String value = parent.get(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        environment.put("JAVA_HOME", javaHome);
        environment.put("JAVA_TOOL_OPTIONS", JAVA_TOOL_OPTIONS);
        return environment;
    }

    /**
     * The environment on Windows.
     *
     * @param parent the server's environment, whose names are matched case-insensitively; of two
     *     spellings of one name the allowlist's wins, else the first in {@code String} order
     * @param javaHome the effective Java home ({@code cli.javaHome}, else the server's
     *     {@code JAVA_HOME})
     * @param systemDirectory the Windows system directory as {@code GetSystemDirectoryW} reports
     *     it, e.g. {@code C:\Windows\system32}
     */
    public static Map<String, String> windows(Map<String, String> parent, String javaHome,
        String systemDirectory) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : WINDOWS_VARIABLES) {
            String value = valueIgnoringCase(parent, name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        environment.put("ComSpec", WindowsCommandLine.interpreter(systemDirectory));
        environment.put("PATH", child(javaHome, "bin") + ";" + systemDirectory);
        environment.put("NoDefaultCurrentDirectoryInExePath", "1");
        environment.put("JAVA_HOME", javaHome);
        environment.put("JAVA_TOOL_OPTIONS", WINDOWS_JAVA_TOOL_OPTIONS);
        return environment;
    }

    /**
     * The value of {@code name} in {@code parent}, whose names are matched case-insensitively as
     * on Windows: the exact spelling, else the first in {@code String} order; null if unset.
     */
    static String valueIgnoringCase(Map<String, String> parent, String name) {
        return WindowsArguments.environmentValue(parent, name);
    }

    /** {@code <directory>\<name>}, without doubling a trailing backslash of the directory. */
    private static String child(String directory, String name) {
        return directory + (directory.endsWith("\\") ? "" : "\\") + name;
    }
}
