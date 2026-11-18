package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.CliCommand.TagRemovalKind;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand.TagScope;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliTagAdminRunner;
import de.dadecker.inubit.mcp.adapter.cli.v81.VersionHistoryParser;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.TaggedVersion;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The 8.1 {@link TagAdministrationPort} (feature 022, US4, research D-18); 9.0 uses it too.
 *
 * <ul>
 *   <li>the active tag and the tag forms run on the node's {@link CliTagAdminRunner};
 *   <li>the versions carrying a tag in a diagram group come from the group's history export
 *       ({@link CliExportRunner#exportHistory}, parsed by
 *       {@link VersionHistoryParser#parseHistory} as for {@code tag_artifacts});
 *   <li>the workflows and modules carrying it outside the group are the tagged versions of the
 *       owner's export by the tag ({@link CliExportRunner#exportRelease}, normalized by the
 *       {@link ReleaseArchivePort} as for a deployment, feature 005 D-4) whose name is not in the
 *       group; a tag on no diagram group of the owner ({@code NOT_FOUND}) leaves none outside.
 *       That export reads only technical diagram groups, so a group of another type is refused.
 * </ul>
 */
final class V81TagAdministrationAdapter implements TagAdministrationPort {

    /** The only diagram type the owner's export by the tag reads ({@code exportRelease}). */
    private static final String TECHNICAL = "technical";

    private final NodeId server;
    private final CliTagAdminRunner tags;
    private final CliExportRunner exports;
    private final ReleaseArchivePort releases;

    V81TagAdministrationAdapter(NodeId server, CliTagAdminRunner tags, CliExportRunner exports,
        ReleaseArchivePort releases) {
        this.server = Objects.requireNonNull(server, "server");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.exports = Objects.requireNonNull(exports, "exports");
        this.releases = Objects.requireNonNull(releases, "releases");
    }

    @Override
    public Optional<String> activeTag(String user) {
        return tags.activeTag(user);
    }

    @Override
    public void setActiveTag(String user, String tag, WritePermit permit) {
        tags.setActiveTag(user, tag, permit);
    }

    @Override
    public void removeActiveTag(String user, WritePermit permit) {
        tags.removeActiveTag(user, permit);
    }

    @Override
    public TaggedObjects tagged(String tag, String owner, Scope scope) {
        if (!(scope instanceof Scope.DiagramGroup group)) {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
                "The versions carrying a tag in a repository path cannot be read on " + server
                    + "; nothing was sent",
                "No StartCLI form that lists the tagged repository files with their versions is"
                    + " recorded yet (candidate: export --exportRepositoryPath with --exportTag,"
                    + " live check T055)",
                "Name a diagram group instead, or check the repository path in the Workbench")
                .withNode(server));
        }
        if (!group.type().equals(TECHNICAL)) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The versions carrying a tag can be read only in a technical diagram group, not in"
                    + " one of type '" + group.type() + "' on " + server + "; nothing was sent",
                "The owner's export by the tag reads only technical diagram groups, as"
                    + " tag_artifacts and the release export do",
                "Name a technical diagram group").withNode(server));
        }
        TagPort.History history = VersionHistoryParser.parseHistory(server,
            exports.exportHistory(owner, group.type(), group.group()));
        List<TaggedVersion> inScope = new ArrayList<>();
        history.diagrams().forEach((name, diagram) ->
            add(inScope, TaggedVersion.Kind.WORKFLOW, name, diagram.versions(), tag));
        history.modules().forEach((name, versions) ->
            add(inScope, TaggedVersion.Kind.MODULE, name, versions, tag));
        inScope.sort(Comparator.comparing(TaggedVersion::kind).thenComparing(TaggedVersion::name)
            .thenComparingInt(TaggedVersion::version));
        Set<String> names = inScope.stream().map(TaggedVersion::name)
            .collect(Collectors.toSet());
        return new TaggedObjects(inScope, ownerWide(owner, tag).keySet().stream()
            .filter(name -> !names.contains(name)).count());
    }

    private static void add(List<TaggedVersion> tagged, TaggedVersion.Kind kind, String name,
        List<VersionEntry> versions, String tag) {
        versions.stream().filter(version -> version.tags().contains(tag))
            .forEach(version -> tagged.add(new TaggedVersion(kind, name, version.version())));
    }

    /** Workflow or module name → its tagged version in the owner's export by {@code tag}. */
    private Map<String, Integer> ownerWide(String owner, String tag) {
        try {
            return releases.normalize(exports.exportRelease(owner, tag)).taggedVersions();
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.NOT_FOUND) {
                return Map.of();
            }
            throw e;
        }
    }

    @Override
    public void removeTag(Removal removal, String tag, Scope scope, WritePermit permit) {
        tags.removeTag(switch (removal) {
            case REMOVE -> TagRemovalKind.REMOVE;
            case DELETE -> TagRemovalKind.DELETE;
        }, tag, switch (scope) {
            case Scope.DiagramGroup group -> new TagScope.DiagramGroup(group.group(),
                group.type());
            case Scope.RepositoryPath path -> new TagScope.RepositoryPath(path.path());
        }, permit);
    }

    @Override
    public String toString() {
        return "V81TagAdministrationAdapter[" + tags + "]";
    }
}
