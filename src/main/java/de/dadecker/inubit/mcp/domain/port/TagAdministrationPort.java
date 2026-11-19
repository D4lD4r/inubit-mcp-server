package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.TaggedVersion;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The tags of one INUBIT server for {@code manage_tags} (feature 022, US4, research D-18), over
 * StartCLI: the active tag of a user, and the removal or deletion of a tag in one diagram group or
 * repository path. Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id.
 *
 * <p>Every change takes the {@link WritePermit} of the call's admission for this port's node and
 * is handed off under the node's {@link WriteLock}; after a version change since the admission it
 * is refused with {@code UNSUPPORTED_VERSION} and nothing is sent. A permit for another node is an
 * {@link IllegalArgumentException}; nothing is sent. No answer of these forms is recorded yet
 * (live check T055, L-4): the active tag is read in a RECONSTRUCTED form, and a change counts as
 * done when StartCLI exits with 0 and no {@code n-NOK} message, as for {@code tag --tagMove}.
 */
public interface TagAdministrationPort {

    /** The scope of a tag removal; without one StartCLI acts on the whole owner (D-18). */
    sealed interface Scope {

        /** One diagram group of one diagram type. */
        record DiagramGroup(String group, String type) implements Scope {
            public DiagramGroup {
                Objects.requireNonNull(group, "group");
                Objects.requireNonNull(type, "type");
            }
        }

        /** One repository path. */
        record RepositoryPath(String path) implements Scope {
            public RepositoryPath {
                Objects.requireNonNull(path, "path");
            }
        }
    }

    /** The two removals of StartCLI's {@code tag} command. */
    enum Removal {
        /** {@code --tagRemove}. */
        REMOVE,
        /** {@code --tagDelete}. */
        DELETE
    }

    /**
     * The versions carrying a tag in one scope and the number of objects outside it that carry
     * the tag too.
     *
     * @param inScope           sorted by kind, name and version
     * @param outsideScopeCount the workflows and modules of the owner's export by the tag that
     *                          are not in {@code inScope}
     */
    record TaggedObjects(List<TaggedVersion> inScope, long outsideScopeCount) {
        public TaggedObjects {
            inScope = List.copyOf(inScope);
            if (outsideScopeCount < 0) {
                throw new IllegalArgumentException("outsideScopeCount " + outsideScopeCount);
            }
        }
    }

    /**
     * The active tag of {@code user}, read with StartCLI {@code user '<user>'}; empty if the
     * answer names none.
     */
    Optional<String> activeTag(String user);

    /** Sets the active tag of {@code user} ({@code tag --tagSetActive --tagUser}). */
    void setActiveTag(String user, String tag, WritePermit permit);

    /** Removes the active tag of {@code user} ({@code tag --tagRemoveActive --tagUser}). */
    void removeActiveTag(String user, WritePermit permit);

    /**
     * The workflow and module versions carrying {@code tag} in {@code scope}, from the history
     * export of the diagram group of {@code owner} as {@code tag_artifacts} reads it, and the
     * number of the workflows and modules outside it carrying {@code tag}, from the owner's export
     * by the tag (no history export: that would append to the check-in comment of every workflow
     * of the owner, feature 004 D-26).
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException before anything is
     *     launched: {@code INVALID_INPUT} for a {@link Scope.DiagramGroup} of a type other than
     *     {@code technical}, because the owner's export by the tag reads only technical diagram
     *     groups, as {@code tag_artifacts} and the release export do; {@code UNSUPPORTED_VERSION}
     *     for a {@link Scope.RepositoryPath}, because no StartCLI form that lists the tagged
     *     repository files with their versions is recorded yet (candidate
     *     {@code export --exportRepositoryPath <path> --exportTag <tag>} with the metadata
     *     {@code tagName} and {@code version}, live check T055)
     */
    TaggedObjects tagged(String tag, String owner, Scope scope);

    /**
     * Removes or deletes {@code tag} in {@code scope} ({@code tag --tagRemove|--tagDelete} with
     * {@code --tagWorkflowGroup}/{@code --tagWorkflowType}/{@code --tagUser '<owner>'} or
     * {@code --tagRepositoryPath}). {@code owner} owns a {@link Scope.DiagramGroup} (8.1.17
     * refuses {@code --tagDelete} there without it); it is not sent with a
     * {@link Scope.RepositoryPath}. {@link Removal#REMOVE} takes only a repository path (8.1.17,
     * T055).
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT} for
     *     {@link Removal#REMOVE} in a diagram group, before anything is launched
     */
    void removeTag(Removal removal, String tag, String owner, Scope scope, WritePermit permit);
}
