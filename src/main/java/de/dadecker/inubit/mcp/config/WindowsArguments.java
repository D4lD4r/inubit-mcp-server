package de.dadecker.inubit.mcp.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The values passed to StartCLI outside its command on Windows (feature 013, FR-002, research
 * R-2): inside double quotes the command interpreter still interprets {@code " % ! ^} and, with an
 * unbalanced quote, {@code & | < >}; these, control characters and characters outside ASCII are
 * refused. Space, {@code ' ( ) ; , = \ * ?} stay literal. Messages name the class returned here,
 * never the value. Pure, so that it is testable on every platform.
 */
public final class WindowsArguments {

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
}
