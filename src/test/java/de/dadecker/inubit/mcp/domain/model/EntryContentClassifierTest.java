package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.domain.model.EntryContentClassifier.Classified;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Feature 020 (T009, FR-009, D-10): text or binary, and the charset of text. */
class EntryContentClassifierTest {

    private static final int WINDOW = 8192;

    @Test
    void anEmptyStreamIsEmptyWithoutContent() {
        Classified classified = EntryContentClassifier.classify(new byte[0]);

        assertThat(classified.kind()).isEqualTo(ContentKind.EMPTY);
        assertThat(classified.detected()).isEmpty();
        assertThat(classified.charset()).isEmpty();
        assertThat(classified.text()).isEmpty();
    }

    @Test
    void eachSignatureAtOffsetZeroIsBinaryWithTheDetectedKind() {
        assertSignature(BinarySignature.ZIP, 0x50, 0x4B, 0x03, 0x04);
        assertSignature(BinarySignature.ZIP, 0x50, 0x4B, 0x05, 0x06);
        assertSignature(BinarySignature.ZIP, 0x50, 0x4B, 0x07, 0x08);
        assertSignature(BinarySignature.GZIP, 0x1F, 0x8B);
        assertSignature(BinarySignature.PDF, 0x25, 0x50, 0x44, 0x46, 0x2D);
        assertSignature(BinarySignature.PNG, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
        assertSignature(BinarySignature.JPEG, 0xFF, 0xD8, 0xFF);
        assertSignature(BinarySignature.GIF, 'G', 'I', 'F', '8', '7', 'a');
        assertSignature(BinarySignature.GIF, 'G', 'I', 'F', '8', '9', 'a');
        assertSignature(BinarySignature.JAVA_SERIALIZED, 0xAC, 0xED, 0x00, 0x05);
    }

    @Test
    void aTextualSignatureWinsOverTheTextRule() {
        Classified pdf = EntryContentClassifier.classify(ascii("%PDF-1.7 plain looking text"));
        Classified gif = EntryContentClassifier.classify(ascii("GIF89a and more"));

        assertThat(pdf.kind()).isEqualTo(ContentKind.BINARY);
        assertThat(pdf.detected()).contains(BinarySignature.PDF);
        assertThat(gif.detected()).contains(BinarySignature.GIF);
    }

    @Test
    void aSignatureElsewhereThanOffsetZeroIsNotDetected() {
        Classified classified = EntryContentClassifier.classify(ascii("x%PDF-1.7 text"));

        assertThat(classified.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(classified.detected()).isEmpty();
    }

    @Test
    void nearMissesOfASignatureAreNotDetected() {
        assertThat(EntryContentClassifier.classify(ascii("%PDF text")).kind())
            .isEqualTo(ContentKind.TEXT);
        assertThat(EntryContentClassifier.classify(ascii("GIF88a text")).kind())
            .isEqualTo(ContentKind.TEXT);
        assertThat(EntryContentClassifier.classify(ascii("PK text")).kind())
            .isEqualTo(ContentKind.TEXT);
    }

    @Test
    void aNulByteAtTheLastWindowPositionIsBinary() {
        byte[] bytes = filled(WINDOW + 100, 'a');
        bytes[WINDOW - 1] = 0x00;

        Classified classified = EntryContentClassifier.classify(bytes);

        assertThat(classified.kind()).isEqualTo(ContentKind.BINARY);
        assertThat(classified.detected()).isEmpty();
        assertThat(classified.charset()).isEmpty();
        assertThat(classified.text()).isEmpty();
    }

    @Test
    void aNulByteJustBehindTheWindowIsNotLookedAt() {
        byte[] bytes = filled(WINDOW + 100, 'a');
        bytes[WINDOW] = 0x00;

        Classified classified = EntryContentClassifier.classify(bytes);

        assertThat(classified.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(classified.charset()).contains(StandardCharsets.UTF_8);
        assertThat(classified.text()).hasValueSatisfying(text -> {
            assertThat(text).hasSize(WINDOW + 100);
            assertThat(text.charAt(WINDOW)).isEqualTo('\0');
        });
    }

    @Test
    void exactlyTenPercentControlBytesIsStillText() {
        byte[] bytes = filled(1000, 'a');
        Arrays.fill(bytes, 0, 100, (byte) 0x01);

        assertThat(EntryContentClassifier.classify(bytes).kind()).isEqualTo(ContentKind.TEXT);
    }

    @Test
    void oneControlByteMoreThanTenPercentIsBinary() {
        byte[] bytes = filled(1000, 'a');
        Arrays.fill(bytes, 0, 101, (byte) 0x01);

        Classified classified = EntryContentClassifier.classify(bytes);

        assertThat(classified.kind()).isEqualTo(ContentKind.BINARY);
        assertThat(classified.detected()).isEmpty();
    }

    @Test
    void theControlShareIsTakenOverTheFirst8KiBOnly() {
        byte[] bytes = filled(2 * WINDOW, (byte) 0x01);
        Arrays.fill(bytes, 0, WINDOW, (byte) 'a');
        Arrays.fill(bytes, 0, WINDOW / 10, (byte) 0x1F);

        assertThat(EntryContentClassifier.classify(bytes).kind()).isEqualTo(ContentKind.TEXT);

        bytes[WINDOW / 10] = 0x7F;

        assertThat(EntryContentClassifier.classify(bytes).kind()).isEqualTo(ContentKind.BINARY);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x0B, 0x0C, 0x0E, 0x0F,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E,
        0x1F, 0x7F})
    void eachControlByteCounts(int control) {
        byte[] bytes = filled(10, 'a');
        bytes[0] = (byte) control;
        bytes[1] = (byte) control;

        assertThat(EntryContentClassifier.classify(bytes).kind()).as("0x%02X", control)
            .isEqualTo(ContentKind.BINARY);
    }

    @Test
    void tabLineFeedAndCarriageReturnDoNotCount() {
        Classified classified = EntryContentClassifier.classify(ascii("\t\t\n\n\r\r\r\nab"));

        assertThat(classified.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(classified.text()).contains("\t\t\n\n\r\r\r\nab");
    }

    @Test
    void bytesFrom0x80UpwardsNeverCount() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (0x80 + i % 0x80);
        }
        bytes[0] = 'a';

        Classified classified = EntryContentClassifier.classify(bytes);

        assertThat(classified.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(classified.charset()).contains(StandardCharsets.ISO_8859_1);
    }

    @Test
    void utf16LittleEndianTextIsBinary() {
        byte[] bytes = "<order id=\"Order-0001\"/>".getBytes(StandardCharsets.UTF_16LE);

        Classified classified = EntryContentClassifier.classify(bytes);

        assertThat(classified.kind()).isEqualTo(ContentKind.BINARY);
        assertThat(classified.detected()).isEmpty();
    }

    @Test
    void validUtf8IsDecodedAsUtf8() {
        String xml = "<customer>Grüße von acme – Order-0001 €</customer>";

        Classified classified = EntryContentClassifier.classify(
            xml.getBytes(StandardCharsets.UTF_8));

        assertThat(classified.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(classified.detected()).isEmpty();
        assertThat(classified.charset()).contains(StandardCharsets.UTF_8);
        assertThat(classified.text()).contains(xml);
    }

    @Test
    void aLeadingUtf8BomIsDropped() {
        byte[] body = "<a>globex</a>".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);

        Classified classified = EntryContentClassifier.classify(bytes);

        assertThat(classified.charset()).contains(StandardCharsets.UTF_8);
        assertThat(classified.text()).contains("<a>globex</a>");
    }

    @Test
    void aLatin1UmlautIsDecodedAsIso88591() {
        byte[] bytes = "<city>Köln</city>".getBytes(StandardCharsets.ISO_8859_1);

        Classified classified = EntryContentClassifier.classify(bytes);

        assertThat(classified.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(classified.charset()).contains(StandardCharsets.ISO_8859_1);
        assertThat(classified.text()).contains("<city>Köln</city>");
    }

    @Test
    void invalidUtf8BehindTheWindowStillSelectsIso88591() {
        byte[] bytes = filled(WINDOW + 10, 'a');
        bytes[WINDOW + 5] = (byte) 0xE4;

        Classified classified = EntryContentClassifier.classify(bytes);

        assertThat(classified.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(classified.charset()).contains(StandardCharsets.ISO_8859_1);
        assertThat(classified.text()).hasValueSatisfying(
            text -> assertThat(text.charAt(WINDOW + 5)).isEqualTo('ä'));
    }

    @Test
    void aThreeByteStreamIsClassified() {
        Classified text = EntryContentClassifier.classify(ascii("abc"));
        Classified jpeg = EntryContentClassifier.classify(bytes(0xFF, 0xD8, 0xFF));
        Classified zipPrefix = EntryContentClassifier.classify(bytes(0x50, 0x4B, 0x03));

        assertThat(text.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(text.charset()).contains(StandardCharsets.UTF_8);
        assertThat(text.text()).contains("abc");
        assertThat(jpeg.detected()).contains(BinarySignature.JPEG);
        assertThat(zipPrefix.kind()).as("0x03 is one of three bytes, no signature")
            .isEqualTo(ContentKind.BINARY);
        assertThat(zipPrefix.detected()).isEmpty();
    }

    @Test
    void nullIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> EntryContentClassifier.classify(null));
    }

    private static void assertSignature(BinarySignature expected, int... signature) {
        byte[] bytes = Arrays.copyOf(bytes(signature), signature.length + 64);
        Arrays.fill(bytes, signature.length, bytes.length, (byte) 'a');

        Classified classified = EntryContentClassifier.classify(bytes);

        assertThat(classified.kind()).as(expected.name()).isEqualTo(ContentKind.BINARY);
        assertThat(classified.detected()).as(expected.name()).isEqualTo(Optional.of(expected));
        assertThat(classified.charset()).isEmpty();
        assertThat(classified.text()).isEmpty();
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] filled(int size, int value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
