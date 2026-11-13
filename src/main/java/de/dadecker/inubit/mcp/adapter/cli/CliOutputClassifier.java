package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.cli.CliOutput.Marker;
import de.dadecker.inubit.mcp.adapter.cli.CliOutput.Message;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Interprets StartCLI output (research R-6, R-7; recordings of spikes S-2/S-3).
 *
 * <ol>
 *   <li>Leading preamble lines are dropped: {@code JAVA_HOME is set}, {@code Password: } (also
 *       when the prompt is followed by text on the same line), {@code SECURITY WARNING: Hostname
 *       verification is disabled…} and {@code Picked up JAVA_TOOL_OPTIONS: …} (stdout or
 *       stderr).
 *   <li>Log records are separated from the command output: a header line
 *       {@code ^(ERROR|WARN|INFO|DEBUG) hh:mm:ss,SSS [}, an optional exception line (continued
 *       while an {@code @Start@} marker is still open), and its {@code \tat …},
 *       {@code Caused by: …} and {@code ... n more} lines.
 *   <li>INUBIT markers {@code @Start@<code>@@@<text>@End@} and {@code <n>-OK:} /
 *       {@code <n>-NOK:} messages are extracted (also after an import's progress text
 *       {@code Completed = … MB / … MB} on the same line).
 *   <li>Classification: cut output ({@link CliResult#truncated()}) is never a success. Exit
 *       code 0 with an {@code n-OK} and without an {@code n-NOK} message
 *       is success. Otherwise the markers decide first, the English texts (guaranteed by the
 *       locale setting of R-6) second; anything else is {@code UNEXPECTED_RESPONSE} with a
 *       scrubbed excerpt of at most 500 chars. A success is never guessed.
 * </ol>
 *
 * <p>Every text that leaves this class is scrubbed of registered secrets.
 */
public final class CliOutputClassifier {

    private static final Pattern LOG_HEADER =
        Pattern.compile("^(?:ERROR|WARN|INFO|DEBUG)\\s+\\d{2}:\\d{2}:\\d{2},\\d{3} \\[");
    private static final Pattern EXCEPTION_LINE = Pattern.compile(
        "^(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?:Exception|Error|Throwable)(?::.*)?$");
    private static final Pattern STACK_LINE =
        Pattern.compile("^(?:\\s+at\\s|\\s+\\.\\.\\. \\d+ more|\\s*Suppressed: )");
    private static final Pattern CAUSED_BY = Pattern.compile("^Caused by: ");
    private static final Pattern MARKER = Pattern.compile(
        "@Start@((?:(?!@@@|@End@).)*)(?:@@@(.*?))?@End@", Pattern.DOTALL);
    private static final Pattern LOGIN_FAILURE = Pattern.compile("@Start@\\s*LoginFailure\\b");
    /**
     * An {@code n-OK}/{@code n-NOK} line. StartCLI prints an import's last progress text
     * {@code Completed = … MB / … MB } without a line break before it (feature 008, probe P-2).
     */
    private static final Pattern MESSAGE = Pattern.compile(
        "^(?:Completed = \\S+ MB / \\S+ MB\\s*)*(\\d+)-(OK|NOK):\\s?(.*)$");
    /** The server's marker for a log file it does not have (feature 021, research D-8). */
    private static final String FILE_WAS_NOT_FOUND = "FileWasNotFound";
    /** The server's marker for a refused request (feature 021, research D-14). */
    private static final String ACCESS_DENIED = "AccessDenied";
    /** StartCLI's text for a temporary log level change without a level (feature 024, D-11). */
    private static final String MISSING_LOG_LEVEL = "Missing log level.";
    private static final Pattern MISSING_MODULE = Pattern.compile("^The module .+ not found$");

    private static final String PASSWORD_PROMPT = "Password:";
    /** StartCLI's answer to an export whose selection (e.g. a tag) matches no diagram group. */
    private static final String NO_TAGGED_GROUP =
        "No workflow group containing workflows for export found";
    private static final String PATH_NOT_FOUND = "Path not found //ibis:Root";
    private static final String INVALID_FILTER =
        "cli.process.option.filter.argument.expression.invalidFilterExpression";
    private static final Pattern NETWORK_FAILURE = Pattern.compile(
        "ConnectException|UnknownHostException|SocketTimeoutException|NoRouteToHostException");
    /** Feature 017 (research D-16, FR-013): StartCLI cannot log in through OAuth. */
    private static final String INTERNAL_USERS = ". On INUBIT 9 the user must exist in the"
        + " internal user management; users that log in only through OAuth cannot use StartCLI";
    /**
     * The 9.0 client's wording of a failed TLS connection; 8.1 prints {@code Login to the server
     * failed.} instead (T047 live check, finding F-2).
     */
    private static final String NINE_SECURE_CONNECTION =
        "Could not establish a secure connection to the process engine.";
    private static final Pattern TLS_FAILURE = Pattern.compile(
        "SSLHandshakeException|SSLException|CertificateException|PKIX path|"
            + "No subject alternative");

    private final SecretScrubber scrubber;
    /** The names of the node's secrets for the next step of {@code AUTH_FAILED}, if known. */
    private final Optional<SecretLabels> labels;
    /** Whether next steps name {@code bin\startcli.bat} instead of {@code startcli.sh}. */
    private final boolean windows;

    /**
     * A classifier whose {@code AUTH_FAILED} next step refers to {@code --check-config}; next
     * steps name {@code startcli.sh}.
     */
    public CliOutputClassifier(SecretScrubber scrubber) {
        this.scrubber = scrubber;
        this.labels = Optional.empty();
        this.windows = false;
    }

    /**
     * A classifier for one node: {@code AUTH_FAILED} names its secrets per source (002 research
     * D-6, feature 010 research R-15); next steps name the client script of the node's StartCLI
     * runner, {@code bin\startcli.bat} if {@code windows} (feature 013, FR-001).
     */
    public CliOutputClassifier(SecretScrubber scrubber, SecretLabels labels, boolean windows) {
        this.scrubber = scrubber;
        this.labels = Optional.of(labels);
        this.windows = windows;
    }

    /** True if StartCLI reported that the login was rejected ({@code @Start@LoginFailure}). */
    public static boolean isLoginFailure(CliResult result) {
        return LOGIN_FAILURE.matcher(result.stdout()).find()
            || LOGIN_FAILURE.matcher(result.stderr()).find();
    }

    /**
     * True if the last non-blank line of {@code stdout} is the client's password prompt alone
     * (feature 013, FR-005): the client prints it before it reads the password, so a call that
     * stops there may wait at its console or for a slow server.
     */
    public static boolean endsAtThePasswordPrompt(String stdout) {
        List<String> lines = lines(stdout);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).strip();
            if (!line.isEmpty()) {
                return line.equals(PASSWORD_PROMPT);
            }
        }
        return false;
    }

    /** A classifier that scrubs with {@link SecretScrubber#global()}. */
    public static CliOutputClassifier withGlobalScrubber() {
        return new CliOutputClassifier(SecretScrubber.global());
    }

    /** Splits the output into command output, log records, markers and messages. */
    public CliOutput parse(CliResult result) {
        List<String> output = new ArrayList<>();
        List<String> log = new ArrayList<>();
        separate(withoutPreamble(lines(result.stdout())), output, log);
        separate(withoutPreamble(lines(result.stderr())), output, log);
        String all = String.join("\n", log) + "\n" + String.join("\n", output);
        Set<Marker> markers = new LinkedHashSet<>();
        Matcher matcher = MARKER.matcher(all);
        while (matcher.find()) {
            markers.add(new Marker(matcher.group(1).strip(),
                matcher.group(2) == null ? "" : matcher.group(2).strip()));
        }
        List<Message> messages = new ArrayList<>();
        for (String line : output) {
            Matcher message = MESSAGE.matcher(line);
            if (message.matches()) {
                messages.add(new Message(Integer.parseInt(message.group(1)),
                    message.group(2).equals("OK"), message.group(3).strip()));
            }
        }
        return new CliOutput(output, log, List.copyOf(markers), messages);
    }

    /** Classifies a run of a command that reports its result as {@code n-OK}/{@code n-NOK}. */
    public CliOutcome classify(CliResult result) {
        CliOutput output = parse(result);
        if (result.truncated()) {
            return new CliOutcome.Failure(error(ErrorCode.UNEXPECTED_RESPONSE,
                "StartCLI output exceeded " + bound(result.outputBound())
                    + " and was cut (exit code " + result.exitCode() + ")",
                "The command printed far more than expected, so its result cannot be trusted",
                "Check the INUBIT system log for the time of the call")
                .withExcerpt(scrubber.scrub(String.join("\n", output.outputLines()))));
        }
        if (result.exitCode() == 0 && !output.okMessages().isEmpty()
            && output.nokMessages().isEmpty()) {
            return new CliOutcome.Success(output);
        }
        String all = String.join("\n", output.logLines()) + "\n"
            + String.join("\n", output.outputLines());
        boolean nine = result.clientMajor().isPresent() && result.clientMajor().getAsInt() >= 9;
        return new CliOutcome.Failure(byMarker(output, all, nine)
            .or(() -> missingArtifact(output))
            .or(() -> missingRepositoryPath(all))
            .or(() -> byText(all, nine))
            .orElseGet(() -> unexpected(result, output)));
    }

    /**
     * Classifies a {@code trace --download} run (feature 021, research D-1, D-8, D-14): the
     * server's marker {@code FileWasNotFound} is a missing {@code trace.log}
     * ({@code NOT_FOUND}), its marker {@code AccessDenied} a refused download
     * ({@code FORBIDDEN}). StartCLI confirms the download without an {@code n-OK} prefix
     * (research "Spike findings"), so exit code 0 without any marker and without an
     * {@code n-NOK} message is a success here; whether the confirmation is there is the caller's
     * check ({@link CliTraceRunner}). Anything else, and cut output, is classified as by
     * {@link #classify(CliResult)}. Only the trace download asks the server for a file, so these
     * rules do not apply to other commands.
     */
    public CliOutcome classifyTraceDownload(CliResult result) {
        if (result.truncated()) {
            return classify(result);
        }
        CliOutput output = parse(result);
        String node = labels.map(names -> names.node().value()).orElse("the INUBIT server");
        for (Marker marker : output.markers()) {
            if (marker.code().equals(FILE_WAS_NOT_FOUND)) {
                return new CliOutcome.Failure(error(ErrorCode.NOT_FOUND,
                    "trace.log does not exist on " + node,
                    "The server has no trace log file; tracing may never have written one",
                    "Check the trace settings of the server in the Workbench"));
            }
            if (marker.code().equals(ACCESS_DENIED)) {
                return new CliOutcome.Failure(error(ErrorCode.FORBIDDEN,
                    "The INUBIT account is not allowed to download trace.log from " + node,
                    "The account lacks the INUBIT right to download the trace log",
                    "Ask the INUBIT administrators for the right, or use another account"));
            }
        }
        if (result.exitCode() == 0 && output.markers().isEmpty()
            && output.nokMessages().isEmpty()) {
            return new CliOutcome.Success(output);
        }
        return classify(result);
    }

    /**
     * Classifies a {@code trace --nodelevel … --package …} or {@code trace --reset} run (feature
     * 024, research D-11, D-20). StartCLI's text for an option form without a level,
     * {@code Missing log level.}, is {@code UNEXPECTED_RESPONSE} whatever its prefix or exit code;
     * the server's marker {@code AccessDenied} is a refused change ({@code FORBIDDEN}). The
     * confirmation of {@code CommandResult("OK")} is taken as an {@code n-OK} message until the
     * write spike T039 records it, so a success is {@link #classify(CliResult)}'s success without
     * any marker and without an exception line in the log records; exit code 0 without any
     * {@code n-OK}/{@code n-NOK} message and without a marker is {@code UNEXPECTED_RESPONSE}
     * "StartCLI did not confirm the log level change" (contract "StartCLI without the
     * confirmation"); anything else, and cut output, is classified as by
     * {@link #classify(CliResult)}.
     */
    public CliOutcome classifyTraceLevel(CliResult result) {
        if (result.truncated()) {
            return classify(result);
        }
        CliOutput output = parse(result);
        String node = labels.map(names -> names.node().value()).orElse("the INUBIT server");
        if (output.markers().stream().anyMatch(marker -> marker.code().equals(ACCESS_DENIED))) {
            return new CliOutcome.Failure(error(ErrorCode.FORBIDDEN,
                "The INUBIT account is not allowed to change log levels on " + node,
                "The account lacks the INUBIT right to change the log level",
                "Ask the INUBIT administrators for the right, or use another account"));
        }
        if (Stream.concat(output.outputLines().stream(), output.logLines().stream())
            .anyMatch(line -> line.contains(MISSING_LOG_LEVEL))) {
            return new CliOutcome.Failure(error(ErrorCode.UNEXPECTED_RESPONSE,
                "StartCLI did not change the log level: " + MISSING_LOG_LEVEL,
                "StartCLI did not take the level of the trace command; the INUBIT client may"
                    + " name its options differently",
                "Reset with reset_temporary_log_level; report the INUBIT version"));
        }
        if (result.exitCode() == 0 && output.okMessages().isEmpty()
            && output.nokMessages().isEmpty() && output.markers().isEmpty()) {
            return new CliOutcome.Failure(error(ErrorCode.UNEXPECTED_RESPONSE,
                "StartCLI did not confirm the log level change",
                "StartCLI ended without its confirmation, so the change may not have been made",
                "Reset with reset_temporary_log_level; report the INUBIT version")
                .withExcerpt(scrubber.scrub(excerpt(output))));
        }
        CliOutcome outcome = classify(result);
        if (outcome instanceof CliOutcome.Success && (!output.markers().isEmpty()
            || output.logLines().stream().anyMatch(line -> EXCEPTION_LINE.matcher(line).find()))) {
            return new CliOutcome.Failure(unexpected(result, output));
        }
        return outcome;
    }

    /** {@code bytes} as whole MiB where it is one, else in bytes. */
    private static String bound(int bytes) {
        return bytes % (1 << 20) == 0 ? (bytes >> 20) + " MiB" : bytes + " bytes";
    }

    private Optional<ToolError> byMarker(CliOutput output, String all, boolean nine) {
        for (Marker marker : output.markers()) {
            String code = marker.code();
            if (code.equals("LoginFailure")) {
                return Optional.of(authFailed(marker.text(), nine));
            }
            if (code.equals("LoginFailed")) {
                if (TLS_FAILURE.matcher(all).find()) {
                    return Optional.of(tlsError());
                }
                if (NETWORK_FAILURE.matcher(all).find()) {
                    return Optional.of(unreachable());
                }
                continue;
            }
            if (code.contains("No process found with id")
                || marker.text().contains("No process found with id")) {
                return Optional.of(notFound(firstLineWith(code + "\n" + marker.text(),
                    "No process found with id")));
            }
            if (code.equals(INVALID_FILTER)) {
                return Optional.of(invalidFilter());
            }
        }
        return Optional.empty();
    }

    /**
     * An export of a diagram group or module that does not exist (feature 003, recorded:
     * {@code 2-NOK: Workflow group not found: <group>}, {@code 2-NOK: The module <name> not
     * found}), or of a selection without any diagram group (feature 005, a text of the 8.1.17
     * client: {@code 2-NOK: No workflow group containing workflows for export found.}).
     */
    private Optional<ToolError> missingArtifact(CliOutput output) {
        for (String message : output.nokMessages()) {
            if (message.startsWith("Workflow group not found:")
                || message.startsWith(NO_TAGGED_GROUP)
                || MISSING_MODULE.matcher(message).matches()) {
                return Optional.of(error(ErrorCode.NOT_FOUND, "StartCLI: " + message,
                    "The diagram group or module does not exist for this owner (names are"
                        + " case-sensitive), or it belongs to another owner",
                    "Check the name and owner with list_inventory, then retry"));
            }
        }
        return Optional.empty();
    }

    /**
     * A repository export of a path that does not exist (feature 005, research D-1:
     * {@code Internal INUBIT error!} with {@code Path not found //ibis:Root/…}).
     */
    private Optional<ToolError> missingRepositoryPath(String all) {
        if (!all.contains(PATH_NOT_FOUND)) {
            return Optional.empty();
        }
        return Optional.of(error(ErrorCode.NOT_FOUND, "StartCLI: "
                + firstLineWith(all, PATH_NOT_FOUND),
            "The repository file or folder does not exist (paths are case-sensitive)",
            "Check the path below /Root/<owner> in the Workbench repository, then retry"));
    }

    private Optional<ToolError> byText(String all, boolean nine) {
        if (all.contains("The user does not exist or password does not match.")) {
            return Optional.of(authFailed("The user does not exist or password does not match.",
                nine));
        }
        if ((all.contains("Login to the server failed.") || all.contains(NINE_SECURE_CONNECTION))
            && TLS_FAILURE.matcher(all).find()) {
            return Optional.of(tlsError());
        }
        if (all.contains("Login to the server failed.") && NETWORK_FAILURE.matcher(all).find()) {
            return Optional.of(unreachable());
        }
        if (all.contains("No process found with id")) {
            return Optional.of(notFound(firstLineWith(all, "No process found with id")));
        }
        if (all.contains("Command not found.")) {
            return Optional.of(error(ErrorCode.INTERNAL,
                "StartCLI does not know the command (Command not found.)",
                "The INUBIT client version does not support an allow-listed command",
                "Check that cliHome matches the INUBIT server version ("
                    + (windows ? "bin\\startcli.bat" : "startcli.sh") + " -v) and report it"));
        }
        if (all.contains("Invalid filter expression!")) {
            return Optional.of(invalidFilter());
        }
        return Optional.empty();
    }

    private ToolError unexpected(CliResult result, CliOutput output) {
        return error(ErrorCode.UNEXPECTED_RESPONSE,
            "Unrecognized StartCLI output (exit code " + result.exitCode() + ")",
            "StartCLI reported a result this MCP server does not know how to interpret",
            "See the excerpt; check the INUBIT system log for the time of the call")
            .withExcerpt(scrubber.scrub(excerpt(output)));
    }

    /** The command's own output first, then the log records without stack frames. */
    private static String excerpt(CliOutput output) {
        List<String> relevant = new ArrayList<>(output.outputLines());
        for (String line : output.logLines()) {
            if (!STACK_LINE.matcher(line).find()) {
                relevant.add(line);
            }
        }
        return String.join("\n", relevant);
    }

    /** @param nine whether the client was chosen for INUBIT 9 or later */
    private ToolError authFailed(String inubitText, boolean nine) {
        return error(ErrorCode.AUTH_FAILED, "StartCLI login failed: " + inubitText,
            "Wrong username or password for this INUBIT server, or the account is locked",
            labels.map(SecretLabels::fixCredentials).orElse("Check the node's username and"
                + " password with --check-config and correct them where they are stored")
                + (nine ? INTERNAL_USERS : ""));
    }

    private ToolError unreachable() {
        return error(ErrorCode.UNREACHABLE, "StartCLI could not connect to the INUBIT server",
            "The INUBIT server is down, cli.url is wrong, or the network/VPN is not connected",
            "Check get_health and cli.url, then retry");
    }

    private ToolError tlsError() {
        return error(ErrorCode.TLS_ERROR, "StartCLI could not establish the TLS connection",
            "The INUBIT server certificate is not in tls.trustStore, or the hostname does not match",
            "Check tls.trustStore and tls.disableHostnameVerification in the configuration");
    }

    private ToolError notFound(String inubitText) {
        return error(ErrorCode.NOT_FOUND, "StartCLI: " + inubitText,
            "The process instance finished, was already restarted or killed, or the id is wrong",
            "Look the instance up again with find_processes");
    }

    private ToolError invalidFilter() {
        return error(ErrorCode.INVALID_INPUT, "StartCLI rejected the filter expression",
            "StartCLI filter values must not contain spaces",
            "Use a value without spaces, or the REST-based tools");
    }

    private ToolError error(ErrorCode code, String message, String likelyCause,
        String nextStep) {
        return ToolError.of(code, scrubber.scrub(message), likelyCause, nextStep);
    }

    private static String firstLineWith(String text, String fragment) {
        for (String line : text.split("\\R")) {
            int at = line.indexOf(fragment);
            if (at >= 0) {
                return line.substring(at).strip();
            }
        }
        return fragment;
    }

    private static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : List.of(text.split("\\R", -1));
    }

    /** Drops the leading preamble lines; a prompt followed by text keeps the text. */
    private static List<String> withoutPreamble(List<String> lines) {
        int start = 0;
        List<String> result = new ArrayList<>(lines);
        while (start < result.size()) {
            String line = result.get(start);
            if (line.startsWith(PASSWORD_PROMPT)) {
                String rest = line.substring(PASSWORD_PROMPT.length()).strip();
                if (rest.isEmpty()) {
                    start++;
                    continue;
                }
                result.set(start, rest);
                continue;
            }
            if (line.isBlank() || isPreamble(line)) {
                start++;
                continue;
            }
            break;
        }
        return result.subList(start, result.size());
    }

    private static boolean isPreamble(String line) {
        return line.equals("JAVA_HOME is set")
            || line.startsWith("SECURITY WARNING: Hostname verification is disabled")
            || line.startsWith("Picked up JAVA_TOOL_OPTIONS:")
            || line.startsWith("Picked up _JAVA_OPTIONS:");
    }

    private enum State { OUTPUT, AFTER_HEADER, IN_MESSAGE, IN_STACK }

    /** Splits lines into log records and command output (blank lines are dropped). */
    private static void separate(List<String> lines, List<String> output, List<String> log) {
        State state = State.OUTPUT;
        int openMarkers = 0;
        for (String line : lines) {
            if (LOG_HEADER.matcher(line).find()) {
                log.add(line);
                state = State.AFTER_HEADER;
                openMarkers = 0;
                continue;
            }
            boolean consumed = switch (state) {
                case OUTPUT -> false;
                case AFTER_HEADER, IN_STACK -> {
                    if (STACK_LINE.matcher(line).find()) {
                        state = State.IN_STACK;
                        yield true;
                    }
                    if ((state == State.AFTER_HEADER && EXCEPTION_LINE.matcher(line).matches())
                        || CAUSED_BY.matcher(line).find()) {
                        state = State.IN_MESSAGE;
                        openMarkers = openMarkers(line);
                        yield true;
                    }
                    yield false;
                }
                case IN_MESSAGE -> {
                    if (STACK_LINE.matcher(line).find()) {
                        state = State.IN_STACK;
                        yield true;
                    }
                    if (openMarkers > 0) {
                        openMarkers += openMarkers(line);
                        yield true;
                    }
                    yield false;
                }
            };
            if (consumed) {
                log.add(line);
            } else {
                state = State.OUTPUT;
                if (!line.isBlank()) {
                    output.add(line);
                }
            }
        }
    }

    /** {@code @Start@} count minus {@code @End@} count of one line. */
    private static int openMarkers(String line) {
        return count(line, "@Start@") - count(line, "@End@");
    }

    private static int count(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + 1)) {
            count++;
        }
        return count;
    }
}
