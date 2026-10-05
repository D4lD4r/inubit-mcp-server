package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A part of an inventory detail that could not be obtained, while the rest could (the inventory
 * counterpart of the health report's {@link UnavailablePart}; contracts/mcp-tools.md §6).
 *
 * @param part        one of {@link #PARTS}
 * @param reason      {@code CODE: message}, e.g. {@code CLI_UNAVAILABLE: …}
 * @param likelyCause copied from the {@link ToolError}
 * @param nextStep    copied from the {@link ToolError}
 */
public record UnavailableInventoryPart(String part, String reason, Optional<String> likelyCause,
    Optional<String> nextStep) {

    /** The version history (CLI export with history, research R-11). */
    public static final String VERSIONS = "versions";
    public static final Set<String> PARTS = Set.of(VERSIONS);

    public UnavailableInventoryPart {
        if (!PARTS.contains(part)) {
            throw new IllegalArgumentException("unknown inventory part " + Names.quote(part));
        }
        Objects.requireNonNull(reason, "reason");
        likelyCause = likelyCause == null ? Optional.empty() : likelyCause;
        nextStep = nextStep == null ? Optional.empty() : nextStep;
    }

    /** The part with {@code CODE: message} of {@code error} as reason, plus cause and step. */
    public static UnavailableInventoryPart of(String part, ToolError error) {
        return new UnavailableInventoryPart(part, error.code() + ": " + error.message(),
            Optional.of(error.likelyCause()), Optional.of(error.nextStep()));
    }
}
