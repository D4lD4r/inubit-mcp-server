package de.dadecker.inubit.mcp.adapter.cli.helper;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ApiMissing;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ConfigAbsent;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ConfigError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ConfigOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Detail;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.DetailsError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.DetailsOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.DetailsUnknown;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.End;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.IoListError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.IoListOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.IoMatch;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Member;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.MembersError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.MembersOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.NodeError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.NodeOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Part;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Problem;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Start;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
 * Feature 021 adds {@code node ok|error} and the part {@code log}; feature 024 (research D-2)
 * adds the frames of {@code clusterNodes}, {@code nodeConfig} and {@code ioLog} and the part
 * {@code message}; the recorded 020 fixtures parse as before.
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
            Arguments.of("carriage return before the line end", P + "login ok\r"),
            Arguments.of("member with five tokens", P + "member a ACTIVE 8.1 z id"),
            Arguments.of("member with seven tokens", P + "member a ACTIVE 8.1 z id 1 x"),
            Arguments.of("member with negative uptime", P + "member a ACTIVE 8.1 z id -1"),
            Arguments.of("member with uptime not a number", P + "member a ACTIVE 8.1 z id 1s"),
            Arguments.of("member with uptime beyond long",
                P + "member a ACTIVE 8.1 z id 99999999999999999999"),
            Arguments.of("member with an unescaped name", P + "member a+b - - - - -"),
            Arguments.of("members without outcome", P + "members"),
            Arguments.of("members with unknown outcome", P + "members listed 2"),
            Arguments.of("members ok without count", P + "members ok"),
            Arguments.of("members ok with count -", P + "members ok -"),
            Arguments.of("members ok with negative count", P + "members ok -1"),
            Arguments.of("members ok with count beyond int", P + "members ok 9999999999"),
            Arguments.of("members ok with two counts", P + "members ok 1 2"),
            Arguments.of("members error without message", P + "members error a"),
            Arguments.of("members error chain with an empty class",
                P + "members error a%3E%3Eb msg"),
            Arguments.of("detail without value", P + "detail key"),
            Arguments.of("detail with a fourth token", P + "detail key value cut x"),
            Arguments.of("detail with a third token other than cut", P + "detail key value cuts"),
            Arguments.of("detail with an escaped cut marker", P + "detail key value %63ut"),
            Arguments.of("details without outcome", P + "details"),
            Arguments.of("details with unknown outcome", P + "details found 1 false"),
            Arguments.of("details ok without truncated", P + "details ok 1"),
            Arguments.of("details ok with truncated not a flag", P + "details ok 1 yes"),
            Arguments.of("details ok with truncated -", P + "details ok 1 -"),
            Arguments.of("details ok with flag in upper case", P + "details ok 1 TRUE"),
            Arguments.of("details ok with count not a number", P + "details ok x false"),
            Arguments.of("details ok with an extra token", P + "details ok 1 false x"),
            Arguments.of("details error without message", P + "details error a"),
            Arguments.of("details unknown with a token", P + "details unknown node-0002"),
            Arguments.of("config without name", P + "config"),
            Arguments.of("config without outcome", P + "config ClusterName"),
            Arguments.of("config with unknown outcome", P + "config ClusterName missing"),
            Arguments.of("config ok with six tokens",
                P + "config ClusterName ok String true false Name Cluster -"),
            Arguments.of("config ok with eight tokens",
                P + "config ClusterName ok String true false Name Cluster - v x"),
            Arguments.of("config ok with editable not a flag",
                P + "config ClusterName ok String yes false Name Cluster - v"),
            Arguments.of("config ok with deprecated -",
                P + "config ClusterName ok String true - Name Cluster - v"),
            Arguments.of("config absent with a token", P + "config ClusterName absent x"),
            Arguments.of("config error without message", P + "config ClusterName error a"),
            Arguments.of("config error with extra token", P + "config ClusterName error a b c"),
            Arguments.of("io without subtype", P + "io"),
            Arguments.of("io with unknown subtype", P + "io read ok 1"),
            Arguments.of("io list without outcome", P + "io list"),
            Arguments.of("io list ok without count", P + "io list ok"),
            Arguments.of("io list ok with count -", P + "io list ok -"),
            Arguments.of("io list error without message", P + "io list error a"),
            Arguments.of("io match without entry", P + "io match"),
            Arguments.of("io match with an empty entry", P + "io match -"),
            Arguments.of("io match with two entries", P + "io match a b"),
            Arguments.of("part message without bytes", P + "part message ok"),
            Arguments.of("part message error without message", P + "part message error a"));
    }

    @Test
    void parsesTheFramesOfAClusterNodesCallWithDetails() {
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + P + "start SOAP_81\n"
            + P + "login ok\n"
            + P + "node ok node-0001\n"
            + P + "member node-0001 ACTIVE 8.1.17 zone-a%20%C3%BC id-0001 3600000\n"
            + P + "member node-0002 - - - - -\n"
            + P + "member node-0003 JOINING 8.1.17 zone-a id-0003 0\n"
            + P + "members ok 4\n"
            + P + "detail Server.Version 8.1.17\n"
            + P + "detail JDK.Home %2Fopt%2Fjava cut\n"
            + P + "detail Empty -\n"
            + P + "details ok 3 true\n"
            + P + "end\n");

        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"), new LoginOk(),
            new NodeOk("node-0001"),
            new Member("node-0001", "ACTIVE", "8.1.17", "zone-a ü", "id-0001",
                Optional.of(3_600_000L)),
            new Member("node-0002", "", "", "", "", Optional.empty()),
            new Member("node-0003", "JOINING", "8.1.17", "zone-a", "id-0003", Optional.of(0L)),
            new MembersOk(4),
            new Detail("Server.Version", "8.1.17", false),
            new Detail("JDK.Home", "/opt/java", true),
            new Detail("Empty", "", false),
            new DetailsOk(3, true), new End());
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void decodesTheErrorAndUnknownFramesOfClusterNodes() {
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + P + "start REST_90\n"
            + P + "login ok\n"
            + P + "node ok node-0001\n"
            + P + "members error java.io.IOException%3Ejava.net.ConnectException "
            + "cluster%20unavailable\n"
            + P + "members ok 0\n"
            + P + "details unknown\n"
            + P + "details ok 0 false\n"
            + P + "details error com.example.FakeException -\n"
            + P + "end\n");

        assertThat(frames.frames()).containsExactly(new Start("REST_90"), new LoginOk(),
            new NodeOk("node-0001"),
            new MembersError(List.of("java.io.IOException", "java.net.ConnectException"),
                "cluster unavailable"),
            new MembersOk(0), new DetailsUnknown(), new DetailsOk(0, false),
            new DetailsError(List.of("com.example.FakeException"), ""), new End());
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void parsesTheFramesOfANodeConfigCall() {
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + P + "start SOAP_81\n"
            + P + "login ok\n"
            + P + "node ok node-0001\n"
            + P + "config ClusterName ok String true false Cluster%20name Cluster "
            + "Name%20of%20the%20cluster cluster-a\n"
            + P + "config ZoneName ok String false true - - - -\n"
            + P + "config noOfRetries absent\n"
            + P + "config indexEnabled error java.lang.IllegalStateException not%20readable\n"
            + P + "end\n");

        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"), new LoginOk(),
            new NodeOk("node-0001"),
            new ConfigOk("ClusterName", "String", true, false, "Cluster name", "Cluster",
                "Name of the cluster", "cluster-a"),
            new ConfigOk("ZoneName", "String", false, true, "", "", "", ""),
            new ConfigAbsent("noOfRetries"),
            new ConfigError("indexEnabled", List.of("java.lang.IllegalStateException"),
                "not readable"),
            new End());
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void parsesTheFramesOfAnIoLogCall() {
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + P + "start SOAP_81\n"
            + P + "login ok\n"
            + P + "node ok node-0001\n"
            + P + "io list ok 2\n"
            + P + "io match p-0001%40%40%40Mapper%28m-1%29%40%40%40Input.1\n"
            + P + "part message ok 0\n"
            + P + "io match none\n"
            + P + "io list error java.io.IOException -\n"
            + P + "part message error java.io.IOException no%20data\n"
            + P + "end\n");

        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"), new LoginOk(),
            new NodeOk("node-0001"), new IoListOk(2),
            new IoMatch(Optional.of("p-0001@@@Mapper(m-1)@@@Input.1")),
            new PartOk(Part.MESSAGE, 0), new IoMatch(Optional.empty()),
            new IoListError(List.of("java.io.IOException"), ""),
            new PartError(Part.MESSAGE, List.of("java.io.IOException"), "no data"), new End());
        assertThat(frames.problem()).isEmpty();
    }

    @Test
    void newFramesOfAnotherNonceAreIgnoredAndAfterEndAreReported() {
        HelperFrames frames = HelperFrames.parse(NONCE, ""
            + "#QE1:" + OTHER_NONCE + " member a - - - - -\n"
            + "#QE1:" + OTHER_NONCE + " progress 1\n"
            + P + "start SOAP_81\n"
            + P + "end\n"
            + P + "details ok 0 false\n");

        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"), new End());
        assertThat(frames.problem()).contains(Problem.FRAME_AFTER_END);
    }

    @Test
    void anUnknownFrameTypeAmongTheNewFramesIsReported() {
        HelperFrames frames = HelperFrames.parse(NONCE,
            P + "start SOAP_81\n" + P + "nodes ok 1\n" + P + "end\n");

        assertThat(frames.problem()).contains(Problem.UNKNOWN_FRAME_TYPE);
        assertThat(frames.frames()).containsExactly(new Start("SOAP_81"));
    }

    /**
     * The 1 MiB stdout cap of 020 is unchanged, and the largest {@code clusterNodes} answer with
     * ASCII tokens (200 {@code member} frames with tokens of 200 characters, {@code detail} frames
     * up to the 256 KiB budget) stays below it and parses; the bound for non-ASCII tokens is T011's.
     */
    @Test
    void theLargestClusterNodesAnswerStaysBelowTheUnchangedOneMibCap() {
        assertThat(ProcessRun.MAX_OUTPUT_BYTES).isEqualTo(1 << 20);
        String token = "n".repeat(200);
        StringBuilder stdout = new StringBuilder()
            .append(P).append("start SOAP_81\n")
            .append(P).append("login ok\n")
            .append(P).append("node ok node-0001\n");
        for (int i = 0; i < 200; i++) {
            stdout.append(P).append("member ").append(String.join(" ", token, token, token, token,
                token)).append(" 999999999999999999\n");
        }
        stdout.append(P).append("members ok 100000\n");
        String detail = P + "detail " + "k".repeat(200) + " " + "v".repeat(4_000) + " cut\n";
        int details = 0;
        for (int budget = 256 * 1024; budget >= detail.length(); budget -= detail.length()) {
            stdout.append(detail);
            details++;
        }
        stdout.append(P).append("details ok ").append(details).append(" true\n")
            .append(P).append("end\n");

        HelperFrames frames = HelperFrames.parse(NONCE, stdout.toString());

        assertThat(stdout.toString().getBytes(StandardCharsets.UTF_8).length)
            .isLessThan(ProcessRun.MAX_OUTPUT_BYTES);
        assertThat(frames.problem()).isEmpty();
        assertThat(frames.ended()).isTrue();
        assertThat(frames.frames()).filteredOn(Member.class::isInstance).hasSize(200);
        assertThat(frames.frames()).filteredOn(Detail.class::isInstance).hasSize(details);
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
