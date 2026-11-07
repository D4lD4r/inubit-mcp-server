package de.dadecker.inubit.mcp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Java major of a JDK from its {@code release} file (feature 017, research D-17); the
 * validator warns when it does not match the INUBIT version of a client.
 */
public final class JdkRelease {

    private static final Pattern JAVA_VERSION =
        Pattern.compile("^JAVA_VERSION=\"?(\\d+)(?:\\.(\\d+))?", Pattern.MULTILINE);

    private JdkRelease() {
    }

    /**
     * The Java major of the JDK in {@code javaHome}, read through {@code reader} from
     * {@code <javaHome>/release}; {@code "1.8…"} is 8; empty when unreadable or unparsable.
     */
    public static OptionalInt major(Path javaHome, Function<Path, Optional<String>> reader) {
        Optional<String> release = reader.apply(javaHome.resolve("release"));
        if (release.isEmpty()) {
            return OptionalInt.empty();
        }
        Matcher matcher = JAVA_VERSION.matcher(release.get());
        if (!matcher.find()) {
            return OptionalInt.empty();
        }
        try {
            int first = Integer.parseInt(matcher.group(1));
            if (first == 1) {
                return matcher.group(2) == null ? OptionalInt.empty()
                    : OptionalInt.of(Integer.parseInt(matcher.group(2)));
            }
            return OptionalInt.of(first);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** Reads {@code file} as text; empty when it cannot be read. */
    public static Optional<String> read(Path file) {
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file))
                : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
