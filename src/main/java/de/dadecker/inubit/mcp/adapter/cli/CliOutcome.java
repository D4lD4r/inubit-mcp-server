package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.domain.model.ToolError;
import java.util.Objects;

/** The classified result of a StartCLI run (research R-7). */
public sealed interface CliOutcome {

    /** Exit code 0 with at least one {@code n-OK} and no {@code n-NOK} message. */
    record Success(CliOutput output) implements CliOutcome {
        public Success {
            Objects.requireNonNull(output, "output");
        }
    }

    /** Any other result, as an actionable, scrubbed {@link ToolError}. */
    record Failure(ToolError error) implements CliOutcome {
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }
}
