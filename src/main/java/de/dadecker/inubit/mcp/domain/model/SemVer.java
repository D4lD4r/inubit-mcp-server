package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Semantic Versioning 2.0.0 version of this server (feature 016, data-model.md → SemVer). Build
 * metadata is accepted and dropped; it does not take part in comparison or equality.
 *
 * @param major      major version
 * @param minor      minor version
 * @param patch      patch version
 * @param preRelease dot-separated pre-release identifiers, empty for a release
 */
public record SemVer(int major, int minor, int patch, List<String> preRelease)
    implements Comparable<SemVer> {

    private static final String NUMBER = "0|[1-9]\\d{0,8}";
    private static final String PRE_ID = "0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*";
    private static final Pattern FORMAT = Pattern.compile("(" + NUMBER + ")\\.(" + NUMBER + ")\\.("
        + NUMBER + ")(?:-((?:" + PRE_ID + ")(?:\\.(?:" + PRE_ID + "))*))?"
        + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    public SemVer {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version numbers must not be negative");
        }
        preRelease = List.copyOf(Objects.requireNonNull(preRelease, "preRelease"));
    }

    /** The version in {@code text}, or empty if it is not a valid SemVer 2.0.0 version. */
    public static Optional<SemVer> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
        return Optional.of(new SemVer(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
            Integer.parseInt(m.group(3)), pre));
    }

    @Override
    public int compareTo(SemVer other) {
        int c = Integer.compare(major, other.major);
        if (c == 0) {
            c = Integer.compare(minor, other.minor);
        }
        if (c == 0) {
            c = Integer.compare(patch, other.patch);
        }
        return c != 0 ? c : comparePreRelease(preRelease, other.preRelease);
    }

    private static int comparePreRelease(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return Boolean.compare(a.isEmpty(), b.isEmpty());
        }
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = compareIdentifier(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareIdentifier(String a, String b) {
        boolean numericA = a.chars().allMatch(Character::isDigit);
        boolean numericB = b.chars().allMatch(Character::isDigit);
        if (numericA && numericB) {
            int c = Integer.compare(a.length(), b.length());
            return c != 0 ? c : a.compareTo(b);
        }
        if (numericA != numericB) {
            return numericA ? -1 : 1;
        }
        return a.compareTo(b);
    }

    @Override
    public String toString() {
        String core = major + "." + minor + "." + patch;
        return preRelease.isEmpty() ? core : core + "-" + String.join(".", preRelease);
    }
}
