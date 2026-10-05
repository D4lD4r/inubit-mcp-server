package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of a write tool's preview step under server-side confirmation (FR-022,
 * data-model.md → ConfirmationChallenge): nothing was changed; the action runs only when the same
 * tool is called again with {@code confirmationCode} before {@code expiresAt}.
 *
 * @param confirmationCode 16 random bytes, URL-safe Base64 without padding (22 chars)
 * @param message          what to do next, for the assistant and its user
 */
public record ConfirmationChallenge(String confirmationCode, Instant expiresAt, Preview preview,
    String message) {

    public ConfirmationChallenge {
        Objects.requireNonNull(confirmationCode, "confirmationCode");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(message, "message");
    }

    /**
     * What would be done: the server, the action and the instance as the Queue Manager shows it
     * now.
     *
     * @param state the instance's state (for a restart: {@code ERROR})
     * @param since when the shown row entered its state
     */
    public record Preview(NodeId node, ProcessAction action, String processId,
        Optional<String> workflow, Optional<String> module, ProcessState state, Instant since) {

        public Preview {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(processId, "processId");
            workflow = workflow == null ? Optional.empty() : workflow;
            module = module == null ? Optional.empty() : module;
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(since, "since");
        }
    }
}
