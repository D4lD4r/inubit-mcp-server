package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The preview of {@code run_e2e_test} where {@code e2eTests} is {@code CONFIRM} (feature 004,
 * US5 AS 2, research D-2): nothing was sent; the message is sent only when the call is
 * repeated with {@code confirmationCode} before {@code expiresAt}.
 */
public record E2ePreview(NodeId node, String endpoint, int payloadBytes,
    Optional<String> soapAction, String confirmationCode, Instant expiresAt, String message) {

    public E2ePreview {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(endpoint, "endpoint");
        soapAction = soapAction == null ? Optional.empty() : soapAction;
        Objects.requireNonNull(confirmationCode, "confirmationCode");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(message, "message");
    }
}
