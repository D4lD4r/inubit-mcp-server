package de.dadecker.inubit.mcp.adapter.cli.helper;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ApiMissing;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Frame;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Start;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The failures of a client-helper run that every operation shares (feature 021 research D-3,
 * D-14; feature 020 D-5). The texts name the operation through its {@link Subject}. The launch
 * failure and the timeout of the run are mapped before, by {@code ProcessRun} with the helper's
 * label.
 *
 * <ul>
 *   <li>Protocol problems are {@code UNEXPECTED_RESPONSE}: output cut at its bound, an unknown or
 *       malformed frame, a frame after {@code end}, no {@code end} on an exit code other than 3
 *       and 4 (such as the helper's silent exit 1), frames that do not fit the exit code.
 *   <li>Exit 4 with a single {@code api missing}: {@code CLI_UNAVAILABLE} naming the client
 *       installation, what it lacks (or "a class or method" when the helper could not name it)
 *       and the {@code defaults.cliClients} entry of the release.
 *   <li>Exit 3 with {@code start <variant of the request>}, {@code login error}: an SSL,
 *       certificate or PKIX failure is {@code TLS_ERROR}; a connect, unknown-host, no-route or
 *       socket-timeout failure {@code UNREACHABLE}; {@code LoginFailure}, "password does not
 *       match" or HTTP 401 {@code AUTH_FAILED} (on INUBIT 9 with the internal-user-management
 *       hint of feature 017); HTTP 403 or {@code AccessDenied} {@code FORBIDDEN}; any other
 *       {@code UNEXPECTED_RESPONSE} with an excerpt. {@code AUTH_FAILED} is returned only here,
 *       when INUBIT rejected the credentials: the caller settles its {@code CredentialGuard}
 *       permit as rejected, which starts the back-off.
 *   <li>An error after the login ({@link #callFailure}): a TLS or network failure, HTTP 403 or
 *       {@code AccessDenied} fails the whole call as above.
 * </ul>
 *
 * <p>Vendor output outside the frames is never used. The only vendor texts in the errors are the
 * excerpts (at most {@value #EXCERPT_LIMIT} chars, whitespace collapsed) and the name of what is
 * missing; they are taken from the frame messages (or the exception class names when there are
 * none) after the request's password and trust-store password, the secrets registered with the
 * {@link SecretScrubber} and the values of secret-named fields ({@link SecretTextMasker}) are
 * masked.
 */
public final class HelperFailures {

    static final int EXCERPT_LIMIT = 200;

    /** The helper's exit codes (feature 020 research D-4; the helper is compiled separately). */
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
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * The operation a run belongs to, as the error texts name it.
     *
     * @param helper the helper, "queue-entry helper"; written after "the" or "The"
     * @param tool the MCP tool, named in {@code UNEXPECTED_RESPONSE}
     * @param unexpectedNextStep the next step of {@code UNEXPECTED_RESPONSE}
     * @param refusal what the node refused, after its id in the {@code FORBIDDEN} message
     * @param refusalCause the likely cause of {@code FORBIDDEN}
     * @param refusalNextStep the next step of {@code FORBIDDEN}
     */
    public record Subject(String helper, String tool, String unexpectedNextStep, String refusal,
        String refusalCause, String refusalNextStep) {

        public Subject {
            Objects.requireNonNull(helper, "helper");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(unexpectedNextStep, "unexpectedNextStep");
            Objects.requireNonNull(refusal, "refusal");
            Objects.requireNonNull(refusalCause, "refusalCause");
            Objects.requireNonNull(refusalNextStep, "refusalNextStep");
        }
    }

    private final SecretScrubber scrubber;
    private final SecretLabels labels;
    private final Subject subject;

    /**
     * @param scrubber the secrets to mask in excerpts and messages
     * @param labels the node's secret names, for the next step of {@code AUTH_FAILED}
     * @param subject the operation the texts name
     */
    public HelperFailures(SecretScrubber scrubber, SecretLabels labels, Subject subject) {
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.labels = Objects.requireNonNull(labels, "labels");
        this.subject = Objects.requireNonNull(subject, "subject");
    }

    /**
     * The shared failure of {@code result}, the run of the helper for {@code request} whose
     * frames are {@code parsed} and whose client installation {@code clientHome} was chosen for
     * {@code release}: a cut output, a frame problem, {@code api missing} or {@code login error};
     * empty if the run is none of these and its frames are the operation's to judge.
     */
    public Optional<ToolError> failure(HelperRequest request, CliResult result,
        HelperFrames parsed, Path clientHome, MajorMinor release) {
        if (result.truncated()) {
            return Optional.of(unexpected("The output of the " + subject.helper()
                + " exceeded " + bound(result.outputBound()) + " and was cut"));
        }
        if (parsed.problem().isPresent()) {
            String frame = switch (parsed.problem().get()) {
                case UNKNOWN_FRAME_TYPE -> "a frame of an unknown type";
                case MALFORMED_FRAME -> "a malformed frame";
                case FRAME_AFTER_END -> "a frame after its end frame";
            };
            return Optional.of(unexpected("The " + subject.helper() + " wrote " + frame));
        }
        List<Frame> frames = parsed.frames();
        int exit = result.exitCode();
        if (exit == EXIT_API_MISSING && frames.size() == 1
            && frames.get(0) instanceof ApiMissing missing) {
            return Optional.of(apiMissing(request, missing, clientHome, release));
        }
        if (exit == EXIT_LOGIN_FAILED && frames.size() == 2 && started(frames, request)
            && frames.get(1) instanceof LoginError error) {
            return Optional.of(loginError(request, error, release));
        }
        return Optional.empty();
    }

    /**
     * The failure of a run that has no {@link #failure} and whose frames the operation did not
     * accept: no {@code end} frame, or frames that do not match exit code {@code exit}.
     */
    public ToolError unmatched(HelperFrames parsed, int exit) {
        if (!parsed.ended() && exit != EXIT_LOGIN_FAILED && exit != EXIT_API_MISSING) {
            return unexpected("The " + subject.helper() + " ended with exit code " + exit
                + " without its end frame");
        }
        return unexpected("The frames of the " + subject.helper() + " do not match its exit"
            + " code " + exit);
    }

    /**
     * {@code TLS_ERROR}, {@code UNREACHABLE} or {@code FORBIDDEN} if an error frame after the
     * login, with the exception classes {@code chain} and {@code message}, shows a failure of the
     * whole call; empty if the operation judges the error itself.
     */
    public Optional<ToolError> callFailure(List<String> chain, String message) {
        String text = rawText(chain, message);
        return connectionFailure(text)
            .or(() -> REFUSED.matcher(text).find() ? Optional.of(forbidden()) : Optional.empty());
    }

    /** Whether the (non-empty) frames begin with {@code start} of the request's variant. */
    public static boolean started(List<Frame> frames, HelperRequest request) {
        return frames.get(0) instanceof Start start
            && start.variant().equals(request.variant());
    }

    /**
     * The scrubbed excerpt of an error frame: its messages, else the simple names of its
     * exception classes, else "no details".
     */
    public String details(HelperRequest request, List<String> chain, String message) {
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

    private ToolError loginError(HelperRequest request, LoginError error, MajorMinor release) {
        String text = rawText(error.exceptionChain(), error.message());
        Optional<ToolError> failure = connectionFailure(text);
        if (failure.isPresent()) {
            return failure.get();
        }
        if (LOGIN_REJECTED.matcher(text).find()) {
            return error(ErrorCode.AUTH_FAILED,
                "Login of the " + subject.helper() + " to " + labels.node() + " was rejected",
                "Wrong username or password for this INUBIT server, or the account is locked",
                labels.fixCredentials() + (release.major() >= 9 ? internalUsers() : ""));
        }
        if (REFUSED.matcher(text).find()) {
            return forbidden();
        }
        return unexpected("The login of the " + subject.helper() + " failed with an error this"
            + " MCP server does not know")
            .withExcerpt(details(request, error.exceptionChain(), error.message()));
    }

    /** Feature 017 (research D-16): the client login cannot use OAuth-only users. */
    private String internalUsers() {
        return ". On INUBIT 9 the user must exist in the internal user management; users that"
            + " log in only through OAuth cannot use StartCLI or the " + subject.helper();
    }

    /** {@code TLS_ERROR} or {@code UNREACHABLE} if {@code text} shows such a failure. */
    private Optional<ToolError> connectionFailure(String text) {
        if (TLS_FAILURE.matcher(text).find()) {
            return Optional.of(error(ErrorCode.TLS_ERROR,
                "The " + subject.helper() + " could not establish the TLS connection to "
                    + labels.node(),
                "The INUBIT server certificate changed after the check before the launch, or"
                    + " the hostname does not match",
                "Check tls.trustStore and tls.disableHostnameVerification in the"
                    + " configuration"));
        }
        if (NETWORK_FAILURE.matcher(text).find()) {
            return Optional.of(error(ErrorCode.UNREACHABLE,
                "The " + subject.helper() + " could not connect to " + labels.node(),
                "The INUBIT server is down, cli.url is wrong, or the network/VPN is not"
                    + " connected",
                "Check get_health and cli.url, then retry"));
        }
        return Optional.empty();
    }

    private ToolError forbidden() {
        return error(ErrorCode.FORBIDDEN, labels.node() + " " + subject.refusal(),
            subject.refusalCause(), subject.refusalNextStep());
    }

    private ToolError apiMissing(HelperRequest request, ApiMissing missing, Path clientHome,
        MajorMinor release) {
        String what = excerpt(request, missing.what());
        String entry = release.text();
        return error(ErrorCode.CLI_UNAVAILABLE, "The INUBIT client installation " + clientHome
                + " (chosen for INUBIT " + entry + ") lacks "
                + (what.isEmpty() ? "a class or method the " + subject.helper() + " needs"
                    : what),
            "The client installation is not an INUBIT " + entry + " client, or the unsupported"
                + " INUBIT interface changed after an INUBIT update",
            "Check that defaults.cliClients.\"" + entry + "\" points to an INUBIT " + entry
                + " client");
    }

    private ToolError unexpected(String likelyCause) {
        return error(ErrorCode.UNEXPECTED_RESPONSE, subject.tool() + " got an unexpected answer"
                + " from " + labels.node() + "; the unsupported INUBIT interface may have"
                + " changed after an INUBIT update", likelyCause,
            subject.unexpectedNextStep());
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
