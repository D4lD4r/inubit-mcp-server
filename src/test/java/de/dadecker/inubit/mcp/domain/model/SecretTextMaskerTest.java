package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Feature 020 (T010, FR-011, D-11): term test, placeholder and masking rules 1–3. */
class SecretTextMaskerTest {

    private static final String SECRET = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
    /** Generous for linear work on ~1–10 MiB; quadratic run time takes minutes to hours. */
    private static final Duration LINEAR = Duration.ofSeconds(10);
    private static final int MIB = 1024 * 1024;
    /** 1 MiB of Base64 as in an attachment. */
    private static final String BASE64 = "QWNtZS0wMDAx+/9z".repeat(MIB / 16);
    /** 1 MiB of Base64 in a JSON string, with escaped line breaks every 64 characters. */
    private static final String BASE64_LINES =
        ("QWNtZS0wMDAx+/9z".repeat(4) + "\\r\\n").repeat(MIB / 68 + 1);
    /** About 1 MiB of neutral attributes after the name of a start tag. */
    private static final String ATTRIBUTE_LIST =
        " n=\"Order-0001\" m='Module-0001' x".repeat(MIB / 33 + 1);

    // term test

    @ParameterizedTest
    @ValueSource(strings = {"password", "passwd", "pwd", "secret", "token", "apikey", "api_key",
        "credential", "authorization", "private", "keystore", "cookie"})
    void everyTermMakesASecretName(String term) {
        assertThat(SecretTextMasker.secretName(term)).isTrue();
        assertThat(SecretTextMasker.secretName("x" + term.toUpperCase() + "Value")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "name", "pass", "passport", "key", "api", "auth", "wsse:User",
        "password:user", ""})
    void otherNamesAreNotSecret(String name) {
        assertThat(SecretTextMasker.secretName(name)).isFalse();
    }

    @Test
    void theTermTestUsesTheLocalNameAfterTheLastColon() {
        assertThat(SecretTextMasker.secretName("wsse:Password")).isTrue();
        assertThat(SecretTextMasker.secretName("a:b:apiToken")).isTrue();
        assertThat(SecretTextMasker.secretName("secret:user")).isFalse();
    }

    // placeholder

    @Test
    void thePlaceholderCarriesScopeAndName() {
        assertThat(SecretTextMasker.placeholder("data", "Password"))
            .isEqualTo("${secret:data.Password}");
        assertThat(SecretTextMasker.placeholder("variables", "api_key-1.x"))
            .isEqualTo("${secret:variables.api_key-1.x}");
    }

    @Test
    void thePlaceholderReplacesCharactersOutsideTheNameSetSoNothingCanBeInjected() {
        assertThat(SecretTextMasker.placeholder("data", "pass${x}word"))
            .isEqualTo("${secret:data.pass__x_word}");
        assertThat(SecretTextMasker.placeholder("data", "a b/c\u00e4\uD83D\uDE00"))
            .isEqualTo("${secret:data.a_b_c__}");
    }

    @Test
    void thePlaceholderCutsTheNameAt64Characters() {
        String name = "token" + "x".repeat(100);

        assertThat(SecretTextMasker.placeholder("data", name))
            .isEqualTo("${secret:data." + name.substring(0, 64) + "}");
    }

    // rule 1: XML element

    @Test
    void rule1MasksThePrefixedWsseElementByItsLocalName() {
        String text = "<wsse:Security><wsse:Username>acme</wsse:Username>"
            + "<wsse:Password Type=\"PasswordText\">" + SECRET + "</wsse:Password></wsse:Security>";

        String masked = SecretTextMasker.mask(text);

        assertThat(masked).isEqualTo("<wsse:Security><wsse:Username>acme</wsse:Username>"
            + "<wsse:Password Type=\"PasswordText\">${secret:data.Password}</wsse:Password>"
            + "</wsse:Security>");
        TestSecrets.assertNoLeak(masked);
    }

    @Test
    void rule1MasksTheWholeContentIncludingChildElements() {
        assertThat(SecretTextMasker.mask("<a:apiToken><x>1</x></a:apiToken><y>2</y>"))
            .isEqualTo("<a:apiToken>${secret:data.apiToken}</a:apiToken><y>2</y>");
    }

    @Test
    void rule1CountsNestedStartTagsOfTheSameName() {
        assertThat(SecretTextMasker.mask("<token><token>a</token>b</token>c"))
            .isEqualTo("<token>${secret:data.token}</token>c");
    }

    @Test
    void rule1IncludesCdataEvenIfItContainsAnEndTag() {
        String text = "<password><![CDATA[a</password>" + SECRET + "]]></password>tail";

        String masked = SecretTextMasker.mask(text);

        assertThat(masked).isEqualTo("<password>${secret:data.password}</password>tail");
        TestSecrets.assertNoLeak(masked);
    }

    @Test
    void rule1WithoutAnEndTagMasksToTheNextTagOrTheEndOfTheText() {
        assertThat(SecretTextMasker.mask("<order>Order-0001</order><password>" + SECRET))
            .isEqualTo("<order>Order-0001</order><password>${secret:data.password}");
        assertThat(SecretTextMasker.mask("<password>" + SECRET + "<next/>rest"))
            .isEqualTo("<password>${secret:data.password}<next/>rest");
    }

    @Test
    void rule1LeavesSelfClosingAndEmptyElementsAlone() {
        assertThat(SecretTextMasker.mask("<password/><password /><secret a=\"1\"/>"))
            .isEqualTo("<password/><password /><secret a=\"1\"/>");
        assertThat(SecretTextMasker.mask("<password></password>"))
            .isEqualTo("<password></password>");
    }

    @Test
    void rule1MasksSecretElementsOfADocumentEmbeddedInCdata() {
        assertThat(SecretTextMasker.mask(
            "<payload><![CDATA[<password>" + SECRET + "</password>]]></payload>"))
            .isEqualTo("<payload><![CDATA[<password>${secret:data.password}</password>]]>"
                + "</payload>");
        assertThat(SecretTextMasker.mask("<payload><![CDATA[<password>" + SECRET + "]]></payload>"))
            .isEqualTo("<payload><![CDATA[<password>${secret:data.password}]]></payload>");
    }

    @Test
    void rule1NeedsTheExactQualifiedNameInTheEndTag() {
        assertThat(SecretTextMasker.mask("<a:token>x</b:token>y</a:token >z"))
            .isEqualTo("<a:token>${secret:data.token}</a:token >z");
    }

    @Test
    void rule1LeavesOtherElementsUnchanged() {
        String text = "<?xml version=\"1.0\"?>\n<order id=\"Order-0001\">\n  <user>acme</user>\n"
            + "</order>\n";

        assertThat(SecretTextMasker.mask(text)).isEqualTo(text);
    }

    // rule 2: XML attribute

    @Test
    void rule2MasksASecretAttributeBetweenItsQuotes() {
        String text = "<login user=\"acme\" password=\"" + SECRET + "\" host='globex'/>";

        String masked = SecretTextMasker.mask(text);

        assertThat(masked).isEqualTo(
            "<login user=\"acme\" password=\"${secret:data.password}\" host='globex'/>");
        TestSecrets.assertNoLeak(masked);
    }

    @Test
    void rule2HandlesSingleQuotesPrefixesAndSpacesAroundTheEqualsSign() {
        assertThat(SecretTextMasker.mask("<a ns:apiKey = 'k1' b=\"2\">text</a>"))
            .isEqualTo("<a ns:apiKey = '${secret:data.apiKey}' b=\"2\">text</a>");
    }

    @Test
    void rule2LeavesNeutralAttributesAndEmptyValuesAlone() {
        String text = "<a name=\"password\" token=\"\">x</a>";

        assertThat(SecretTextMasker.mask(text)).isEqualTo(text);
    }

    // rule 3: JSON key

    @Test
    void rule3MasksAStringInsideItsQuotes() {
        String text = "{\"user\": \"acme\", \"password\": \"" + SECRET + "\"}";

        String masked = SecretTextMasker.mask(text);

        assertThat(masked)
            .isEqualTo("{\"user\": \"acme\", \"password\": \"${secret:data.password}\"}");
        TestSecrets.assertNoLeak(masked);
    }

    @Test
    void rule3RespectsEscapesInTheString() {
        assertThat(SecretTextMasker.mask("{\"token\":\"a\\\"b\\\\\",\"n\":1}"))
            .isEqualTo("{\"token\":\"${secret:data.token}\",\"n\":1}");
    }

    @Test
    void rule3ReplacesAnObjectOrArrayAsAWhole() {
        assertThat(SecretTextMasker.mask("{\"credentials\": {\"u\":1}, \"n\": 2}"))
            .isEqualTo("{\"credentials\": \"${secret:data.credentials}\", \"n\": 2}");
        assertThat(SecretTextMasker.mask("{\"tokens\":[\"a\",{\"b\":\"]}\"}],\"n\":2}"))
            .isEqualTo("{\"tokens\":\"${secret:data.tokens}\",\"n\":2}");
    }

    @Test
    void rule3MasksAScalarUpToTheNextDelimiter() {
        assertThat(SecretTextMasker.mask("{\"pwd\":1234,\"apiKey\": true}"))
            .isEqualTo("{\"pwd\":${secret:data.pwd},\"apiKey\": ${secret:data.apiKey}}");
        assertThat(SecretTextMasker.mask("[{\"secret\":abc] \"cookie\": x y"))
            .isEqualTo("[{\"secret\":${secret:data.secret}] \"cookie\": ${secret:data.cookie} y");
    }

    @Test
    void rule3MasksToTheEndOfBrokenJson() {
        assertThat(SecretTextMasker.mask("{\"password\": \"" + SECRET))
            .isEqualTo("{\"password\": \"${secret:data.password}");
        assertThat(SecretTextMasker.mask("{\"token\": {\"a\": [1, \"" + SECRET))
            .isEqualTo("{\"token\": \"${secret:data.token}\"");
    }

    @Test
    void rule3UsesTheLocalNameOfAPrefixedKey() {
        assertThat(SecretTextMasker.mask("{\"ns:secret\":\"s\"}"))
            .isEqualTo("{\"ns:secret\":\"${secret:data.secret}\"}");
    }

    @Test
    void rule3LeavesNeutralKeysAndSecretWordsInValuesAlone() {
        String text = "{\"name\": \"password\", \"note\": \"token: x\", \"list\": [\"secret\"]}";

        assertThat(SecretTextMasker.mask(text)).isEqualTo(text);
    }

    // all rules together

    @Test
    void aSecretValueInsideAnotherMaskedValueIsMaskedOnce() {
        assertThat(SecretTextMasker.mask("<password><x token=\"t\"/>{\"secret\":\"s\"}</password>"))
            .isEqualTo("<password>${secret:data.password}</password>");
        assertThat(SecretTextMasker.mask("{\"secret\": {\"password\": \"p\"}}"))
            .isEqualTo("{\"secret\": \"${secret:data.secret}\"}");
    }

    @Test
    void aJsonValueReachingPastAnUnclosedElementExtendsItsMask() {
        String masked = SecretTextMasker.mask("<token>a {\"password\":\"abc<" + SECRET + "\"}");

        assertThat(masked).isEqualTo("<token>${secret:data.token}\"}");
        TestSecrets.assertNoLeak(masked);
    }

    @Test
    void aJsonObjectReachingPastAnUnclosedElementExtendsItsMask() {
        String masked = SecretTextMasker.mask(
            "<token>a {\"secret\": {\"x\":\"<\", \"pwd\":\"" + SECRET + "\"}}");

        assertThat(masked).isEqualTo("<token>${secret:data.token}}");
        TestSecrets.assertNoLeak(masked);
    }

    @Test
    void crossingSecretElementsAreMaskedAsOneRange() {
        String masked = SecretTextMasker.mask(
            "<password><token>x</password>" + SECRET + "</token>tail");

        assertThat(masked).isEqualTo("<password>${secret:data.password}</token>tail");
        TestSecrets.assertNoLeak(masked);
    }

    @Test
    void aSecretKeyInsideAMaskedJsonValueStillExtendsTheMaskInBrokenJson() {
        String masked = SecretTextMasker.mask(
            "{\"token\": {\"a\":\"x\"pwd\":\"abc}" + SECRET + "\"}");

        assertThat(masked).isEqualTo("{\"token\": \"${secret:data.token}\"\"}");
        TestSecrets.assertNoLeak(masked);
    }

    @Test
    void aSecretKeyInsideAMaskedJsonScalarStillExtendsTheMaskInBrokenJson() {
        String masked = SecretTextMasker.mask("{\"token\":a\"pwd\":\"abc " + SECRET + "\"}");

        assertThat(masked).isEqualTo("{\"token\":${secret:data.token}\"}");
        TestSecrets.assertNoLeak(masked);
    }

    @Test
    void theRestOfTheTextStaysIdentical() {
        String text = "line 1\r\n<order>Order-0001</order>\t<secret>s</secret>\n"
            + "{\"module\": \"Module-0001\", \"token\": \"t\"}\n\u00e4\u00f6\u00fc \uD83D\uDE00\n";

        assertThat(SecretTextMasker.mask(text)).isEqualTo("line 1\r\n<order>Order-0001</order>\t"
            + "<secret>${secret:data.secret}</secret>\n"
            + "{\"module\": \"Module-0001\", \"token\": \"${secret:data.token}\"}\n"
            + "\u00e4\u00f6\u00fc \uD83D\uDE00\n");
    }

    @Test
    void textWithoutSecretsIsReturnedUnchanged() {
        assertThat(SecretTextMasker.mask("")).isEmpty();
        assertThat(SecretTextMasker.mask("plain < text > with \" quotes ' and {braces}"))
            .isEqualTo("plain < text > with \" quotes ' and {braces}");
    }

    // run time stays linear on broken text up to the 10 MiB decode bound (D-11)

    @Test
    void manyUnclosedSecretStartTagsAreMaskedInLinearTime() {
        int count = 100_000;

        String masked = assertTimeoutPreemptively(LINEAR,
            () -> SecretTextMasker.mask("<token>abc ".repeat(count)));

        assertThat(masked).isEqualTo("<token>${secret:data.token}".repeat(count));
    }

    @Test
    void manyNestedUnclosedSecretJsonObjectsAreMaskedInLinearTime() {
        String masked = assertTimeoutPreemptively(LINEAR,
            () -> SecretTextMasker.mask("{\"token\":".repeat(100_000) + "1"));

        assertThat(masked).isEqualTo("{\"token\":\"${secret:data.token}\"");
    }

    @Test
    void manyUndelimitedSecretJsonScalarsAreMaskedInLinearTime() {
        String masked = assertTimeoutPreemptively(LINEAR,
            () -> SecretTextMasker.mask("\"token\":a".repeat(100_000)));

        assertThat(masked).isEqualTo("\"token\":${secret:data.token}");
    }

    @Test
    void manyNestedClosedSecretJsonObjectsAreMaskedInLinearTime() {
        int count = 100_000;

        String masked = assertTimeoutPreemptively(LINEAR, () -> SecretTextMasker.mask(
            "{\"token\":".repeat(count) + "1" + "}".repeat(count)));

        assertThat(masked).isEqualTo("{\"token\":\"${secret:data.token}\"}");
    }

    @Test
    void tenMebibytesOfClosedSecretElementsAreMaskedQuickly() {
        int count = 10 * 1024 * 1024 / 21;

        String masked = assertTimeoutPreemptively(LINEAR,
            () -> SecretTextMasker.mask("<token>abcdef</token>".repeat(count)));

        assertThat(masked).isEqualTo("<token>${secret:data.token}</token>".repeat(count));
    }

    // values and attribute lists of 1 MiB (Base64 attachments): no stack overflow, linear time

    @Test
    void aMebibyteJsonStringUnderANeutralKeyIsKeptAndTheSecretNextToItMasked() {
        assertThat(maskInTime("{\"file\":\"" + BASE64_LINES + "\",\"pwd\":\"" + SECRET + "\"}"))
            .isEqualTo("{\"file\":\"" + BASE64_LINES + "\",\"pwd\":\"${secret:data.pwd}\"}");
        assertThat(maskInTime("{\"file\":\"" + BASE64)).isEqualTo("{\"file\":\"" + BASE64);
    }

    @Test
    void aMebibyteJsonStringUnderASecretKeyIsMasked() {
        assertThat(maskInTime("{\"token\":\"" + BASE64_LINES + "\",\"n\":1}"))
            .isEqualTo("{\"token\":\"${secret:data.token}\",\"n\":1}");
        assertThat(maskInTime("{\"token\":\"" + BASE64))
            .isEqualTo("{\"token\":\"${secret:data.token}");
    }

    @Test
    void aMebibyteAttributeValueIsKeptAndTheSecretsAroundItMasked() {
        String value = "<doc data=\"" + BASE64 + "\"";

        assertThat(maskInTime(value + " pwd=\"" + SECRET + "\">body</doc>"))
            .isEqualTo(value + " pwd=\"${secret:data.pwd}\">body</doc>");
        assertThat(maskInTime(value + " pwd=\"" + SECRET + "\">body"))
            .isEqualTo(value + " pwd=\"${secret:data.pwd}\">body");
        assertThat(maskInTime("<token data=\"" + BASE64 + "\">" + SECRET + "</token>"))
            .isEqualTo("<token data=\"" + BASE64 + "\">${secret:data.token}</token>");
        assertThat(maskInTime("<token data=\"" + BASE64 + "\">" + SECRET))
            .isEqualTo("<token data=\"" + BASE64 + "\">${secret:data.token}");
    }

    @Test
    void aMebibyteAttributeValueInACutOffStartTagIsKept() {
        String value = "<doc data=\"" + BASE64;

        assertThat(maskInTime(value)).isEqualTo(value);
        assertThat(maskInTime(value + "\" <password>" + SECRET + "</password>"))
            .isEqualTo(value + "\" <password>${secret:data.password}</password>");
    }

    @Test
    void aMebibyteAttributeListIsKeptAndTheSecretsAroundItMasked() {
        assertThat(maskInTime("<doc" + ATTRIBUTE_LIST + " pwd='" + SECRET + "'>body</doc>"))
            .isEqualTo("<doc" + ATTRIBUTE_LIST + " pwd='${secret:data.pwd}'>body</doc>");
        assertThat(maskInTime("<doc" + ATTRIBUTE_LIST + " pwd='" + SECRET + "'>body"))
            .isEqualTo("<doc" + ATTRIBUTE_LIST + " pwd='${secret:data.pwd}'>body");
        assertThat(maskInTime("<token" + ATTRIBUTE_LIST + ">" + SECRET + "</token>"))
            .isEqualTo("<token" + ATTRIBUTE_LIST + ">${secret:data.token}</token>");
        assertThat(maskInTime("<token" + ATTRIBUTE_LIST + ">" + SECRET))
            .isEqualTo("<token" + ATTRIBUTE_LIST + ">${secret:data.token}");
    }

    @Test
    void aMebibyteAttributeListInACutOffStartTagIsKept() {
        assertThat(maskInTime("<doc" + ATTRIBUTE_LIST)).isEqualTo("<doc" + ATTRIBUTE_LIST);
        assertThat(maskInTime("<doc" + ATTRIBUTE_LIST + " <password>" + SECRET + "</password>"))
            .isEqualTo("<doc" + ATTRIBUTE_LIST + " <password>${secret:data.password}</password>");
    }

    @Test
    void aMebibyteNameWithoutAValueInAStartTagIsSkippedInLinearTime() {
        String name = "a".repeat(MIB);

        assertThat(maskInTime("<doc " + name + " pwd=\"" + SECRET + "\"/>"))
            .isEqualTo("<doc " + name + " pwd=\"${secret:data.pwd}\"/>");
    }

    private static String maskInTime(String text) {
        return assertTimeoutPreemptively(LINEAR, () -> SecretTextMasker.mask(text));
    }

    @Test
    void nullIsRefused() {
        assertThatNullPointerException().isThrownBy(() -> SecretTextMasker.mask(null));
        assertThatNullPointerException().isThrownBy(() -> SecretTextMasker.secretName(null));
        assertThatNullPointerException()
            .isThrownBy(() -> SecretTextMasker.placeholder("data", null));
    }
}
