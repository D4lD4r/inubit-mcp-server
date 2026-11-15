package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.domain.model.QueueEntryBounds;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The masking of node details, configuration values and messages of the node tools (feature 024,
 * D-21): the configured credentials of every node replaced ({@code knownSecrets}, the
 * {@code SecretScrubber}), then the secret-named values masked ({@link SecretTextMasker}), and only
 * then cut, so no piece of a secret survives the cut.
 *
 * <p>Thread-safe; it keeps no state of its own.
 */
public final class NodeTextMasking {

    /** Ends a text cut by {@link #maskAndCut}. */
    public static final String CUT_MARKER = "…[cut]";

    /**
     * A masked text within its limit.
     *
     * @param text the masked text, ending in {@link #CUT_MARKER} if it was cut
     * @param cut  whether the masked text was longer than the limit
     */
    public record Bounded(String text, boolean cut) {

        public Bounded {
            Objects.requireNonNull(text, "text");
        }
    }

    private final UnaryOperator<String> knownSecrets;

    /**
     * @param knownSecrets replaces the configured credentials of every node in a text (the
     *                     {@code SecretScrubber}); applied before masking
     */
    public NodeTextMasking(UnaryOperator<String> knownSecrets) {
        this.knownSecrets = Objects.requireNonNull(knownSecrets, "knownSecrets");
    }

    /** {@code text} with the configured credentials replaced and secret-named values masked. */
    public String mask(String text) {
        return SecretTextMasker.mask(knownSecrets.apply(text));
    }

    /**
     * {@code text} {@link #mask masked}; if that is longer than {@code max} characters, cut so
     * that it ends in {@link #CUT_MARKER} and has at most {@code max} characters, never inside a
     * surrogate pair.
     *
     * @param max the limit including the marker; longer than the marker
     */
    public Bounded maskAndCut(String text, int max) {
        if (max <= CUT_MARKER.length()) {
            throw new IllegalArgumentException("max must be longer than the cut marker: " + max);
        }
        String masked = mask(text);
        if (masked.length() <= max) {
            return new Bounded(masked, false);
        }
        return new Bounded(QueueEntryBounds.cut(masked, max - CUT_MARKER.length()) + CUT_MARKER,
            true);
    }
}
