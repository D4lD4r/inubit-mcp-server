package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;

/**
 * The version-specific archive work of an import (feature 004, research D-6, D-7, D-9, D-11):
 * building the import archive from workspace files (or a rendered backup) with the target's
 * secrets, and comparing reviewed content. The application never reads the formats itself.
 * Failures are thrown as {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException}.
 */
public interface ImportArchivePort {

    /** One artifact to import; a module needs its plugin type. */
    record Artifact(String name, Optional<String> pluginType, boolean created) {
        public Artifact {
            Objects.requireNonNull(name, "name");
            pluginType = pluginType == null ? Optional.empty() : pluginType;
        }
    }

    /**
     * What to build.
     *
     * @param files         workspace-relative path → content (artifact files and {@code .meta/})
     * @param targetExports raw exports of the target: its current secrets and versions
     * @param user          the INUBIT user of the check-in comment
     * @param server        the INUBIT host of the check-in comment
     * @param time          {@code dd.MM.yyyy HH:mm:ss}
     * @param takenNames    the names of the owner's workflows and modules on the target
     */
    record Build(GroupId group, String owner, Optional<String> diagramGroup,
        List<Artifact> workflows, List<Artifact> modules, SortedMap<String, byte[]> files,
        List<byte[]> targetExports, String reason, String user, String server, String time,
        Set<String> takenNames) {
        public Build {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(owner, "owner");
            diagramGroup = diagramGroup == null ? Optional.empty() : diagramGroup;
            workflows = List.copyOf(workflows);
            modules = List.copyOf(modules);
            Objects.requireNonNull(files, "files");
            targetExports = List.copyOf(targetExports);
            Objects.requireNonNull(reason, "reason");
            takenNames = Set.copyOf(takenNames);
        }

        @Override
        public String toString() {
            return "Build[" + workflows.size() + " workflows, " + modules.size() + " modules]";
        }
    }

    /** The import archive (with secret values; never logged) and the names it holds. */
    record Archive(byte[] zip, List<String> workflows, List<String> modules) {
        public Archive {
            zip = zip.clone();
            workflows = List.copyOf(workflows);
            modules = List.copyOf(modules);
        }

        @Override
        public byte[] zip() {
            return zip.clone();
        }

        @Override
        public String toString() {
            return "Archive[" + workflows.size() + " workflows, " + modules.size() + " modules]";
        }
    }

    /**
     * The import archive of {@code build}.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code SECRET_UNRESOLVED}, {@code PRECONDITION_FAILED}, {@code INTERNAL}
     */
    Archive assemble(Build build);

    /**
     * True if two versions of the workspace file {@code path} have the same reviewed content:
     * XML compared normalized, ignoring what INUBIT rewrites on every import ({@code
     * CheckinComment}, {@code LastUpdate}, UIDs, {@code CheckoutUser}); other files byte by
     * byte.
     */
    boolean equivalent(String path, byte[] expected, byte[] actual);

    /** The {@code CheckinComment} text of a workflow or module index file, if any. */
    Optional<String> checkinComment(byte[] file);
}
