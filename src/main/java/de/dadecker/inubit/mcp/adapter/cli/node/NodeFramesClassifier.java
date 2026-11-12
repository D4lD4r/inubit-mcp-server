package de.dadecker.inubit.mcp.adapter.cli.node;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFailures;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ApiMissing;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Frame;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.NodeError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.NodeOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Start;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Judges the part of a client-helper run that every node-tools operation shares (feature 024,
 * research D-2, D-3, D-20); the operation's own frames are handed on.
 *
 * <ul>
 *   <li>A complete run has exit code 0 and the frames {@code start <variant of the request>},
 *       {@code login ok}, {@code node ok <name>} or {@code node error}, the operation's frames,
 *       {@code end}. After {@code node error} only {@code end} may follow. Other frames, frames
 *       that do not match the exit code, or a missing {@code end} are {@code UNEXPECTED_RESPONSE}
 *       ({@link HelperFailures#unmatched}); so is a {@code start}, {@code login …},
 *       {@code node …} or {@code api missing} frame among the operation's frames.
 *   <li>The INUBIT node name of {@code node ok} must be 1–128 chars of {@code [A-Za-z0-9_.:-]}
 *       (it appears in the result and in log lines), else {@code UNEXPECTED_RESPONSE} without
 *       repeating the name.
 *   <li>{@code node error}: a TLS or network failure, HTTP 403 or {@code AccessDenied} fails the
 *       call as {@link HelperFailures#callFailure} maps it; anything else is
 *       {@code UNEXPECTED_RESPONSE} with a scrubbed excerpt.
 *   <li>The cut output, frame problems, {@code api missing} and {@code login error} are mapped by
 *       {@link HelperFailures} with the texts of the tool's {@link HelperFailures.Subject}.
 * </ul>
 */
public final class NodeFramesClassifier {

    /** The helper's exit code of a complete run (feature 020 research D-4). */
    private static final int EXIT_OK = 0;
    private static final Pattern NODE_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private final HelperFailures failures;

    /**
     * @param scrubber the secrets to mask in excerpts and messages
     * @param labels the node's secret names, for the next step of {@code AUTH_FAILED}
     * @param subject the tool the texts name
     */
    public NodeFramesClassifier(SecretScrubber scrubber, SecretLabels labels,
        HelperFailures.Subject subject) {
        this.failures = new HelperFailures(scrubber, labels, subject);
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
    public sealed interface Outcome permits Answered, Failure {

        /** What the run says about the credentials. */
        Login login();
    }

    /**
     * The run answered for INUBIT node {@code inubitNode}.
     *
     * @param inubitNode the checked name of {@code node ok}
     * @param frames the operation's frames between {@code node ok} and {@code end}, in order
     */
    public record Answered(String inubitNode, List<Frame> frames) implements Outcome {

        public Answered {
            Objects.requireNonNull(inubitNode, "inubitNode");
            frames = List.copyOf(frames);
        }

        @Override
        public Login login() {
            return Login.ACCEPTED;
        }
    }

    /** The call failed; nothing the helper wrote may be used. */
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
            List<Frame> operation = answer.subList(1, answer.size());
            if (answer.get(0) instanceof NodeOk node
                && operation.stream().noneMatch(NodeFramesClassifier::shared)) {
                return answered(node.name(), operation);
            }
        }
        return new Failure(failures.unmatched(parsed, result.exitCode()), login);
    }

    /** A frame every operation shares, which has no place among the operation's frames. */
    private static boolean shared(Frame frame) {
        return frame instanceof Start || frame instanceof LoginOk || frame instanceof LoginError
            || frame instanceof NodeOk || frame instanceof NodeError
            || frame instanceof ApiMissing;
    }

    private Outcome answered(String name, List<Frame> operation) {
        if (!NODE_NAME.matcher(name).matches()) {
            // the name itself is not repeated: it is not known to be safe to show
            return new Failure(failures.unexpected("The INUBIT client helper reported an INUBIT"
                + " node name that is not 1-128 characters of A-Z, a-z, 0-9, '_', '.', ':' and"
                + " '-'"), Login.ACCEPTED);
        }
        return new Answered(name, operation);
    }

    private ToolError nodeError(HelperRequest request, NodeError error) {
        return failures.callFailure(error.exceptionChain(), error.message())
            .orElseGet(() -> failures.unexpected(
                    "INUBIT reported an error when asked for the node that answers")
                .withExcerpt(failures.details(request, error.exceptionChain(),
                    error.message())));
    }
}
