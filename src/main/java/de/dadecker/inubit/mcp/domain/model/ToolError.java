package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * An actionable failure: what failed, the likely cause and a next step (FR-027).
 *
 * <p>Callers are responsible for passing scrubbed text; this domain type cannot know the secrets.
 * The {@code excerpt} of an unexpected response is cut to {@value #MAX_EXCERPT_LENGTH} chars.
 */
public record ToolError(
    ErrorCode code,
    String message,
    String likelyCause,
    String nextStep,
    Optional<NodeId> node,
    Optional<String> excerpt) {

    public static final int MAX_EXCERPT_LENGTH = 500;

    public ToolError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(likelyCause, "likelyCause");
        Objects.requireNonNull(nextStep, "nextStep");
        node = node == null ? Optional.empty() : node;
        excerpt = excerpt == null ? Optional.empty() : excerpt.map(ToolError::cut);
    }

    public static ToolError of(ErrorCode code, String message, String likelyCause,
        String nextStep) {
        return new ToolError(code, message, likelyCause, nextStep, Optional.empty(),
            Optional.empty());
    }

    public ToolError withNode(NodeId newNode) {
        return new ToolError(code, message, likelyCause, nextStep, Optional.of(newNode), excerpt);
    }

    public ToolError withExcerpt(String newExcerpt) {
        return new ToolError(code, message, likelyCause, nextStep, node,
            Optional.ofNullable(newExcerpt));
    }

    private static String cut(String text) {
        if (text.length() <= MAX_EXCERPT_LENGTH) {
            return text;
        }
        int end = MAX_EXCERPT_LENGTH;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
