package de.dadecker.inubit.mcp.application.nodetools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.application.nodetools.NodeTextMasking.Bounded;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Masking of node details, configuration values and messages (feature 024, D-21): configured
 * credentials of every node, then {@code SecretTextMasker}, then the cut with {@code …[cut]}.
 */
class NodeTextMaskingTest {

    private static final String MARKER = NodeTextMasking.CUT_MARKER;

    /** The scrubber holds the credentials of every configured node, not only the one asked. */
    private final SecretScrubber scrubber = new SecretScrubber();

    {
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
    }

    private final NodeTextMasking masking = new NodeTextMasking(scrubber::scrub);

    @Test
    void cutMarkerIsFixed() {
        assertThat(MARKER).isEqualTo("…[cut]");
    }

    @Test
    void configuredPasswordOfAnotherNodeIsReplaced() {
        assertThat(masking.mask("-Dcluster.peer=node2 -Dpeer.pass="
            + TestSecrets.CONFIGURED_PASSWORD + " -Xmx2g"))
            .isEqualTo("-Dcluster.peer=node2 -Dpeer.pass=*** -Xmx2g");
    }

    @Test
    void authorizationBasicHeaderIsMasked() {
        String basic = Base64.getEncoder().encodeToString(("acme:"
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE).getBytes(StandardCharsets.UTF_8));

        assertThat(masking.mask("Authorization: Basic " + basic))
            .isEqualTo("Authorization: ${secret:authorization}");
    }

    @Test
    void passwordElementIsMasked() {
        assertThat(masking.mask("<db><user>acme</user><password>"
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "</password></db>"))
            .isEqualTo("<db><user>acme</user><password>${secret:data.password}</password></db>");
    }

    @Test
    void jsonSecretValueIsMasked() {
        assertThat(masking.mask("{\"url\": \"jdbc:fake://db\", \"secret\": \""
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "\"}"))
            .isEqualTo("{\"url\": \"jdbc:fake://db\", \"secret\": \"${secret:data.secret}\"}");
    }

    @Test
    void textWithinTheLimitIsReturnedWholeAndNotCut() {
        Bounded shown = masking.maskAndCut("x".repeat(20), 20);

        assertThat(shown.text()).isEqualTo("x".repeat(20));
        assertThat(shown.cut()).isFalse();
    }

    @Test
    void longerTextIsCutToTheLimitIncludingTheMarker() {
        Bounded shown = masking.maskAndCut("x".repeat(2_001), 2_000);

        assertThat(shown.text()).hasSize(2_000)
            .isEqualTo("x".repeat(2_000 - MARKER.length()) + MARKER);
        assertThat(shown.cut()).isTrue();
    }

    @Test
    void secretsAreMaskedBeforeTheCut() {
        String password = TestSecrets.CONFIGURED_PASSWORD;
        String secret = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        // each cut first would leave the start of a secret in the shown text
        String[] texts = {
            "x".repeat(20) + password + "tail".repeat(5),
            "<password>" + secret + "</password>",
            "{\"apiToken\": \"" + secret + "\"}",
            "Authorization: Basic " + Base64.getEncoder().encodeToString(("acme:" + secret)
                .getBytes(StandardCharsets.UTF_8))};

        for (String text : texts) {
            Bounded shown = masking.maskAndCut(text, 30);

            assertThat(shown.cut()).as(text).isTrue();
            assertThat(shown.text()).as(text).hasSizeLessThanOrEqualTo(30).endsWith(MARKER)
                .doesNotContain(password.substring(0, 4), secret.substring(0, 4));
        }
        assertThat(masking.maskAndCut(texts[0], 30).text())
            .isEqualTo("x".repeat(20) + "***t" + MARKER);
    }

    @Test
    void cutNeverSplitsASurrogatePair() {
        int keep = 10 - MARKER.length();
        String text = "x".repeat(keep - 1) + "😀" + "y".repeat(10);

        Bounded shown = masking.maskAndCut(text, 10);

        assertThat(shown.text()).isEqualTo("x".repeat(keep - 1) + MARKER);
        assertThat(shown.cut()).isTrue();
    }

    @Test
    void theLimitMustBeLongerThanTheMarker() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> masking.maskAndCut("text", MARKER.length()));
        assertThat(masking.maskAndCut("x".repeat(20), MARKER.length() + 1).text())
            .isEqualTo("x" + MARKER);
    }

    @Test
    void resultDoesNotLeakSecrets() {
        String text = "<login><password>" + TestSecrets.CREDENTIAL_PAYLOAD_VALUE
            + "</password><note>" + TestSecrets.CONFIGURED_PASSWORD + "</note></login>";

        TestSecrets.assertNoLeak(masking.mask(text));
        for (int max : new int[] {MARKER.length() + 1, 25, 40, 2_000}) {
            TestSecrets.assertNoLeak(masking.maskAndCut(text, max).text());
        }
    }
}
