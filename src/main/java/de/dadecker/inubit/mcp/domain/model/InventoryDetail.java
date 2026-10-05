package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The details of one diagram or module ({@code get_inventory_item}, data-model.md →
 * InventoryDetail). Every server's detail has this structure, so that the assistant can compare
 * servers field by field (FR-017).
 *
 * <ul>
 *   <li>Diagrams: {@code type}/{@code group} from the diagram list, {@code active},
 *       {@code checkinComment} and {@code owner} from the REST export ({@code workflow.xml}),
 *       {@code modules} from {@code modelByName}, {@code versions} from the CLI export with
 *       history of the diagram's group; {@code lastChange} is the check-in time of the newest
 *       version.
 *   <li>Modules: the module index entry ({@code type} = plugin type, {@code group} = module
 *       group, {@code active}, {@code lastChange} = {@code LastUpdate}, comments,
 *       {@code connector}), its usage ({@code workflows}: all technical workflows that use it,
 *       sorted by name; {@code workflowCount}; {@code usageComplete}: whether the server's usage
 *       index could be built completely, T126) and the versions from the history export of the
 *       group of the first using workflow that is in the diagram list.
 *   <li>Not found: only {@code server}, {@code kind}, {@code name}, {@code owner} and up to five
 *       {@code similarNames} (Story 3 / AS 5).
 *   <li>There is no "active version": INUBIT 8.1 does not expose one (FR-016).
 * </ul>
 *
 * @param versions    newest first; absent if unavailable (then {@code unavailable} says why)
 * @param unavailable parts that could not be obtained, e.g. the version history
 * @param truncated   true if text or list entries were cut to keep the result bounded
 */
public record InventoryDetail(
    NodeId node,
    InventoryKind kind,
    String name,
    Optional<String> type,
    Optional<String> group,
    String owner,
    Optional<Boolean> active,
    Optional<Instant> lastChange,
    Optional<List<String>> workflows,
    Optional<Integer> workflowCount,
    Optional<Boolean> usageComplete,
    Optional<String> checkinComment,
    Optional<String> userComment,
    Optional<List<VersionEntry>> versions,
    Optional<List<ModuleRef>> modules,
    Optional<ConnectorFlags> connector,
    Optional<List<String>> similarNames,
    List<UnavailableInventoryPart> unavailable,
    boolean truncated) {

    /** Upper bound of {@code similarNames}. */
    public static final int MAX_SIMILAR_NAMES = 5;

    public InventoryDetail {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(owner, "owner");
        type = type == null ? Optional.empty() : type;
        group = group == null ? Optional.empty() : group;
        active = active == null ? Optional.empty() : active;
        lastChange = lastChange == null ? Optional.empty() : lastChange;
        workflows = workflows == null ? Optional.empty() : workflows.map(List::copyOf);
        workflowCount = workflowCount == null ? Optional.empty() : workflowCount;
        usageComplete = usageComplete == null ? Optional.empty() : usageComplete;
        checkinComment = checkinComment == null ? Optional.empty() : checkinComment;
        userComment = userComment == null ? Optional.empty() : userComment;
        versions = versions == null ? Optional.empty() : versions.map(List::copyOf);
        modules = modules == null ? Optional.empty() : modules.map(List::copyOf);
        connector = connector == null ? Optional.empty() : connector;
        similarNames = similarNames == null ? Optional.empty() : similarNames.map(List::copyOf);
        similarNames.ifPresent(names -> {
            if (names.size() > MAX_SIMILAR_NAMES) {
                throw new IllegalArgumentException("at most " + MAX_SIMILAR_NAMES
                    + " similar names, got " + names.size());
            }
        });
        unavailable = List.copyOf(unavailable);
    }

    /** The item of a {@code NOT_FOUND} result: the requested name plus similar names. */
    public static InventoryDetail notFound(NodeId node, InventoryKind kind, String name,
        String owner, List<String> similarNames) {
        return new InventoryDetail(node, kind, name, Optional.empty(), Optional.empty(), owner,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(similarNames), List.of(), false);
    }
}
