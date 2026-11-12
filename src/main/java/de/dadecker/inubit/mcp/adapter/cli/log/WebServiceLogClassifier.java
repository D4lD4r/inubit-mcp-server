package de.dadecker.inubit.mcp.adapter.cli.log;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFailures;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Frame;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.NodeError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.NodeOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Part;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogPresence;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Judges one run of the client helper's {@code serverLog} operation (research D-4, D-5, D-8,
 * D-14): its frames and exit code become the downloaded web service log or one
 * {@link ToolError}.
 *
 * <ul>
 *   <li>A complete run has exit code 0 and the frames {@code start <variant of the request>},
 *       {@code login ok}, then either {@code node ok <name>} and one {@code part log} frame, or
 *       {@code node error}, then {@code end}. Other frames on exit 0 or 1 are
 *       {@code UNEXPECTED_RESPONSE} ({@link HelperFailures#unmatched}).
 *   <li>The INUBIT node name of {@code node ok} must be 1–128 chars of {@code [A-Za-z0-9_.:-]}
 *       (it appears in the result and the log line), else {@code UNEXPECTED_RESPONSE}.
 *   <li>{@code part log ok <bytes>} is the log, {@link LogPresence#PRESENT} also with 0 bytes:
 *       this channel tells a missing file apart (D-8).
 *   <li>{@code part log error} whose message contains {@code not found on} (INUBIT's "File
 *       webservice.log not found on &lt;node&gt;") is {@code NOT_FOUND}.
 *   <li>A {@code node error} or other {@code part log error}: a TLS or network failure, HTTP 403
 *       or {@code AccessDenied} fails the call as {@link HelperFailures#callFailure} maps it;
 *       anything else ({@code Server not in cluster mode.} included) is
 *       {@code UNEXPECTED_RESPONSE} with a scrubbed excerpt.
 *   <li>Everything else (the protocol problems, {@code api missing}, {@code login error}) is
 *       mapped by {@link HelperFailures} with the web service log texts of this class.
 * </ul>
 *
 * <p>Vendor output outside the frames never leaves this class; excerpts are masked and bounded by
 * {@link HelperFailures}.
 */
public final class WebServiceLogClassifier {

    /** The helper's exit code of a complete run (research D-4). */
    private static final int EXIT_OK = 0;

    private static final Pattern NODE_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final String NOT_FOUND = "not found on";

    private static final HelperFailures.Subject SUBJECT = new HelperFailures.Subject(
        "INUBIT client helper", "download_server_log",
        "Download the log in the Workbench; report the INUBIT version",
        "refused the web service log download for the configured user",
        "The INUBIT user of this node lacks the right to download server logs",
        "Give the user the right the Workbench's Cluster Manager needs (Configuration)");

    private final SecretScrubber scrubber;
    private final SecretLabels labels;
    private final HelperFailures failures;

    /**
     * @param scrubber the secrets to mask in excerpts and messages
     * @param labels the node's secret names, for the next step of {@code AUTH_FAILED}
     */
    public WebServiceLogClassifier(SecretScrubber scrubber, SecretLabels labels) {
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.labels = Objects.requireNonNull(labels, "labels");
        this.failures = new HelperFailures(scrubber, labels, SUBJECT);
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
    public sealed interface Outcome permits Downloaded, Failure {

        /** What the run says about the credentials. */
        Login login();
    }

    /**
     * The helper wrote the web service log of INUBIT node {@code inubitNode} to its output file.
     *
     * @param inubitNode the checked INUBIT node name of {@code node ok}
     * @param size the bytes of {@code part log ok}
     * @param presence whether the log is known to exist
     */
    public record Downloaded(String inubitNode, long size, LogPresence presence)
        implements Outcome {

        public Downloaded {
            Objects.requireNonNull(inubitNode, "inubitNode");
            Objects.requireNonNull(presence, "presence");
        }

        @Override
        public Login login() {
            return Login.ACCEPTED;
        }
    }

    /** The call failed; the output file must not be used. */
    public record Failure(ToolError error, Login login) implements Outcome {

        public Failure {
            Objects.requireNonNull(error, "error");
            Objects.requireNonNull(login, "login");
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
        Optional<ToolError> failure =
            failures.failure(request, result, parsed, clientHome, release);
        if (failure.isPresent()) {
            ToolError error = failure.get();
            // AUTH_FAILED comes only from a rejected login (HelperFailures)
            return new Failure(error,
                error.code() == ErrorCode.AUTH_FAILED ? Login.REJECTED : login);
        }
        if (result.exitCode() == EXIT_OK && parsed.ended() && frames.size() >= 4
            && HelperFailures.started(frames, request) && frames.get(1) instanceof LoginOk) {
            List<Frame> answer = frames.subList(2, frames.size() - 1);
            if (answer.size() == 1 && answer.get(0) instanceof NodeError error) {
                return new Failure(nodeError(request, error), Login.ACCEPTED);
            }
            if (answer.size() == 2 && answer.get(0) instanceof NodeOk node
                && isLogFrame(answer.get(1))) {
                return downloaded(request, node.name(), answer.get(1));
            }
        }
        return new Failure(failures.unmatched(parsed, result.exitCode()), login);
    }

    private static boolean isLogFrame(Frame frame) {
        return frame instanceof PartOk ok && ok.part() == Part.LOG
            || frame instanceof PartError error && error.part() == Part.LOG;
    }

    private ToolError nodeError(HelperRequest request, NodeError error) {
        return failures.callFailure(error.exceptionChain(), error.message())
            .orElseGet(() -> failures.unexpected(
                    "INUBIT reported an error when asked for the node of the web service log")
                .withExcerpt(failures.details(request, error.exceptionChain(),
                    error.message())));
    }

    private Outcome downloaded(HelperRequest request, String name, Frame log) {
        if (!NODE_NAME.matcher(name).matches()) {
            // the name itself is not repeated: it is not known to be safe to show
            return new Failure(failures.unexpected("The INUBIT client helper reported an INUBIT"
                + " node name that is not 1-128 characters of A-Z, a-z, 0-9, '_', '.', ':' and"
                + " '-'"),
                Login.ACCEPTED);
        }
        if (log instanceof PartOk ok) {
            return new Downloaded(name, ok.bytes(), LogPresence.PRESENT);
        }
        PartError error = (PartError) log;
        if (error.message().contains(NOT_FOUND)) {
            return new Failure(ToolError.of(ErrorCode.NOT_FOUND, scrubber.scrub(labels.node()
                    + " has no web service log (webservice.log on INUBIT node " + name + ")"),
                "Web service logging is disabled in the server configuration, or the log does"
                    + " not exist yet",
                "Enable web service logging in the server configuration, then retry")
                .withNode(labels.node()), Login.ACCEPTED);
        }
        ToolError failure = failures.callFailure(error.exceptionChain(), error.message())
            .orElseGet(() -> failures.unexpected("INUBIT reported an error when downloading"
                    + " webservice.log from INUBIT node " + name)
                .withExcerpt(failures.details(request, error.exceptionChain(),
                    error.message())));
        return new Failure(failure, Login.ACCEPTED);
    }
}
