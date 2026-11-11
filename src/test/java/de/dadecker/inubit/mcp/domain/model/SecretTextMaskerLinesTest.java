package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 021 (T013, FR-010, research D-11): {@code maskLines} masks rules 1–7 and the known
 * credential spans (rule 0) and keeps every line break of the window.
 */
class SecretTextMaskerLinesTest {

    private static final Path EXCERPT = Path.of("src/test/resources/fixtures/server-log/excerpt");
    private static final String SECRET = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
    private static final String PASSWORD = TestSecrets.CONFIGURED_PASSWORD;
    private static final String AUTHORIZATION = "${secret:authorization}";

    // one test per rule

    @Test
    void rule1MasksAnElementValueOverItsLines() {
        String window = "a\n<pwd>" + SECRET + "\n" + SECRET + "\n" + SECRET + "</pwd> b\nc";

        assertThat(SecretTextMasker.maskLines(window, List.of()))
            .isEqualTo("a\n<pwd>${secret:data.pwd}\n\n</pwd> b\nc");
    }

    @Test
    void rule2MasksAnAttributeValueOverItsLines() {
        String window = "<login user=\"acme\" password=\"" + SECRET + "\n" + SECRET
            + "\" mode=\"basic\"/>\nnext";

        assertThat(SecretTextMasker.maskLines(window, List.of()))
            .isEqualTo("<login user=\"acme\" password=\"${secret:data.password}\n"
                + "\" mode=\"basic\"/>\nnext");
    }

    @Test
    void rule3MasksAPrettyPrintedJsonObjectOverItsLines() {
        String window = "{\"credentials\": {\n  \"user\": \"acme\",\n  \"pwd\": \"" + SECRET
            + "\"\n}, \"mode\": 1}\nnext";

        assertThat(SecretTextMasker.maskLines(window, List.of()))
            .isEqualTo("{\"credentials\": \"${secret:data.credentials}\"\n\n\n, \"mode\": 1}\nnext");
    }

    @Test
    void rule4MasksAnAuthorizationHeaderUpToTheLineEnd() {
        String window = "GET /orders\nAuthorization: Digest " + SECRET + "\nAccept: */*";

        assertThat(SecretTextMasker.maskLines(window, List.of()))
            .isEqualTo("GET /orders\nAuthorization: " + AUTHORIZATION + "\nAccept: */*");
    }

    @Test
    void rule5MasksASecretHeaderLineUpToTheLineEnd() {
        String window = "GET /orders\nX-Api-Key: " + SECRET + "\nAccept: */*";

        assertThat(SecretTextMasker.maskLines(window, List.of()))
            .isEqualTo("GET /orders\nX-Api-Key: ${secret:data.X-Api-Key}\nAccept: */*");
    }

    @Test
    void rule6MasksANameValuePairUpToItsDelimiter() {
        String window = "GET /orders?token=" + SECRET + "&user=acme\nnext";

        assertThat(SecretTextMasker.maskLines(window, List.of()))
            .isEqualTo("GET /orders?token=${secret:data.token}&user=acme\nnext");
        assertThat(SecretTextMasker.maskLines("db_password='" + SECRET + "\nnext", List.of()))
            .isEqualTo("db_password='${secret:data.db_password}\nnext");
    }

    @Test
    void rule7MasksBasicAndBearerCredentials() {
        String basic = Base64.getEncoder()
            .encodeToString(("acme:" + SECRET).getBytes(StandardCharsets.UTF_8));
        String window = "retry with Basic " + basic + " after 401\nforward Bearer\n" + SECRET
            + " to globex";

        assertThat(SecretTextMasker.maskLines(window, List.of()))
            .isEqualTo("retry with Basic " + AUTHORIZATION + " after 401\nforward Bearer\n"
                + AUTHORIZATION + " to globex");
    }

    @Test
    void withoutLineBreaksInAMaskedValueBothEntryPointsAgree() throws IOException {
        String window = Files.readString(EXCERPT.resolve("secrets_everywhere.log"));

        assertThat(SecretTextMasker.maskLines(window, List.of()))
            .isEqualTo(SecretTextMasker.mask(window));
    }

    // rule 0: known credentials

    /** US1-3: a configured password of the profile is masked. */
    @Test
    void aKnownSpanIsMaskedByTheMaskToken() {
        String window = "login of acme with " + PASSWORD + " refused\nnext";

        assertThat(SecretTextMasker.maskLines(window, spans(window, PASSWORD)))
            .isEqualTo("login of acme with *** refused\nnext");
    }

    @Test
    void aKnownSpanWinsATieByItsLowerRuleNumber() {
        String window = "GET /orders?token=" + PASSWORD + "&user=acme";

        assertThat(SecretTextMasker.maskLines(window, spans(window, PASSWORD)))
            .isEqualTo("GET /orders?token=***&user=acme");
    }

    @Test
    void aKnownSpanInsideALongerRuleRangeIsMergedIntoIt() {
        String window = "<pwd>a " + PASSWORD + " b</pwd>";

        assertThat(SecretTextMasker.maskLines(window, spans(window, PASSWORD)))
            .isEqualTo("<pwd>${secret:data.pwd}</pwd>");
    }

    @Test
    void aKnownSpanStartingEarlierAbsorbsAnOverlappingRuleRange() {
        String window = "x" + PASSWORD + "\n<pwd>" + SECRET + "</pwd>";
        int start = window.indexOf(PASSWORD);
        int end = window.indexOf(SECRET) + 3;

        assertThat(SecretTextMasker.maskLines(window, List.of(new SecretSpan(start, end))))
            .isEqualTo("x***\n</pwd>");
    }

    // fixtures

    /** US1-8. */
    @Test
    void aPasswordElementOverLines10To12KeepsLine13() throws IOException {
        String window = Files.readString(EXCERPT.resolve("secrets_multiline.log"));
        List<String> in = lines(window);

        List<String> out = lines(SecretTextMasker.maskLines(window, List.of()));

        assertThat(out.get(9))
            .isEqualTo("<login><user>acme</user><password>${secret:data.password}");
        assertThat(out.get(10)).isEmpty();
        assertThat(out.get(11)).isEqualTo("</password><mode>basic</mode></login>");
        assertThat(out.get(12)).isEqualTo(in.get(12))
            .isEqualTo("2026-11-02 08:15:01,113 INFO  [acme.http.Client] node1 login request sent");
        assertThat(out.subList(0, 9)).isEqualTo(in.subList(0, 9));
        assertThat(out.subList(12, out.size())).isEqualTo(in.subList(12, in.size()));
        TestSecrets.assertNoLeak(String.join("\n", out));
    }

    /** Rules 1–7 and the profile password (US1-3) in one log. */
    @Test
    void everyRuleOnceInTheLogIsMasked() throws IOException {
        String window = Files.readString(EXCERPT.resolve("secrets_everywhere.log"));

        String masked = SecretTextMasker.maskLines(window, spans(window, PASSWORD));

        assertThat(masked).doesNotContain("-secret-0001", "YWNtZTphdXRob3JpemF0aW9uLXNlY3JldC0wMDAx",
            "YWNtZTpiYXNpYy1zZWNyZXQtMDAwMQ==", PASSWORD);
        assertThat(lines(masked)).containsSubsequence(
            "2026-11-02 09:00:00,001 DEBUG [acme.soap.Client] node1 envelope"
                + " <wsse:Password>${secret:data.Password}</wsse:Password> built",
            "2026-11-02 09:00:00,002 DEBUG [acme.db.Pool] node1 config"
                + " <connection user=\"acme\" password=\"${secret:data.password}\"/>",
            "2026-11-02 09:00:00,003 DEBUG [acme.rest.Client] node1 payload"
                + " {\"user\": \"acme\", \"apiKey\": \"${secret:data.apiKey}\"}",
            "Authorization: " + AUTHORIZATION,
            "X-Api-Key: ${secret:data.X-Api-Key}",
            "Accept: application/xml",
            "2026-11-02 09:00:00,005 DEBUG [acme.http.Client] node1 GET"
                + " https://globex.example.test/orders?token=${secret:data.token}&user=acme",
            "2026-11-02 09:00:00,006 WARN  [acme.http.Client] node1 retrying with Basic "
                + AUTHORIZATION + " after 401",
            "2026-11-02 09:00:00,007 DEBUG [acme.http.Client] node1 forwarding Bearer "
                + AUTHORIZATION + " to globex.example.test",
            "2026-11-02 09:00:00,008 ERROR [acme.auth.Login] node1 login of acme with *** refused");
    }

    @Test
    void crlfLineEndsArePreserved() throws IOException {
        String window = Files.readString(EXCERPT.resolve("crlf.log"));

        String masked = SecretTextMasker.maskLines(window, List.of());

        assertThat(masked).isEqualTo(String.join("\r\n",
            "2026-11-02 10:00:00,001 INFO  [acme.http.Server] node1 request received",
            "Authorization: " + AUTHORIZATION,
            "<password>${secret:data.password}",
            "",
            "</password> done",
            "2026-11-02 10:00:00,005 INFO  [acme.http.Server] node1 request finished",
            ""));
        assertThat(masked.replace("\r\n", "")).doesNotContain("\r", "\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"secrets_multiline.log", "secrets_everywhere.log", "crlf.log"})
    void theLineCountOfEveryFixtureIsKept(String fixture) throws IOException {
        String window = Files.readString(EXCERPT.resolve(fixture));

        String masked = SecretTextMasker.maskLines(window, spans(window, PASSWORD));

        assertThat(masked).isNotEqualTo(window);
        assertThat(lines(masked)).hasSameSizeAs(lines(window));
        assertThat(terminators(masked)).isEqualTo(terminators(window));
    }

    @Test
    void aRegionKeepsEveryCarriageReturnAndLineFeedInOrder() {
        String window = "<pwd>a\r\nb\rc\nd\n\re</pwd>";

        assertThat(SecretTextMasker.maskLines(window, List.of()))
            .isEqualTo("<pwd>${secret:data.pwd}\r\n\r\n\n\r</pwd>");
    }

    @Test
    void aWindowWithoutSecretsIsUnchanged() {
        String window = "2026-11-02 08:15:01,101 INFO node1 started\r\n\nnext\r";

        assertThat(SecretTextMasker.maskLines(window, List.of())).isEqualTo(window);
    }

    /** Every occurrence of {@code value} in {@code text}, as the profile's known credentials. */
    private static List<SecretSpan> spans(String text, String value) {
        List<SecretSpan> spans = new ArrayList<>();
        for (int i = text.indexOf(value); i >= 0; i = text.indexOf(value, i + 1)) {
            spans.add(new SecretSpan(i, i + value.length()));
        }
        return spans;
    }

    /** The lines of {@code text} split at CRLF, CR or LF, a trailing empty line included. */
    private static List<String> lines(String text) {
        return List.of(text.split("\r\n|\r|\n", -1));
    }

    /** Only the {@code \r} and {@code \n} of {@code text}, in order. */
    private static String terminators(String text) {
        return text.replaceAll("[^\r\n]", "");
    }
}
