package de.dadecker.inubit.mcp.domain.model;

/**
 * An inclusive range of INUBIT versions on major.minor; the patch is ignored, so
 * {@code 8.1–8.1} contains every 8.1.x (data-model.md → VersionRange).
 */
public record VersionRange(int fromMajor, int fromMinor, int toMajor, int toMinor) {

    public VersionRange {
        if (fromMajor < 0 || fromMinor < 0 || toMajor < 0 || toMinor < 0) {
            throw new IllegalArgumentException("Negative bound in version range");
        }
        if (compare(fromMajor, fromMinor, toMajor, toMinor) > 0) {
            throw new IllegalArgumentException("Version range " + fromMajor + "." + fromMinor
                + "–" + toMajor + "." + toMinor + " has its lower bound above its upper bound");
        }
    }

    /** Whether the major.minor of {@code version} lies within the bounds. */
    public boolean contains(InubitVersion version) {
        return compare(fromMajor, fromMinor, version.major(), version.minor()) <= 0
            && compare(version.major(), version.minor(), toMajor, toMinor) <= 0;
    }

    private static int compare(int majorA, int minorA, int majorB, int minorB) {
        int c = Integer.compare(majorA, majorB);
        return c != 0 ? c : Integer.compare(minorA, minorB);
    }
}
