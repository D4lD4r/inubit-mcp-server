package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.queue.HelperFrames.ApiMissing;
import de.dadecker.inubit.mcp.adapter.cli.queue.HelperFrames.End;
import de.dadecker.inubit.mcp.adapter.cli.queue.HelperFrames.LoginError;
import de.dadecker.inubit.mcp.adapter.cli.queue.HelperFrames.LoginOk;
import de.dadecker.inubit.mcp.adapter.cli.queue.HelperFrames.Part;
import de.dadecker.inubit.mcp.adapter.cli.queue.HelperFrames.PartError;
import de.dadecker.inubit.mcp.adapter.cli.queue.HelperFrames.PartOk;
import de.dadecker.inubit.mcp.adapter.cli.queue.HelperFrames.Problem;
import de.dadecker.inubit.mcp.adapter.cli.queue.HelperFrames.Start;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The stdout frames of the queue-entry helper (research D-4, data-model "Helper frames"): only
 * lines of the call's nonce count, tokens are percent-decoded, {@code -} is an empty token, and
 * an unknown or malformed frame, a frame after {@code end} or a missing {@code end} is reported.
 */
class HelperFramesTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_NONCE = "fedcba9876543210fedcba9876543210";
    private static final String P = "#QE1:" + NONCE + " ";

    @Test
    void parsesTheFramesOfASuccessfulReadAndIgnoresVendorOutput() {
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + "log4j:WARN No appenders could be found\n"
            + P + "start SOAP_81\n"
            + "12:00:00,000 INFO  [main] connecting to inubit.example.test\n"
            + P + "login ok\n"
            + P + "part data ok 1048576\n"
            + "\n"
            + P + "part variables ok 0\n"
            + P + "end\n");

        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"), new LoginOk(),
            new PartOk(Part.DATA, 1_048_576), new PartOk(Part.VARIABLES, 0), new End());
        assertThat(frames.ended()).isTrue();
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void decodesErrorFramesWithTheirExceptionChainAndMessages() {
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + P + "start REST_90\n"
            + P + "login ok\n"
            + P + "part data error java.io.IOException%3Ejava.lang.IllegalStateException "
            + "%40Start%40Unable%20to%20download%0Areason%3A%20%C3%BC%2B%25\n"
            + P + "part variables error - -\n"
            + P + "end\n");

        assertThat(frames.frames()).containsExactly(new Start("REST_90"), new LoginOk(),
            new PartError(Part.DATA, List.of("java.io.IOException",
                "java.lang.IllegalStateException"), "@Start@Unable to download\nreason: ü+%"),
            new PartError(Part.VARIABLES, List.of(), ""), new End());
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void aLoginErrorWithoutMessageHasAnEmptyMessageAndNoEnd() {
        HelperFrames frames = HelperFrames.parse(NONCE,
            P + "start SOAP_81\n" + P + "login error java.net.ConnectException -\n");

        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"),
            new LoginError(List.of("java.net.ConnectException"), ""));
        assertThat(frames.ended()).as("a missing end is reported").isFalse();
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void anApiMissingFrameNamesWhatIsMissing() {
        HelperFrames frames = HelperFrames.parse(NONCE,
            P + "api missing com.inubit.ibis.soap.IBISSoapClient\n" + P + "api missing -\n");

        assertThat(frames.frames()).containsExactly(
            new ApiMissing("com.inubit.ibis.soap.IBISSoapClient"), new ApiMissing(""));
        assertThat(frames.ended()).isFalse();
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void emptyOutputHasNoFramesAndNoEnd() {
        HelperFrames frames = HelperFrames.parse(NONCE, "");

        assertThat(frames.frames()).isEmpty();
        assertThat(frames.ended()).isFalse();
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void linesOfAnotherNonceOrWithoutTheExactPrefixAreIgnored() {
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + "#QE1:" + OTHER_NONCE + " login ok\n"
            + "#QE1:" + NONCE.toUpperCase() + " login ok\n"
            + "#QE1:" + NONCE + "0 login ok\n"
            + "#QE1: login ok\n"
            + "#QE2:" + NONCE + " login ok\n"
            + " " + P + "login ok\n"
            + "vendor says " + P + "end\n"
            + P + "start SOAP_81\n"
            + "#QE1:" + OTHER_NONCE + " end\n"
            + P + "login error java.lang.SecurityException LoginFailure\n");

        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"),
            new LoginError(List.of("java.lang.SecurityException"), "LoginFailure"));
        assertThat(frames.ended()).isFalse();
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void anUnknownFrameTypeIsReportedAndEndsParsing() {
        HelperFrames frames = HelperFrames.parse(NONCE,
            P + "start SOAP_81\n" + P + "progress 50\n" + P + "login ok\n" + P + "end\n");

        assertThat(frames.problem()).contains(Problem.UNKNOWN_FRAME_TYPE);
        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"));
        assertThat(frames.ended()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedFrames")
    void aMalformedFrameIsReported(String label, String frame) {
        HelperFrames frames = HelperFrames.parse(NONCE,
            P + "start SOAP_81\n" + frame + "\n" + P + "end\n");

        assertThat(frames.problem()).contains(Problem.MALFORMED_FRAME);
        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"));
    }

    static Stream<Arguments> malformedFrames() {
        return Stream.of(
            Arguments.of("prefix without tokens", "#QE1:" + NONCE),
            Arguments.of("prefix and a space only", P),
            Arguments.of("empty token", P + "login  ok"),
            Arguments.of("trailing space", P + "login ok "),
            Arguments.of("start without variant", P + "start"),
            Arguments.of("start with two tokens", P + "start SOAP_81 x"),
            Arguments.of("login without outcome", P + "login"),
            Arguments.of("login with unknown outcome", P + "login maybe"),
            Arguments.of("login ok with a token", P + "login ok x"),
            Arguments.of("login error without message", P + "login error java.io.IOException"),
            Arguments.of("login error with extra token", P + "login error a b c"),
            Arguments.of("exception chain with an empty class", P + "login error a%3E%3Eb msg"),
            Arguments.of("part of unknown kind", P + "part content ok 1"),
            Arguments.of("part without bytes", P + "part data ok"),
            Arguments.of("part with negative bytes", P + "part data ok -1"),
            Arguments.of("part with bytes not a number", P + "part data ok 1k"),
            Arguments.of("part with bytes beyond long", P + "part data ok 99999999999999999999"),
            Arguments.of("part with unknown outcome", P + "part data skipped"),
            Arguments.of("part error without message", P + "part data error a"),
            Arguments.of("api with unknown subtype", P + "api present a"),
            Arguments.of("api missing without what", P + "api missing"),
            Arguments.of("end with a token", P + "end now"),
            Arguments.of("reserved character not escaped", P + "api missing a+b"),
            Arguments.of("truncated escape", P + "api missing a%4"),
            Arguments.of("escape not hex", P + "api missing a%G1"),
            Arguments.of("escape with non-ASCII digits", P + "api missing a%\u0663\u0663"),
            Arguments.of("escape decodes to invalid UTF-8", P + "api missing a%FF"),
            Arguments.of("carriage return before the line end", P + "login ok\r"));
    }

    @Test
    void aDuplicateEndIsReported() {
        HelperFrames frames = HelperFrames.parse(NONCE,
            P + "start SOAP_81\n" + P + "end\n" + P + "end\n");

        assertThat(frames.problem()).contains(Problem.FRAME_AFTER_END);
        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"), new End());
    }

    @Test
    void aFrameAfterEndIsReported() {
        HelperFrames frames = HelperFrames.parse(NONCE,
            P + "start SOAP_81\n" + P + "end\n" + "vendor shutdown\n" + P + "login ok\n");

        assertThat(frames.problem()).contains(Problem.FRAME_AFTER_END);
    }

    @Test
    void ignoredLinesAndMalformedFramesNeverReachTheResult() {
        String secrets = TestSecrets.CONFIGURED_PASSWORD + " " + TestSecrets.TRUST_STORE_PASSWORD
            + " " + TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + "DEBUG request password=" + secrets + "\n"
            + "#QE1:" + OTHER_NONCE + " api missing " + TestSecrets.CONFIGURED_PASSWORD + "\n"
            + P + "start SOAP_81\n"
            + P + "login ok\n"
            + P + "part data ok " + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "\n");

        assertThat(frames.problem()).contains(Problem.MALFORMED_FRAME);
        TestSecrets.assertNoLeak(frames.toString());
        TestSecrets.assertNoLeak(frames.problem().orElseThrow().toString());
    }
}
