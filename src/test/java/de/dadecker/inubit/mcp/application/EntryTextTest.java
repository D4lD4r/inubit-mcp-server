package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.BinarySignature;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Data;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The content shaping shared by {@code read_queue_entry} (feature 020) and {@code read_io_log}
 * (feature 024, D-9): classify → configured credentials → {@code SecretTextMasker} → cut.
 */
class EntryTextTest {

    /** The scrubber holds the credentials of every configured node, not only the one read. */
    private final SecretScrubber scrubber = new SecretScrubber();

    {
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
    }

    private final EntryText entryText = new EntryText(scrubber::scrub);

    @Test
    void configuredPasswordOfAnotherNodeIsReplacedBeforeTheCut() {
        String password = TestSecrets.CONFIGURED_PASSWORD;
        String text = "x".repeat(95) + password + "tail";

        Data data = entryText.data(utf8(text), 100);

        assertThat(data.text()).contains("x".repeat(95) + "***ta");
        assertThat(data.totalChars()).contains(95 + 3 + 4);
        assertThat(data.truncated()).contains(true);
        assertThat(data.text().orElseThrow()).doesNotContain(password.substring(0, 4));
    }

    @Test
    void authorizationBasicHeaderIsMasked() {
        String basic = Base64.getEncoder().encodeToString(("acme:"
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE).getBytes(StandardCharsets.UTF_8));

        Data data = entryText.data(utf8("Accept: text/xml\nAuthorization: Basic " + basic
            + "\n"), 20_000);

        assertThat(data.text()).contains("Accept: text/xml\nAuthorization:"
            + " ${secret:authorization}\n");
        assertThat(data.text().orElseThrow()).doesNotContain(basic);
    }

    @Test
    void passwordElementIsMaskedBeforeTheCut() {
        String secret = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        String text = "<order><password>" + secret + "</password></order>";
        // a cut first would leave the first part of the secret
        int max = "<order><password>".length() + 10;

        Data data = entryText.data(utf8(text), max);

        assertThat(data.text().orElseThrow()).hasSize(max)
            .startsWith("<order><password>${secret:")
            .doesNotContain(secret.substring(0, 4));
        assertThat(data.totalChars())
            .contains("<order><password>${secret:data.password}</password></order>".length());
    }

    @Test
    void jsonSecretValueIsMaskedBeforeTheCut() {
        String secret = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        String text = "{\"order\": \"Order-0001\", \"apiToken\": \"" + secret + "\"}";

        Data whole = entryText.data(utf8(text), 20_000);
        Data cut = entryText.data(utf8(text), 40);

        assertThat(whole.text()).contains("{\"order\": \"Order-0001\", \"apiToken\":"
            + " \"${secret:data.apiToken}\"}");
        assertThat(cut.text().orElseThrow()).hasSize(40)
            .doesNotContain(secret.substring(0, 4));
    }

    @Test
    void cutNeverSplitsASurrogatePair() {
        String text = "x".repeat(9) + "😀" + "y";

        Data data = entryText.data(utf8(text), 10);

        assertThat(data.text()).contains("x".repeat(9));
        assertThat(data.returnedChars()).contains(9);
        assertThat(data.totalChars()).contains(12);
        assertThat(data.truncated()).contains(true);
    }

    @Test
    void latin1TextIsDecodedAndItsCharsetNamed() {
        byte[] latin1 = "Grüße an Order-0001".getBytes(StandardCharsets.ISO_8859_1);

        Data data = entryText.data(latin1, 20_000);

        assertThat(data.kind()).contains("text");
        assertThat(data.charset()).contains("ISO-8859-1");
        assertThat(data.text()).contains("Grüße an Order-0001");
        assertThat(data.sizeBytes()).contains((long) latin1.length);
        assertThat(data.truncated()).contains(false);
    }

    @Test
    void binaryIsReturnedWithoutContent() {
        byte[] zip = {0x50, 0x4B, 0x03, 0x04, 'a', 'b', 'c', 'd', 'e', 'f'};

        Data data = entryText.data(zip, 20_000);

        assertThat(data.kind()).contains("binary");
        assertThat(data.sizeBytes()).contains(10L);
        assertThat(data.detected()).contains(BinarySignature.ZIP);
        assertThat(data.text()).isEmpty();
        assertThat(data.hint()).contains(Data.BINARY_HINT);
    }

    @Test
    void emptyContentHasKindEmpty() {
        Data data = entryText.data(new byte[0], 20_000);

        assertThat(data.kind()).contains("empty");
        assertThat(data.sizeBytes()).contains(0L);
        assertThat(data.text()).isEmpty();
    }

    @Test
    void maskedReplacesConfiguredCredentialsThenSecretNamedValues() {
        assertThat(entryText.masked("user=acme password=" + TestSecrets.CREDENTIAL_PAYLOAD_VALUE
            + " note=" + TestSecrets.CONFIGURED_PASSWORD))
            .isEqualTo("user=acme password=${secret:data.password} note=***");
    }

    @Test
    void resultDoesNotLeakSecrets() {
        String text = "<login><user>acme</user><password>" + TestSecrets.CREDENTIAL_PAYLOAD_VALUE
            + "</password><note>" + TestSecrets.CONFIGURED_PASSWORD + "</note></login>";

        for (int max : new int[] {1, 30, 40, 60, 20_000}) {
            TestSecrets.assertNoLeak(entryText.data(utf8(text), max).toString());
        }
        TestSecrets.assertNoLeak(entryText.masked(text));
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
