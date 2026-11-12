package de.dadecker.inubit.mcp.adapter.cli.node;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFailures;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Member;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.MembersOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeFramesClassifier.Answered;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeFramesClassifier.Failure;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeFramesClassifier.Login;
import de.dadecker.inubit.mcp.adapter.cli.node.NodeFramesClassifier.Outcome;
import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T016 (feature 024, research D-3, D-20): the part of the frame classification every node-tools
 * operation shares, against inline frames: {@code start}, {@code login ok}, {@code node ok} with
 * the node-name rule or {@code node error}, the operation's frames, {@code end}, exit code 0.
 * Every text that leaves the classifier is checked for leaks.
 */
class NodeFramesClassifierTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String P = "#QE1:" + NONCE + " ";
    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final SecretLabels LABELS = new SecretLabels.Store(
        new CredentialNames("INUBIT_ACME", NODE), StoreKind.KEYCHAIN);
    private static final Path HOME_81 = Path.of("/opt/acme/inubit-8.1/client");
    private static final MajorMinor V81 = new MajorMinor(8, 1);
    private static final HelperFailures.Subject SUBJECT = new HelperFailures.Subject(
        "INUBIT client helper", "list_cluster_nodes", "Check the cluster in the Workbench",
        "refused the node list for the configured user", "The user lacks the right",
        "Give the user the right");
    private static final String UNEXPECTED = "list_cluster_nodes got an unexpected answer from"
        + " test/node1; the unsupported INUBIT interface may have changed after an INUBIT update";
    private static final HelperRequest REQUEST = new HelperRequest(NONCE, "SOAP_81",
        URI.create("http://inubit.example.test:8000/ibis/servlet/IBISSoapServlet"),
        Optional.of(new HelperRequest.TrustStore(Path.of("/tmp/trust.p12"),
            Secret.of(TestSecrets.TRUST_STORE_PASSWORD))),
        true, "jdoe", Secret.of(TestSecrets.CONFIGURED_PASSWORD), new HelperRequest.OwnNode());

    /** Registers nothing: the request's own passwords must be masked by the classifier. */
    private final NodeFramesClassifier classifier =
        new NodeFramesClassifier(new SecretScrubber(), LABELS, SUBJECT);

    // --- answered ----------------------------------------------------------------------------

    @Test
    void anOwnNodeRunAnswersTheNodeNameWithoutFurtherFrames() {
        Outcome outcome = classify(0, "noise before\n" + run("node ok node1"));

        assertThat(outcome).isEqualTo(new Answered("node1", List.of()));
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
    }

    @Test
    void theOperationFramesBetweenTheNodeAndTheEndAreHandedOnInOrder() {
        Outcome outcome = classify(0, run("node ok node1",
            "member node1 ACTIVE 8.1.17 zone-a 1 3600000",
            "member node2 - - - - -", "members ok 2"));

        assertThat(outcome).isEqualTo(new Answered("node1", List.of(
            new Member("node1", "ACTIVE", "8.1.17", "zone-a", "1", Optional.of(3_600_000L)),
            new Member("node2", "", "", "", "", Optional.empty()),
            new MembersOk(2))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"n", "node1", "inubit-node_01.zone:a", "N0de.1:2-3_4"})
    void validNodeNamesAreAccepted(String name) {
        assertThat(classify(0, run("node ok " + encode(name))))
            .isEqualTo(new Answered(name, List.of()));
    }

    @Test
    void aNodeNameOf128CharactersIsAccepted() {
        String name = "n".repeat(128);

        assertThat(classify(0, run("node ok " + name))).isEqualTo(new Answered(name, List.of()));
    }

    // --- the node-name rule (D-3) ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "node 1", "node/1", "nöde1", "node1\u0000", "<b>node1</b>"})
    void anInvalidNodeNameIsAnUnexpectedResponseThatDoesNotRepeatIt(String name) {
        Outcome outcome = classify(0, run("node ok " + encode(name)));

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo("The INUBIT client helper reported an INUBIT"
            + " node name that is not 1-128 characters of A-Z, a-z, 0-9, '_', '.', ':' and '-'");
        assertThat(error.node()).contains(NODE);
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
        if (!name.isEmpty()) {
            assertThat(error.toString()).doesNotContain(name);
        }
    }

    @Test
    void aNodeNameOf129CharactersIsAnUnexpectedResponse() {
        assertThat(error(classify(0, run("node ok " + "n".repeat(129)))).code())
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    // --- node error -----------------------------------------------------------------------------

    @Test
    void aNodeErrorIsAnUnexpectedResponseWithAMaskedExcerpt() {
        Outcome outcome = classify(0, run("node error " + encode("java.io.IOException") + " "
            + encode("getNode failed for jdoe/" + TestSecrets.CONFIGURED_PASSWORD)));

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo("INUBIT reported an error when asked for the"
            + " node that answers");
        assertThat(error.nextStep()).isEqualTo("Check the cluster in the Workbench");
        assertThat(error.excerpt()).contains("getNode failed for jdoe/***");
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
        assertNoLeak(error);
    }

    @Test
    void aNodeErrorWithoutAMessageNamesItsExceptionClasses() {
        ToolError error = error(classify(0, run("node error "
            + encode("com.inubit.ibis.utils.InubitException") + " -")));

        assertThat(error.excerpt()).contains("InubitException");
    }

    @Test
    void aNodeErrorShowingAFailureOfTheWholeCallKeepsItsCode() {
        assertThat(error(classify(0, run("node error " + encode("java.io.IOException") + " "
            + encode("HTTP 403 Forbidden")))).code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error(classify(0, run("node error " + encode("java.net.ConnectException")
            + " " + encode("Connection refused")))).code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error(classify(0, run("node error "
            + encode("javax.net.ssl.SSLHandshakeException") + " " + encode("PKIX path"))))
            .code()).isEqualTo(ErrorCode.TLS_ERROR);
    }

    @Test
    void framesAfterANodeErrorAreAnUnexpectedResponse() {
        ToolError error = error(classify(0, run("node error " + encode("java.io.IOException")
            + " x", "members ok 0")));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.likelyCause())
            .isEqualTo("The frames of the INUBIT client helper do not match its exit code 0");
    }

    // --- exit code and frames ---------------------------------------------------------------------

    @Test
    void exitZeroWithoutTheEndFrameIsAnUnexpectedResponse() {
        Outcome outcome = classify(0, P + "start SOAP_81\n" + P + "login ok\n" + P
            + "node ok node1\n");

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.likelyCause()).isEqualTo("The INUBIT client helper ended with exit code"
            + " 0 without its end frame");
        assertThat(outcome.login()).as("the login frame came").isEqualTo(Login.ACCEPTED);
    }

    @Test
    void aSilentExitOneIsAnUnexpectedResponse() {
        Outcome outcome = classify(1, "Exception in thread main\n");

        assertThat(error(outcome).likelyCause()).isEqualTo("The INUBIT client helper ended with"
            + " exit code 1 without its end frame");
        assertThat(outcome.login()).isEqualTo(Login.UNKNOWN);
    }

    @Test
    void aCompleteRunWithANonZeroExitCodeIsAnUnexpectedResponse() {
        ToolError error = error(classify(1, run("node ok node1")));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.likelyCause())
            .isEqualTo("The frames of the INUBIT client helper do not match its exit code 1");
    }

    @Test
    void aRunWithoutTheNodeFrameIsAnUnexpectedResponse() {
        assertThat(error(classify(0, run())).code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error(classify(0, run("members ok 0"))).code())
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void aStartOfAnotherVariantIsAnUnexpectedResponse() {
        String stdout = run("node ok node1").replace("start SOAP_81", "start REST_90");

        assertThat(error(classify(0, stdout)).code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void aSharedFrameAmongTheOperationFramesIsAnUnexpectedResponse() {
        for (String frame : List.of("node ok node2", "node error x y", "login ok",
            "start SOAP_81")) {
            assertThat(error(classify(0, run("node ok node1", frame))).code()).as(frame)
                .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        }
    }

    @Test
    void anUnknownFrameIsAnUnexpectedResponse() {
        ToolError error = error(classify(0, run("node ok node1", "nodes ok 2")));

        assertThat(error.likelyCause())
            .isEqualTo("The INUBIT client helper wrote a frame of an unknown type");
    }

    @Test
    void framesOfAnotherNonceAreIgnored() {
        String foreign = "#QE1:" + "f".repeat(32) + " node ok node9\n";

        assertThat(classify(0, foreign + run("node ok node1")))
            .isEqualTo(new Answered("node1", List.of()));
    }

    @Test
    void aCutOutputIsAnUnexpectedResponse() {
        Outcome outcome = classifier.classify(REQUEST,
            new CliResult(0, run("node ok node1"), "", Duration.ofSeconds(1), true, 1 << 20),
            HOME_81, V81);

        assertThat(error(outcome).likelyCause())
            .isEqualTo("The output of the INUBIT client helper exceeded 1 MiB and was cut");
    }

    // --- login and API (HelperFailures) --------------------------------------------------------

    @Test
    void aRejectedLoginIsAuthFailedAndRejectsTheCredentials() {
        Outcome outcome = classify(3, P + "start SOAP_81\n" + P + "login error "
            + encode("com.inubit.ibis.utils.LoginFailure") + " " + encode("password does not"
            + " match") + "\n");

        assertThat(error(outcome).code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error(outcome).message())
            .isEqualTo("Login of the INUBIT client helper to test/node1 was rejected");
        assertThat(outcome.login()).isEqualTo(Login.REJECTED);
    }

    @Test
    void aLoginRefusedWithHttp403IsForbiddenWithTheSubjectsTexts() {
        Outcome outcome = classify(3, P + "start SOAP_81\n" + P + "login error "
            + encode("java.io.IOException") + " " + encode("HTTP 403 Forbidden") + "\n");

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error.message())
            .isEqualTo("test/node1 refused the node list for the configured user");
        assertThat(outcome.login()).isEqualTo(Login.UNKNOWN);
    }

    @Test
    void aMissingVendorApiIsCliUnavailableNamingTheClientEntry() {
        Outcome outcome = classify(4, P + "api missing "
            + encode("com.inubit.ibis.server.cluster.ClusterClient.getNodes()") + "\n");

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).contains("ClusterClient.getNodes()");
        assertThat(error.nextStep()).contains("defaults.cliClients.\"8.1\"");
    }

    // --- leak check -------------------------------------------------------------------------------

    @Test
    void noOutcomeCarriesTheRequestsPasswords() {
        String both = TestSecrets.CONFIGURED_PASSWORD + " " + TestSecrets.TRUST_STORE_PASSWORD;
        List<Outcome> outcomes = List.of(
            classify(0, run("node error " + encode("java.io.IOException") + " " + encode(both))),
            classify(0, run("node error " + encode("java.io.IOException") + " "
                + encode("HTTP 403 " + both))),
            classify(3, P + "start SOAP_81\n" + P + "login error " + encode("java.io.IOException")
                + " " + encode("odd " + both) + "\n"),
            classify(4, P + "api missing " + encode(both) + "\n"),
            classify(0, run("node ok " + encode(both))));

        for (Outcome outcome : outcomes) {
            assertNoLeak(error(outcome));
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private Outcome classify(int exit, String stdout) {
        return classifier.classify(REQUEST,
            new CliResult(exit, stdout, "", Duration.ofSeconds(1), false, 1 << 20), HOME_81, V81);
    }

    /** {@code start}, {@code login ok}, the given frames, {@code end}. */
    private static String run(String... frames) {
        StringBuilder out = new StringBuilder(P + "start SOAP_81\n" + P + "login ok\n");
        for (String frame : frames) {
            out.append(P).append(frame).append('\n');
        }
        return out.append(P).append("end\n").toString();
    }

    /** A frame token as the helper writes it: RFC 3986 percent-encoded, {@code -} if empty. */
    private static String encode(String text) {
        return text.isEmpty() ? "-" : URLEncoder.encode(text, StandardCharsets.UTF_8)
            .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static ToolError error(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Failure.class);
        return ((Failure) outcome).error();
    }

    private static void assertNoLeak(ToolError error) {
        TestSecrets.assertNoLeak(error.message());
        TestSecrets.assertNoLeak(error.likelyCause());
        TestSecrets.assertNoLeak(error.nextStep());
        error.excerpt().ifPresent(TestSecrets::assertNoLeak);
    }
}
