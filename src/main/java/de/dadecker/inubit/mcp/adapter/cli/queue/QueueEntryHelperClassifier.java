package de.dadecker.inubit.mcp.adapter.cli.queue;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ApiMissing;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Frame;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Part;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Start;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Judges one run of the queue-entry helper (research D-4, D-5; FR-012, FR-013): its frames and
 * exit code become part reports or one {@link ToolError} for the whole call.
 *
 * <ul>
 *   <li>The output must be complete and its frames must fit the exit code exactly: 0 =
 *       {@code start <variant of the request>}, {@code login ok}, one {@code part} frame per
 *       requested part (data before variables), {@code end}; 3 = {@code start <variant>},
 *       {@code login error}; 4 = a single {@code api missing}. Anything else (output cut at its
 *       bound, an unknown or malformed frame, a frame after {@code end}, no {@code end} on
 *       another exit code such as the helper's silent exit 1, a {@code start} of another or an
 *       empty variant) is {@code UNEXPECTED_RESPONSE}: the unsupported interface may have
 *       changed.
 *   <li>{@code login error}: an SSL, certificate or PKIX failure is {@code TLS_ERROR}; a
 *       connect, unknown-host, no-route or socket-timeout failure {@code UNREACHABLE};
 *       {@code LoginFailure}, "password does not match" or HTTP 401 {@code AUTH_FAILED} (on
 *       INUBIT 9 with the internal-user-management hint of feature 017); HTTP 403 or
 *       {@code AccessDenied} {@code FORBIDDEN}; any other {@code UNEXPECTED_RESPONSE} with an
 *       excerpt.
 *   <li>{@code part … error}: INUBIT's markers {@code @Start@Unable to download} and
 *       {@code MissingWorkflowWithName} make the part {@link PartReport.NotFound}; a TLS or
 *       network failure, HTTP 403 or {@code AccessDenied} fails the whole call as above; any other
 *       error fails only that part with "INUBIT reported an error: &lt;excerpt&gt;".
 *   <li>{@code api missing}: {@code CLI_UNAVAILABLE} naming the client installation, what it
 *       lacks (or "a class or method" when the helper could not name it) and the
 *       {@code defaults.cliClients} entry of the release.
 * </ul>
 *
 * <p>Vendor output outside the frames never leaves this class. The only vendor texts that do
 * are the excerpts (at most {@value #EXCERPT_LIMIT} chars, whitespace collapsed) and the name of
 * what is missing; they are taken from the frame messages (or the exception class names when
 * there are none) after the request's password and trust-store password, the secrets registered
 * with the {@link SecretScrubber} and the values of secret-named fields
 * ({@link SecretTextMasker}) are masked.
 */
public final class QueueEntryHelperClassifier {

    static final int EXCERPT_LIMIT = 200;

    /** The helper's exit codes (research D-4; the helper is compiled separately, D-2). */
    private static final int EXIT_OK = 0;
    private static final int EXIT_LOGIN_FAILED = 3;
    private static final int EXIT_API_MISSING = 4;

    private static final Pattern TLS_FAILURE = Pattern.compile(
        "\\b(?:SSL|Certificate|CertPath|Validator)\\w*Exception\\b|PKIX path"
            + "|unable to find valid certification path|No subject alternative");
    private static final Pattern NETWORK_FAILURE = Pattern.compile("\\b(?:ConnectException"
        + "|UnknownHostException|NoRouteToHostException|SocketTimeoutException)\\b");
    private static final Pattern LOGIN_REJECTED = Pattern.compile("LoginFailure"
        + "|password does not match|" + httpStatus(401, "Unauthorized"));
    private static final Pattern REFUSED =
        Pattern.compile("AccessDenied|" + httpStatus(403, "Forbidden"));
    private static final Pattern NOT_FOUND =
        Pattern.compile("@Start@Unable to download|MissingWorkflowWithName");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Feature 017 (research D-16): the client login cannot use OAuth-only users. */
    private static final String INTERNAL_USERS = ". On INUBIT 9 the user must exist in the"
        + " internal user management; users that log in only through OAuth cannot use StartCLI"
        + " or the queue-entry helper";
    private static final String REPORTED = "INUBIT reported an error: ";

    private final SecretScrubber scrubber;
    private final SecretLabels labels;

    /**
     * @param scrubber the secrets to mask in excerpts and messages
     * @param labels the node's secret names, for the next step of {@code AUTH_FAILED}
     */
    public QueueEntryHelperClassifier(SecretScrubber scrubber, SecretLabels labels) {
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.labels = Objects.requireNonNull(labels, "labels");
    }

    /** What the run says about the node's credentials, for the {@code CredentialGuard}. */
    public enum Login {
        /** A {@code login ok} frame came. */
        ACCEPTED,
        /** INUBIT rejected the credentials ({@code AUTH_FAILED}). */
        REJECTED,
        /** Neither: no login was attempted or it failed for another reason. */
        UNKNOWN
    }

    /** The classification of one run. */
    public sealed interface Outcome permits Reported, Failure {

        /** What the run says about the credentials. */
        Login login();
    }

    /**
     * The run ended normally; one report per requested part.
     *
     * @param parts the requested parts and their reports
     */
    public record Reported(Map<EntryPart, PartReport> parts) implements Outcome {

        public Reported {
            parts = Map.copyOf(parts);
        }

        @Override
        public Login login() {
            return Login.ACCEPTED;
        }
    }

    /** The whole call failed; no part may be used. */
    public record Failure(ToolError error, Login login) implements Outcome {

        public Failure {
            Objects.requireNonNull(error, "error");
            Objects.requireNonNull(login, "login");
        }
    }

    /** What the helper reported for one requested part. */
    public sealed interface PartReport {

        /** The part was written to its output file, {@code bytes} long. */
        record Written(long bytes) implements PartReport {
        }

        /** INUBIT did not find the part. */
        record NotFound() implements PartReport {
        }

        /** Reading the part failed; {@code reason} is a fixed text with a scrubbed excerpt. */
        record Failed(String reason) implements PartReport {

            public Failed {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /**
     * Classifies {@code result}, the run of the helper for {@code request}, whose client
     * installation {@code clientHome} was chosen for {@code release}.
     */
    public Outcome classify(HelperRequest request, CliResult result, Path clientHome,
        MajorMinor release) {
        HelperFrames parsed = HelperFrames.parse(request.nonce(), result.stdout());
        List<Frame> frames = parsed.frames();
        Login login = frames.contains(new LoginOk()) ? Login.ACCEPTED : Login.UNKNOWN;
        if (result.truncated()) {
            return unexpected(login, "The output of the queue-entry helper exceeded "
                + bound(result.outputBound()) + " and was cut");
        }
        if (parsed.problem().isPresent()) {
            return unexpected(login, switch (parsed.problem().get()) {
                case UNKNOWN_FRAME_TYPE -> "The queue-entry helper wrote a frame of an unknown type";
                case MALFORMED_FRAME -> "The queue-entry helper wrote a malformed frame";
                case FRAME_AFTER_END -> "The queue-entry helper wrote a frame after its end frame";
            });
        }
        int exit = result.exitCode();
        if (exit == EXIT_API_MISSING && frames.size() == 1
            && frames.get(0) instanceof ApiMissing missing) {
            return new Failure(apiMissing(request, missing, clientHome, release), login);
        }
        if (exit == EXIT_LOGIN_FAILED && frames.size() == 2 && started(frames, request)
            && frames.get(1) instanceof LoginError error) {
            return loginError(request, error, release);
        }
        if (exit == EXIT_OK && parsed.ended()) {
            Optional<List<Frame>> parts = partFrames(frames, request);
            if (parts.isPresent()) {
                return report(request, parts.get());
            }
        }
        if (!parsed.ended() && exit != EXIT_LOGIN_FAILED && exit != EXIT_API_MISSING) {
            return unexpected(login, "The queue-entry helper ended with exit code " + exit
                + " without its end frame");
        }
        return unexpected(login, "The frames of the queue-entry helper do not match its exit"
            + " code " + exit);
    }

    /**
     * The part frames of an ended run if the frames are {@code start <variant>},
     * {@code login ok}, one frame per requested part in order, {@code end}.
     */
    private static Optional<List<Frame>> partFrames(List<Frame> frames, HelperRequest request) {
        List<Part> expected = request.dataOut().isPresent()
            ? List.of(Part.DATA, Part.VARIABLES) : List.of(Part.VARIABLES);
        if (frames.size() != expected.size() + 3 || !started(frames, request)
            || !(frames.get(1) instanceof LoginOk)) {
            return Optional.empty();
        }
        List<Frame> parts = frames.subList(2, frames.size() - 1);
        for (int i = 0; i < expected.size(); i++) {
            Part part = switch (parts.get(i)) {
                case PartOk ok -> ok.part();
                case PartError error -> error.part();
                default -> null;
            };
            if (part != expected.get(i)) {
                return Optional.empty();
            }
        }
        return Optional.of(parts);
    }

    private static boolean started(List<Frame> frames, HelperRequest request) {
        return frames.get(0) instanceof Start start && start.variant().equals(request.variant());
    }

    private Outcome report(HelperRequest request, List<Frame> partFrames) {
        Map<EntryPart, PartReport> reports = new EnumMap<>(EntryPart.class);
        for (Frame frame : partFrames) {
            if (frame instanceof PartOk ok) {
                reports.put(entryPart(ok.part()), new PartReport.Written(ok.bytes()));
                continue;
            }
            PartError error = (PartError) frame;
            String text = rawText(error.exceptionChain(), error.message());
            if (NOT_FOUND.matcher(error.message()).find()) {
                reports.put(entryPart(error.part()), new PartReport.NotFound());
                continue;
            }
            Optional<ToolError> callFailure = connectionFailure(text)
                .or(() -> REFUSED.matcher(text).find() ? Optional.of(forbidden())
                    : Optional.empty());
            if (callFailure.isPresent()) {
                return new Failure(callFailure.get(), Login.ACCEPTED);
            }
            reports.put(entryPart(error.part()), new PartReport.Failed(REPORTED
                + details(request, error.exceptionChain(), error.message())));
        }
        return new Reported(reports);
    }

    private Outcome loginError(HelperRequest request, LoginError error, MajorMinor release) {
        String text = rawText(error.exceptionChain(), error.message());
        Optional<ToolError> failure = connectionFailure(text);
        if (failure.isPresent()) {
            return new Failure(failure.get(), Login.UNKNOWN);
        }
        if (LOGIN_REJECTED.matcher(text).find()) {
            return new Failure(error(ErrorCode.AUTH_FAILED,
                "Login of the queue-entry helper to " + labels.node() + " was rejected",
                "Wrong username or password for this INUBIT server, or the account is locked",
                labels.fixCredentials() + (release.major() >= 9 ? INTERNAL_USERS : "")),
                Login.REJECTED);
        }
        if (REFUSED.matcher(text).find()) {
            return new Failure(forbidden(), Login.UNKNOWN);
        }
        return new Failure(unexpectedError("The login of the queue-entry helper failed with an"
            + " error this MCP server does not know")
            .withExcerpt(details(request, error.exceptionChain(), error.message())),
            Login.UNKNOWN);
    }

    /** {@code TLS_ERROR} or {@code UNREACHABLE} if {@code text} shows such a failure. */
    private Optional<ToolError> connectionFailure(String text) {
        if (TLS_FAILURE.matcher(text).find()) {
            return Optional.of(error(ErrorCode.TLS_ERROR,
                "The queue-entry helper could not establish the TLS connection to "
                    + labels.node(),
                "The INUBIT server certificate changed after the check before the launch, or"
                    + " the hostname does not match",
                "Check tls.trustStore and tls.disableHostnameVerification in the"
                    + " configuration"));
        }
        if (NETWORK_FAILURE.matcher(text).find()) {
            return Optional.of(error(ErrorCode.UNREACHABLE,
                "The queue-entry helper could not connect to " + labels.node(),
                "The INUBIT server is down, cli.url is wrong, or the network/VPN is not"
                    + " connected",
                "Check get_health and cli.url, then retry"));
        }
        return Optional.empty();
    }

    private ToolError forbidden() {
        return error(ErrorCode.FORBIDDEN, labels.node()
                + " refused reading the Queue Manager entry for the configured user",
            "The INUBIT user of this node lacks the right to view Queue Manager files",
            "Give the user the Queue Manager right the Workbench needs for \"View file\"");
    }

    private ToolError apiMissing(HelperRequest request, ApiMissing missing, Path clientHome,
        MajorMinor release) {
        String what = excerpt(request, missing.what());
        String entry = release.text();
        return error(ErrorCode.CLI_UNAVAILABLE, "The INUBIT client installation " + clientHome
                + " (chosen for INUBIT " + entry + ") lacks "
                + (what.isEmpty() ? "a class or method the queue-entry helper needs" : what),
            "The client installation is not an INUBIT " + entry + " client, or the unsupported"
                + " INUBIT interface changed after an INUBIT update",
            "Check that defaults.cliClients.\"" + entry + "\" points to an INUBIT " + entry
                + " client");
    }

    private Failure unexpected(Login login, String likelyCause) {
        return new Failure(unexpectedError(likelyCause), login);
    }

    private ToolError unexpectedError(String likelyCause) {
        return error(ErrorCode.UNEXPECTED_RESPONSE, "read_queue_entry got an unexpected answer"
                + " from " + labels.node() + "; the unsupported INUBIT interface may have"
                + " changed after an INUBIT update", likelyCause,
            "Read the entry in the Workbench (Queue Manager, View file); report the INUBIT"
                + " version");
    }

    private ToolError error(ErrorCode code, String message, String likelyCause,
        String nextStep) {
        return ToolError.of(code, scrubber.scrub(message), likelyCause, nextStep)
            .withNode(labels.node());
    }

    /** The exception classes and messages of an error frame, for the markers above only. */
    private static String rawText(List<String> chain, String message) {
        return String.join("\n", chain) + "\n" + message;
    }

    /**
     * The scrubbed excerpt of an error frame: its messages, else the simple names of its
     * exception classes, else "no details".
     */
    private String details(HelperRequest request, List<String> chain, String message) {
        String excerpt = excerpt(request, message);
        if (excerpt.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String name : chain) {
                names.add(name.substring(name.lastIndexOf('.') + 1));
            }
            excerpt = excerpt(request, String.join(" > ", names));
        }
        return excerpt.isEmpty() ? "no details" : excerpt;
    }

    /**
     * {@code text} with the request's passwords, the registered secrets and secret-named values
     * masked, whitespace collapsed and cut at {@value #EXCERPT_LIMIT} chars.
     */
    private String excerpt(HelperRequest request, String text) {
        List<String> passwords = new ArrayList<>(List.of(request.password().reveal()));
        request.trustStore().ifPresent(store -> passwords.add(store.password().reveal()));
        // longest first, so a password inside the other one cannot leave pieces unmasked
        passwords.sort(Comparator.comparingInt(String::length).reversed());
        String masked = text;
        for (String password : passwords) {
            masked = masked.replace(password, SecretScrubber.MASK);
        }
        masked = SecretTextMasker.mask(scrubber.scrub(masked));
        String flat = WHITESPACE.matcher(masked).replaceAll(" ").strip();
        if (flat.length() <= EXCERPT_LIMIT) {
            return flat;
        }
        int end = EXCERPT_LIMIT;
        if (Character.isHighSurrogate(flat.charAt(end - 1))) {
            end--;
        }
        return flat.substring(0, end);
    }

    private static EntryPart entryPart(Part part) {
        return switch (part) {
            case DATA -> EntryPart.DATA;
            case VARIABLES -> EntryPart.VARIABLES;
        };
    }

    /** {@code bytes} as whole MiB where it is one, else in bytes. */
    private static String bound(int bytes) {
        return bytes % (1 << 20) == 0 ? (bytes >> 20) + " MiB" : bytes + " bytes";
    }

    /**
     * An HTTP status {@code code} after {@code HTTP}, {@code status}, {@code status code},
     * {@code statusCode} or {@code response code}, or followed by its reason phrase.
     */
    private static String httpStatus(int code, String reason) {
        return "(?i:\\b(?:HTTP(?:/[0-9.]+)?|status(?:\\s*code)?|statusCode|response\\s+code)"
            + "\"?\\s*[:=]?\\s*\"?" + code + "\\b|\\b" + code + "\\s+" + reason + "\\b)";
    }
}
