package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The preview of a writing call under server-side confirmation (feature 004, research D-2,
 * contracts/mcp-tools-delta.md): nothing was sent; the call runs only when it is repeated with
 * {@code confirmationCode} before {@code expiresAt}.
 *
 * @param scope         the scope as text ({@code diagram group G} / {@code modules a, b})
 * @param checkWarnings the number of WARNING findings of the checks
 */
public record ImportPreview(NodeId node, String scope, String baseCommit, List<String> create,
    List<String> modify, List<String> notImported, int checkWarnings, OwnerKind ownerKind,
    String confirmationCode, Instant expiresAt, String message) {

    public ImportPreview {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(baseCommit, "baseCommit");
        create = List.copyOf(create);
        modify = List.copyOf(modify);
        notImported = List.copyOf(notImported);
        Objects.requireNonNull(ownerKind, "ownerKind");
        Objects.requireNonNull(confirmationCode, "confirmationCode");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(message, "message");
    }
}
