package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The version of a local StartCLI client and the server range it declares (data-model.md →
 * ClientVersion, FR-011, research D-8). A probe that prints a version but no range gives a client
 * with an empty range, whose range check is {@link Compatibility#NOT_EVALUABLE}.
 *
 * @param version       the client's own version, raw kept
 * @param supportedFrom lower bound of the declared server range; present exactly when
 *                      {@code supportedTo} is
 * @param supportedTo   upper bound of the declared server range
 */
public record ClientVersion(InubitVersion version, Optional<InubitVersion> supportedFrom,
    Optional<InubitVersion> supportedTo) {

    /** Client version against server version on major.minor.patch (FR-011). */
    public enum Comparison { MATCH, MISMATCH, NOT_COMPARABLE }

    /** Server version against the client's declared range on major.minor (FR-011). */
    public enum Compatibility { COMPATIBLE, INCOMPATIBLE, NOT_EVALUABLE }

    public ClientVersion {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(supportedFrom, "supportedFrom");
        Objects.requireNonNull(supportedTo, "supportedTo");
        if (supportedFrom.isPresent() != supportedTo.isPresent()) {
            throw new IllegalArgumentException("Client range of " + Names.quote(version.raw())
                + " needs both bounds or none");
        }
        range(supportedFrom, supportedTo); // rejects a lower bound above the upper bound
    }

    /**
     * {@code MATCH} if major, minor and patch are equal, {@code MISMATCH} if any differs,
     * {@code NOT_COMPARABLE} if the server is unknown or either version has no patch. Suffixes
     * are ignored.
     */
    public Comparison compare(Optional<InubitVersion> server) {
        if (server.isEmpty() || version.patch().isEmpty() || server.get().patch().isEmpty()) {
            return Comparison.NOT_COMPARABLE;
        }
        InubitVersion s = server.get();
        return version.sameMinor(s) && version.patch().equals(s.patch())
            ? Comparison.MATCH : Comparison.MISMATCH;
    }

    /**
     * Whether the server's major.minor lies in the declared range (inclusive, patch ignored);
     * {@code NOT_EVALUABLE} if the server is unknown or no range was declared.
     */
    public Compatibility compatibleWith(Optional<InubitVersion> server) {
        Optional<VersionRange> range = range(supportedFrom, supportedTo);
        if (range.isEmpty() || server.isEmpty()) {
            return Compatibility.NOT_EVALUABLE;
        }
        return of(range.get().contains(server.get()));
    }

    /**
     * Range check for a configured version line ({@code V8_1} = 8 and 1, {@code V9_X} = 9 without
     * minor). A line without minor is compatible only if some minor of that major lies in the
     * range, i.e. the major lies between the bounds' majors (D-8).
     */
    public Compatibility compatibleWith(int major, OptionalInt minor) {
        Optional<VersionRange> range = range(supportedFrom, supportedTo);
        if (range.isEmpty()) {
            return Compatibility.NOT_EVALUABLE;
        }
        if (minor.isPresent()) {
            return of(range.get().contains(new InubitVersion(major, minor.getAsInt(),
                OptionalInt.empty(), major + "." + minor.getAsInt())));
        }
        return of(range.get().fromMajor() <= major && major <= range.get().toMajor());
    }

    private static Optional<VersionRange> range(Optional<InubitVersion> from,
        Optional<InubitVersion> to) {
        if (from.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new VersionRange(from.get().major(), from.get().minor(),
            to.orElseThrow().major(), to.orElseThrow().minor()));
    }

    private static Compatibility of(boolean inside) {
        return inside ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
    }
}
