package de.dadecker.inubit.mcp.adapter.cli.log;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequestTest;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Downloaded;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Failure;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Login;
import de.dadecker.inubit.mcp.adapter.cli.log.WebServiceLogClassifier.Outcome;
import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogPresence;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Frames and exit code of the client helper's {@code serverLog} operation to the downloaded web
 * service log or a tool error (research D-5, D-8, D-14), against inline frames; every text that
 * leaves the classifier is checked for leaks (SC-003).
 */
class WebServiceLogClassifierTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String P = "#QE1:" + NONCE + " ";
    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final SecretLabels LABELS = new SecretLabels.Store(
        new CredentialNames("INUBIT_ACME", NODE), StoreKind.KEYCHAIN);
    private static final Path HOME_81 = Path.of("/opt/acme/inubit-8.1/client");
    private static final MajorMinor V81 = new MajorMinor(8, 1);
    private static final String UNEXPECTED = "download_server_log got an unexpected answer from"
        + " test/node1; the unsupported INUBIT interface may have changed after an INUBIT update";
    private static final String WORKBENCH =
        "Download the log in the Workbench (Cluster Manager); report the INUBIT version";

    /** Registers nothing: the request's own passwords must be masked by the classifier itself. */
    private final WebServiceLogClassifier classifier =
        new WebServiceLogClassifier(new SecretScrubber(), LABELS);

    // --- success -----------------------------------------------------------------------------

    @Test
    void aCompleteDownloadReportsTheNodeNameAndSizeAndAcceptsTheLogin() {
        Outcome outcome = classify(0, "noise before\n" + P + "start SOAP_81\n" + P + "login ok\n"
            + P + "node ok node1\n" + "noise between\n" + P + "part log ok 22400000\n" + P
            + "end\n");

        assertThat(outcome).isEqualTo(new Downloaded("node1", 22_400_000, LogPresence.PRESENT));
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
    }

    @Test
    void anEmptyLogIsPresentWithSizeZero() {
        Outcome outcome = classify(0, success("node1", P + "part log ok 0"));

        assertThat(outcome).isEqualTo(new Downloaded("node1", 0, LogPresence.PRESENT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"n", "inubit-node_01.acme:8443", "ABCxyz0189_.:-"})
    void aNodeNameOfTheAllowedCharactersIsTaken(String name) {
        assertThat(classify(0, success(name, P + "part log ok 5")))
            .isEqualTo(new Downloaded(name, 5, LogPresence.PRESENT));
    }

    @Test
    void aNodeNameOfOneHundredTwentyEightCharsIsTaken() {
        String name = "n".repeat(128);

        assertThat(classify(0, success(name, P + "part log ok 5")))
            .isEqualTo(new Downloaded(name, 5, LogPresence.PRESENT));
    }

    // --- node name ----------------------------------------------------------------------------

    static Stream<String> invalidNames() {
        return Stream.of("n".repeat(129), "node/1", "", "node 1", "nöde", "node1\n",
            "..\\node");
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void anInvalidNodeNameIsAnUnexpectedResponseThatDoesNotRepeatTheName(String name) {
        Outcome outcome = classify(0, success(name, P + "part log ok 5"));

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo("The client helper reported an INUBIT node"
            + " name that is not 1-128 characters of A-Z, a-z, 0-9, '_', '.', ':' and '-'");
        assertThat(error.nextStep()).isEqualTo(WORKBENCH);
        assertThat(error.node()).contains(NODE);
        assertThat(error.excerpt()).isEmpty();
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
    }

    @Test
    void anInvalidNodeNameWinsOverAMissingFile() {
        Outcome outcome = classify(0, success("node/1", partError("java.io.IOException",
            "File webservice.log not found on node/1")));

        assertThat(error(outcome).code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error(outcome).message()).doesNotContain("node/1");
    }

    // --- missing file -------------------------------------------------------------------------

    @Test
    void aPartLogErrorSayingNotFoundOnIsNotFound() {
        Outcome outcome = classify(0, success("node1", partError(
            "com.inubit.ibis.utils.InubitException", "File webservice.log not found on node1")));

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.message()).isEqualTo(
            "test/node1 has no web service log (webservice.log on INUBIT node node1)");
        assertThat(error.likelyCause()).isEqualTo(
            "Web service logging is disabled in the server configuration, or the log does not"
                + " exist yet");
        assertThat(error.nextStep()).isEqualTo(
            "Enable web service logging in the server configuration, then retry");
        assertThat(error.node()).contains(NODE);
        assertThat(error.excerpt()).isEmpty();
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
    }

    @Test
    void aNodeErrorSayingNotFoundOnIsNoMissingFile() {
        Outcome outcome = classify(0, nodeErrorRun("java.io.IOException",
            "Node not found on server"));

        assertThat(error(outcome).code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    // --- refused ------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"HTTP 403", "Server returned HTTP response code: 403 for URL",
        "403 Forbidden", "@Start@AccessDenied@@@no right@End@"})
    void aRefusedNodeLookupIsForbidden(String message) {
        Outcome outcome = classify(0, nodeErrorRun("java.io.IOException", message));

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error.message()).isEqualTo(
            "test/node1 refused the web service log download for the configured user");
        assertThat(error.likelyCause()).isEqualTo(
            "The INUBIT user of this node lacks the right to download server logs");
        assertThat(error.nextStep()).isEqualTo(
            "Give the user the right the Workbench's Cluster Manager needs (Configuration)");
        assertThat(error.node()).contains(NODE);
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
    }

    @Test
    void aRefusedDownloadIsForbiddenToo() {
        Outcome outcome = classify(0, success("node1",
            partError("java.io.IOException", "HTTP 403")));

        assertThat(error(outcome).code()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    // --- connection failures after the login -------------------------------------------------

    static Stream<Arguments> connectionFailures() {
        return Stream.of(
            Arguments.of("javax.net.ssl.SSLHandshakeException", "PKIX path building failed",
                ErrorCode.TLS_ERROR),
            Arguments.of("java.net.ConnectException", "Connection refused",
                ErrorCode.UNREACHABLE));
    }

    @ParameterizedTest
    @MethodSource("connectionFailures")
    void aConnectionFailureInTheNodeLookupOrTheDownloadFailsTheCall(String chain,
        String message, ErrorCode code) {
        Outcome node = classify(0, nodeErrorRun(chain, message));
        Outcome part = classify(0, success("node1", partError(chain, message)));

        assertThat(error(node).code()).isEqualTo(code);
        assertThat(error(part).code()).isEqualTo(code);
        assertThat(node.login()).isEqualTo(Login.ACCEPTED);
        assertThat(part.login()).isEqualTo(Login.ACCEPTED);
    }

    // --- other node and part errors -----------------------------------------------------------

    @Test
    void aServerNotInClusterModeIsAnUnexpectedResponseWithItsText() {
        Outcome outcome = classify(0, nodeErrorRun("com.inubit.ibis.utils.InubitException",
            "Server not in cluster mode."));

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo(
            "INUBIT reported an error when asked for the node of the web service log");
        assertThat(error.nextStep()).isEqualTo(WORKBENCH);
        assertThat(error.excerpt()).contains("Server not in cluster mode.");
        assertThat(error.node()).contains(NODE);
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
    }

    @Test
    void theHelpersNodeErrorForANullLookupIsAnUnexpectedResponse() {
        Outcome outcome = classify(0, P + "start SOAP_81\n" + P + "login ok\n" + P
            + "node error - " + encode("the cluster client returned no node or node name")
            + "\n" + P + "end\n");

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.excerpt()).contains("the cluster client returned no node or node name");
    }

    @Test
    void anotherPartLogErrorIsAnUnexpectedResponseWithAScrubbedExcerptOfAtMostTwoHundredChars() {
        String message = "@Start@InternalError@@@Could not copy\n  the  log " + "x".repeat(300);

        Outcome outcome = classify(0, success("node1", partError(
            "java.io.IOException>java.lang.IllegalStateException", message + "\nHTTP 500")));

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo(
            "INUBIT reported an error when downloading webservice.log from INUBIT node node1");
        assertThat(error.excerpt()).contains(
            ("@Start@InternalError@@@Could not copy the log " + "x".repeat(300))
                .substring(0, 200));
    }

    @Test
    void anErrorWithoutMessageNamesTheExceptionClasses() {
        Outcome outcome = classify(0, success("node1",
            partError("java.lang.NullPointerException>java.io.EOFException", "")));

        assertThat(error(outcome).excerpt()).contains("NullPointerException > EOFException");
    }

    // --- shared cases (HelperFailures) --------------------------------------------------------

    @Test
    void aRejectedLoginIsAuthFailedAndRejectsTheCredentials() {
        Outcome outcome = classify(3, P + "start SOAP_81\n" + P + "login error "
            + encode("com.inubit.ibis.utils.InubitException") + " " + encode("LoginFailure")
            + "\n");

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).isEqualTo(
            "Login of the client helper to test/node1 was rejected");
        assertThat(outcome.login()).isEqualTo(Login.REJECTED);
    }

    @Test
    void aMissingVendorApiIsCliUnavailable() {
        Outcome outcome = classify(4, P + "api missing "
            + encode("ClusterClient.clusterDownload(File,NodeName,String)") + "\n");

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The INUBIT client installation " + HOME_81
            + " (chosen for INUBIT 8.1) lacks ClusterClient.clusterDownload(File,NodeName,String)");
        assertThat(outcome.login()).isEqualTo(Login.UNKNOWN);
    }

    @Test
    void aRunWithoutEndFrameIsAnUnexpectedResponse() {
        Outcome outcome = classify(1, P + "start SOAP_81\n" + P + "login ok\n" + P
            + "node ok node1\n");

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo(
            "The client helper ended with exit code 1 without its end frame");
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
    }

    @Test
    void anUnknownFrameIsAnUnexpectedResponse() {
        Outcome outcome = classify(0, P + "start SOAP_81\n" + P + "login ok\n" + P
            + "cluster ok node1\n" + P + "end\n");

        assertThat(error(outcome).likelyCause())
            .isEqualTo("The client helper wrote a frame of an unknown type");
    }

    static Stream<Arguments> framesNotMatchingTheExitCode() {
        return Stream.of(
            Arguments.of(1, success("node1", P + "part log ok 5")),
            Arguments.of(0, success("node1", "")),
            Arguments.of(0, P + "start SOAP_81\n" + P + "login ok\n" + P + "part log ok 5\n" + P
                + "end\n"),
            Arguments.of(0, success("node1", P + "part data ok 5")),
            Arguments.of(0, success("node1", P + "part log ok 5\n" + P + "part log ok 5")),
            Arguments.of(0, success("node1", P + "node ok node1\n" + P + "part log ok 5")),
            Arguments.of(0, nodeErrorRun("java.io.IOException", "x").replace(P + "end\n",
                P + "part log ok 5\n" + P + "end\n")),
            Arguments.of(0, success("node1", P + "part log ok 5").replace("SOAP_81", "REST_90")),
            Arguments.of(0, P + "start SOAP_81\n" + P + "node ok node1\n" + P + "part log ok 5\n"
                + P + "end\n"),
            Arguments.of(0, P + "start SOAP_81\n" + P + "end\n"));
    }

    @ParameterizedTest
    @MethodSource("framesNotMatchingTheExitCode")
    void framesThatDoNotFormTheServerLogSequenceAreAnUnexpectedResponse(int exit,
        String stdout) {
        ToolError error = error(classify(exit, stdout));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.likelyCause()).isEqualTo(
            "The frames of the client helper do not match its exit code " + exit);
    }

    // --- leaks --------------------------------------------------------------------------------

    @Test
    void noSecretOfTheRequestOrThePayloadLeavesTheClassifier() {
        String echo = "download for jdoe with " + TestSecrets.CONFIGURED_PASSWORD + " and store "
            + TestSecrets.TRUST_STORE_PASSWORD + "; <password>"
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "</password> apiToken="
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        String noise = "DEBUG request password=" + TestSecrets.CONFIGURED_PASSWORD + " "
            + TestSecrets.TRUST_STORE_PASSWORD + " " + TestSecrets.CREDENTIAL_PAYLOAD_VALUE
            + "\n";
        List<Outcome> outcomes = List.of(
            classify(0, noise + nodeErrorRun("java.io.IOException", echo)),
            classify(0, noise + nodeErrorRun("java.io.IOException", "HTTP 403 " + echo)),
            classify(0, noise + success("node1", partError("java.io.IOException", echo))),
            classify(0, noise + success("node1", partError("java.io.IOException",
                "File webservice.log not found on node1 " + echo))),
            classify(0, noise + success("node/" + TestSecrets.CONFIGURED_PASSWORD,
                P + "part log ok 1")),
            classify(0, noise + success("node1", partError("java.net.ConnectException", echo))),
            classify(3, noise + P + "start SOAP_81\n" + P + "login error "
                + encode("java.io.IOException") + " " + encode(echo) + "\n"),
            classify(4, noise + P + "api missing " + encode(echo) + "\n"),
            classify(1, noise));

        List<String> texts = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            ToolError error = error(outcome);
            texts.add(error.message());
            texts.add(error.likelyCause());
            texts.add(error.nextStep());
            error.excerpt().ifPresent(texts::add);
        }
        texts.forEach(TestSecrets::assertNoLeak);
        assertThat(String.join("\n", texts)).doesNotContain("DEBUG request");
    }

    @Test
    void secretsRegisteredWithTheScrubberAreMaskedInTheExcerpt() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register("registered-secret-0001");
        WebServiceLogClassifier scrubbing = new WebServiceLogClassifier(scrubber, LABELS);

        Outcome outcome = scrubbing.classify(HelperRequestTest.serverLogRequest(r -> { }),
            result(0, nodeErrorRun("java.io.IOException", "token registered-secret-0001")),
            HOME_81, V81);

        assertThat(error(outcome).excerpt()).contains("token ***");
    }

    // --- helpers ------------------------------------------------------------------------------

    private Outcome classify(int exit, String stdout) {
        return classifier.classify(HelperRequestTest.serverLogRequest(r -> { }),
            result(exit, stdout), HOME_81, V81);
    }

    private static CliResult result(int exit, String stdout) {
        return new CliResult(exit, stdout, "", Duration.ofSeconds(1), false, 1 << 20);
    }

    /** Start, login ok, {@code node ok <name>}, the {@code part} line (if any), end. */
    private static String success(String name, String part) {
        return P + "start SOAP_81\n" + P + "login ok\n" + P + "node ok " + encode(name) + "\n"
            + (part.isEmpty() ? "" : part + "\n") + P + "end\n";
    }

    /** Start, login ok, {@code node error}, end: the helper downloads nothing after it. */
    private static String nodeErrorRun(String chain, String message) {
        return P + "start SOAP_81\n" + P + "login ok\n" + P + "node error " + encode(chain) + " "
            + encode(message) + "\n" + P + "end\n";
    }

    private static String partError(String chain, String message) {
        return P + "part log error " + encode(chain) + " " + encode(message);
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
}
