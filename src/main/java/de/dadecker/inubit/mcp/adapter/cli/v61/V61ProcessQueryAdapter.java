package de.dadecker.inubit.mcp.adapter.cli.v61;

import de.dadecker.inubit.mcp.adapter.RefusingPorts;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand.Listing;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The process query port of an INUBIT 6.1 node (feature 011, FR-010, research D-10): one StartCLI
 * session through {@link V61Connection} per {@link #find}, with the {@code ps} listings read by
 * {@link V61PsParser}; every filter, the sort and the paging are applied here.
 *
 * <ul>
 *   <li>Listings: no state, or a state without a listing of its own ({@code ACTIVE}), → the full
 *       listing {@code ps -a}; otherwise one {@code ps -e} / {@code ps -w} / {@code ps -q} per
 *       state in state order; each asks for {@link CliSessionCommand#MAX_ROWS} rows.
 *       {@code hangingOnly} does not change the listings. Whether {@code ps -w} also lists
 *       {@code Retry} instances (state {@code WAITING}) is open until recorded (REC, research
 *       D-10); the full listing does.
 *   <li>Filters on the parsed rows: the requested states, {@code workflow} equal, {@code since}
 *       inclusive, {@link ProcessQuery#effectiveUntil()} inclusive (for {@code hangingOnly} the
 *       last instant before {@code now - threshold}, as on 8.1) and, for {@code hangingOnly}, a
 *       non-final state.
 *   <li>Sorted by {@code since}, newest first; the page is the slice {@code offset … offset +
 *       limit} of the matches, whose number is the total.
 *   <li>The session keeps up to {@link #LISTING_OUTPUT_BYTES} of output per listing (one
 *       {@link CliSessionCommand#MAX_ROWS}-row listing with modules is about 1.3 MB); a session
 *       above that is cut and answered with {@code UNEXPECTED_RESPONSE}.
 *   <li>A listing that printed {@link CliSessionCommand#MAX_ROWS} rows, or fewer rows than its
 *       total line, hit the cap: the page is {@code truncated} and its total a lower bound, also
 *       after filtering and merging.
 *   <li>Size bounds as on 8.1: every text of a returned item except {@code processId} (digits) is
 *       cut to {@link ItemBounds#MAX_FIELD_CHARS}; an item still above
 *       {@link ItemBounds#MAX_ITEM_CHARS} (JSON escapes) loses its optional texts. A cut item
 *       marks the page {@code truncated}.
 * </ul>
 *
 * <p>{@code tag} is refused with {@code UNSUPPORTED_VERSION} by {@link #validate} and again by
 * {@link #find} (the listing has no tag column); {@link #findByProcessId} is refused. Session
 * failures are classified as in {@link V61HealthAdapter} and carry the node.
 */
public final class V61ProcessQueryAdapter implements ProcessQueryPort {

    /** The output bound per listing of a session (research D-10). */
    static final int LISTING_OUTPUT_BYTES = 4 << 20;

    /** The states that have a listing of their own. */
    private static final Map<ProcessState, Listing> OWN_LISTING = Map.of(
        ProcessState.ERROR, Listing.ERROR,
        ProcessState.WAITING, Listing.WAITING,
        ProcessState.QUEUED, Listing.QUEUED);

    private final NodeId node;
    private final V61Connection connection;
    private final CliOutputClassifier classifier;

    public V61ProcessQueryAdapter(NodeId node, V61Connection connection,
        CliOutputClassifier classifier) {
        this.node = Objects.requireNonNull(node, "node");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /**
     * Without contact: {@code INVALID_INPUT} (without node) for {@code offset + limit} above
     * {@link CliSessionCommand#MAX_ROWS}, {@code UNSUPPORTED_VERSION} (with node) for {@code tag}.
     */
    @Override
    public void validate(ProcessQuery query) {
        if ((long) query.offset() + query.limit() > CliSessionCommand.MAX_ROWS) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "offset + limit must be at most " + CliSessionCommand.MAX_ROWS + ", got "
                    + query.offset() + " + " + query.limit(),
                "Paging deeper than " + CliSessionCommand.MAX_ROWS + " entries is not supported",
                "Narrow the query with states, workflow or since/until instead of paging"
                    + " further"));
        }
        if (query.tag().isPresent()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
                "find_processes: INUBIT 6.1 on " + node + " does not support the input tag;"
                    + " nothing was sent",
                "The process listing of StartCLI 6.1 has no tag column",
                "Omit tag for this node, or filter by workflow").withNode(node));
        }
    }

    @Override
    public Page<ProcessInstance> find(ProcessQuery query) {
        validate(query);
        List<CliSessionCommand> commands = commands(query.states());
        CliResult result = connection.session(commands, commands.size() * LISTING_OUTPUT_BYTES);
        if (result.exitCode() != 0 || result.truncated()
            || CliOutputClassifier.isLoginFailure(result)) {
            // the session prints no n-OK message, so the classifier never calls this a success
            if (classifier.classify(result) instanceof CliOutcome.Failure failure) {
                throw failed(failure.error());
            }
        }
        List<V61PsParser.Listing> listings;
        try {
            listings = V61PsParser.parse(result.stdout(), commands, node, query.now(),
                query.hangingThreshold());
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
        boolean capped = listings.stream().anyMatch(listing ->
            listing.instances().size() >= CliSessionCommand.MAX_ROWS
                || listing.total() > listing.instances().size());
        List<ProcessInstance> matches = listings.stream()
            .flatMap(listing -> listing.instances().stream())
            .filter(instance -> matches(query, instance))
            .sorted(Comparator.comparing(ProcessInstance::since).reversed())
            .toList();
        List<ProcessInstance> slice = matches.subList(Math.min(query.offset(), matches.size()),
            Math.min(query.offset() + query.limit(), matches.size()));
        List<ProcessInstance> items = slice.stream().map(V61ProcessQueryAdapter::bounded)
            .toList();
        int next = query.offset() + query.limit();
        return new Page<>(items, query.offset(), query.limit(),
            OptionalLong.of(matches.size()), capped, capped || !items.equals(slice),
            next < matches.size() ? OptionalInt.of(next) : OptionalInt.empty());
    }

    /** INUBIT 6.1 has no lookup of one process id (restart/kill are not offered). */
    @Override
    public ProcessRows findByProcessId(String processId, Instant now,
        Duration hangingThreshold) {
        throw new RefusingPorts(node).refusal();
    }

    /** The listings of {@code states} (empty = all states). */
    private static List<CliSessionCommand> commands(Set<ProcessState> states) {
        if (states.isEmpty() || !OWN_LISTING.keySet().containsAll(states)) {
            return List.of(CliSessionCommand.psList(Listing.ALL, CliSessionCommand.MAX_ROWS));
        }
        return states.stream().sorted()
            .map(state -> CliSessionCommand.psList(OWN_LISTING.get(state),
                CliSessionCommand.MAX_ROWS))
            .toList();
    }

    private static boolean matches(ProcessQuery query, ProcessInstance instance) {
        return (query.states().isEmpty() || query.states().contains(instance.state()))
            && (!query.hangingOnly() || instance.state().nonFinal())
            && (query.workflow().isEmpty() || query.workflow().equals(instance.workflow()))
            && query.since().map(since -> !instance.since().isBefore(since)).orElse(true)
            && query.effectiveUntil().map(until -> !instance.since().isAfter(until))
                .orElse(true);
    }

    /** {@code instance} within the size bounds of the class comment (equal if nothing is cut). */
    static ProcessInstance bounded(ProcessInstance instance) {
        ProcessInstance cut = withTexts(instance, cut(instance.rawState()),
            instance.workflow().map(V61ProcessQueryAdapter::cut),
            instance.module().map(V61ProcessQueryAdapter::cut),
            instance.inubitNode().map(V61ProcessQueryAdapter::cut),
            instance.owner().map(V61ProcessQueryAdapter::cut),
            instance.priority().map(V61ProcessQueryAdapter::cut));
        if (ResultJson.size(cut) <= ItemBounds.MAX_ITEM_CHARS) {
            return cut;
        }
        return withTexts(instance, cut.rawState(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ProcessInstance withTexts(ProcessInstance instance, String rawState,
        Optional<String> workflow, Optional<String> module, Optional<String> inubitNode,
        Optional<String> owner, Optional<String> priority) {
        return new ProcessInstance(instance.node(), instance.processId(),
            instance.globalProcessId(), instance.state(), rawState, instance.since(),
            instance.timeInState(), instance.hanging(), workflow, module, instance.moduleType(),
            instance.tag(), inubitNode, owner, priority);
    }

    /**
     * {@code text} cut to {@link ItemBounds#MAX_FIELD_CHARS}, ending with the truncation marker
     * when cut (a surrogate pair is never split).
     */
    private static String cut(String text) {
        if (text.length() <= ItemBounds.MAX_FIELD_CHARS) {
            return text;
        }
        int end = ItemBounds.MAX_FIELD_CHARS - ItemBounds.TRUNCATION_MARKER.length();
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + ItemBounds.TRUNCATION_MARKER;
    }

    private ToolErrorException failed(ToolError error) {
        return new ToolErrorException(error.node().isPresent() ? error : error.withNode(node));
    }

    @Override
    public String toString() {
        return "V61ProcessQueryAdapter[" + node + "]";
    }
}
