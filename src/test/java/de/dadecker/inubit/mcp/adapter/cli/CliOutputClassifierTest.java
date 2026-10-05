package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliOutput.Marker;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
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
            Duration.ofSeconds(2), false);
    }

    private static CliResult result(int exitCode, String stdout) {
        return new CliResult(exitCode, stdout, PICKED_UP, Duration.ofSeconds(1), false);
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

    @Test
    void thePickedUpLineIsPreambleOnStdoutToo() {
        CliOutput output = classifier.parse(new CliResult(0, PICKED_UP + PREAMBLE
            + "1-OK: done.\n", "", Duration.ZERO, false));

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

    @ParameterizedTest
    @ValueSource(strings = {"processErrorStart_ok", "kill_ok", "export_history_sample",
        "export_modules_sample"})
    void exitZeroWithAnOkMessageIsSuccess(String fixtureCase) {
        CliOutcome outcome = classifier.classify(fixture(fixtureCase));

        assertThat(outcome).isInstanceOfSatisfying(CliOutcome.Success.class,
            success -> assertThat(success.output().okMessages()).hasSize(1));
    }

    @Test
    void aLoginFailureOfANodeNamesItsCredentialVariablesUnderTheEffectivePrefix() {
        // 002 research D-6: the next step names the variables the profile actually reads
        CliOutputClassifier forNode = new CliOutputClassifier(scrubber,
            new CredentialVariables("INUBIT_ACME", NodeId.parse("test/node1")));

        CliOutcome outcome = forNode.classify(fixture("login_failed"));

        assertThat(outcome).isInstanceOfSatisfying(CliOutcome.Failure.class, failure -> {
            assertThat(failure.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(failure.error().nextStep()).contains("INUBIT_ACME_TEST_NODE1_PASSWORD",
                "INUBIT_ACME_TEST_PASSWORD", "restart the MCP client");
        });
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
            true);

        ToolError error = failure(truncated);

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("1 MB");
    }
}
