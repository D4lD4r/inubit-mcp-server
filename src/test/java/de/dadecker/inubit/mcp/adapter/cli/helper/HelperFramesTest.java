package de.dadecker.inubit.mcp.adapter.cli.helper;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ApiMissing;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.End;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.NodeError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.NodeOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Part;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Problem;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Start;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The stdout frames of the client helper (research D-4, data-model "Helper frames"): only
 * lines of the call's nonce count, tokens are percent-decoded, {@code -} is an empty token, and
 * an unknown or malformed frame, a frame after {@code end} or a missing {@code end} is reported.
 * Feature 021 adds {@code node ok|error} and the part {@code log}; the recorded 020 fixtures
 * parse as before.
 */
class HelperFramesTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_NONCE = "fedcba9876543210fedcba9876543210";
    private static final String P = "#QE1:" + NONCE + " ";
    private static final Path QUEUE_ENTRY_FIXTURES =
        Path.of("src/test/resources/fixtures/queue-entry");
    private static final String READ_BOTH =
        "Start LoginOk PartOk:DATA PartOk:VARIABLES End | -";
    private static final String BOTH_FAILED =
        "Start LoginOk PartError:DATA PartError:VARIABLES End | -";

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
    void parsesTheFramesOfAServerLogRead() {
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + P + "start SOAP_81\n"
            + P + "login ok\n"
            + P + "node ok node-0001%20%C3%BC\n"
            + P + "part log ok 52428800\n"
            + P + "end\n");

        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"), new LoginOk(),
            new NodeOk("node-0001 ü"), new PartOk(Part.LOG, 52_428_800), new End());
        assertThat(frames.ended()).isTrue();
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void decodesNodeAndLogErrorFramesWithTheirExceptionChainAndMessages() {
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + P + "start REST_90\n"
            + P + "login ok\n"
            + P + "node error java.io.IOException%3Ejava.net.ConnectException "
            + "cluster%20unavailable%0Aconnection%20refused\n"
            + P + "part log error java.io.IOException -\n"
            + P + "node error - -\n"
            + P + "end\n");

        assertThat(frames.frames()).containsExactly(new Start("REST_90"), new LoginOk(),
            new NodeError(List.of("java.io.IOException", "java.net.ConnectException"),
                "cluster unavailable\nconnection refused"),
            new PartError(Part.LOG, List.of("java.io.IOException"), ""),
            new NodeError(List.of(), ""), new End());
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
            Arguments.of("part log without bytes", P + "part log ok"),
            Arguments.of("node without outcome", P + "node"),
            Arguments.of("node with unknown outcome", P + "node found a"),
            Arguments.of("node ok without name", P + "node ok"),
            Arguments.of("node ok with two names", P + "node ok a b"),
            Arguments.of("node error without message", P + "node error java.io.IOException"),
            Arguments.of("node error with extra token", P + "node error a b c"),
            Arguments.of("node error chain with an empty class", P + "node error a%3E%3Eb msg"),
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("queueEntryFixtures")
    void everyRecordedQueueEntryFixtureParsesAsBefore(String fixture, String parsed)
        throws IOException {
        HelperFrames frames = HelperFrames.parse(NONCE,
            Files.readString(QUEUE_ENTRY_FIXTURES.resolve(fixture)));

        String summary = frames.frames().stream().map(HelperFramesTest::summary)
            .collect(Collectors.joining(" "));

        assertThat(summary + " | " + frames.problem().map(Enum::name).orElse("-"))
            .isEqualTo(parsed);
    }

    @Test
    void everyQueueEntryFixtureIsPinned() throws IOException {
        try (Stream<Path> files = Files.walk(QUEUE_ENTRY_FIXTURES)) {
            assertThat(files.filter(file -> file.toString().endsWith(".frames"))
                .map(file -> QUEUE_ENTRY_FIXTURES.relativize(file).toString().replace('\\', '/')))
                .containsExactlyInAnyOrderElementsOf(queueEntryFixtures()
                    .map(arguments -> (String) arguments.get()[0]).toList());
        }
    }

    /** Each 020 {@code .frames} fixture with its parse result before feature 021 (pinned). */
    static Stream<Arguments> queueEntryFixtures() {
        return Stream.of(
            Arguments.of("v8_1/api_missing.frames", "ApiMissing | -"),
            Arguments.of("v8_1/binary_zip.frames", READ_BOTH),
            Arguments.of("v8_1/cutoff_tag.frames", READ_BOTH),
            Arguments.of("v8_1/error_entry.frames", READ_BOTH),
            Arguments.of("v8_1/garbage_frames.frames", "Start LoginOk | UNKNOWN_FRAME_TYPE"),
            Arguments.of("v8_1/latin1_text.frames", READ_BOTH),
            Arguments.of("v8_1/login_failure.frames", "Start LoginError | -"),
            Arguments.of("v8_1/long_values.frames", READ_BOTH),
            Arguments.of("v8_1/many_variables.frames", READ_BOTH),
            Arguments.of("v8_1/missing_variables.frames",
                "Start LoginOk PartOk:DATA PartError:VARIABLES End | -"),
            Arguments.of("v8_1/not_found_file.frames", BOTH_FAILED),
            Arguments.of("v8_1/secrets_everywhere.frames", READ_BOTH),
            Arguments.of("v8_1/tls_failure.frames", "Start LoginError | -"),
            Arguments.of("v8_1/too_large.frames", "Start LoginOk PartOk:VARIABLES End | -"),
            Arguments.of("v8_1/utf16_text.frames", READ_BOTH),
            Arguments.of("v9_0/error_entry.frames", READ_BOTH),
            Arguments.of("v9_0/missing_workflow.frames", BOTH_FAILED),
            Arguments.of("v9_0/not_found_file.frames", BOTH_FAILED));
    }

    private static String summary(HelperFrames.Frame frame) {
        return switch (frame) {
            case PartOk ok -> "PartOk:" + ok.part();
            case PartError error -> "PartError:" + error.part();
            default -> frame.getClass().getSimpleName();
        };
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
