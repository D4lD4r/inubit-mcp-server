package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/** Carries a {@link ToolError} through code paths that return a value on success. */
public class ToolErrorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ToolError error;

    public ToolErrorException(ToolError error) {
        super(Objects.requireNonNull(error, "error").code() + ": " + error.message(), null, false,
            false);
        this.error = error;
    }

    public ToolError error() {
        return error;
    }
}
