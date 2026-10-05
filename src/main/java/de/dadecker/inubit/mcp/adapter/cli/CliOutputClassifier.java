package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliOutput.Marker;
import de.dadecker.inubit.mcp.adapter.cli.CliOutput.Message;
import de.dadecker.inubit.mcp.config.CredentialVariables;
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
 *       {@code <n>-NOK:} messages are extracted.
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
    private static final Pattern MESSAGE = Pattern.compile("^(\\d+)-(OK|NOK):\\s?(.*)$");

    private static final String PASSWORD_PROMPT = "Password:";
    private static final String INVALID_FILTER =
        "cli.process.option.filter.argument.expression.invalidFilterExpression";
    private static final Pattern NETWORK_FAILURE = Pattern.compile(
        "ConnectException|UnknownHostException|SocketTimeoutException|NoRouteToHostException");
    private static final Pattern TLS_FAILURE = Pattern.compile(
        "SSLHandshakeException|SSLException|CertificateException|PKIX path|"
            + "No subject alternative");

    private final SecretScrubber scrubber;
    /** The node's credential variables for the next step of {@code AUTH_FAILED}, if known. */
    private final Optional<CredentialVariables> variables;

    /** A classifier whose {@code AUTH_FAILED} next step refers to {@code --check-config}. */
    public CliOutputClassifier(SecretScrubber scrubber) {
        this.scrubber = scrubber;
        this.variables = Optional.empty();
    }

    /**
     * A classifier for one node: {@code AUTH_FAILED} names its credential variables under the
     * profile's effective prefix (002 research D-6).
     */
    public CliOutputClassifier(SecretScrubber scrubber, CredentialVariables variables) {
        this.scrubber = scrubber;
        this.variables = Optional.of(variables);
    }

    /** True if StartCLI reported that the login was rejected ({@code @Start@LoginFailure}). */
    public static boolean isLoginFailure(CliResult result) {
        return LOGIN_FAILURE.matcher(result.stdout()).find()
            || LOGIN_FAILURE.matcher(result.stderr()).find();
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
                "StartCLI output exceeded 1 MB and was cut (exit code " + result.exitCode() + ")",
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
        return new CliOutcome.Failure(byMarker(output, all)
            .or(() -> byText(all))
            .orElseGet(() -> unexpected(result, output)));
    }

    private Optional<ToolError> byMarker(CliOutput output, String all) {
        for (Marker marker : output.markers()) {
            String code = marker.code();
            if (code.equals("LoginFailure")) {
                return Optional.of(authFailed(marker.text()));
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

    private Optional<ToolError> byText(String all) {
        if (all.contains("The user does not exist or password does not match.")) {
            return Optional.of(authFailed("The user does not exist or password does not match."));
        }
        if (all.contains("Login to the server failed.") && TLS_FAILURE.matcher(all).find()) {
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
                "Check that cliHome matches the INUBIT server version (startcli.sh -v) and report it"));
        }
        if (all.contains("Invalid filter expression!")) {
            return Optional.of(invalidFilter());
        }
        return Optional.empty();
    }

    private ToolError unexpected(CliResult result, CliOutput output) {
        // the command's own output first, then the log records without stack frames
        List<String> relevant = new ArrayList<>(output.outputLines());
        for (String line : output.logLines()) {
            if (!STACK_LINE.matcher(line).find()) {
                relevant.add(line);
            }
        }
        String excerpt = String.join("\n", relevant);
        return error(ErrorCode.UNEXPECTED_RESPONSE,
            "Unrecognized StartCLI output (exit code " + result.exitCode() + ")",
            "StartCLI reported a result this MCP server does not know how to interpret",
            "See the excerpt; check the INUBIT system log for the time of the call")
            .withExcerpt(scrubber.scrub(excerpt));
    }

    private ToolError authFailed(String inubitText) {
        return error(ErrorCode.AUTH_FAILED, "StartCLI login failed: " + inubitText,
            "Wrong username or password for this INUBIT server, or the account is locked",
            variables.map(CredentialGuard::fixCredentials).orElse("Check the username and"
                + " password variables (see --check-config) and restart the MCP client"));
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
