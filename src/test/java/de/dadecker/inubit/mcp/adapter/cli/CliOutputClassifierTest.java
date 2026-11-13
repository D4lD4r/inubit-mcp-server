package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliOutput.Marker;
import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.ParameterNames;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.config.StoreKind;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T023: preamble and log stripping, {@code n-OK}/{@code n-NOK} and marker extraction, and the
 * classification table of research R-7, against the recorded fixtures of S-2/S-3 (T009).
 */
class CliOutputClassifierTest {

    private static final String PREAMBLE = "JAVA_HOME is set\nPassword: \nSECURITY WARNING:"
        + " Hostname verification is disabled. Your connection might not be secured!\n";
    private static final String PICKED_UP =
        "Picked up JAVA_TOOL_OPTIONS: -Duser.language=en -Duser.country=US\n";

    private final SecretScrubber scrubber = new SecretScrubber();
    private final CliOutputClassifier classifier = new CliOutputClassifier(scrubber);

    private static CliResult fixture(String fixtureCase) {
        return new CliResult(
            Integer.parseInt(FakeProcessLauncher.fixtureText(fixtureCase + ".exit").strip()),
            FakeProcessLauncher.fixtureText(fixtureCase + ".stdout"),
            FakeProcessLauncher.fixtureText(fixtureCase + ".stderr"),
            Duration.ofSeconds(2), false, CliRunner.MAX_OUTPUT_BYTES);
    }

    private static CliResult result(int exitCode, String stdout) {
        return new CliResult(exitCode, stdout, PICKED_UP, Duration.ofSeconds(1), false,
            CliRunner.MAX_OUTPUT_BYTES);
    }

    private ToolError failure(CliResult result) {
        CliOutcome outcome = classifier.classify(result);
        assertThat(outcome).isInstanceOf(CliOutcome.Failure.class);
        return ((CliOutcome.Failure) outcome).error();
    }

    // --- parsing --------------------------------------------------------------------------

    @Test
    void thePreambleIsStrippedFromStdoutAndStderr() {
        CliOutput output = classifier.parse(fixture("processErrorStart_ok"));

        assertThat(output.outputLines()).containsExactly("1-OK: Process 110190387 restarted.");
        assertThat(output.logLines()).isEmpty();
    }

    @Test
    void aPasswordPromptWithoutLineBreakIsStrippedFromTheFollowingText() {
        CliOutput output = classifier.parse(result(0,
            "JAVA_HOME is set\nPassword: 1-OK: Module exported successfully.\n"));

        assertThat(output.outputLines()).containsExactly("1-OK: Module exported successfully.");
    }

    /** T023 (feature 013, FR-005): the client's prompt as the last output of a call. */
    @ParameterizedTest
    @ValueSource(strings = {"Password: ", "Password:", "Password: \n", "Password: \r\n",
        "JAVA_HOME is set\r\nPassword: ", "JAVA_HOME is set\nPassword: \n\n"})
    void thePasswordPromptAsTheLastOutputIsRecognised(String escaped) {
        assertThat(CliOutputClassifier.endsAtThePasswordPrompt(unescape(escaped))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "Password: 1-OK: Module exported successfully.\n",
        "Password: \nConnecting\n", "JAVA_HOME is set\n", "Enter Password: x"})
    void otherOutputIsNotThePasswordPrompt(String escaped) {
        assertThat(CliOutputClassifier.endsAtThePasswordPrompt(unescape(escaped))).isFalse();
    }

    private static String unescape(String escaped) {
        return escaped.replace("\\r", "\r").replace("\\n", "\n");
    }

    @Test
    void aMessageAfterAnImportsProgressTextOnTheSameLineIsRead() {
        CliOutput output = classifier.parse(fixture("import_repository_new_files"));

        assertThat(output.okMessages()).containsExactly("Imported successfully");
        assertThat(output.nokMessages()).isEmpty();
    }

    @Test
    void thePickedUpLineIsPreambleOnStdoutToo() {
        CliOutput output = classifier.parse(new CliResult(0, PICKED_UP + PREAMBLE
            + "1-OK: done.\n", "", Duration.ZERO, false, CliRunner.MAX_OUTPUT_BYTES));

        assertThat(output.outputLines()).containsExactly("1-OK: done.");
    }

    @Test
    void logLinesAndTheirStackTracesAreSeparatedFromTheOutput() {
        CliOutput output = classifier.parse(fixture("unreachable"));

        assertThat(output.outputLines()).containsExactly("CONNECTION ERROR",
            "Login to the server failed. : Error opening socket: java.net.ConnectException:"
                + " Connection refused");
        assertThat(output.logLines()).first().asString()
            .startsWith("ERROR 15:58:59,219 [main      ] IBISHTTPUtils");
        assertThat(output.logLines()).contains("java.net.ConnectException: Connection refused")
            .anySatisfy(line -> assertThat(line).startsWith("\tat sun.nio.ch.Net.connect0"))
            .anySatisfy(line -> assertThat(line).startsWith("ERROR 15:58:59,233 [main      ] CLI"));
    }

    @Test
    void multiLineExceptionMessagesStayInTheLogRecordUntilTheMarkerIsClosed() {
        CliOutput output = classifier.parse(fixture("kill_unknown"));

        assertThat(output.outputLines()).containsExactly("EXECUTION ERROR",
            "Internal INUBIT error!", "Kill processes failed:",
            "999999999: Exception from service object: : Internal INUBIT error! : No process"
                + " found with id 999999999!");
        assertThat(output.logLines()).contains("@End@");
    }

    @Test
    void aWarnLogLineWithoutStackTraceDoesNotSwallowTheOutput() {
        CliOutput output = classifier.parse(result(0, PREAMBLE
            + "WARN  10:00:00,000 [main      ] CLI   something\n"
            + "INFO 10:00:00,001 [main      ] CLI   other\n1-OK: done.\n"));

        assertThat(output.outputLines()).containsExactly("1-OK: done.");
        assertThat(output.logLines()).hasSize(2);
    }

    @Test
    void markersAreExtractedWithCodeAndText() {
        CliOutput output = classifier.parse(fixture("processErrorStart_unknown"));

        assertThat(output.markers()).containsExactly(
            new Marker("IError", "Internal INUBIT error!"),
            new Marker("No process found with id [999999999]!",
                "No process found with id [999999999]!"));
    }

    @Test
    void markerWithSeveralTextPartsKeepsTheCodeBeforeTheFirstSeparator() {
        CliOutput output = classifier.parse(fixture("ps_filter_space"));

        assertThat(output.markers()).extracting(Marker::code).containsExactly("IError",
            "cli.process.option.filter.argument.expression.invalidFilterExpression");
    }

    @Test
    void okAndNokMessagesAreExtracted() {
        CliOutput output = classifier.parse(result(1, PREAMBLE
            + "1-OK: first done.\n2-NOK: second failed.\n"));

        assertThat(output.okMessages()).containsExactly("first done.");
        assertThat(output.nokMessages()).containsExactly("second failed.");
    }

    @Test
    void commandOutputWithoutMessagesIsKept() {
        CliOutput output = classifier.parse(fixture("ps_all"));

        assertThat(output.outputLines()).first()
            .isEqualTo("UID,PID,PRIO,STATE,DATE,WORKFLOW,MODULE,NODE,TAG");
        assertThat(output.outputLines()).last().isEqualTo("Total: 5");
        assertThat(output.okMessages()).isEmpty();
    }

    // --- classification (research R-7) ---------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "login_failed, AUTH_FAILED",
        "unreachable, UNREACHABLE",
        "processErrorStart_unknown, NOT_FOUND",
        "kill_unknown, NOT_FOUND",
        "processErrorStart_unknown_default_locale, NOT_FOUND",
        "unknown_command, INTERNAL",
        "ps_filter_space, INVALID_INPUT"})
    void recordedFailuresAreClassified(String fixtureCase, ErrorCode expected) {
        ToolError error = failure(fixture(fixtureCase));

        assertThat(error.code()).isEqualTo(expected);
        assertThat(error.message()).isNotBlank();
        assertThat(error.nextStep()).isNotBlank();
    }

    /**
     * T025 (T019 review, spec edge case "missing right"): the recorded 8.1 answer to wrong
     * credentials is a login failure and no missing "CLI login access" right, so the credential
     * guard still pauses on it. The refusal for a missing right is not recorded (T014 ran with an
     * account that has the right); its SYNTHETIC form names the right in a marker.
     */
    @Test
    void theRecordedWrongCredentialsAreALoginFailureAndNoMissingCliRight() {
        CliResult wrong = fixture("login_failed");

        assertThat(CliOutputClassifier.isLoginFailure(wrong)).isTrue();
        assertThat(CliOutputClassifier.isMissingCliRight(wrong)).isFalse();
        assertThat(failure(wrong).code()).isEqualTo(ErrorCode.AUTH_FAILED);
    }

    @Test
    void aMarkerNamingTheCliLoginRightIsForbidden() {
        CliResult missing = result(1, PREAMBLE + "ERROR 08:00:01,234 [main] @Start@LoginFailure@@@"
            + "The user jdoe has no CLI login access right.@End@\n");

        assertThat(CliOutputClassifier.isMissingCliRight(missing)).isTrue();
        ToolError error = failure(missing);
        assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error.message()).contains("\"CLI login access\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"processErrorStart_ok", "kill_ok", "export_history_sample",
        "export_modules_sample"})
    void exitZeroWithAnOkMessageIsSuccess(String fixtureCase) {
        CliOutcome outcome = classifier.classify(fixture(fixtureCase));

        assertThat(outcome).isInstanceOfSatisfying(CliOutcome.Success.class,
            success -> assertThat(success.output().okMessages()).hasSize(1));
    }

    @Test
    void aLoginFailureOfACredentialStoreNodeNamesItsEntriesAndNeedsNoRestart() {
        // feature 011 (T016, FR-010): the entries under the effective prefix, no restart
        CliOutputClassifier forNode = new CliOutputClassifier(scrubber, new SecretLabels.Store(
            new CredentialNames("INUBIT_ACME", NodeId.parse("test/node1")), StoreKind.KEYCHAIN),
            false);

        CliOutcome outcome = forNode.classify(fixture("login_failed"));

        assertThat(outcome).isInstanceOfSatisfying(CliOutcome.Failure.class, failure -> {
            assertThat(failure.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(failure.error().nextStep()).isEqualTo("Correct the credential store"
                + " entries INUBIT_ACME_TEST_NODE1_USERNAME (or INUBIT_ACME_TEST_USERNAME) and"
                + " INUBIT_ACME_TEST_NODE1_PASSWORD (or INUBIT_ACME_TEST_PASSWORD) (service"
                + " inubit-mcp) in the macOS Keychain; no restart is needed");
        });
    }

    @Test
    void aLoginFailureOfAnAwsNodeNamesItsParametersAndNeedsNoRestart() {
        // feature 010 (T021, research R-15, FR-008)
        CliOutputClassifier forNode = new CliOutputClassifier(scrubber, new SecretLabels.Aws(
            NodeId.parse("test/node1"), new StoreLocation("eu-central-1", Optional.empty()),
            new ParameterNames(Optional.of("/inubit/test/node1/username"),
                Optional.of("/inubit/test/node1/password"), Optional.empty(), Optional.empty(),
                Optional.empty())), false);

        CliOutcome outcome = forNode.classify(fixture("login_failed"));

        assertThat(outcome).isInstanceOfSatisfying(CliOutcome.Failure.class, failure -> {
            assertThat(failure.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(failure.error().nextStep()).isEqualTo("Correct the parameter value(s)"
                + " /inubit/test/node1/username, /inubit/test/node1/password in Parameter Store"
                + " (eu-central-1); no restart is needed");
        });
    }

    @Test
    void aLoginFailureOfAClientChosenForNineNamesTheInternalUserManagement() {
        // feature 017 (T012, research D-16, FR-013): whatever the address kind
        CliOutputClassifier forNode = new CliOutputClassifier(scrubber, new SecretLabels.Store(
            new CredentialNames("INUBIT_ACME", NodeId.parse("test/node1")), StoreKind.KEYCHAIN),
            false);

        ToolError byMarker = ((CliOutcome.Failure) forNode.classify(
            fixture("login_failed").forClientMajor(OptionalInt.of(9)))).error();
        ToolError byText = failure(result(1, PREAMBLE + "EXECUTION ERROR\nThe user does not"
            + " exist or password does not match.\n").forClientMajor(OptionalInt.of(10)));

        assertThat(byMarker.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(byMarker.nextStep()).isEqualTo("Correct the credential store entries"
            + " INUBIT_ACME_TEST_NODE1_USERNAME (or INUBIT_ACME_TEST_USERNAME) and"
            + " INUBIT_ACME_TEST_NODE1_PASSWORD (or INUBIT_ACME_TEST_PASSWORD) (service"
            + " inubit-mcp) in the macOS Keychain; no restart is needed. On INUBIT 9 the user"
            + " must exist in the internal user management; users that log in only through"
            + " OAuth cannot use StartCLI");
        assertThat(byText.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(byText.nextStep()).isEqualTo("Check the node's username and password with"
            + " --check-config and correct them where they are stored. On INUBIT 9 the user must"
            + " exist in the internal user management; users that log in only through OAuth"
            + " cannot use StartCLI");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 6, 8})
    void aLoginFailureBelowNineOrOfAnUnknownMajorKeepsTheText(int major) {
        // feature 017 (T012, FR-009): the 0.8.0 text
        CliResult login = fixture("login_failed").forClientMajor(
            major < 0 ? OptionalInt.empty() : OptionalInt.of(major));

        assertThat(failure(login).nextStep()).isEqualTo("Check the node's username and"
            + " password with --check-config and correct them where they are stored");
    }

    @Test
    void aResultWithoutClientMajorHasNone() {
        assertThat(result(0, "").clientMajor()).isEmpty();
        assertThat(result(0, "").forClientMajor(OptionalInt.of(9)).clientMajor()).hasValue(9);
    }

    @Test
    void theGermanSummaryIsStillNotFoundThroughTheMarker() {
        CliResult german = fixture("processErrorStart_unknown_default_locale");

        assertThat(german.stdout()).contains("Interner INUBIT-Fehler!");
        assertThat(failure(german).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void markersWinOverEnglishTexts() {
        CliResult result = result(1, PREAMBLE
            + "ERROR 10:00:00,000 [main      ] CLI                       ERROR\n"
            + "com.inubit.ibis.cli.CliConnectionException: @Start@LoginFailure@@@Login"
            + " rejected.@End@\n\tat com.inubit.ibis.cli.CLI.execute(CLI.java:233)\n"
            + "CONNECTION ERROR\nCommand not found.\n");

        assertThat(failure(result).code()).isEqualTo(ErrorCode.AUTH_FAILED);
    }

    /** Feature 013 T013: the next step names the client script of the platform (FR-001). */
    @Test
    void anUnknownCommandNamesTheClientScriptOfThePlatform() {
        SecretLabels labels = new SecretLabels.Store(
            new CredentialNames("INUBIT_ACME", NodeId.parse("test/node1")), StoreKind.KEYCHAIN);
        CliResult result = fixture("unknown_command");

        ToolError posix = ((CliOutcome.Failure) new CliOutputClassifier(scrubber, labels, false)
            .classify(result)).error();
        ToolError windows = ((CliOutcome.Failure) new CliOutputClassifier(scrubber, labels, true)
            .classify(result)).error();

        assertThat(posix.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(posix.nextStep()).isEqualTo("Check that cliHome matches the INUBIT server"
            + " version (startcli.sh -v) and report it");
        assertThat(windows.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(windows.nextStep()).isEqualTo("Check that cliHome matches the INUBIT server"
            + " version (bin\\startcli.bat -v) and report it");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "No process found with id 4711!|NOT_FOUND",
        "The user does not exist or password does not match.|AUTH_FAILED",
        "Command not found.|INTERNAL",
        "Invalid filter expression!|INVALID_INPUT"})
    void englishTextsClassifyWhenNoMarkerDecides(String text, ErrorCode expected) {
        assertThat(failure(result(1, PREAMBLE + "EXECUTION ERROR\n" + text + "\n")).code())
            .isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"java.net.UnknownHostException: no-such-host",
        "java.net.SocketTimeoutException: connect timed out",
        "java.net.ConnectException: Connection refused"})
    void loginFailedWithANetworkExceptionIsUnreachable(String exception) {
        CliResult result = result(1, PREAMBLE
            + "ERROR 10:00:00,000 [main      ] CLI                       ERROR\n"
            + "com.inubit.ibis.cli.CliConnectionException: @Start@LoginFailed@@@Login to the"
            + " server failed.@End@Error opening socket: " + exception + "\n"
            + "CONNECTION ERROR\n");

        assertThat(failure(result).code()).isEqualTo(ErrorCode.UNREACHABLE);
    }

    @Test
    void loginFailedWithATlsExceptionIsATlsError() {
        CliResult result = result(1, PREAMBLE
            + "ERROR 10:00:00,000 [main      ] CLI                       ERROR\n"
            + "com.inubit.ibis.cli.CliConnectionException: @Start@LoginFailed@@@Login to the"
            + " server failed.@End@javax.net.ssl.SSLHandshakeException: PKIX path building"
            + " failed\nCONNECTION ERROR\n");

        assertThat(failure(result).code()).isEqualTo(ErrorCode.TLS_ERROR);
    }

    @Test
    void aNineClientsSecureConnectionFailureIsATlsError() {
        // recorded live with the INUBIT 9.0.10 client against a server whose certificate the
        // client did not trust (T047, 2026-11-09); 9.0 no longer prints "Login to the server failed."
        CliResult result = result(1, PREAMBLE
            + "SECURITY WARNING: Hostname verification is disabled. Your connection might not be"
            + " secured!\n"
            + "Authentication failed: Could not establish a secure connection to the process"
            + " engine.\n"
            + "Please check your TLS/SSL configuration.\n"
            + "EXECUTION ERROR\n"
            + "PKIX path building failed: sun.security.provider.certpath."
            + "SunCertPathBuilderException: unable to find valid certification path to requested"
            + " target\n");

        assertThat(failure(result).code()).isEqualTo(ErrorCode.TLS_ERROR);
    }

    @Test
    void aNineClientsSecureConnectionFailureWithoutATlsCauseIsNotGuessed() {
        CliResult result = result(1, PREAMBLE
            + "Authentication failed: Could not establish a secure connection to the process"
            + " engine.\n"
            + "EXECUTION ERROR\n"
            + "Something else\n");

        assertThat(failure(result).code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void loginFailedWithoutAKnownCauseIsNotGuessed() {
        CliResult result = result(1, PREAMBLE
            + "com.inubit.ibis.cli.CliConnectionException: @Start@LoginFailed@@@Login to the"
            + " server failed.@End@Something else\nCONNECTION ERROR\n");

        assertThat(failure(result).code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void unknownOutputIsAnUnexpectedResponseWithAScrubbedBoundedExcerpt() {
        scrubber.register("hunter2-secret");
        CliResult result = result(1, PREAMBLE + "Something odd happened with hunter2-secret\n"
            + "x".repeat(5000) + "\n");

        ToolError error = failure(result);

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("exit code 1");
        assertThat(error.excerpt()).hasValueSatisfying(excerpt -> assertThat(excerpt)
            .hasSizeLessThanOrEqualTo(ToolError.MAX_EXCERPT_LENGTH)
            .startsWith("Something odd happened with ***")
            .doesNotContain("hunter2-secret", "JAVA_HOME is set"));
    }

    @Test
    void exitZeroWithoutAnOkMessageIsNeverAGuessedSuccess() {
        assertThat(failure(fixture("ps_all")).code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(failure(result(0, PREAMBLE)).code())
            .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void aNokMessageIsAFailureEvenWithExitZero() {
        ToolError error = failure(result(0, PREAMBLE + "1-NOK: Process could not be restarted.\n"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.excerpt()).hasValueSatisfying(excerpt ->
            assertThat(excerpt).contains("1-NOK: Process could not be restarted."));
    }

    @Test
    void anOkMessageWithANonZeroExitCodeIsNoSuccess() {
        assertThat(classifier.classify(result(1, PREAMBLE + "1-OK: done.\n")))
            .isInstanceOf(CliOutcome.Failure.class);
    }

    @Test
    void knownFailuresQuoteTheInubitTextScrubbed() {
        ToolError error = failure(fixture("processErrorStart_unknown"));

        assertThat(error.message()).contains("No process found with id [999999999]!");
    }

    @Test
    void truncatedOutputIsNeverASuccess() {
        CliResult truncated = new CliResult(0, PREAMBLE + "1-OK: done.\n", "", Duration.ZERO,
            true, CliRunner.MAX_OUTPUT_BYTES);

        ToolError error = failure(truncated);

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("exceeded 1 MiB");
    }

    // --- trace download (feature 021, research D-8, D-14) ------------------------------------

    /**
     * Reconstructed from the 8.1.17/9.0.10 bytecode (research "Static facts"): the server throws
     * {@code InubitException("FileWasNotFound", …, <absolute path>)}, StartCLI logs it like every
     * other {@code InubitException} (see {@code kill_unknown}). No missing-file case was recorded.
     */
    private static CliResult traceLogMissing(String serverPath) {
        return result(1, PREAMBLE
            + "ERROR 10:00:00,000 [main      ] CLI                       ERROR\n"
            + "com.inubit.ibis.utils.InubitException: @Start@FileWasNotFound@@@" + serverPath
            + "@End@\n"
            + "\tat com.inubit.ibis.cli.cmds.TraceCommand.execute(TraceCommand.java:1)\n"
            + "EXECUTION ERROR\n" + serverPath + "\n");
    }

    private static final SecretLabels NODE1 = new SecretLabels.Store(
        new CredentialNames("INUBIT_ACME", NodeId.parse("test/node1")), StoreKind.KEYCHAIN);

    @Test
    void aMissingTraceLogIsNotFoundInTheTraceContext() {
        CliOutputClassifier forNode = new CliOutputClassifier(scrubber, NODE1, false);

        CliOutcome outcome = forNode.classifyTraceDownload(
            traceLogMissing("/opt/inubit/server/log/trace.log"));

        assertThat(outcome).isInstanceOfSatisfying(CliOutcome.Failure.class, failure -> {
            assertThat(failure.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(failure.error().message())
                .isEqualTo("trace.log does not exist on test/node1");
            assertThat(failure.error().nextStep())
                .isEqualTo("Check the trace settings of the server in the Workbench");
        });
    }

    @Test
    void aClassifierWithoutNodeNamesTheServerGenerically() {
        ToolError error = ((CliOutcome.Failure) classifier.classifyTraceDownload(
            traceLogMissing("/opt/inubit/server/log/trace.log"))).error();

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.message()).isEqualTo("trace.log does not exist on the INUBIT server");
    }

    @Test
    void theSameMarkerOutsideTheTraceContextKeepsItsClassification() {
        // an export never asked for trace.log: the marker is no known rule there
        CliResult result = traceLogMissing("/opt/inubit/server/log/trace.log");

        ToolError error = failure(result);

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo("Unrecognized StartCLI output (exit code 1)");
    }

    @ParameterizedTest
    @CsvSource({
        "login_failed, AUTH_FAILED",
        "unreachable, UNREACHABLE",
        "processErrorStart_unknown, NOT_FOUND",
        "unknown_command, INTERNAL",
        "ps_filter_space, INVALID_INPUT"})
    void theTraceContextKeepsEveryOtherRule(String fixtureCase, ErrorCode expected) {
        CliResult result = fixture(fixtureCase);

        ToolError trace = ((CliOutcome.Failure) classifier.classifyTraceDownload(result)).error();

        assertThat(trace.code()).isEqualTo(expected);
        assertThat(trace).isEqualTo(failure(result));
    }

    /** {@code fixtures/server-log/v8_1/trace/<fixtureCase>.*} (T024). */
    private static CliResult traceFixture(String fixtureCase) {
        String base = "server-log/v8_1/trace/" + fixtureCase;
        return new CliResult(
            Integer.parseInt(text(base + ".exit").strip()), text(base + ".stdout"),
            text(base + ".stderr"), Duration.ofSeconds(3), false, CliRunner.MAX_OUTPUT_BYTES);
    }

    private static String text(String path) {
        return new String(FakeProcessLauncher.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void theRecordedTraceConfirmationIsASuccessInTheTraceContextOnly() {
        // 8.1.17 (research "Spike findings"): "Trace data (<n> MB) stored to: …" without n-OK
        CliResult ok = traceFixture("ok");

        assertThat(classifier.classifyTraceDownload(ok)).isInstanceOf(CliOutcome.Success.class);
        assertThat(failure(ok).code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void exitCodeZeroWithoutAMarkerIsNoFailureInTheTraceContext() {
        // whether StartCLI confirmed the download is CliTraceRunner's check (research D-1)
        CliResult listing = fixture("ps_all");

        assertThat(classifier.classifyTraceDownload(listing))
            .isInstanceOf(CliOutcome.Success.class);
        assertThat(failure(listing).code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void anAccessDeniedMarkerIsForbiddenInTheTraceContextOnly() {
        CliOutputClassifier forNode = new CliOutputClassifier(scrubber, NODE1, false);
        CliResult refused = traceFixture("forbidden");

        CliOutcome outcome = forNode.classifyTraceDownload(refused);

        assertThat(outcome).isInstanceOfSatisfying(CliOutcome.Failure.class, failure -> {
            assertThat(failure.error().code()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(failure.error().message())
                .isEqualTo("The INUBIT account is not allowed to download trace.log from"
                    + " test/node1");
            assertThat(failure.error().excerpt()).isEmpty();
            assertThat(String.join("\n", failure.error().message(),
                failure.error().likelyCause(), failure.error().nextStep()))
                .doesNotContain("jdoe");
        });
        // an export never asked for trace.log: unchanged outside the trace context
        assertThat(failure(refused).message())
            .isEqualTo("Unrecognized StartCLI output (exit code 1)");
    }

    @Test
    void cutTraceOutputIsNeverForbidden() {
        CliResult refused = traceFixture("forbidden");
        CliResult cut = new CliResult(refused.exitCode(), refused.stdout(), refused.stderr(),
            Duration.ZERO, true, CliRunner.MAX_OUTPUT_BYTES);

        ToolError error = ((CliOutcome.Failure) classifier.classifyTraceDownload(cut)).error();

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
    }

    @Test
    void cutTraceOutputIsNeverNotFound() {
        CliResult missing = traceLogMissing("/opt/inubit/server/log/trace.log");
        CliResult cut = new CliResult(missing.exitCode(), missing.stdout(), missing.stderr(),
            Duration.ZERO, true, CliRunner.MAX_OUTPUT_BYTES);

        ToolError error = ((CliOutcome.Failure) classifier.classifyTraceDownload(cut)).error();

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("exceeded 1 MiB");
    }

    @Test
    void aMissingTraceLogLeaksNeitherSecretsNorTheServerPath() {
        scrubber.register("hunter2-secret");
        CliOutputClassifier forNode = new CliOutputClassifier(scrubber, NODE1, false);

        ToolError error = ((CliOutcome.Failure) forNode.classifyTraceDownload(
            traceLogMissing("/opt/hunter2-secret/log/trace.log"))).error();

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.excerpt()).isEmpty();
        assertThat(String.join("\n", error.message(), error.likelyCause(), error.nextStep()))
            .doesNotContain("hunter2-secret", "/opt/");
    }

    @Test
    void theCutMessageNamesTheBoundOfTheRun() {
        CliResult cut = new CliResult(0, "", "", Duration.ZERO, true, 12 << 20);

        assertThat(failure(cut).message()).contains("exceeded 12 MiB").doesNotContain("1 MB");
    }
}
