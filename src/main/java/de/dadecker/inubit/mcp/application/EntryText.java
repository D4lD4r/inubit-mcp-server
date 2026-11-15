package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.EntryContentClassifier;
import de.dadecker.inubit.mcp.domain.model.EntryContentClassifier.Classified;
import de.dadecker.inubit.mcp.domain.model.QueueEntryBounds;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Data;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The content shaping of an entry's text (feature 020, FR-008…FR-011, D-10/D-11), shared by
 * {@code read_queue_entry} and {@code read_io_log} (feature 024, D-9): the bytes are classified,
 * a text is cleared of the configured credentials ({@code knownSecrets}), masked
 * ({@link SecretTextMasker}) and only then cut, never inside a surrogate pair, so no piece of a
 * secret survives the cut.
 *
 * <p>Thread-safe; it keeps no state of its own.
 */
public final class EntryText {

    private final UnaryOperator<String> knownSecrets;

    /**
     * @param knownSecrets replaces the configured credentials in a text (the
     *                     {@code SecretScrubber}); applied before masking and cutting
     */
    public EntryText(UnaryOperator<String> knownSecrets) {
        this.knownSecrets = Objects.requireNonNull(knownSecrets, "knownSecrets");
    }

    /**
     * {@code bytes} as {@code empty}, {@code binary} (size and detected signature) or
     * {@code text} (charset, sizes, the masked text cut at {@code maxChars}).
     */
    public Data data(byte[] bytes, int maxChars) {
        Classified classified = EntryContentClassifier.classify(bytes);
        return switch (classified.kind()) {
            case EMPTY -> Data.empty();
            case BINARY -> Data.binary(bytes.length, classified.detected());
            case TEXT -> {
                String masked = masked(classified.text().orElseThrow());
                yield Data.text(classified.charset().map(Charset::name).orElseThrow(),
                    bytes.length, masked.length(), QueueEntryBounds.cut(masked, maxChars));
            }
        };
    }

    /** The configured credentials replaced, then the secret-named values masked. */
    public String masked(String text) {
        return SecretTextMasker.mask(knownSecrets.apply(text));
    }
}
