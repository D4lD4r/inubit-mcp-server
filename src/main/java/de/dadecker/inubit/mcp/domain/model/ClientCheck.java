package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * What was recorded for one StartCLI client of a node (feature 017, research D-4,
 * data-model.md → ClientCheckLookup): its probed version, or the code of its failed probe.
 */
public sealed interface ClientCheck {

    /** The client was probed: its version and supported range. */
    record Checked(ClientVersion version) implements ClientCheck {
        public Checked {
            Objects.requireNonNull(version, "version");
        }
    }

    /** The client's probe failed or timed out and no result of it is known. */
    record Failed(ErrorCode code) implements ClientCheck {
        public Failed {
            Objects.requireNonNull(code, "code");
        }
    }
}
