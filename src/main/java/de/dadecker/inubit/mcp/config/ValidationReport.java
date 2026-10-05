package de.dadecker.inubit.mcp.config;

import java.util.List;

/** All startup findings, collected so that they can be reported together (FR-004). */
public record ValidationReport(List<String> errors, List<String> warnings) {

    public ValidationReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    /** The server refuses to start if this is true. */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
