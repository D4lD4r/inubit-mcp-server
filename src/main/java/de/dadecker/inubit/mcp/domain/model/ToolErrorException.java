package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Carries a {@link ToolError} through code paths that return a value on success. */
public class ToolErrorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ToolError error;
    private final transient Object details;
    private final transient List<String> warnings;

    public ToolErrorException(ToolError error) {
        this(error, null);
    }

    /**
     * @param details shown with the error, as the JSON of a tool result (feature 022: the new
     *                preview of a {@code CONFLICT}); {@code null}: none
     */
    public ToolErrorException(ToolError error, Object details) {
        this(error, details, List.of());
    }

    /** @param warnings the node entry's warnings beside the error (feature 025, FR-030 (b)) */
    protected ToolErrorException(ToolError error, Object details, List<String> warnings) {
        super(Objects.requireNonNull(error, "error").code() + ": " + error.message(), null, false,
            false);
        this.error = error;
        this.details = details;
        this.warnings = List.copyOf(warnings);
    }

    public ToolError error() {
        return error;
    }

    /** What the error response shows besides the {@link ToolError}, if anything. */
    public Optional<Object> details() {
        return Optional.ofNullable(details);
    }

    /**
     * The warnings a fan-out shows beside this error in the node's entry (feature 025, FR-030
     * (b)); empty by default.
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * This error with {@code warnings} instead of its own. A subclass that callers tell apart
     * overrides it to keep its type, as {@link ContactedException} does.
     */
    public ToolErrorException withWarnings(List<String> warnings) {
        return new ToolErrorException(error, details, warnings);
    }
}
