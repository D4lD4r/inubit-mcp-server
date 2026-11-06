package de.dadecker.inubit.mcp.domain.model;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An INUBIT major.minor without patch, e.g. {@code 9.0} (feature 017, data-model.md →
 * MajorMinor): the key of a {@code cliClients} entry and the version named in messages.
 */
public record MajorMinor(int major, int minor) {

    private static final Pattern TEXT = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");

    public MajorMinor {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("Negative version component in " + major + "."
                + minor);
        }
    }

    /**
     * Reads exactly {@code major.minor} (no leading zeros, blanks, patch or suffix); empty for
     * anything else, also for a component beyond {@code int}.
     */
    public static Optional<MajorMinor> parse(String text) {
        if (text == null || !TEXT.matcher(text).matches()) {
            return Optional.empty();
        }
        int dot = text.indexOf('.');
        try {
            return Optional.of(new MajorMinor(Integer.parseInt(text.substring(0, dot)),
                Integer.parseInt(text.substring(dot + 1))));
        } catch (NumberFormatException tooLarge) {
            return Optional.empty();
        }
    }

    /** The major.minor of {@code version}; patch and suffix are dropped. */
    public static MajorMinor of(InubitVersion version) {
        return new MajorMinor(version.major(), version.minor());
    }

    /** {@code "M.m"}, e.g. {@code "9.0"}. */
    public String text() {
        return major + "." + minor;
    }
}
