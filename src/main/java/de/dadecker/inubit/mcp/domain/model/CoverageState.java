package de.dadecker.inubit.mcp.domain.model;

/**
 * The state of one coverage list entry on one version line (feature 022, research D-2,
 * data-model.md → CoverageState). Only {@link Reachable} admits a call.
 */
public sealed interface CoverageState {

    /** The longest exclusion reason (contracts/coverage-list.md, "Rows"). */
    int MAX_REASON_LENGTH = 120;

    /** Sent by {@code tool} for its action key {@code action} (research D-2, "Action key"). */
    record Reachable(String tool, String action) implements CoverageState {
        public Reachable {
            requireText(tool, "tool");
            requireText(action, "action");
        }
    }

    /** Read already by {@code tool} through another entry (research D-6). */
    record Covered(String tool) implements CoverageState {
        public Covered {
            requireText(tool, "tool");
        }
    }

    /** Documented, not yet verified with a recording. */
    record Planned() implements CoverageState {
    }

    /** Not documented by the vendor for this version. */
    record Undocumented() implements CoverageState {
    }

    /** Never sent, for {@code reason} (at most 120 characters). */
    record Excluded(String reason) implements CoverageState {
        public Excluded {
            requireText(reason, "reason");
            if (reason.length() > MAX_REASON_LENGTH) {
                throw new IllegalArgumentException("Exclusion reason longer than "
                    + MAX_REASON_LENGTH + " characters: " + reason.length());
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Empty " + name);
        }
    }
}
