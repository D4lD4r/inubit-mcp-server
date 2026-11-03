package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.WindowsArguments;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The argument list the JDK receives for a StartCLI call on Windows (feature 013, research R-1,
 * R-2, R-4, contracts/windows-invocation.md):
 * {@code [<system dir>\cmd.exe, /d, /v:off, /s, /c, ""<script>" "<arg1>" … "<argN>""]}.
 *
 * <ul>
 *   <li>The last element, the inner line, starts and ends with {@code "}, so the JDK passes it
 *       unchanged and {@code /s /c} makes the interpreter strip exactly these outer quotes.
 *   <li>Every StartCLI argument is wrapped in double quotes; trailing backslashes are doubled so
 *       that they do not escape the closing quote under the C runtime rules of the Java launcher.
 *   <li>The script and every argument are re-checked with {@link WindowsArguments#refusal}; a
 *       refused character here means a check before (configuration, credentials) is missing,
 *       so it is an {@link IllegalStateException}, never the user-facing refusal.
 *   <li>The inner line may have at most {@link #MAX_INNER_LENGTH} characters; a longer one is
 *       {@link TooLong}, caused by the tool input if the line would fit with an empty
 *       {@code --execCommand} value, else by the configuration.
 * </ul>
 *
 * <p>Pure: Windows paths are plain strings, so the class behaves the same on every host.
 */
public final class WindowsCommandLine {

    /**
     * The interpreter's 8 191 characters minus a 2 048-character margin for what
     * {@code startcli.bat} adds (research R-4; revisited in T031).
     */
    public static final int MAX_INNER_LENGTH = 8191 - 2048;

    /** The outcome of {@link #build}. */
    public sealed interface Result permits Built, TooLong {
    }

    /** The argument list for {@code ProcessBuilder}; element 0 is the interpreter. */
    public record Built(List<String> command) implements Result {

        public Built {
            command = List.copyOf(command);
        }
    }

    /** The inner line would have {@code length} (&gt; {@link #MAX_INNER_LENGTH}) characters. */
    public record TooLong(Cause cause, int length) implements Result {

        public TooLong {
            Objects.requireNonNull(cause, "cause");
        }
    }

    /** What makes the inner line too long. */
    public enum Cause {
        /** The {@code --execCommand} value (diagram groups, paths) of the tool call. */
        TOOL_INPUT,
        /** The configured parts alone: CLI home, username, trust store, StartCLI URL. */
        CONFIGURATION
    }

    private static final String EXEC_COMMAND = "--execCommand";

    private WindowsCommandLine() {
    }

    /**
     * Builds the call of {@code script} with {@code arguments}.
     *
     * @param systemDirectory the Windows system directory as {@code GetSystemDirectoryW} reports
     *     it, e.g. {@code C:\Windows\system32}
     * @param script the absolute path of {@code <cli.home>\bin\startcli.bat}
     * @param arguments the StartCLI arguments ({@code -u}, …, {@code --execCommand},
     *     {@code <command>}, {@code <url>})
     * @throws IllegalStateException if the script or an argument contains a character
     *     {@link WindowsArguments#refusal} refuses; the message names its position and class,
     *     never the value
     */
    public static Result build(String systemDirectory, String script, List<String> arguments) {
        guard("the script path", script);
        for (int i = 0; i < arguments.size(); i++) {
            guard("StartCLI argument " + (i + 1), arguments.get(i));
        }
        List<String> parts = new ArrayList<>(arguments.size() + 1);
        parts.add('"' + script + '"');
        int toolInput = 0;
        for (int i = 0; i < arguments.size(); i++) {
            String quoted = quote(arguments.get(i));
            parts.add(quoted);
            if (i > 0 && arguments.get(i - 1).equals(EXEC_COMMAND)) {
                toolInput += quoted.length() - 2;
            }
        }
        String inner = '"' + String.join(" ", parts) + '"';
        if (inner.length() > MAX_INNER_LENGTH) {
            Cause cause = inner.length() - toolInput > MAX_INNER_LENGTH
                ? Cause.CONFIGURATION : Cause.TOOL_INPUT;
            return new TooLong(cause, inner.length());
        }
        return new Built(List.of(interpreter(systemDirectory), "/d", "/v:off", "/s", "/c",
            inner));
    }

    /** {@code <system dir>\cmd.exe}; a directory ending in {@code \} (a root) gets none added. */
    static String interpreter(String systemDirectory) {
        String separator = systemDirectory.endsWith("\\") ? "" : "\\";
        return systemDirectory + separator + "cmd.exe";
    }

    /** {@code "<value>"} with the trailing backslashes of {@code value} doubled. */
    private static String quote(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '\\') {
            end--;
        }
        String trailing = value.substring(end);
        return '"' + value + trailing + '"';
    }

    private static void guard(String what, String value) {
        Optional<String> refusal = WindowsArguments.refusal(value);
        if (refusal.isPresent()) {
            throw new IllegalStateException(what + " contains a character the Windows command"
                + " interpreter interprets (" + refusal.get() + "); it must be refused before"
                + " the command line is built");
        }
    }
}
