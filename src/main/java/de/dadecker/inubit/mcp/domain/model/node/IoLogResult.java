package de.dadecker.inubit.mcp.domain.model.node;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.List;
import java.util.Objects;

/**
 * The result of {@code read_io_log} (feature 024, research D-8; shape per
 * contracts/mcp-tools-delta.md): the server asked, the INUBIT node that answered, the run, and its
 * listed entries after the {@code module} filter, plus hints and warnings.
 *
 * @param node          the server the call addressed
 * @param answeringNode the INUBIT node that answered ({@code inubitNode})
 * @param workflow      the process name of the request
 * @param processId     the process ID of the request
 * @param total         all entries of the run
 * @param matching      the entries left after the {@code module} filter
 * @param entries       the first {@value IoLogBounds#MAX_LISTED} matching entries, server order
 * @param truncated     whether more entries matched than are listed
 * @param hints         {@link #EMPTY_LISTING_HINT} and {@link #RAW_MODULE_HINT} where they apply
 * @param warnings      {@link #WARNING} first, then the version gate's warnings
 */
public record IoLogResult(NodeId node, String answeringNode, String workflow, String processId,
    int total, int matching, List<IoLogEntry> entries, boolean truncated, List<String> hints,
    List<String> warnings) {

    /** The warning of every result. */
    public static final String WARNING = ClusterNodesResult.WARNING;
    /** The hint of a run without any logged message (US2-4). */
    public static final String EMPTY_LISTING_HINT = "No logged message for this run:"
        + " input/output logging may be off for the process or its modules, the messages may"
        + " already be removed, or the run may have executed on another cluster node";
    /** The hint of listed entries whose module part is not {@code <name>(<id>)} (D-8). */
    public static final String RAW_MODULE_HINT =
        "Some entries have an unexpected form; they are listed with their raw module part";

    public IoLogResult {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(answeringNode, "answeringNode");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(processId, "processId");
        entries = List.copyOf(entries);
        hints = List.copyOf(hints);
        warnings = List.copyOf(warnings);
    }
}
