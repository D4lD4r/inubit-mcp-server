package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The preview of {@code restore_backup} or {@code set_active} under server-side confirmation
 * (feature 004, research D-2, D-14, D-15): nothing was sent; the call runs only when it is
 * repeated with {@code confirmationCode} before {@code expiresAt}.
 *
 * @param scope  the scope as text ({@code diagram group G}, {@code modules a, b} or
 *               {@code workflow W of diagram group G})
 * @param modify the artifacts the call would re-import (each gets a new version)
 * @param notes  what else the user should know (e.g. created artifacts that stay, the change of
 *               the active flag)
 */
public record WritePreview(NodeId node, String scope, List<String> modify, List<String> notes,
    String confirmationCode, Instant expiresAt, String message) {

    public WritePreview {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(scope, "scope");
        modify = List.copyOf(modify);
        notes = List.copyOf(notes);
        Objects.requireNonNull(confirmationCode, "confirmationCode");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(message, "message");
    }
}
