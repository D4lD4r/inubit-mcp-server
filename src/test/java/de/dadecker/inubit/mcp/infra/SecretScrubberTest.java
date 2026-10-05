package de.dadecker.inubit.mcp.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class SecretScrubberTest {

    private final SecretScrubber scrubber = new SecretScrubber();

    @Test
    void secretToStringIsMasked() {
        Secret secret = Secret.of("s3cr3t-Pa55");

        assertThat(secret).hasToString("***");
        assertThat(String.valueOf(secret)).isEqualTo("***");
        assertThat(List.of(secret).toString()).doesNotContain("s3cr3t");
        assertThat(secret.reveal()).isEqualTo("s3cr3t-Pa55");
    }

    @Test
    void secretsWithEqualValuesAreEqual() {
        assertThat(Secret.of("abc")).isEqualTo(Secret.of("abc"))
            .hasSameHashCodeAs(Secret.of("abc"));
        assertThat(Secret.of("abc")).isNotEqualTo(Secret.of("abd"));
    }

    @Test
    void registeredSecretIsReplacedInArbitraryText() {
        Secret secret = scrubber.register("hunter2");

        assertThat(secret.reveal()).isEqualTo("hunter2");
        assertThat(scrubber.scrub("login with hunter2 failed")).isEqualTo("login with *** failed");
    }

    @Test
    void repeatedOccurrencesAreAllReplaced() {
        scrubber.register("pw");

        assertThat(scrubber.scrub("pw pw-xpw")).isEqualTo("*** ***-x***");
        // adjacent occurrences form one masked run
        assertThat(scrubber.scrub("pwpw")).isEqualTo("***");
    }

    @Test
    void secretEmbeddedInOtherTextIsReplaced() {
        scrubber.register("Geheim!42");

        assertThat(scrubber.scrub("password=Geheim!42;next")).isEqualTo("password=***;next");
        assertThat(scrubber.scrub("{\"p\":\"Geheim!42\"}")).isEqualTo("{\"p\":\"***\"}");
    }

    @Test
    void overlappingSecretsUseTheLongestMatch() {
        scrubber.register("abc");
        scrubber.register("abcdef");

        assertThat(scrubber.scrub("xxabcdefyy abc")).isEqualTo("xx***yy ***");
    }

    @Test
    void overlappingOccurrencesLeaveNoFragment() {
        scrubber.register("aXa");

        // both occurrences overlap; no fragment of the second one may remain
        assertThat(scrubber.scrub("aXaXa!")).isEqualTo("***!");
    }

    @Test
    void regexMetacharactersAreTreatedLiterally() {
        scrubber.register("a.b*c$1\\E(");

        assertThat(scrubber.scrub("x a.b*c$1\\E( y aXbbc")).isEqualTo("x *** y aXbbc");
    }

    @Test
    void basicAuthTokenOfUserAndPasswordIsReplaced() {
        Secret password = scrubber.register("hunter2");
        String token = Base64.getEncoder()
            .encodeToString("jdoe:hunter2".getBytes(StandardCharsets.UTF_8));

        Secret registeredToken = scrubber.registerBasicAuth("jdoe", password);

        assertThat(registeredToken.reveal()).isEqualTo(token);
        assertThat(scrubber.scrub("Authorization: Basic " + token))
            .isEqualTo("Authorization: Basic ***");
    }

    private static final String SPECIAL = "pa\"ss&<w/rd";

    @Test
    void jsonEscapedFormIsScrubbed() {
        scrubber.register(SPECIAL);

        assertThat(scrubber.scrub("{\"p\":\"pa\\\"ss&<w/rd\"}"))
            .isEqualTo("{\"p\":\"***\"}");
        assertThat(scrubber.scrub("{\"p\":\"pa\\\"ss&<w\\/rd\"}"))
            .isEqualTo("{\"p\":\"***\"}");
    }

    @Test
    void xmlEscapedFormIsScrubbed() {
        scrubber.register(SPECIAL);
        scrubber.register("it's");

        assertThat(scrubber.scrub("<p>pa&quot;ss&amp;&lt;w/rd</p>")).isEqualTo("<p>***</p>");
        assertThat(scrubber.scrub("<p a='it&apos;s'/>")).isEqualTo("<p a='***'/>");
        assertThat(scrubber.scrub("x > y")).isEqualTo("x > y");
    }

    @Test
    void xmlEscapingCoversGreaterThan() {
        scrubber.register("a>b");

        assertThat(scrubber.scrub("<v>a&gt;b</v>")).isEqualTo("<v>***</v>");
    }

    @Test
    void urlEncodedFormsAreScrubbed() {
        scrubber.register(SPECIAL);
        scrubber.register("with space");

        assertThat(scrubber.scrub("?p=pa%22ss%26%3Cw%2Frd&x=1")).isEqualTo("?p=***&x=1");
        assertThat(scrubber.scrub("q=with+space")).isEqualTo("q=***");
        assertThat(scrubber.scrub("q=with%20space")).isEqualTo("q=***");
    }

    @Test
    void xmlTextContentFormEscapingOnlyAmpersandAndAngleBracketsIsScrubbed() {
        scrubber.register("a\"b'c&d<e>f");

        // text content: serializers escape only & < > and keep quotes raw
        assertThat(scrubber.scrub("<p>a\"b'c&amp;d&lt;e&gt;f</p>")).isEqualTo("<p>***</p>");
    }

    @Test
    void numericCharacterReferenceFormsAreScrubbed() {
        scrubber.register("say \"hi\" it's");

        assertThat(scrubber.scrub("<p v=\"say &#34;hi&#34; it&#39;s\"/>"))
            .isEqualTo("<p v=\"***\"/>");
        assertThat(scrubber.scrub("<p v=\"say &#x22;hi&#x22; it&#x27;s\"/>"))
            .isEqualTo("<p v=\"***\"/>");
    }

    @Test
    void lowercaseUrlEncodedFormsAreScrubbed() {
        scrubber.register("pa/ss wörd");

        assertThat(scrubber.scrub("p=pa%2fss+w%c3%b6rd")).isEqualTo("p=***");
        assertThat(scrubber.scrub("p=pa%2fss%20w%c3%b6rd")).isEqualTo("p=***");
        assertThat(scrubber.scrub("p=pa%2Fss%20w%C3%B6rd")).isEqualTo("p=***");
    }

    @Test
    void jsonUnicodeEscapedFormOfNonAsciiCharactersIsScrubbed() {
        scrubber.register("pässwörd😀");

        assertThat(scrubber.scrub("{\"p\":\"p\\u00e4ssw\\u00f6rd\\ud83d\\ude00\"}"))
            .isEqualTo("{\"p\":\"***\"}");
        assertThat(scrubber.scrub("{\"p\":\"p\\u00E4ssw\\u00F6rd\\uD83D\\uDE00\"}"))
            .isEqualTo("{\"p\":\"***\"}");
    }

    @Test
    void base64FormIsScrubbed() {
        scrubber.register(SPECIAL);
        String base64 =
            Base64.getEncoder().encodeToString(SPECIAL.getBytes(StandardCharsets.UTF_8));

        assertThat(scrubber.scrub("blob " + base64)).isEqualTo("blob ***");
    }

    @Test
    void basicAuthTokenIsScrubbedInItsEncodedForms() {
        Secret password = scrubber.register(SPECIAL);
        String token = Base64.getEncoder()
            .encodeToString(("jdoe:" + SPECIAL).getBytes(StandardCharsets.UTF_8));

        scrubber.registerBasicAuth("jdoe", password);

        assertThat(scrubber.scrub("Basic " + token)).isEqualTo("Basic ***");
        assertThat(scrubber.scrub("auth=" + java.net.URLEncoder.encode(token,
            StandardCharsets.UTF_8))).isEqualTo("auth=***");
    }

    @Test
    void registeringAnExistingSecretWrapperRegistersItsValue() {
        Secret secret = Secret.of("tr0ub4dor");

        scrubber.register(secret);

        assertThat(scrubber.scrub("tr0ub4dor")).isEqualTo("***");
    }

    @Test
    void textWithoutSecretsIsUnchangedAndNullIsHandled() {
        scrubber.register("secret");

        assertThat(scrubber.scrub("nothing here")).isEqualTo("nothing here");
        assertThat(scrubber.scrub(null)).isNull();
        assertThat(new SecretScrubber().scrub("no secrets registered"))
            .isEqualTo("no secrets registered");
    }

    @Test
    void emptySecretsAreIgnored() {
        scrubber.register("");

        assertThat(scrubber.scrub("abc")).isEqualTo("abc");
    }

    @Test
    void containsSecretReportsLeaks() {
        scrubber.register("leak-me");

        assertThat(scrubber.containsSecret("a leak-me b")).isTrue();
        assertThat(scrubber.containsSecret("clean")).isFalse();
    }

    @Test
    void globalScrubberIsASingleton() {
        assertThat(SecretScrubber.global()).isSameAs(SecretScrubber.global());
    }

    @Test
    void registrationAndScrubbingAreThreadSafe() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String value = "secret-value-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    scrubber.register(value);
                    return scrubber.scrub("[" + value + "]").equals("[***]");
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        for (int i = 0; i < threads; i++) {
            assertThat(scrubber.scrub("secret-value-" + i)).isEqualTo("***");
        }
    }
}
