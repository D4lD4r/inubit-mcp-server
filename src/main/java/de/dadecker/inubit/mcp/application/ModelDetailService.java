package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ModelDetailPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The reads of {@code read_model_details} (feature 022, US3, research D-21): one module of a
 * diagram and the uploaded libraries of one server. {@code metadata} and {@code checkIsConnector}
 * are not built in US3 (owner decision 2026-11-14).
 *
 * <ul>
 *   <li>Each action is admitted by {@link VersionGate#admitCall} with the entries of its
 *       {@link ActionSpec} ({@link #SPECS}) after the inputs are checked and before anything is
 *       sent.
 *   <li>{@code module}: {@link ModelDetailPort#module} over REST, its secrets masked by the
 *       adapter (D-19); an unknown module id is the port's {@code NOT_FOUND} (AS2).
 *   <li>{@code libraries}: one StartCLI run per type, {@code driver} before {@code plugin} when
 *       no type is given, each listing in the answer's order; the joined list is paged
 *       ({@code offset} 0–{@value #MAX_OFFSET}, {@code limit} 1–{@value #MAX_LIMIT}, default
 *       {@value #DEFAULT_LIMIT}). The row format counts as verified only when every listing read
 *       says so (L-21). A failed run fails the call (AS3).
 * </ul>
 */
public final class ModelDetailService {

    public static final String TOOL = "read_model_details";

    public static final int MAX_OFFSET = 9999;
    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_LIMIT = 100;

    /** The library types in the order they are listed without {@code libraryType}. */
    private static final List<String> TYPES = List.of("driver", "plugin");
    /** The name rule of data-model.md "Validation rules" (FR-020). */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");
    /** The {@code id} of {@code ns4:Node} (T039). */
    private static final Pattern MODULE_ID = Pattern.compile("^[0-9]{1,10}$");

    private static final CoverageKey NODE_BY_MODEL =
        new CoverageKey(Channel.REST, "GET /model/nodeByModel/{model}/{node}");
    private static final CoverageKey LIBRARY_LIST = new CoverageKey(Channel.CLI, "library --list");
    private static final CoverageKey LIBRARY_TYPE = new CoverageKey(Channel.CLI, "library --type");

    public static final ActionSpec MODULE = spec("module", Set.of(NODE_BY_MODEL));
    public static final ActionSpec LIBRARIES = spec("libraries",
        Set.of(LIBRARY_LIST, LIBRARY_TYPE));

    /** The actions of the tool (research D-2 action key {@code action}). */
    public static final List<ActionSpec> SPECS = List.of(MODULE, LIBRARIES);

    /**
     * What one action read on one server.
     *
     * @param source   the channel the value came from (FR-009)
     * @param warnings the version gate's warnings and notes for the server
     */
    public record Read<T>(NodeId node, T value, Channel source, List<String> warnings) {

        public Read {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * A page of libraries.
     *
     * @param rowFormatVerified false while each line is read as one file name (L-21)
     */
    public record Libraries(Page<LibraryEntry> page, boolean rowFormatVerified) {

        public Libraries {
            Objects.requireNonNull(page, "page");
        }
    }

    private final TargetResolver targets;
    private final VersionGate gate;
    private final Function<NodeId, ModelDetailPort> ports;

    /** @param ports the model-detail port of a server */
    public ModelDetailService(TargetResolver targets, VersionGate gate,
        Function<NodeId, ModelDetailPort> ports) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.ports = Objects.requireNonNull(ports, "ports");
    }

    /**
     * The module {@code moduleId} of {@code diagram} on {@code target} (AS2).
     *
     * @throws ToolErrorException {@code INVALID_INPUT} (diagram, module id, a stage or unknown
     *     target syntax), {@code TARGET_UNKNOWN}, the gate's refusal, or the port's error, e.g.
     *     {@code NOT_FOUND} for an unknown module id
     */
    public Read<ModuleDetail> module(String target, String diagram, String moduleId) {
        if (!NAME.matcher(diagram).matches()) {
            throw invalid("Invalid diagram name", "It must match " + NAME.pattern(),
                "Give the exact name of the diagram (workflow) that holds the module");
        }
        if (!MODULE_ID.matcher(moduleId).matches()) {
            throw invalid("Invalid moduleId", "A module id has 1 to 10 digits",
                "Give the numeric id of the module in its diagram");
        }
        NodeId node = targets.resolveSingleServer(target);
        List<String> warnings = admit(node, MODULE);
        return new Read<>(node, ports.apply(node).module(diagram, moduleId), Channel.REST,
            warnings);
    }

    /**
     * A page of the libraries of {@code target}: of {@code type}, or of both types (AS3).
     *
     * @throws ToolErrorException {@code INVALID_INPUT} (type, offset, limit, a stage or unknown
     *     target syntax), {@code TARGET_UNKNOWN}, the gate's refusal, or the error of a run
     */
    public Read<Libraries> libraries(String target, Optional<String> type, int offset,
        OptionalInt limit) {
        if (type.filter(name -> !TYPES.contains(name)).isPresent()) {
            throw invalid("Invalid libraryType", "A library type is driver or plugin",
                "Use driver or plugin, or leave libraryType out for both");
        }
        int size = checkPage(offset, limit);
        NodeId node = targets.resolveSingleServer(target);
        List<String> warnings = admit(node, LIBRARIES);
        ModelDetailPort port = ports.apply(node);
        List<LibraryEntry> entries = new ArrayList<>();
        boolean verified = true;
        for (String each : type.map(List::of).orElse(TYPES)) {
            LibraryEntry.Listing listing = port.libraries(each);
            entries.addAll(listing.entries());
            verified &= listing.rowFormatVerified();
        }
        return new Read<>(node, new Libraries(page(entries, offset, size), verified),
            Channel.CLI, warnings);
    }

    private List<String> admit(NodeId node, ActionSpec spec) {
        VersionGate.Admission admission = gate.admitCall(TOOL, node, spec, spec.callEntries());
        List<String> warnings = new ArrayList<>(admission.warnings());
        warnings.addAll(admission.notes());
        return warnings;
    }

    /** The page size; {@code INVALID_INPUT} for an offset or limit outside its range. */
    private static int checkPage(int offset, OptionalInt limit) {
        if (offset < 0 || offset > MAX_OFFSET) {
            throw invalid("Invalid offset " + offset + ": expected 0–" + MAX_OFFSET,
                "offset is the position of the first entry of the page",
                "Use an offset from 0 to " + MAX_OFFSET + ", such as the nextOffset of the last"
                    + " page");
        }
        int size = limit.orElse(DEFAULT_LIMIT);
        if (size < 1 || size > MAX_LIMIT) {
            throw invalid("Invalid limit " + size + ": expected 1–" + MAX_LIMIT,
                "limit is the number of entries of one page",
                "Use a limit from 1 to " + MAX_LIMIT + ", or leave it out for " + DEFAULT_LIMIT);
        }
        return size;
    }

    private static <T> Page<T> page(List<T> all, int offset, int limit) {
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        return new Page<>(all.subList(from, to), offset, limit, OptionalLong.of(all.size()),
            false, false, to < all.size() ? OptionalInt.of(to) : OptionalInt.empty());
    }

    private static ToolErrorException invalid(String message, String cause, String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message, cause,
            nextStep));
    }

    private static ActionSpec spec(String action, Set<CoverageKey> entries) {
        return new ActionSpec(TOOL, action, Capability.MODEL_DETAILS, ActionSpec.Area.READ,
            ActionSpec.Kind.READ, entries, input -> entries, Set.of(), false, Optional.empty(),
            ActionSpec.RestartNotice.NONE);
    }
}
