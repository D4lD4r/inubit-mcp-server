package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperClassifier.Failure;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperClassifier.Login;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperClassifier.Outcome;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperClassifier.PartReport;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperClassifier.Reported;
import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Frames and exit code of the queue-entry helper to a tool error or part reports (research D-5,
 * FR-012, FR-013), against the recorded 8.1 {@code not_found_file}, the reconstructed 8.1 and
 * 9.0 spike texts in {@code fixtures/queue-entry/} and inline frames; every text that leaves the
 * classifier is checked for leaks (SC-003).
 */
class QueueEntryHelperClassifierTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String P = "#QE1:" + NONCE + " ";
    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final SecretLabels LABELS = new SecretLabels.Store(
        new CredentialNames("INUBIT_ACME", NODE), StoreKind.KEYCHAIN);
    private static final Path HOME_81 = Path.of("/opt/acme/inubit-8.1/client");
    private static final Path HOME_90 = Path.of("/opt/acme/inubit-9.0/client");
    private static final MajorMinor V81 = new MajorMinor(8, 1);
    private static final MajorMinor V90 = new MajorMinor(9, 0);
    private static final String UNEXPECTED = "read_queue_entry got an unexpected answer from"
        + " test/node1; the unsupported INUBIT interface may have changed after an INUBIT update";
    private static final String WORKBENCH = "Read the entry in the Workbench (Queue Manager,"
        + " View file); report the INUBIT version";
    private static final String INTERNAL_USERS = ". On INUBIT 9 the user must exist in the"
        + " internal user management; users that log in only through OAuth cannot use StartCLI"
        + " or the queue-entry helper";
    private static final String FILE = "module-0001(1001)@100000001@"
        + "00000000-0a00-0000-0000-000000000001_1790861373736";

    /** Registers nothing: the request's own passwords must be masked by the classifier itself. */
    private final QueueEntryHelperClassifier classifier =
        new QueueEntryHelperClassifier(new SecretScrubber(), LABELS);

    // --- success -----------------------------------------------------------------------------

    @Test
    void aCompleteReadReportsBothPartsAndAcceptsTheLogin() {
        Outcome outcome = classify81(0, "noise before\n"
            + P + "start SOAP_81\n" + P + "login ok\n" + P + "part data ok 772\n"
            + "noise between\n" + P + "part variables ok 1234\n" + P + "end\n");

        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
        assertThat(outcome).isInstanceOfSatisfying(Reported.class, reported ->
            assertThat(reported.parts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                EntryPart.DATA, new PartReport.Written(772),
                EntryPart.VARIABLES, new PartReport.Written(1234))));
    }

    @Test
    void aVariablesOnlyReadReportsOnlyTheVariables() {
        HelperRequest request = HelperRequestTest.request(r -> r.dataOut = Optional.empty());

        Outcome outcome = classifier.classify(request, result(0, ""
            + P + "start SOAP_81\n" + P + "login ok\n" + P + "part variables ok 0\n" + P
            + "end\n"), HOME_81, V81);

        assertThat(outcome).isInstanceOfSatisfying(Reported.class, reported ->
            assertThat(reported.parts()).containsExactlyEntriesOf(
                Map.of(EntryPart.VARIABLES, new PartReport.Written(0))));
    }

    // --- parts not found / failed -----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"v8_1/not_found_file", "v9_0/not_found_file",
        "v9_0/missing_workflow"})
    void theSpikeMarkersOfAMissingFileOrWorkflowMakeBothPartsNotFound(String fixture) {
        if (fixture.startsWith("v8_1/")) {
            assertThat(fixtureText(fixture + ".origin")).as("tasks.md T035")
                .startsWith("recorded 2026-11-11 8.1.17 neutralized");
        }
        Outcome outcome = classifyFixture(fixture);

        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
        assertThat(outcome).isInstanceOfSatisfying(Reported.class, reported ->
            assertThat(reported.parts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                EntryPart.DATA, new PartReport.NotFound(),
                EntryPart.VARIABLES, new PartReport.NotFound())));
    }

    @Test
    void anotherPartErrorFailsOnlyThatPartWithAScrubbedExcerptOfAtMostTwoHundredChars() {
        String message = "@Start@InternalError@@@Could not read\n  the  entry " + "x".repeat(300);

        Outcome outcome = classify81(0, success(
            partError("data", "java.io.IOException>java.lang.IllegalStateException",
                message + "\nHTTP 500"),
            P + "part variables ok 12"));

        assertThat(outcome).isInstanceOfSatisfying(Reported.class, reported -> {
            assertThat(reported.parts().get(EntryPart.VARIABLES))
                .isEqualTo(new PartReport.Written(12));
            String expected = "@Start@InternalError@@@Could not read the entry "
                + "x".repeat(300);
            assertThat(reported.parts().get(EntryPart.DATA)).isEqualTo(new PartReport.Failed(
                "INUBIT reported an error: " + expected.substring(0, 200)));
        });
    }

    @Test
    void aPartErrorWithoutMessageNamesTheExceptionClassesAndWithoutAnyNamesNoDetails() {
        Outcome outcome = classify81(0, success(
            partError("data", "java.lang.NullPointerException>java.io.EOFException", ""),
            partError("variables", "", "")));

        assertThat(outcome).isInstanceOfSatisfying(Reported.class, reported ->
            assertThat(reported.parts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                EntryPart.DATA, new PartReport.Failed(
                    "INUBIT reported an error: NullPointerException > EOFException"),
                EntryPart.VARIABLES, new PartReport.Failed(
                    "INUBIT reported an error: no details"))));
    }

    @Test
    void theExcerptIsCutBeforeALoneHighSurrogate() {
        String message = "e".repeat(199) + "\uD83D\uDE00 rest";

        Outcome outcome = classify81(0, success(partError("data", "java.io.IOException",
            message), P + "part variables ok 1"));

        assertThat(outcome).isInstanceOfSatisfying(Reported.class, reported ->
            assertThat(reported.parts().get(EntryPart.DATA)).isEqualTo(new PartReport.Failed(
                "INUBIT reported an error: " + "e".repeat(199))));
    }

    @ParameterizedTest
    @MethodSource("connectionFailures")
    void aConnectionFailureInAPartFailsTheWholeCall(String chain, String message,
        ErrorCode code) {
        Outcome outcome = classify81(0, success(P + "part data ok 1",
            partError("variables", chain, message)));

        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
        assertThat(error(outcome).code()).isEqualTo(code);
        assertThat(error(outcome).node()).contains(NODE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP 403", "Server returned HTTP response code: 403 for URL",
        "403 Forbidden", "{\"statusCode\": 403, \"message\": \"Access denied\"}",
        "@Start@AccessDenied@@@no right@End@"})
    void aRefusedPartReadIsForbidden(String message) {
        Outcome outcome = classify81(0, success(partError("data", "java.io.IOException",
            message), P + "part variables ok 1"));

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error.message()).isEqualTo(
            "test/node1 refused reading the Queue Manager entry for the configured user");
        assertThat(error.nextStep()).isEqualTo(
            "Give the user the Queue Manager right the Workbench needs for \"View file\"");
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
    }

    @Test
    void aStatusCodeInsideOtherNumbersIsNoRefusal() {
        Outcome outcome = classify81(0, success(partError("data", "java.io.IOException",
            "entry 14031 failed after 4403 ms"), P + "part variables ok 1"));

        assertThat(outcome).isInstanceOf(Reported.class);
    }

    @Test
    void aPartErrorThatOnlyMentionsAccessDeniedIsAnOrdinaryPartFailure() {
        Outcome outcome = classify81(0, success(partError("data", "java.io.IOException",
            "access denied to /archive/Order-0001.xml"), P + "part variables ok 1"));

        assertThat(outcome).isInstanceOfSatisfying(Reported.class, reported -> {
            assertThat(reported.parts().get(EntryPart.DATA)).isEqualTo(new PartReport.Failed(
                "INUBIT reported an error: access denied to /archive/Order-0001.xml"));
            assertThat(reported.parts().get(EntryPart.VARIABLES))
                .isEqualTo(new PartReport.Written(1));
        });
    }

    // --- login ------------------------------------------------------------------------------

    @Test
    void theReconstructedLoginFailureIsAuthFailedAndRejectsTheCredentials() {
        Outcome outcome = classifyFixture("v8_1/login_failure");

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).isEqualTo(
            "Login of the queue-entry helper to test/node1 was rejected");
        assertThat(error.likelyCause()).isEqualTo(
            "Wrong username or password for this INUBIT server, or the account is locked");
        assertThat(error.nextStep()).isEqualTo(LABELS.fixCredentials());
        assertThat(error.node()).contains(NODE);
        assertThat(error.excerpt()).isEmpty();
        assertThat(outcome.login()).isEqualTo(Login.REJECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LoginFailure", "The user does not exist or password does not match.",
        "HTTP 401", "401 Unauthorized", "Server returned HTTP response code: 401"})
    void aRejectedLoginOnNineNamesTheInternalUserManagement(String message) {
        Outcome outcome = classify90(3, P + "start REST_90\n"
            + loginError("java.lang.SecurityException", message));

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.nextStep()).isEqualTo(LABELS.fixCredentials() + INTERNAL_USERS);
        assertThat(outcome.login()).isEqualTo(Login.REJECTED);
    }

    @Test
    void theReconstructedTlsFailureIsATlsError() {
        Outcome outcome = classifyFixture("v8_1/tls_failure");

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
        assertThat(error.message()).isEqualTo(
            "The queue-entry helper could not establish the TLS connection to test/node1");
        assertThat(error.nextStep()).isEqualTo(
            "Check tls.trustStore and tls.disableHostnameVerification in the configuration");
        assertThat(outcome.login()).isEqualTo(Login.UNKNOWN);
    }

    @ParameterizedTest
    @MethodSource("connectionFailures")
    void aConnectionFailureAtLoginHasItsCodeAndDecidesNothingAboutTheCredentials(String chain,
        String message, ErrorCode code) {
        Outcome outcome = classify81(3, P + "start SOAP_81\n" + loginError(chain, message));

        assertThat(error(outcome).code()).isEqualTo(code);
        assertThat(outcome.login()).isEqualTo(Login.UNKNOWN);
    }

    static Stream<Arguments> connectionFailures() {
        return Stream.of(
            Arguments.of("javax.net.ssl.SSLHandshakeException", "", ErrorCode.TLS_ERROR),
            Arguments.of("javax.net.ssl.SSLPeerUnverifiedException", "x", ErrorCode.TLS_ERROR),
            Arguments.of("java.security.cert.CertificateException",
                "No subject alternative names present", ErrorCode.TLS_ERROR),
            Arguments.of("java.lang.IllegalStateException", "PKIX path building failed",
                ErrorCode.TLS_ERROR),
            Arguments.of("com.inubit.ibis.utils.IBISException",
                "wrapped: javax.net.ssl.SSLException: Unrecognized SSL message",
                ErrorCode.TLS_ERROR),
            Arguments.of("java.io.IOException>java.net.ConnectException", "x\nConnection refused",
                ErrorCode.UNREACHABLE),
            Arguments.of("java.net.UnknownHostException", "inubit.example.test",
                ErrorCode.UNREACHABLE),
            Arguments.of("java.net.NoRouteToHostException", "", ErrorCode.UNREACHABLE),
            Arguments.of("java.net.SocketTimeoutException", "Read timed out",
                ErrorCode.UNREACHABLE),
            Arguments.of("java.lang.RuntimeException",
                "java.net.ConnectException: Connection refused", ErrorCode.UNREACHABLE));
    }

    @Test
    void anUnreachableServerNamesCliUrl() {
        ToolError error = error(classify81(3, P + "start SOAP_81\n"
            + loginError("java.net.ConnectException", "Connection refused")));

        assertThat(error.message()).isEqualTo(
            "The queue-entry helper could not connect to test/node1");
        assertThat(error.nextStep()).isEqualTo("Check get_health and cli.url, then retry");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP 403", "AccessDenied"})
    void aRefusedLoginIsForbidden(String message) {
        Outcome outcome = classify81(3, P + "start SOAP_81\n"
            + loginError("java.io.IOException", message));

        assertThat(error(outcome).code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(outcome.login()).isEqualTo(Login.UNKNOWN);
    }

    @Test
    void anUnknownLoginErrorIsUnexpectedWithAScrubbedExcerpt() {
        Outcome outcome = classify81(3, P + "start SOAP_81\n"
            + loginError("java.lang.IllegalStateException", "HTTP 500\nserver busy"));

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo(
            "The login of the queue-entry helper failed with an error this MCP server does not"
                + " know");
        assertThat(error.excerpt()).contains("HTTP 500 server busy");
        assertThat(outcome.login()).isEqualTo(Login.UNKNOWN);
    }

    // --- vendor API missing -----------------------------------------------------------------

    @Test
    void theSyntheticMissingVendorClassIsCliUnavailableNamingTheClientEntry() {
        ToolError error = error(classifyFixture("v8_1/api_missing"));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The INUBIT client installation "
            + HOME_81 + " (chosen for INUBIT 8.1) lacks"
            + " com.inubit.ibis.configuration.utils.io.MultipartDownloadThread");
        assertThat(error.nextStep()).isEqualTo(
            "Check that defaults.cliClients.\"8.1\" points to an INUBIT 8.1 client");
        assertThat(error.node()).contains(NODE);
    }

    @Test
    void aMissingVendorApiWithoutNameSaysWhatKindOfThingIsMissing() {
        ToolError error = error(classifier.classify(HelperRequestTest.request(
            r -> r.variant = "REST_90"), result(4, P + "api missing -\n"), HOME_90, V90));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The INUBIT client installation " + HOME_90
            + " (chosen for INUBIT 9.0) lacks a class or method the queue-entry helper needs");
        assertThat(error.nextStep()).isEqualTo(
            "Check that defaults.cliClients.\"9.0\" points to an INUBIT 9.0 client");
    }

    // --- unexpected answers -----------------------------------------------------------------

    @Test
    void theSyntheticUnknownFrameTypeIsUnexpected() {
        ToolError error = error(classifyFixture("v8_1/garbage_frames"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo(
            "The queue-entry helper wrote a frame of an unknown type");
        assertThat(error.nextStep()).isEqualTo(WORKBENCH);
        assertThat(error.excerpt()).isEmpty();
    }

    @Test
    void outputOverTheBoundIsUnexpectedEvenWithCompleteFrames() {
        String complete = success(P + "part data ok 1", P + "part variables ok 1");

        ToolError error = error(classifier.classify(HelperRequestTest.request(r -> { }),
            new CliResult(0, complete, "", Duration.ofSeconds(1), true, 1 << 20), HOME_81, V81));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.likelyCause()).isEqualTo(
            "The output of the queue-entry helper exceeded 1 MiB and was cut");
    }

    @Test
    void aSilentExitOneIsUnexpected() {
        Outcome outcome = classify81(1, "log4j:WARN something\n");

        ToolError error = error(outcome);
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo(
            "The queue-entry helper ended with exit code 1 without its end frame");
        assertThat(error.nextStep()).isEqualTo(WORKBENCH);
        assertThat(error.excerpt()).isEmpty();
        assertThat(outcome.login()).isEqualTo(Login.UNKNOWN);
    }

    @Test
    void anExitAfterASuccessfulLoginStillAcceptsTheCredentials() {
        Outcome outcome = classify81(1, P + "start SOAP_81\n" + P + "login ok\n");

        assertThat(error(outcome).code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(outcome.login()).isEqualTo(Login.ACCEPTED);
    }

    @ParameterizedTest
    @MethodSource("framesNotMatchingTheExitCode")
    void framesThatDoNotMatchTheExitCodeAreUnexpected(int exit, String stdout, String cause) {
        ToolError error = error(classify81(exit, stdout));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.likelyCause()).isEqualTo(cause);
        assertThat(error.excerpt()).isEmpty();
    }

    static Stream<Arguments> framesNotMatchingTheExitCode() {
        String start = P + "start SOAP_81\n";
        String data = P + "part data ok 1";
        String variables = P + "part variables ok 1";
        String loginFailure = loginError("java.io.IOException", "LoginFailure");
        String mismatch = "The frames of the queue-entry helper do not match its exit code ";
        String noEnd = "The queue-entry helper ended with exit code ";
        return Stream.of(
            Arguments.of(2, "", noEnd + "2 without its end frame"),
            Arguments.of(0, start + P + "login ok\n" + data + "\n" + variables + "\n",
                noEnd + "0 without its end frame"),
            Arguments.of(1, success(data, variables), mismatch + "1"),
            Arguments.of(3, success(data, variables), mismatch + "3"),
            Arguments.of(3, start, mismatch + "3"),
            Arguments.of(3, loginFailure, mismatch + "3"),
            Arguments.of(3, P + "start REST_90\n" + loginFailure, mismatch + "3"),
            Arguments.of(3, start + loginFailure + P + "end\n", mismatch + "3"),
            Arguments.of(0, start + loginFailure + P + "end\n", mismatch + "0"),
            Arguments.of(4, start + P + "api missing x\n", mismatch + "4"),
            Arguments.of(4, "", mismatch + "4"),
            Arguments.of(3, P + "start SOAP_81\n" + P + "login ok\n", mismatch + "3"),
            Arguments.of(0, P + "api missing x\n" + P + "end\n", mismatch + "0"),
            Arguments.of(0, P + "start -\n" + P + "login ok\n" + data + "\n" + variables + "\n"
                + P + "end\n", mismatch + "0"),
            Arguments.of(0, P + "start REST_90\n" + P + "login ok\n" + data + "\n" + variables
                + "\n" + P + "end\n", mismatch + "0"),
            Arguments.of(0, P + "login ok\n" + data + "\n" + variables + "\n" + P + "end\n",
                mismatch + "0"),
            Arguments.of(0, start + data + "\n" + variables + "\n" + P + "end\n", mismatch + "0"),
            Arguments.of(0, success(variables, data), mismatch + "0"),
            Arguments.of(0, success(data), mismatch + "0"),
            Arguments.of(0, success(variables), mismatch + "0"),
            Arguments.of(0, success(data, data, variables), mismatch + "0"),
            Arguments.of(0, success(data, variables, variables), mismatch + "0"),
            Arguments.of(0, start + P + "login ok\n" + P + "login ok\n" + data + "\n" + variables
                + "\n" + P + "end\n", mismatch + "0"),
            Arguments.of(0, success(data, variables) + P + "end\n",
                "The queue-entry helper wrote a frame after its end frame"),
            Arguments.of(0, start + P + "login maybe\n",
                "The queue-entry helper wrote a malformed frame"));
    }

    @Test
    void aVariablesOnlyRequestAnsweredWithADataPartIsUnexpected() {
        HelperRequest request = HelperRequestTest.request(r -> r.dataOut = Optional.empty());

        ToolError error = error(classifier.classify(request, result(0,
            success(P + "part data ok 1", P + "part variables ok 1")), HOME_81, V81));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void framesOfAnotherNonceDoNotCount() {
        String other = "#QE1:fedcba9876543210fedcba9876543210 ";

        ToolError error = error(classify81(0, other + "start SOAP_81\n" + other + "login ok\n"
            + other + "part data ok 1\n" + other + "part variables ok 1\n" + other + "end\n"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    // --- leak check -------------------------------------------------------------------------

    @Test
    void noSecretOfTheRequestOrThePayloadLeavesTheClassifier() {
        String echo = "login of jdoe with " + TestSecrets.CONFIGURED_PASSWORD + " and store "
            + TestSecrets.TRUST_STORE_PASSWORD + "; <password>"
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "</password> apiToken="
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
        String noise = "DEBUG request password=" + TestSecrets.CONFIGURED_PASSWORD + " "
            + TestSecrets.TRUST_STORE_PASSWORD + " " + TestSecrets.CREDENTIAL_PAYLOAD_VALUE
            + "\n";
        List<Outcome> outcomes = List.of(
            classify81(0, noise + success(partError("data", "java.io.IOException", echo),
                partError("variables", "java.io.IOException", echo + " HTTP 418"))),
            classify81(3, noise + P + "start SOAP_81\n" + loginError("java.io.IOException",
                echo)),
            classify81(3, noise + P + "start SOAP_81\n" + loginError("java.io.IOException",
                "LoginFailure " + echo)),
            classify81(3, noise + P + "start SOAP_81\n" + loginError(
                "javax.net.ssl.SSLException", echo)),
            classify81(0, noise + success(P + "part data ok 1",
                partError("variables", "java.io.IOException", "HTTP 403 " + echo))),
            classify81(4, noise + P + "api missing " + encode(echo) + "\n"),
            classify81(1, noise),
            classify81(0, noise + P + "start SOAP_81\n" + P + "unknown " + encode(echo) + "\n"));

        List<String> texts = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            if (outcome instanceof Failure failure) {
                ToolError error = failure.error();
                texts.add(error.message());
                texts.add(error.likelyCause());
                texts.add(error.nextStep());
                error.excerpt().ifPresent(texts::add);
            } else {
                ((Reported) outcome).parts().values().forEach(part -> texts.add(part.toString()));
            }
        }
        assertThat(texts).hasSizeGreaterThan(outcomes.size());
        texts.forEach(TestSecrets::assertNoLeak);
        assertThat(String.join("\n", texts)).doesNotContain("DEBUG request");
    }

    @Test
    void secretsRegisteredWithTheScrubberAreMaskedToo() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register("registered-secret-0001");
        QueueEntryHelperClassifier scrubbing = new QueueEntryHelperClassifier(scrubber, LABELS);

        Outcome outcome = scrubbing.classify(HelperRequestTest.request(r -> { }), result(0,
            success(partError("data", "java.io.IOException", "token registered-secret-0001"),
                P + "part variables ok 1")), HOME_81, V81);

        assertThat(((Reported) outcome).parts().get(EntryPart.DATA)).isEqualTo(
            new PartReport.Failed("INUBIT reported an error: token ***"));
    }

    // --- helpers ----------------------------------------------------------------------------

    private Outcome classify81(int exit, String stdout) {
        return classifier.classify(HelperRequestTest.request(r -> { }), result(exit, stdout),
            HOME_81, V81);
    }

    private Outcome classify90(int exit, String stdout) {
        return classifier.classify(HelperRequestTest.request(r -> r.variant = "REST_90"),
            result(exit, stdout), HOME_90, V90);
    }

    /** {@code <version>/<case>} with its {@code .origin} (a recording or a reconstruction). */
    private Outcome classifyFixture(String fixture) {
        String origin = fixtureText(fixture + ".origin");
        assertThat(origin).containsPattern(
            "^(reconstructed spike 2026-11-10|recorded \\S+ \\S+ neutralized)");
        assertThat(fixtureText(fixture + ".README")).isNotBlank();
        int exit = Integer.parseInt(fixtureText(fixture + ".exit").strip());
        String stdout = fixtureText(fixture + ".frames");
        return fixture.startsWith("v9_0/") ? classify90(exit, stdout) : classify81(exit, stdout);
    }

    private static String fixtureText(String name) {
        String resource = "/fixtures/queue-entry/" + name;
        try (InputStream in = QueueEntryHelperClassifierTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CliResult result(int exit, String stdout) {
        return new CliResult(exit, stdout, "", Duration.ofSeconds(1), false, 1 << 20);
    }

    /** Start, login ok, the {@code parts} lines, end. */
    private static String success(String... parts) {
        return P + "start SOAP_81\n" + P + "login ok\n" + String.join("\n", parts) + "\n" + P
            + "end\n";
    }

    private static String partError(String part, String chain, String message) {
        return P + "part " + part + " error " + encode(chain) + " " + encode(message);
    }

    private static String loginError(String chain, String message) {
        return P + "login error " + encode(chain) + " " + encode(message) + "\n";
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
