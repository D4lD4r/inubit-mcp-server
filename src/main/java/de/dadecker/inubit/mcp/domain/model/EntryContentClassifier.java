package de.dadecker.inubit.mcp.domain.model;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a data stream or variable value of a Queue Manager entry is text or binary and
 * decodes text (feature 020, FR-009, D-10). Pure; the caller bounds the input to 10 MiB.
 */
public final class EntryContentClassifier {

    private static final int WINDOW_BYTES = 8192;
    private static final char BOM = '\uFEFF';

    private EntryContentClassifier() {
    }

    /**
     * The outcome of {@link #classify(byte[])}: {@code detected} only for a matched signature,
     * {@code charset} and {@code text} only for {@link ContentKind#TEXT}.
     */
    public record Classified(ContentKind kind, Optional<BinarySignature> detected,
        Optional<Charset> charset, Optional<String> text) {

        public Classified {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detected, "detected");
            Objects.requireNonNull(charset, "charset");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Empty → {@code EMPTY}; a signature at offset 0 → {@code BINARY} with it; a NUL byte or more
     * than 10 % control bytes in the first min(8 KiB, size) bytes → {@code BINARY}; otherwise
     * {@code TEXT}, UTF-8 (leading BOM dropped) if the whole stream is valid UTF-8, else
     * ISO-8859-1.
     */
    public static Classified classify(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            return new Classified(ContentKind.EMPTY, Optional.empty(), Optional.empty(),
                Optional.empty());
        }
        for (BinarySignature signature : BinarySignature.values()) {
            if (signature.matches(bytes)) {
                return binary(Optional.of(signature));
            }
        }
        if (binaryWindow(bytes)) {
            return binary(Optional.empty());
        }
        Optional<String> utf8 = decodeUtf8(bytes);
        if (utf8.isPresent()) {
            String text = utf8.get();
            if (!text.isEmpty() && text.charAt(0) == BOM) {
                text = text.substring(1);
            }
            return text(StandardCharsets.UTF_8, text);
        }
        return text(StandardCharsets.ISO_8859_1, new String(bytes, StandardCharsets.ISO_8859_1));
    }

    private static boolean binaryWindow(byte[] bytes) {
        int window = Math.min(WINDOW_BYTES, bytes.length);
        int controls = 0;
        for (int i = 0; i < window; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0x00) {
                return true;
            }
            if (isControl(b)) {
                controls++;
            }
        }
        return controls * 10L > window;
    }

    private static boolean isControl(int b) {
        return b <= 0x08 || b == 0x0B || b == 0x0C || (b >= 0x0E && b <= 0x1F) || b == 0x7F;
    }

    private static Optional<String> decodeUtf8(byte[] bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    private static Classified binary(Optional<BinarySignature> detected) {
        return new Classified(ContentKind.BINARY, detected, Optional.empty(), Optional.empty());
    }

    private static Classified text(Charset charset, String text) {
        return new Classified(ContentKind.TEXT, Optional.empty(), Optional.of(charset),
            Optional.of(text));
    }
}
