package de.dadecker.inubit.mcp.adapter.cli.queue;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFailures;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Frame;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Part;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest.QueueEntryRead;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.file.Path;
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
 *   <li>{@code part … error}: INUBIT's markers {@code @Start@Unable to download} and
 *       {@code MissingWorkflowWithName} make the part {@link PartReport.NotFound}; a TLS or
 *       network failure, HTTP 403 or {@code AccessDenied} fails the whole call
 *       ({@link HelperFailures#callFailure}); any other error fails only that part with "INUBIT
 *       reported an error: &lt;excerpt&gt;".
 *   <li>Everything else (the protocol problems, {@code api missing}, {@code login error}) is
 *       mapped by {@link HelperFailures} with the queue-entry texts of this class.
 * </ul>
 *
 * <p>Vendor output outside the frames never leaves this class; excerpts are masked and bounded by
 * {@link HelperFailures}.
 */
public final class QueueEntryHelperClassifier {

    /** The helper's exit code of a complete run (research D-4). */
    private static final int EXIT_OK = 0;

    private static final Pattern NOT_FOUND =
        Pattern.compile("@Start@Unable to download|MissingWorkflowWithName");

    private static final HelperFailures.Subject SUBJECT = new HelperFailures.Subject(
        "queue-entry helper", "read_queue_entry",
        "Read the entry in the Workbench (Queue Manager, View file); report the INUBIT version",
        "refused reading the Queue Manager entry for the configured user",
        "The INUBIT user of this node lacks the right to view Queue Manager files",
        "Give the user the Queue Manager right the Workbench needs for \"View file\"");
    private static final String REPORTED = "INUBIT reported an error: ";

    private final HelperFailures failures;

    /**
     * @param scrubber the secrets to mask in excerpts and messages
     * @param labels the node's secret names, for the next step of {@code AUTH_FAILED}
     */
    public QueueEntryHelperClassifier(SecretScrubber scrubber, SecretLabels labels) {
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
        Optional<ToolError> failure =
            failures.failure(request, result, parsed, clientHome, release);
        if (failure.isPresent()) {
            ToolError error = failure.get();
            // AUTH_FAILED comes only from a rejected login (HelperFailures)
            return new Failure(error,
                error.code() == ErrorCode.AUTH_FAILED ? Login.REJECTED : login);
        }
        if (result.exitCode() == EXIT_OK && parsed.ended()) {
            Optional<List<Frame>> parts = partFrames(frames, request);
            if (parts.isPresent()) {
                return report(request, parts.get());
            }
        }
        return new Failure(failures.unmatched(parsed, result.exitCode()), login);
    }

    /**
     * The part frames of an ended run if the frames are {@code start <variant>},
     * {@code login ok}, one frame per requested part in order, {@code end}.
     */
    private static Optional<List<Frame>> partFrames(List<Frame> frames, HelperRequest request) {
        boolean data = request.operation() instanceof QueueEntryRead read
            && read.dataOut().isPresent();
        List<Part> expected = data ? List.of(Part.DATA, Part.VARIABLES) : List.of(Part.VARIABLES);
        if (frames.size() != expected.size() + 3 || !HelperFailures.started(frames, request)
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

    private Outcome report(HelperRequest request, List<Frame> partFrames) {
        Map<EntryPart, PartReport> reports = new EnumMap<>(EntryPart.class);
        for (Frame frame : partFrames) {
            if (frame instanceof PartOk ok) {
                reports.put(entryPart(ok.part()), new PartReport.Written(ok.bytes()));
                continue;
            }
            PartError error = (PartError) frame;
            if (NOT_FOUND.matcher(error.message()).find()) {
                reports.put(entryPart(error.part()), new PartReport.NotFound());
                continue;
            }
            Optional<ToolError> callFailure =
                failures.callFailure(error.exceptionChain(), error.message());
            if (callFailure.isPresent()) {
                return new Failure(callFailure.get(), Login.ACCEPTED);
            }
            reports.put(entryPart(error.part()), new PartReport.Failed(REPORTED
                + failures.details(request, error.exceptionChain(), error.message())));
        }
        return new Reported(reports);
    }

    private static EntryPart entryPart(Part part) {
        return switch (part) {
            case DATA -> EntryPart.DATA;
            case VARIABLES -> EntryPart.VARIABLES;
            // partFrames lets only the requested queue-entry parts through
            case LOG -> throw new IllegalStateException("not a queue-entry part");
        };
    }
}
