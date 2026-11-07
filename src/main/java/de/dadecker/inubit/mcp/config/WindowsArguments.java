package de.dadecker.inubit.mcp.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The values passed to StartCLI outside its command on Windows (feature 013, FR-002, research
 * R-2): inside double quotes the command interpreter still interprets {@code " % ! ^} and, with an
 * unbalanced quote, {@code & | < >}; these, control characters and characters outside ASCII are
 * refused. Space, {@code ' ( ) ; , = \ * ?} stay literal. Messages name the class returned here,
 * never the value. Pure, so that it is testable on every platform.
 *
 * <p>The inner line of the interpreter call, {@code ""<script>" "<arg1>" … "<argN>""}, may have
 * at most {@link #MAX_LINE_LENGTH} characters (research R-4); {@link #lineLength} is the
 * arithmetic the command line and the configuration check share.
 */
public final class WindowsArguments {

    /**
     * The interpreter's 8 191 characters minus a 2 048-character margin for what
     * {@code startcli.bat} adds (research R-4; revisited in T031).
     */
    public static final int MAX_LINE_LENGTH = 8191 - 2048;

    private static final Map<Character, String> INTERPRETED = Map.of(
        '"', "double quote",
        '%', "percent sign",
        '!', "exclamation mark",
        '^', "caret",
        '&', "ampersand",
        '|', "vertical bar",
        '<', "less-than sign",
        '>', "greater-than sign");

    private WindowsArguments() {
    }

    /**
     * The value of {@code name} in {@code environment}, whose names Windows matches ignoring case:
     * the exact spelling, else the first in {@code String} order; null if unset. A blank value is
     * returned as found, so that the runner and the configuration check agree on it.
     */
    public static String environmentValue(Map<String, String> environment, String name) {
        String exact = environment.get(name);
        if (exact != null) {
            return exact;
        }
        return environment.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .min(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .orElse(null);
    }

    /** The class of the first refused character of {@code value}, empty if it may be passed. */
    public static Optional<String> refusal(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x7f) {
                return Optional.of("non-ASCII character");
            }
            if (Character.isISOControl(c)) {
                return Optional.of("control character");
            }
            String interpreted = INTERPRETED.get(c);
            if (interpreted != null) {
                return Optional.of(interpreted);
            }
        }
        return Optional.empty();
    }

    /**
     * True if {@code path} is a network path ({@code \\server\share…}), which the command
     * interpreter does not accept as its working directory.
     */
    public static boolean isUnc(Path path) {
        return path.toString().startsWith("\\\\");
    }

    /**
     * The inner line {@code ""<script>" "<arg1>" … "<argN>""}: the script and every argument in
     * double quotes, trailing backslashes doubled so that they do not escape the closing quote
     * under the C runtime rules of the Java launcher, the whole in one more pair of quotes.
     */
    public static String line(String script, List<String> arguments) {
        List<String> parts = new ArrayList<>(arguments.size() + 1);
        parts.add('"' + script + '"');
        arguments.forEach(argument -> parts.add(quote(argument)));
        return '"' + String.join(" ", parts) + '"';
    }

    /** The length of {@link #line}. */
    public static int lineLength(String script, List<String> arguments) {
        return line(script, arguments).length();
    }

    /** {@code "<value>"} with the trailing backslashes of {@code value} doubled. */
    public static String quote(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '\\') {
            end--;
        }
        return '"' + value + value.substring(end) + '"';
    }
}
