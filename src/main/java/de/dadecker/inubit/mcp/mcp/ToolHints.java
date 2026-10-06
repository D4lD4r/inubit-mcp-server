package de.dadecker.inubit.mcp.mcp;

import java.util.Objects;

/**
 * The MCP tool annotations of a {@link ToolHandler} (Constitution I: write tools are marked
 * destructive and non-idempotent so that clients can ask for confirmation). Kept SDK-free so that
 * handlers do not depend on the SDK's annotation type.
 */
public record ToolHints(
    String title,
    boolean readOnly,
    boolean destructive,
    boolean idempotent,
    boolean openWorld) {

    public ToolHints {
        Objects.requireNonNull(title, "title");
        if (readOnly && destructive) {
            throw new IllegalArgumentException("A read-only tool cannot be destructive");
        }
    }

    /** A read-only, idempotent tool; {@code openWorld} if it talks to INUBIT. */
    public static ToolHints readOnly(String title, boolean openWorld) {
        return new ToolHints(title, true, false, true, openWorld);
    }

    /**
     * A read-only tool (for INUBIT) with an explicit idempotence hint; {@code idempotent} is
     * {@code false} for a tool that records something locally on every call (feature 003:
     * {@code export_artifacts} writes a history entry).
     */
    public static ToolHints readOnly(String title, boolean openWorld, boolean idempotent) {
        return new ToolHints(title, true, false, idempotent, openWorld);
    }

    /** A state-changing tool: destructive, non-idempotent, open world. */
    public static ToolHints destructive(String title) {
        return new ToolHints(title, false, true, false, true);
    }
}
