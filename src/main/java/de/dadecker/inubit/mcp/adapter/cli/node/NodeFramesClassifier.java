package de.dadecker.inubit.mcp.adapter.cli.node;

import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFailures;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ApiMissing;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ConfigAbsent;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ConfigError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ConfigOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Detail;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.DetailsError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.DetailsOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.DetailsUnknown;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Frame;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.IoListError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.IoListOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.IoMatch;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.LoginOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Member;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.MembersError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.MembersOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.NodeError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.NodeOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Part;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Start;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.application.nodetools.NodeTextMasking;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNode;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import de.dadecker.inubit.mcp.domain.model.node.IoLogBounds;
import de.dadecker.inubit.mcp.domain.model.node.IoLogEntry;
import de.dadecker.inubit.mcp.domain.model.node.IoLogListing;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValue;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValues;
import de.dadecker.inubit.mcp.domain.model.node.NodeDetail;
import de.dadecker.inubit.mcp.domain.model.node.NodeStatus;
import de.dadecker.inubit.mcp.domain.port.IoLogPort.IoLogRead;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Judges the part of a client-helper run that every node-tools operation shares (feature 024,
 * research D-2, D-3, D-20); the operation's own frames are handed on, and those of
 * {@code clusterNodes} are judged by {@link #clusterNodes} (D-7), those of {@code ioLog} by
 * {@link #ioListing} and {@link #ioRead} (D-8, D-9), those of {@code nodeConfig} by
 * {@link #nodeConfig} (D-10).
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
    /** The rule of {@link ClusterNode#rawStatus()} (research D-7). */
    private static final Pattern RAW_STATUS = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    /** The members an {@code INVALID_INPUT} for an unknown details node names at most. */
    private static final int MAX_NAMED_MEMBERS = 20;
    /**
     * INUBIT's refusal of the I/O log listing for the account, in the message of
     * {@code io list error} (spike T018, research D-20).
     */
    private static final Pattern NOT_ALLOWED_ACCESS = Pattern.compile("@Start@NotAllowedAccess@");
    /**
     * The exception chain and message part of INUBIT 8.1.17's answer for a configuration property
     * the node does not have (spike T018, research D-10): an internal INUBIT error wrapping the
     * server's {@code NullPointerException}.
     */
    private static final List<String> MISSING_PROPERTY_CHAIN =
        List.of("com.inubit.ibis.utils.InubitException");
    private static final String MISSING_PROPERTY_MESSAGE =
        "Cannot invoke \"Object.getClass()\" because \"o\" is null";

    private final HelperFailures failures;
    private final SecretLabels labels;
    private final NodeTextMasking masking;

    /**
     * @param scrubber the secrets to mask in excerpts and messages
     * @param labels the node's secret names, for the next step of {@code AUTH_FAILED}
     * @param subject the tool the texts name
     */
    public NodeFramesClassifier(SecretScrubber scrubber, SecretLabels labels,
        HelperFailures.Subject subject) {
        this.failures = new HelperFailures(scrubber, labels, subject);
        this.labels = labels;
        this.masking = new NodeTextMasking(scrubber::scrub);
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
        return callError(request, error.exceptionChain(), error.message(),
            "INUBIT reported an error when asked for the node that answers");
    }

    /**
     * The cluster's members and, if the request asked for them, the details of one member, from
     * the {@code clusterNodes} frames of a run that answered for {@code inubitNode} (research
     * D-7, D-20). The frames are: one {@code member} per listed element (at most
     * {@value ClusterNodes#MAX_NODES}), then {@code members ok <count>}; with {@code details},
     * then {@code details unknown}, or {@code detail} frames followed by {@code details ok
     * <count> <truncated>} or {@code details error}.
     *
     * <ul>
     *   <li>Status {@code ACTIVE}, {@code MAINTENANCE}, {@code OFFLINE} as named; none
     *       ({@code -}) is {@code UNKNOWN}; another text of 1–64 {@code [A-Za-z0-9_.-]} is
     *       {@code UNKNOWN} with that raw status. Version, zone and node ID at most
     *       {@value ClusterNode#MAX_TEXT_CHARS} characters, absent for {@code -}. The uptime is
     *       absent for {@code -} and for an {@code OFFLINE} member with uptime 0, the value
     *       INUBIT 8.1 gives a member that does not answer (spike T018).
     *   <li>Detail values have the configured credentials and secret-named values masked
     *       ({@link NodeTextMasking}) before they are cut to
     *       {@value NodeDetail#MAX_VALUE_CHARS} characters; a value the helper cut stays marked
     *       as cut. Keys are not masked and must not exceed {@value NodeDetail#MAX_KEY_CHARS}.
     *   <li>{@code details error} makes the details {@link ClusterNodes.Unavailable} with the
     *       scrubbed excerpt as reason; the list is returned.
     * </ul>
     *
     * @throws ToolErrorException {@code INVALID_INPUT} naming up to
     *     {@value #MAX_NAMED_MEMBERS} members for {@code details unknown};
     *     {@code UNEXPECTED_RESPONSE} (or {@link HelperFailures#callFailure}'s code) for
     *     {@code members error}; {@code UNEXPECTED_RESPONSE} for any other frame, order or count,
     *     and for a member name, status, text or detail key that breaks the rules above, without
     *     repeating it
     */
    public ClusterNodes clusterNodes(HelperRequest request, String inubitNode,
        List<Frame> frames) {
        Optional<String> details = ((HelperRequest.ClusterNodes) request.operation()).details();
        int next = 0;
        List<ClusterNode> nodes = new ArrayList<>();
        while (next < frames.size() && frames.get(next) instanceof Member member) {
            nodes.add(member(member));
            next++;
        }
        Frame end = next < frames.size() ? frames.get(next) : null;
        if (end instanceof MembersError error) {
            throw new ToolErrorException(callError(request, error.exceptionChain(),
                error.message(), "INUBIT reported an error when asked for the cluster's nodes"));
        }
        if (!(end instanceof MembersOk ok)) {
            throw unexpected("The INUBIT client helper did not end the member list with"
                + " members ok");
        }
        if (nodes.size() != Math.min(ok.count(), ClusterNodes.MAX_NODES)) {
            throw unexpected("The INUBIT client helper listed " + nodes.size() + " members, but"
                + " reported " + ok.count());
        }
        List<Frame> rest = frames.subList(next + 1, frames.size());
        Optional<ClusterNodes.Details> read = Optional.empty();
        if (details.isPresent()) {
            read = Optional.of(details(request, details.get(), nodes, ok.count(), rest));
        } else if (!rest.isEmpty()) {
            throw unexpected("The INUBIT client helper wrote frames after the member list that"
                + " were not asked for");
        }
        return new ClusterNodes(inubitNode, nodes, ok.count(), ok.count() > nodes.size(), read);
    }

    private ClusterNode member(Member member) {
        if (!ClusterNode.isValidName(member.name())) {
            throw unexpected("The INUBIT client helper reported a cluster member name that is"
                + " not 1-128 characters of A-Z, a-z, 0-9, '_', '.', ':' and '-'");
        }
        Optional<String> text = optional(member.status());
        NodeStatus status = NodeStatus.of(text);
        Optional<String> rawStatus = status == NodeStatus.UNKNOWN ? text : Optional.empty();
        if (rawStatus.isPresent() && !RAW_STATUS.matcher(rawStatus.get()).matches()) {
            throw unexpected("The INUBIT client helper reported a member status that is not 1-64"
                + " characters of A-Z, a-z, 0-9, '_', '.' and '-'");
        }
        for (String value : List.of(member.version(), member.zone(), member.nodeId())) {
            if (value.length() > ClusterNode.MAX_TEXT_CHARS) {
                throw unexpected("The INUBIT client helper reported a member version, zone or"
                    + " node ID of more than " + ClusterNode.MAX_TEXT_CHARS + " characters");
            }
        }
        Optional<Long> uptimeMs = member.uptimeMs();
        if (status == NodeStatus.OFFLINE && uptimeMs.equals(Optional.of(0L))) {
            uptimeMs = Optional.empty();
        }
        return new ClusterNode(member.name(), status, rawStatus, optional(member.version()),
            optional(member.zone()), optional(member.nodeId()), uptimeMs.map(Duration::ofMillis));
    }

    private ClusterNodes.Details details(HelperRequest request, String node,
        List<ClusterNode> nodes, int total, List<Frame> frames) {
        if (frames.isEmpty()) {
            throw unexpected("The INUBIT client helper did not answer the details of the node");
        }
        List<Frame> entries = frames.subList(0, frames.size() - 1);
        Frame last = frames.get(frames.size() - 1);
        if (entries.stream().allMatch(Detail.class::isInstance)) {
            if (last instanceof DetailsUnknown && entries.isEmpty()) {
                throw new ToolErrorException(unknownNode(node, nodes, total));
            }
            if (last instanceof DetailsError error) {
                return new ClusterNodes.Unavailable(node,
                    failures.details(request, error.exceptionChain(), error.message()));
            }
            if (last instanceof DetailsOk ok && ok.count() == entries.size()
                && entries.size() <= ClusterNodes.MAX_PAIRS) {
                List<NodeDetail> pairs = new ArrayList<>();
                for (Frame entry : entries) {
                    pairs.add(pair((Detail) entry));
                }
                return new ClusterNodes.Available(node, pairs, ok.truncated());
            }
        }
        throw unexpected("The detail frames of the INUBIT client helper do not end in one"
            + " details frame that matches them");
    }

    private NodeDetail pair(Detail detail) {
        if (detail.key().length() > NodeDetail.MAX_KEY_CHARS) {
            throw unexpected("The INUBIT client helper reported a detail key of more than "
                + NodeDetail.MAX_KEY_CHARS + " characters");
        }
        NodeTextMasking.Bounded value =
            masking.maskAndCut(detail.value(), NodeDetail.MAX_VALUE_CHARS);
        return new NodeDetail(detail.key(), value.text(), detail.cut() || value.cut());
    }

    /**
     * The configuration values of a {@code nodeConfig} run that answered for {@code inubitNode}
     * (research D-10, D-20, D-21): one {@code config} frame per requested name, in request order.
     *
     * <ul>
     *   <li>{@code config <name> ok …} is {@link NodeConfigValue.Present}; the value has the
     *       configured credentials and secret-named values masked ({@link NodeTextMasking})
     *       before it is cut to {@value NodeConfigValue#MAX_VALUE_CHARS} characters, and a
     *       cut value is marked as cut.
     *   <li>{@code config <name> absent} is {@link NodeConfigValue.Absent}; so is a
     *       {@code config <name> error} with exactly INUBIT 8.1.17's answer for a property the
     *       node does not have (spike T018): the only exception class
     *       {@code com.inubit.ibis.utils.InubitException} and a message containing
     *       {@code Cannot invoke "Object.getClass()" because "o" is null}.
     *   <li>Any other {@code config <name> error} is {@link NodeConfigValue.Failed} with
     *       {@link HelperFailures#callFailure}'s code, else {@code UNEXPECTED_RESPONSE}, and the
     *       scrubbed excerpt as message; the other names are still answered.
     * </ul>
     *
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} if the frames do not answer each
     *     requested name exactly once in request order (a name not asked for, a missing, repeated
     *     or reordered name, any other frame), without repeating the name
     */
    public NodeConfigValues nodeConfig(HelperRequest request, String inubitNode,
        List<Frame> frames) {
        List<NodeConfigName> names = ((HelperRequest.NodeConfig) request.operation()).names();
        if (frames.size() != names.size()) {
            throw unexpectedConfigFrames();
        }
        List<NodeConfigValue> values = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            values.add(configValue(request, names.get(i), frames.get(i)));
        }
        return new NodeConfigValues(inubitNode, values);
    }

    private NodeConfigValue configValue(HelperRequest request, NodeConfigName name, Frame frame) {
        String wire = name.wireName();
        if (frame instanceof ConfigOk ok && ok.name().equals(wire)) {
            NodeTextMasking.Bounded value =
                masking.maskAndCut(ok.value(), NodeConfigValue.MAX_VALUE_CHARS);
            return new NodeConfigValue.Present(name, value.text(), value.cut(), ok.type(),
                ok.displayName(), ok.description(), ok.category(), ok.editable(), ok.deprecated());
        }
        if (frame instanceof ConfigAbsent absent && absent.name().equals(wire)) {
            return new NodeConfigValue.Absent(name);
        }
        if (frame instanceof ConfigError error && error.name().equals(wire)) {
            if (error.exceptionChain().equals(MISSING_PROPERTY_CHAIN)
                && error.message().contains(MISSING_PROPERTY_MESSAGE)) {
                return new NodeConfigValue.Absent(name);
            }
            ErrorCode code = failures.callFailure(error.exceptionChain(), error.message())
                .map(ToolError::code).orElse(ErrorCode.UNEXPECTED_RESPONSE);
            return new NodeConfigValue.Failed(name, code,
                failures.details(request, error.exceptionChain(), error.message()));
        }
        throw unexpectedConfigFrames();
    }

    private ToolErrorException unexpectedConfigFrames() {
        // the frame's name is not repeated: it is not known to be safe to show
        return unexpected("The INUBIT client helper did not answer each requested configuration"
            + " name once, in request order");
    }

    /**
     * The I/O log listing of a listing-only {@code ioLog} run that answered for
     * {@code inubitNode} (research D-8, D-20): the only frame is {@code io list ok <count>} and
     * {@code listOut} holds {@code count} entries, one per line, UTF-8, at most
     * {@value IoLogBounds#MAX_LIST_BYTES} bytes, each an entry of the requested process
     * ({@link IoLogEntry#parse}).
     *
     * @throws ToolErrorException {@code FORBIDDEN} for an {@code io list error} with INUBIT's
     *     {@code NotAllowedAccess}; {@link HelperFailures#callFailure}'s code, else
     *     {@code UNEXPECTED_RESPONSE} with a scrubbed excerpt, for another {@code io list error};
     *     {@code UNEXPECTED_RESPONSE} for any other frame, order or count, a listing that is
     *     missing, too large or not UTF-8, and an entry that breaks the rules, without quoting it
     */
    public IoLogListing ioListing(HelperRequest request, String inubitNode, List<Frame> frames) {
        IoLogListing listing = listing(request, inubitNode, frames);
        if (frames.size() != 1) {
            throw unexpected("The INUBIT client helper wrote frames after the listing that were"
                + " not asked for");
        }
        return listing;
    }

    /**
     * The read of an {@code ioLog} run with {@code ref} that answered for {@code inubitNode}
     * (research D-8, D-9, D-20): the listing as {@link #ioListing}, then {@code io match none} if
     * no listed entry has the reference ({@link IoLogRead.NoMatch}), else {@code io match <the
     * listed entry with the reference>} and {@code part message ok <bytes>} with
     * {@code messageOut} a regular file of that size ({@link IoLogRead.Message}, whose close runs
     * {@code deletePrivateDirectory}).
     *
     * @throws ToolErrorException the listing's failures; {@link HelperFailures#callFailure}'s
     *     code, else {@code UNEXPECTED_RESPONSE} with a scrubbed excerpt, for
     *     {@code part message error}; {@code UNEXPECTED_RESPONSE} for a match that disagrees with
     *     the listing, any other frame or order, and a missing message file or one of another
     *     size
     */
    public IoLogRead ioRead(HelperRequest request, String inubitNode, List<Frame> frames,
        Runnable deletePrivateDirectory) {
        HelperRequest.IoLog log = (HelperRequest.IoLog) request.operation();
        IoMessageRef ref = log.ref().orElseThrow();
        IoLogListing listing = listing(request, inubitNode, frames);
        Optional<IoLogEntry> listed = listing.entries().stream()
            .filter(entry -> entry.ref().equals(ref)).findFirst();
        if (frames.size() < 2 || !(frames.get(1) instanceof IoMatch match)
            || !match.entry().equals(listed.map(IoLogEntry::entry))) {
            throw unexpected("The match of the INUBIT client helper does not agree with the"
                + " listing");
        }
        if (listed.isEmpty() && frames.size() == 2) {
            return new IoLogRead.NoMatch(listing);
        }
        if (frames.size() == 3 && frames.get(2) instanceof PartError error
            && error.part() == Part.MESSAGE) {
            throw new ToolErrorException(callError(request, error.exceptionChain(),
                error.message(), "INUBIT reported an error when asked for the message"));
        }
        if (listed.isEmpty() || frames.size() != 3 || !(frames.get(2) instanceof PartOk ok)
            || ok.part() != Part.MESSAGE) {
            throw unexpected("The INUBIT client helper did not end the read with one part"
                + " message frame");
        }
        Path file = log.messageOut().orElseThrow();
        if (!regularFile(file, ok.bytes())) {
            throw unexpected("The INUBIT client helper reported " + ok.bytes() + " bytes, but"
                + " did not leave a regular file of that length");
        }
        return new IoLogRead.Message(listing, listed.get(), file, ok.bytes(),
            deletePrivateDirectory);
    }

    /** The listing of the first frame and {@code listOut}; see {@link #ioListing}. */
    private IoLogListing listing(HelperRequest request, String inubitNode, List<Frame> frames) {
        HelperRequest.IoLog log = (HelperRequest.IoLog) request.operation();
        Frame first = frames.isEmpty() ? null : frames.get(0);
        if (first instanceof IoListError error && frames.size() == 1) {
            throw new ToolErrorException(NOT_ALLOWED_ACCESS.matcher(error.message()).find()
                ? failures.forbidden()
                : callError(request, error.exceptionChain(), error.message(),
                    "INUBIT reported an error when asked for the input/output logging"));
        }
        if (!(first instanceof IoListOk ok)) {
            throw unexpected("The INUBIT client helper did not answer with io list ok or one"
                + " io list error");
        }
        List<IoLogEntry> entries = new ArrayList<>();
        for (String line : listingText(log.listOut()).lines().toList()) {
            try {
                entries.add(IoLogEntry.parse(log.processId(), line));
            } catch (IllegalArgumentException e) {
                // the entry is not repeated: it is not known to be safe to show
                throw unexpected("The INUBIT client helper listed an entry that is not an"
                    + " input/output logging entry of the requested process");
            }
        }
        if (entries.size() != ok.count()) {
            throw unexpected("The INUBIT client helper listed " + entries.size() + " entries,"
                + " but reported " + ok.count());
        }
        return new IoLogListing(inubitNode, entries);
    }

    /**
     * The listing file as UTF-8 text: a regular file (not a link) of at most
     * {@value IoLogBounds#MAX_LIST_BYTES} bytes.
     */
    private String listingText(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw unexpected("The INUBIT client helper did not leave the listing as a"
                    + " regular file");
            }
            if (attributes.size() > IoLogBounds.MAX_LIST_BYTES) {
                throw unexpected("The INUBIT client helper wrote a listing of more than "
                    + IoLogBounds.MAX_LIST_BYTES + " bytes");
            }
            return Files.readString(file);
        } catch (IOException e) {
            throw unexpected("The listing of the INUBIT client helper is missing or not UTF-8 ("
                + e.getClass().getSimpleName() + ")");
        }
    }

    /** Whether {@code file} is a regular file (not a link) of {@code size} bytes. */
    private static boolean regularFile(Path file, long size) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && attributes.size() == size;
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code INVALID_INPUT}: {@code node} is not a member; names the first members. */
    private ToolError unknownNode(String node, List<ClusterNode> nodes, int total) {
        List<String> names = nodes.stream().limit(MAX_NAMED_MEMBERS).map(ClusterNode::name)
            .toList();
        String known = names.isEmpty() ? "none" : String.join(", ", names);
        if (total > names.size()) {
            known += " and " + (total - names.size()) + " more";
        }
        return ToolError.of(ErrorCode.INVALID_INPUT, "Node " + node + " is not in the cluster of "
                + labels.node() + "; known nodes: " + known,
            "The INUBIT server knows no cluster member of that name", "Use a name from the list")
            .withNode(labels.node());
    }

    /** {@link HelperFailures#callFailure}'s code, else {@code UNEXPECTED_RESPONSE}. */
    private ToolError callError(HelperRequest request, List<String> chain, String message,
        String likelyCause) {
        return failures.callFailure(chain, message)
            .orElseGet(() -> failures.unexpected(likelyCause)
                .withExcerpt(failures.details(request, chain, message)));
    }

    private ToolErrorException unexpected(String likelyCause) {
        return new ToolErrorException(failures.unexpected(likelyCause));
    }

    /** A frame text, absent when the helper wrote {@code -}. */
    private static Optional<String> optional(String text) {
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}
