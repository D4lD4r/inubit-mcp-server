package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;

/** Carries a {@link ToolError} through code paths that return a value on success. */
public class ToolErrorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ToolError error;
    private final transient Object details;

    public ToolErrorException(ToolError error) {
        this(error, null);
    }

    /**
     * @param details shown with the error, as the JSON of a tool result (feature 022: the new
     *                preview of a {@code CONFLICT}); {@code null}: none
     */
    public ToolErrorException(ToolError error, Object details) {
        super(Objects.requireNonNull(error, "error").code() + ": " + error.message(), null, false,
            false);
        this.error = error;
        this.details = details;
    }

    public ToolError error() {
        return error;
    }

    /** What the error response shows besides the {@link ToolError}, if anything. */
    public Optional<Object> details() {
        return Optional.ofNullable(details);
    }
}
