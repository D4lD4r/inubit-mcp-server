package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An INUBIT version as reported by a server or client or as configured (data-model.md →
 * InubitVersion, research D-1).
 *
 * <p>The natural ordering compares major, minor, then patch (an absent patch counts as 0) and
 * ignores the suffix in {@code raw}; it is therefore inconsistent with {@link #equals}, which
 * includes {@code raw} and the presence of the patch.
 *
 * @param patch absent if the raw value has none
 * @param raw   the value as reported or configured, suffix kept (e.g. {@code 8.1.17-HF3})
 */
public record InubitVersion(int major, int minor, OptionalInt patch, String raw)
    implements Comparable<InubitVersion> {

    private static final Pattern LEADING_VERSION =
        Pattern.compile("^(\\d{1,9})\\.(\\d{1,9})(?:\\.(\\d{1,9}))?(?!\\d)");

    public InubitVersion {
        patch = patch == null ? OptionalInt.empty() : patch;
        Objects.requireNonNull(raw, "raw");
        if (major < 0 || minor < 0 || patch.orElse(0) < 0) {
            throw new IllegalArgumentException("Negative version component in "
                + Names.quote(raw));
        }
    }

    /**
     * Reads the leading {@code major.minor[.patch]} of {@code value}; anything after it stays in
     * {@code raw} only. Empty if {@code value} does not start with a parsable major.minor
     * (the version is then unknown).
     */
    public static Optional<InubitVersion> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher m = LEADING_VERSION.matcher(value);
        if (!m.find()) {
            return Optional.empty();
        }
        OptionalInt patch = m.group(3) == null
            ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(m.group(3)));
        return Optional.of(new InubitVersion(Integer.parseInt(m.group(1)),
            Integer.parseInt(m.group(2)), patch, value));
    }

    /** Whether {@code other} has the same major and minor version. */
    public boolean sameMinor(InubitVersion other) {
        return major == other.major && minor == other.minor;
    }

    @Override
    public int compareTo(InubitVersion other) {
        int c = Integer.compare(major, other.major);
        if (c == 0) {
            c = Integer.compare(minor, other.minor);
        }
        return c != 0 ? c : Integer.compare(patch.orElse(0), other.patch.orElse(0));
    }
}
