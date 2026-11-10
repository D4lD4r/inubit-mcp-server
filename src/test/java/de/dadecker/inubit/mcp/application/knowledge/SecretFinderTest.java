package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.application.knowledge.SecretFinder.Finding;
import de.dadecker.inubit.mcp.application.knowledge.SecretFinder.Kind;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 019 (T012, T013, FR-010, research D-6, D-7): the secret rules on free text.
 */
class SecretFinderTest {

    private static final String VALUE = "s3cr3t-Value-42";

    private final SecretFinder finder = new SecretFinder(FakeConfiguredSecrets.nothingConfigured());

    private String masked(String text) {
        return finder.mask(text).text();
    }

    private static byte[] jks() {
        byte[] content = new byte[96];
        content[0] = (byte) 0xfe;
        content[1] = (byte) 0xed;
        content[2] = (byte) 0xfe;
        content[3] = (byte) 0xed;
        Arrays.fill(content, 4, content.length, (byte) 7);
        return content;
    }

    private static byte[] pkcs12() throws KeyStoreException, IOException,
        NoSuchAlgorithmException, CertificateException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.store(out, "changeit".toCharArray());
        return out.toByteArray();
    }

    // (a) configured credentials

    @Test
    void aConfiguredCredentialIsFoundInEveryPosition() {
        SecretFinder configured = new SecretFinder(FakeConfiguredSecrets.held("Hunter2pw"));
        String text = "Login with Hunter2pw.\nurl=https://u:Hunter2pw@host\nxHunter2pwx\n";

        assertThat(configured.mask(text)).isEqualTo(new Masked(
            "Login with ***.\nurl=https://u:***@host\nx***x\n", 3));
        assertThat(configured.find(text)).extracting(Finding::kind, Finding::line, Finding::key)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(Kind.CONFIGURED_CREDENTIAL, 1,
                    Optional.empty()),
                org.assertj.core.groups.Tuple.tuple(Kind.CONFIGURED_CREDENTIAL, 2,
                    Optional.empty()),
                org.assertj.core.groups.Tuple.tuple(Kind.CONFIGURED_CREDENTIAL, 3,
                    Optional.empty()));
    }

    @Test
    void aConfiguredCredentialAndAnAssignmentOfItAreMaskedOnce() {
        SecretFinder configured = new SecretFinder(FakeConfiguredSecrets.held("Hunter2pw"));

        assertThat(configured.find("password: Hunter2pw")).extracting(Finding::kind)
            .containsExactlyInAnyOrder(Kind.CONFIGURED_CREDENTIAL, Kind.ASSIGNMENT);
        assertThat(configured.mask("password: Hunter2pw"))
            .isEqualTo(new Masked("password: ***", 1));
    }

    @Test
    void theFinderMasksWhatIsKnownAndLeavesReadinessToTheCaller() {
        FakeConfiguredSecrets secrets = FakeConfiguredSecrets.waiting("node1", "credential store");
        SecretFinder waiting = new SecretFinder(secrets);

        assertThat(waiting.find("Hunter2pw")).isEmpty();
        secrets.hold("Hunter2pw");
        assertThat(waiting.mask("Hunter2pw")).isEqualTo(new Masked("***", 1));
        assertThat(secrets.prepared()).isEmpty();
    }

    // (d) assignments, one line

    @ParameterizedTest
    @ValueSource(strings = {
        "password: " + VALUE,
        "password=" + VALUE,
        "db.Password = " + VALUE,
        "  - smtp_passwd: " + VALUE,
        "* API-Token: " + VALUE,
        "client_secret:" + VALUE,
        "PWD=" + VALUE,
        "passphrase = " + VALUE,
        "credentials: " + VALUE,
        "x-apikey: " + VALUE,
        "\"password\": \"" + VALUE + "\",",
        "'token' = '" + VALUE + "'",
        "<Connection user=\"a\" password=\"" + VALUE + "\" timeout=\"5\"/>",
        "password: \"" + VALUE + "\" # quoted",
        "Passwort-Hinweis: — kein Schlüssel; password=" + VALUE,
    })
    void anAssignmentMasksTheValueOnly(String line) {
        String text = "intro\n" + line + "\nnext line\n";

        String result = masked(text);

        assertThat(result).doesNotContain(VALUE).contains("***").startsWith("intro\n")
            .endsWith("\nnext line\n");
        assertThat(finder.find(text)).isNotEmpty().allSatisfy(finding -> {
            assertThat(finding.kind()).isEqualTo(Kind.ASSIGNMENT);
            assertThat(finding.line()).isEqualTo(2);
            assertThat(finding.key()).isPresent();
        });
    }

    @Test
    void theKeyStaysReadableAndIsNamedByTheFinding() {
        String text = "user: admin\n\"db.Password\": \"" + VALUE + "\"\n";

        assertThat(masked(text)).isEqualTo("user: admin\n\"db.Password\": \"***\"\n");
        assertThat(finder.find(text)).singleElement().satisfies(finding -> {
            assertThat(finding.key()).contains("db.Password");
            assertThat(text.substring(finding.start(), finding.end())).isEqualTo(VALUE);
        });
    }

    @Test
    void anUnquotedValueRunsToTheEndOfTheLine() {
        assertThat(masked("token = abc def ghi  \r\nrest")).isEqualTo("token = ***  \r\nrest");
    }

    @Test
    void aQuotedValueEndsAtTheMatchingUnescapedQuote() {
        assertThat(masked("{\"token\": \"a\\\"b\", \"user\": \"u\"}"))
            .isEqualTo("{\"token\": \"***\", \"user\": \"u\"}");
        assertThat(masked("token: 'open to the end")).isEqualTo("token: '***");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password:", "password = ", "password: ***", "password: \"***\"",
        "password: ${secret:db.password}", "password=\"${secret:node1.login}\"",
        "password: \"\"", "username: admin", "url: https://host:8443/x"})
    void emptyMaskedPlaceholderAndOtherKeysAreExempt(String line) {
        assertThat(finder.find(line + "\n")).isEmpty();
        assertThat(finder.mask(line + "\n")).isEqualTo(new Masked(line + "\n", 0));
    }

    // (d) YAML block scalars

    @ParameterizedTest
    @ValueSource(strings = {"|", ">", "|-", ">+2", "|2-"})
    void aYamlBlockScalarIsOneSpan(String indicator) {
        String text = "db:\n  password: " + indicator + "\n    line-one-" + VALUE + "\n\n"
            + "    line-two\n  user: admin\n";

        String result = masked(text);

        assertThat(result).isEqualTo("db:\n  password: " + indicator + "\n    ***\n"
            + "  user: admin\n");
        assertThat(finder.find(text)).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(Kind.ASSIGNMENT);
            assertThat(finding.line()).isEqualTo(3);
        });
    }

    @Test
    void anEmptyYamlBlockIsExempt() {
        assertThat(finder.find("password: |\nuser: admin\n")).isEmpty();
        assertThat(finder.find("password: >-\n\n   \nuser: admin\n")).isEmpty();
    }

    // (d) XML elements

    @Test
    void anElementNamedByAKeyWordIsMaskedAcrossLines() {
        String text = "<config>\n  <dbPassword>\n    " + VALUE + "\n  </dbPassword>\n"
            + "  <user>admin</user>\n</config>\n";

        assertThat(masked(text)).isEqualTo("<config>\n  <dbPassword>\n    ***\n  </dbPassword>\n"
            + "  <user>admin</user>\n</config>\n");
        assertThat(finder.find(text)).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(Kind.ASSIGNMENT);
            assertThat(finding.key()).contains("dbPassword");
            assertThat(finding.line()).isEqualTo(3);
        });
    }

    @Test
    void anElementWithANameAttributeNamingAKeyWordIsMasked() {
        String text = "<Property name=\"Login.Password\" kind=\"x\">" + VALUE + "</Property>\n"
            + "<Property name='Login.User'>admin</Property>\n";

        assertThat(masked(text)).isEqualTo("<Property name=\"Login.Password\" kind=\"x\">***"
            + "</Property>\n<Property name='Login.User'>admin</Property>\n");
        assertThat(finder.find(text)).singleElement()
            .satisfies(finding -> assertThat(finding.key()).contains("Login.Password"));
    }

    @Test
    void aCdataValueIsMasked() {
        assertThat(masked("<token><![CDATA[" + VALUE + "]]></token>"))
            .isEqualTo("<token>***</token>");
    }

    @Test
    void nestedElementsOfTheSameNameEndAtTheMatchingClosingTag() {
        assertThat(masked("<secret><secret>a</secret>" + VALUE + "</secret>after"))
            .isEqualTo("<secret>***</secret>after");
    }

    @Test
    void aMissingClosingTagMasksToTheEndOfTheText() {
        assertThat(masked("<Property name=\"x.password\">\n" + VALUE + "\nmore\n"))
            .isEqualTo("<Property name=\"x.password\">\n***\n");
    }

    @Test
    void aSelfClosingOrEmptyElementHasNoValue() {
        assertThat(finder.find("<password/>\n<token></token>\n<secret>  </secret>\n"
            + "<pwd>***</pwd>\n<pwd>${secret:a.b}</pwd>\n")).isEmpty();
    }

    // (c) INUBIT encrypted values

    @ParameterizedTest
    @ValueSource(strings = {"AES-U1lOVEgtQUVTLTAwMDAwMDAx",
        "AESGs1-y+U1lOQUVTRzAwMDAwMDE:U1lOVEhJVjE:U1lOVEgtQUVTRy0wMDE==",
        "AES-abcdefghijklmnop"})
    void anEncryptedTokenIsOneSpan(String token) {
        String text = "The value is " + token + ".\n";

        assertThat(masked(text)).isEqualTo("The value is ***.\n");
        assertThat(finder.find(text)).singleElement()
            .satisfies(finding -> {
                assertThat(finding.kind()).isEqualTo(Kind.INUBIT_ENCRYPTED);
                assertThat(finding.key()).isEmpty();
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES-short", "AES-abcdefghijklmno", "xAES-abcdefghijklmnopqrst",
        "AES-256 is a cipher", "AESGCM mode"})
    void shortOrEmbeddedTokensAreNotEncryptedValues(String text) {
        assertThat(finder.find(text)).isEmpty();
    }

    @Test
    void anEncryptedTokenAfterAnEqualsSignIsFound() {
        assertThat(masked("var.login=AES-U1lOVEgtQUVTLTAwMDAwMDAx\n"))
            .isEqualTo("var.login=***\n");
    }

    @Test
    void theContentOfAnEncryptedOrPasswordPropertyIsMasked() {
        String text = "<Property encrypted=\"true\" name=\"var.login\">U1lOMDAwMDA2</Property>\n"
            + "<Property type='Password'>\n  U1lOMDAwMDA2\n</Property>\n"
            + "<Property encrypted=\"false\">plain</Property>\n";

        assertThat(masked(text)).isEqualTo("<Property encrypted=\"true\" name=\"var.login\">***"
            + "</Property>\n<Property type='Password'>\n  ***\n</Property>\n"
            + "<Property encrypted=\"false\">plain</Property>\n");
        assertThat(finder.find(text)).extracting(Finding::kind, Finding::line)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(Kind.INUBIT_ENCRYPTED, 1),
                org.assertj.core.groups.Tuple.tuple(Kind.INUBIT_ENCRYPTED, 3));
    }

    // (b) key material

    @Test
    void aPemPrivateKeyIsOneSpan() {
        String text = "before\nkey: -----BEGIN RSA PRIVATE KEY-----\nMIIEow" + VALUE
            + "\n-----END RSA PRIVATE KEY-----\nafter\n";

        assertThat(masked(text)).isEqualTo("before\nkey: ***\nafter\n");
        assertThat(finder.find(text)).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(Kind.KEY_MATERIAL);
            assertThat(finding.line()).isEqualTo(2);
        });
    }

    @Test
    void aPemPrivateKeyOnOneEscapedLineAndAnUnterminatedOneAreMasked() {
        assertThat(masked("{\"k\": \"-----BEGIN PRIVATE KEY-----\\nMII" + VALUE
            + "\\n-----END PRIVATE KEY-----\\n\"}")).isEqualTo("{\"k\": \"***\\n\"}");
        assertThat(masked("x\n-----BEGIN ENCRYPTED PRIVATE KEY-----\n" + VALUE + "\n"))
            .isEqualTo("x\n***");
    }

    @Test
    void aPublicCertificateIsNotKeyMaterial() {
        assertThat(finder.find("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"))
            .isEmpty();
    }

    @Test
    void aBase64KeystoreInsideALineIsMasked() throws Exception {
        String jks = Base64.getEncoder().encodeToString(jks());
        String p12 = Base64.getEncoder().encodeToString(pkcs12());

        assertThat(masked("store=" + jks + " end")).isEqualTo("store=*** end");
        assertThat(masked("<KeyStore>" + p12 + "</KeyStore>"))
            .isEqualTo("<KeyStore>***</KeyStore>");
        assertThat(finder.find("x " + jks)).singleElement()
            .satisfies(finding -> assertThat(finding.kind()).isEqualTo(Kind.KEY_MATERIAL));
    }

    @Test
    void aMimeWrappedKeystoreIsOneBlock() throws Exception {
        String wrapped = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs12());
        assertThat(wrapped).contains("\n");
        String indented = "    " + wrapped.replace("\n", "\n    ");
        String text = "keystore: |\n" + indented + "\nnext: 1\n";

        assertThat(finder.find(text)).anySatisfy(finding -> {
            assertThat(finding.kind()).isEqualTo(Kind.KEY_MATERIAL);
            assertThat(finding.line()).isEqualTo(2);
            assertThat(text.substring(finding.start(), finding.end())).isEqualTo(indented.strip());
        });
        assertThat(masked(text)).isEqualTo("keystore: |\n    ***\nnext: 1\n");

        String afterKey = "cert: " + wrapped + "\nnext: 1\n";
        assertThat(masked(afterKey)).isEqualTo("cert: ***\nnext: 1\n");
    }

    @Test
    void longBase64ThatIsNoKeystoreStaysReadable() {
        String other = Base64.getEncoder().encodeToString(new byte[90]);

        assertThat(finder.find("data: " + other)).isEmpty();
        assertThat(finder.find("short " + Base64.getEncoder().encodeToString(
            Arrays.copyOf(jks(), 40)))).isEmpty();
    }

    // general

    @Test
    void findingsAreOrderedAndNeverHoldTheValue() {
        String text = "token: " + VALUE + "\nAES-U1lOVEgtQUVTLTAwMDAwMDAx\n<pwd>" + VALUE
            + "</pwd>\n";

        List<Finding> findings = finder.find(text);

        assertThat(findings).extracting(Finding::line).containsExactly(1, 2, 3);
        assertThat(findings).allSatisfy(finding -> assertThat(finding.toString())
            .doesNotContain(VALUE).doesNotContain("U1lOVEgt"));
        assertThat(finder.mask(text)).isEqualTo(new Masked("token: ***\n***\n<pwd>***</pwd>\n",
            3));
    }

    @Test
    void overlappingFindingsAreMaskedOnce() {
        assertThat(finder.mask("password=AES-U1lOVEgtQUVTLTAwMDAwMDAx"))
            .isEqualTo(new Masked("password=***", 1));
    }

    @Test
    void aTextWithoutSecretsIsUnchanged() {
        String text = "# Deployment\n\nRestart the node after a change: `restart`.\n"
            + "| a | b |\n|---|---|\n";

        assertThat(finder.mask(text)).isEqualTo(new Masked(text, 0));
        assertThat(finder.find("")).isEmpty();
    }

    @Test
    void theInputIsBoundedByTheReadBound() {
        String big = "a".repeat(KnowledgeLimits.READ_BYTES + 1);

        assertThatThrownBy(() -> finder.find(big)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("aaaa");
        assertThat(finder.find("a".repeat(KnowledgeLimits.READ_BYTES))).isEmpty();
    }

    @Test
    void repeatedMarkersAndAssignmentsStayLinear() {
        String markers = "-----BEGIN x ".repeat(200_000) + "\nPRIVATE KEY-----";
        String quoted = "password='a' ".repeat(200_000);
        String plain = "password=a ".repeat(200_000);

        long started = System.nanoTime();
        assertThat(finder.find(markers)).isEmpty();
        assertThat(finder.find(quoted)).hasSize(200_000);
        assertThat(finder.find(plain)).singleElement();
        assertThat(System.nanoTime() - started).isLessThan(java.time.Duration.ofSeconds(10)
            .toNanos());
    }

    @Test
    void aFindingHasAValidSpan() {
        assertThatThrownBy(() -> new Finding(Kind.ASSIGNMENT, 3, 3, 1, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Finding(Kind.ASSIGNMENT, 0, 3, 0, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
