package de.dadecker.inubit.mcp.adapter.cli.helper;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
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
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The failures every operation of the client helper shares (feature 021 research D-3, D-14;
 * feature 020 D-5): protocol problems, {@code api missing}, the login and the TLS, network and
 * refusal failures. Each case is characterized with the queue-entry subject (the texts of feature
 * 020, byte for byte) and with a log subject; every text is checked for leaks (SC-003).
 */
class HelperFailuresTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String P = "#QE1:" + NONCE + " ";
    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final SecretLabels LABELS = new SecretLabels.Store(
        new CredentialNames("INUBIT_ACME", NODE), StoreKind.KEYCHAIN);
    private static final Path HOME_81 = Path.of("/opt/acme/inubit-8.1/client");
    private static final Path HOME_90 = Path.of("/opt/acme/inubit-9.0/client");
    private static final MajorMinor V81 = new MajorMinor(8, 1);
    private static final MajorMinor V90 = new MajorMinor(9, 0);

    private static final HelperFailures.Subject QUEUE_SUBJECT = new HelperFailures.Subject(
        "queue-entry helper", "read_queue_entry",
        "Read the entry in the Workbench (Queue Manager, View file); report the INUBIT version",
        "refused reading the Queue Manager entry for the configured user",
        "The INUBIT user of this node lacks the right to view Queue Manager files",
        "Give the user the Queue Manager right the Workbench needs for \"View file\"");
    private static final HelperFailures.Subject LOG_SUBJECT = new HelperFailures.Subject(
        "client helper", "download_server_log",
        "Download the log in the Workbench (Cluster Manager); report the INUBIT version",
        "refused the web service log download for the configured user",
        "The INUBIT user of this node lacks the right to download server logs",
        "Give the user the right the Workbench's Cluster Manager needs");

    /** Registers nothing: the request's own passwords must be masked by the failures. */
    private final HelperFailures queue =
        new HelperFailures(new SecretScrubber(), LABELS, QUEUE_SUBJECT);
    private final HelperFailures log = new HelperFailures(new SecretScrubber(), LABELS, LOG_SUBJECT);

    // --- protocol problems ------------------------------------------------------------------

    @Test
    void outputOverTheBoundIsUnexpectedEvenWithCompleteFrames() {
        String complete = success();
        CliResult cut = new CliResult(0, complete, "", Duration.ofSeconds(1), true, 1 << 20);

        assertUnexpected(failure(queue, request81(), cut, V81),
            "The output of the queue-entry helper exceeded 1 MiB and was cut");
        assertLogUnexpected(failure(log, request81(), cut, V81),
            "The output of the client helper exceeded 1 MiB and was cut");
    }

    @Test
    void aBoundThatIsNoWholeMibIsNamedInBytes() {
        CliResult cut = new CliResult(1, "", "", Duration.ofSeconds(1), true, 1000);

        assertThat(failure(queue, request81(), cut, V81).likelyCause()).isEqualTo(
            "The output of the queue-entry helper exceeded 1000 bytes and was cut");
    }

    @ParameterizedTest
    @MethodSource("frameProblems")
    void aFrameProblemIsUnexpected(String stdout, String queueCause, String logCause) {
        assertUnexpected(failure(queue, 0, stdout), queueCause);
        assertLogUnexpected(failure(log, 0, stdout), logCause);
    }

    static Stream<Arguments> frameProblems() {
        return Stream.of(
            Arguments.of(P + "start SOAP_81\n" + P + "unknown x\n",
                "The queue-entry helper wrote a frame of an unknown type",
                "The client helper wrote a frame of an unknown type"),
            Arguments.of(P + "start SOAP_81\n" + P + "login maybe\n",
                "The queue-entry helper wrote a malformed frame",
                "The client helper wrote a malformed frame"),
            Arguments.of(success() + P + "end\n",
                "The queue-entry helper wrote a frame after its end frame",
                "The client helper wrote a frame after its end frame"));
    }

    @Test
    void aSilentExitOneHasNoEndFrame() {
        assertThat(failure(queue, 1, "log4j:WARN something\n")).isNull();

        assertUnexpected(unmatched(queue, 1, "log4j:WARN something\n"),
            "The queue-entry helper ended with exit code 1 without its end frame");
        assertLogUnexpected(unmatched(log, 1, "log4j:WARN something\n"),
            "The client helper ended with exit code 1 without its end frame");
    }

    @ParameterizedTest
    @MethodSource("framesNotMatchingTheExitCode")
    void framesThatDoNotMatchTheExitCodeAreUnexpected(int exit, String stdout) {
        assertThat(failure(queue, exit, stdout)).isNull();

        assertUnexpected(unmatched(queue, exit, stdout),
            "The frames of the queue-entry helper do not match its exit code " + exit);
        assertLogUnexpected(unmatched(log, exit, stdout),
            "The frames of the client helper do not match its exit code " + exit);
    }

    static Stream<Arguments> framesNotMatchingTheExitCode() {
        String start = P + "start SOAP_81\n";
        String loginFailure = loginError("java.io.IOException", "LoginFailure");
        return Stream.of(
            Arguments.of(1, success()),
            Arguments.of(3, success()),
            Arguments.of(3, start),
            Arguments.of(3, ""),
            Arguments.of(3, loginFailure),
            Arguments.of(3, P + "start REST_90\n" + loginFailure),
            Arguments.of(3, P + "start -\n" + loginFailure),
            Arguments.of(3, start + loginFailure + P + "end\n"),
            Arguments.of(0, start + loginFailure + P + "end\n"),
            Arguments.of(4, start + P + "api missing x\n"),
            Arguments.of(4, ""),
            Arguments.of(0, P + "api missing x\n" + P + "end\n"),
            Arguments.of(0, success()));
    }

    @Test
    void aCompleteRunHasNoSharedFailure() {
        assertThat(failure(queue, 0, success())).isNull();
        assertThat(failure(log, 0, success())).isNull();
    }

    @Test
    void framesOfAnotherNonceDoNotCount() {
        String other = "#QE1:fedcba9876543210fedcba9876543210 ";
        String stdout = other + "start SOAP_81\n" + other + "login error - LoginFailure\n";

        assertThat(failure(queue, 3, stdout)).isNull();
        assertThat(unmatched(queue, 3, stdout).likelyCause()).isEqualTo(
            "The frames of the queue-entry helper do not match its exit code 3");
    }

    @Test
    void aRunStartsWithTheVariantOfItsRequest() {
        HelperRequest request = request81();

        assertThat(HelperFailures.started(frames(P + "start SOAP_81\n"), request)).isTrue();
        assertThat(HelperFailures.started(frames(P + "start REST_90\n"), request)).isFalse();
        assertThat(HelperFailures.started(frames(P + "login ok\n"), request)).isFalse();
    }

    // --- vendor API missing -----------------------------------------------------------------

    @Test
    void aMissingVendorClassIsCliUnavailableNamingTheClientEntry() {
        String stdout = P + "api missing " + encode("com.inubit.ibis.Missing") + "\n";

        for (HelperFailures failures : List.of(queue, log)) {
            ToolError error = failure(failures, 4, stdout);
            assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
            assertThat(error.message()).isEqualTo("The INUBIT client installation " + HOME_81
                + " (chosen for INUBIT 8.1) lacks com.inubit.ibis.Missing");
            assertThat(error.likelyCause()).isEqualTo("The client installation is not an INUBIT"
                + " 8.1 client, or the unsupported INUBIT interface changed after an INUBIT"
                + " update");
            assertThat(error.nextStep()).isEqualTo(
                "Check that defaults.cliClients.\"8.1\" points to an INUBIT 8.1 client");
            assertThat(error.node()).contains(NODE);
        }
    }

    @Test
    void aMissingVendorApiWithoutNameSaysWhatKindOfThingIsMissing() {
        String stdout = P + "api missing -\n";

        assertThat(failure(queue, request90(), result(4, stdout), V90).message()).isEqualTo(
            "The INUBIT client installation " + HOME_90 + " (chosen for INUBIT 9.0) lacks a class"
                + " or method the queue-entry helper needs");
        assertThat(failure(log, request90(), result(4, stdout), V90).message()).isEqualTo(
            "The INUBIT client installation " + HOME_90 + " (chosen for INUBIT 9.0) lacks a class"
                + " or method the client helper needs");
    }

    // --- login ------------------------------------------------------------------------------

    @Test
    void aRejectedLoginIsAuthFailed() {
        String stdout = P + "start SOAP_81\n" + loginError("java.io.IOException", "LoginFailure");

        ToolError queueError = failure(queue, 3, stdout);
        assertThat(queueError.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(queueError.message()).isEqualTo(
            "Login of the queue-entry helper to test/node1 was rejected");
        assertThat(queueError.likelyCause()).isEqualTo(
            "Wrong username or password for this INUBIT server, or the account is locked");
        assertThat(queueError.nextStep()).isEqualTo(LABELS.fixCredentials());
        assertThat(queueError.node()).contains(NODE);
        assertThat(queueError.excerpt()).isEmpty();
        assertThat(failure(log, 3, stdout).message()).isEqualTo(
            "Login of the client helper to test/node1 was rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LoginFailure", "The user does not exist or password does not match.",
        "HTTP 401", "401 Unauthorized", "Server returned HTTP response code: 401"})
    void aRejectedLoginOnNineNamesTheInternalUserManagement(String message) {
        String stdout = P + "start REST_90\n" + loginError("java.lang.SecurityException", message);

        ToolError queueError = failure(queue, request90(), result(3, stdout), V90);
        assertThat(queueError.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(queueError.nextStep()).isEqualTo(LABELS.fixCredentials() + ". On INUBIT 9 the"
            + " user must exist in the internal user management; users that log in only through"
            + " OAuth cannot use StartCLI or the queue-entry helper");
        assertThat(failure(log, request90(), result(3, stdout), V90).nextStep()).isEqualTo(
            LABELS.fixCredentials() + ". On INUBIT 9 the user must exist in the internal user"
                + " management; users that log in only through OAuth cannot use StartCLI or the"
                + " client helper");
    }

    @Test
    void aTlsFailureAtLoginIsATlsError() {
        String stdout = P + "start SOAP_81\n"
            + loginError("javax.net.ssl.SSLHandshakeException", "PKIX path building failed");

        ToolError queueError = failure(queue, 3, stdout);
        assertThat(queueError.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(queueError.message()).isEqualTo(
            "The queue-entry helper could not establish the TLS connection to test/node1");
        assertThat(queueError.likelyCause()).isEqualTo("The INUBIT server certificate changed"
            + " after the check before the launch, or the hostname does not match");
        assertThat(queueError.nextStep()).isEqualTo(
            "Check tls.trustStore and tls.disableHostnameVerification in the configuration");
        assertThat(failure(log, 3, stdout).message()).isEqualTo(
            "The client helper could not establish the TLS connection to test/node1");
    }

    @Test
    void aNetworkFailureAtLoginIsUnreachableNamingCliUrl() {
        String stdout = P + "start SOAP_81\n"
            + loginError("java.net.ConnectException", "Connection refused");

        ToolError queueError = failure(queue, 3, stdout);
        assertThat(queueError.code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(queueError.message()).isEqualTo(
            "The queue-entry helper could not connect to test/node1");
        assertThat(queueError.likelyCause()).isEqualTo("The INUBIT server is down, cli.url is"
            + " wrong, or the network/VPN is not connected");
        assertThat(queueError.nextStep()).isEqualTo("Check get_health and cli.url, then retry");
        assertThat(failure(log, 3, stdout).message()).isEqualTo(
            "The client helper could not connect to test/node1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP 403", "AccessDenied", "403 Forbidden"})
    void aRefusedLoginIsForbiddenWithTheTextsOfTheSubject(String message) {
        String stdout = P + "start SOAP_81\n" + loginError("java.io.IOException", message);

        assertQueueForbidden(failure(queue, 3, stdout));
        assertLogForbidden(failure(log, 3, stdout));
    }

    @Test
    void anUnknownLoginErrorIsUnexpectedWithAScrubbedExcerpt() {
        String stdout = P + "start SOAP_81\n"
            + loginError("java.lang.IllegalStateException", "HTTP 500\nserver busy");

        ToolError queueError = failure(queue, 3, stdout);
        assertUnexpected(queueError, "The login of the queue-entry helper failed with an error"
            + " this MCP server does not know");
        assertThat(queueError.excerpt()).contains("HTTP 500 server busy");
        ToolError logError = failure(log, 3, stdout);
        assertThat(logError.message()).isEqualTo(LOG_UNEXPECTED);
        assertThat(logError.likelyCause()).isEqualTo("The login of the client helper failed with"
            + " an error this MCP server does not know");
    }

    // --- failures of the whole call after the login ------------------------------------------

    @ParameterizedTest
    @MethodSource("connectionFailures")
    void aConnectionFailureFailsTheWholeCall(String chain, String message, ErrorCode code) {
        assertThat(queue.callFailure(List.of(chain), message).map(ToolError::code))
            .contains(code);
        assertThat(log.callFailure(List.of(chain), message).map(ToolError::code))
            .contains(code);
    }

    static Stream<Arguments> connectionFailures() {
        return Stream.of(
            Arguments.of("javax.net.ssl.SSLHandshakeException", "", ErrorCode.TLS_ERROR),
            Arguments.of("java.security.cert.CertificateException",
                "No subject alternative names present", ErrorCode.TLS_ERROR),
            Arguments.of("com.inubit.ibis.utils.IBISException",
                "wrapped: javax.net.ssl.SSLException: Unrecognized SSL message",
                ErrorCode.TLS_ERROR),
            Arguments.of("java.net.UnknownHostException", "inubit.example.test",
                ErrorCode.UNREACHABLE),
            Arguments.of("java.net.SocketTimeoutException", "Read timed out",
                ErrorCode.UNREACHABLE),
            Arguments.of("java.io.IOException", "HTTP 403", ErrorCode.FORBIDDEN),
            Arguments.of("java.io.IOException", "AccessDenied", ErrorCode.FORBIDDEN));
    }

    @Test
    void aRefusalAfterTheLoginHasTheTextsOfTheSubject() {
        assertQueueForbidden(queue.callFailure(List.of("java.io.IOException"),
            "Server returned HTTP response code: 403").orElseThrow());
        assertLogForbidden(log.callFailure(List.of("java.io.IOException"), "statusCode=403")
            .orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP 4030", "status 500", "file 403.xml missing", "LoginFailure"})
    void anOrdinaryErrorIsNoCallFailure(String message) {
        assertThat(queue.callFailure(List.of("java.io.IOException"), message)).isEmpty();
    }

    // --- excerpts ---------------------------------------------------------------------------

    @Test
    void theDetailsAreTheCollapsedMessageCutAtTwoHundredChars() {
        String message = "first line\n\tsecond " + "x".repeat(300);

        String details = queue.details(request81(), List.of("java.io.IOException"), message);

        assertThat(details).hasSize(200).startsWith("first line second xxx");
    }

    @Test
    void detailsWithoutMessageNameTheExceptionClassesAndWithoutAnyNamesNoDetails() {
        assertThat(queue.details(request81(),
            List.of("java.io.IOException", "java.net.SocketException"), ""))
            .isEqualTo("IOException > SocketException");
        assertThat(queue.details(request81(), List.of(), "")).isEqualTo("no details");
    }

    @Test
    void theExcerptIsCutBeforeALoneHighSurrogate() {
        String message = "x".repeat(199) + "😀 tail";

        assertThat(queue.details(request81(), List.of(), message)).isEqualTo("x".repeat(199));
    }

    // --- leak check -------------------------------------------------------------------------

    @Test
    void noSecretOfTheRequestOrThePayloadLeavesTheFailures() {
        String echo = "login of jdoe with " + TestSecrets.CONFIGURED_PASSWORD + " and store "
            + TestSecrets.TRUST_STORE_PASSWORD + "; <password>"
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "</password> apiToken="
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        String noise = "DEBUG request password=" + TestSecrets.CONFIGURED_PASSWORD + " "
            + TestSecrets.TRUST_STORE_PASSWORD + " " + TestSecrets.CREDENTIAL_PAYLOAD_VALUE
            + "\n";
        String start = noise + P + "start SOAP_81\n";
        List<ToolError> errors = new ArrayList<>();
        for (HelperFailures failures : List.of(queue, log)) {
            errors.add(failure(failures, 3, start + loginError("java.io.IOException", echo)));
            errors.add(failure(failures, 3, start + loginError("java.io.IOException",
                "LoginFailure " + echo)));
            errors.add(failure(failures, 3, start + loginError("javax.net.ssl.SSLException",
                echo)));
            errors.add(failure(failures, 3, start + loginError("java.io.IOException",
                "HTTP 403 " + echo)));
            errors.add(failure(failures, 4, noise + P + "api missing " + encode(echo) + "\n"));
            errors.add(failure(failures, 0, start + P + "unknown " + encode(echo) + "\n"));
            errors.add(unmatched(failures, 1, noise));
            errors.add(failures.callFailure(List.of("java.io.IOException"), "HTTP 403 " + echo)
                .orElseThrow());
        }

        List<String> texts = new ArrayList<>();
        for (ToolError error : errors) {
            texts.add(error.message());
            texts.add(error.likelyCause());
            texts.add(error.nextStep());
            error.excerpt().ifPresent(texts::add);
        }
        texts.add(queue.details(request81(), List.of("java.io.IOException"), echo));
        texts.forEach(TestSecrets::assertNoLeak);
        assertThat(String.join("\n", texts)).doesNotContain("DEBUG request");
    }

    @Test
    void secretsRegisteredWithTheScrubberAreMaskedToo() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register("registered-secret-0001");
        HelperFailures scrubbing = new HelperFailures(scrubber, LABELS, QUEUE_SUBJECT);

        assertThat(scrubbing.details(request81(), List.of(), "token registered-secret-0001"))
            .isEqualTo("token ***");
    }

    // --- helpers ----------------------------------------------------------------------------

    private static final String QUEUE_UNEXPECTED = "read_queue_entry got an unexpected answer"
        + " from test/node1; the unsupported INUBIT interface may have changed after an INUBIT"
        + " update";
    private static final String LOG_UNEXPECTED = "download_server_log got an unexpected answer"
        + " from test/node1; the unsupported INUBIT interface may have changed after an INUBIT"
        + " update";

    private static void assertUnexpected(ToolError error, String likelyCause) {
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(QUEUE_UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo(likelyCause);
        assertThat(error.nextStep()).isEqualTo(QUEUE_SUBJECT.unexpectedNextStep());
        assertThat(error.node()).contains(NODE);
    }

    private static void assertLogUnexpected(ToolError error, String likelyCause) {
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(LOG_UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo(likelyCause);
        assertThat(error.nextStep()).isEqualTo(
            "Download the log in the Workbench (Cluster Manager); report the INUBIT version");
        assertThat(error.excerpt()).isEmpty();
    }

    private static void assertQueueForbidden(ToolError error) {
        assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error.message()).isEqualTo(
            "test/node1 refused reading the Queue Manager entry for the configured user");
        assertThat(error.likelyCause()).isEqualTo(
            "The INUBIT user of this node lacks the right to view Queue Manager files");
        assertThat(error.nextStep()).isEqualTo(
            "Give the user the Queue Manager right the Workbench needs for \"View file\"");
        assertThat(error.node()).contains(NODE);
    }

    private static void assertLogForbidden(ToolError error) {
        assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error.message()).isEqualTo(
            "test/node1 refused the web service log download for the configured user");
        assertThat(error.nextStep()).isEqualTo(
            "Give the user the right the Workbench's Cluster Manager needs");
    }

    /** The shared failure of an 8.1 run, or {@code null} if it has none. */
    private static ToolError failure(HelperFailures failures, int exit, String stdout) {
        return failure(failures, request81(), result(exit, stdout), V81);
    }

    private static ToolError failure(HelperFailures failures, HelperRequest request,
        CliResult result, MajorMinor release) {
        Path home = release.equals(V90) ? HOME_90 : HOME_81;
        Optional<ToolError> error = failures.failure(request, result,
            HelperFrames.parse(request.nonce(), result.stdout()), home, release);
        return error.orElse(null);
    }

    private static ToolError unmatched(HelperFailures failures, int exit, String stdout) {
        return failures.unmatched(HelperFrames.parse(NONCE, stdout), exit);
    }

    private static List<HelperFrames.Frame> frames(String stdout) {
        return HelperFrames.parse(NONCE, stdout).frames();
    }

    private static HelperRequest request81() {
        return HelperRequestTest.request(r -> { });
    }

    private static HelperRequest request90() {
        return HelperRequestTest.request(r -> r.variant = "REST_90");
    }

    private static CliResult result(int exit, String stdout) {
        return new CliResult(exit, stdout, "", Duration.ofSeconds(1), false, 1 << 20);
    }

    /** Start, login ok, end: the frames a complete run shares with every operation. */
    private static String success() {
        return P + "start SOAP_81\n" + P + "login ok\n" + P + "end\n";
    }

    private static String loginError(String chain, String message) {
        return P + "login error " + encode(chain) + " " + encode(message) + "\n";
    }

    /** A frame token as the helper writes it: RFC 3986 percent-encoded, {@code -} if empty. */
    private static String encode(String text) {
        return text.isEmpty() ? "-" : URLEncoder.encode(text, StandardCharsets.UTF_8)
            .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
}
