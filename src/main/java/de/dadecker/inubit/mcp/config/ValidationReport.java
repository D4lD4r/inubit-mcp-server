package de.dadecker.inubit.mcp.config;

import java.util.List;
import java.util.Optional;

/**
 * All startup findings, collected so that they can be reported together (FR-004).
 *
 * @param workspace the outcome of preparing the workspace (feature 003: usable, created just now,
 *                  or unusable); empty if it was not prepared (a relative path or an invalid
 *                  profile name, both reported as errors)
 */
public record ValidationReport(List<String> errors, List<String> warnings,
    Optional<WorkspaceDirectory.Result> workspace) {

    public ValidationReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
        workspace = workspace == null ? Optional.empty() : workspace;
    }

    /** A report without workspace outcome. */
    public ValidationReport(List<String> errors, List<String> warnings) {
        this(errors, warnings, Optional.empty());
    }

    /** The server refuses to start if this is true. */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
