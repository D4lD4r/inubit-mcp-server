package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import java.util.List;
import java.util.Map;
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
     * @param fromRelease   feature 005 (research D-7): a new workflow takes the flag of its
     *                      file and an existing one keeps the target's; otherwise (feature 004)
     *                      a new workflow must be inactive in its file
     */
    record Build(GroupId group, String owner, Optional<String> diagramGroup,
        List<Artifact> workflows, List<Artifact> modules, SortedMap<String, byte[]> files,
        List<byte[]> targetExports, String reason, String user, String server, String time,
        Set<String> takenNames, boolean fromRelease) {

        /** A build of feature 004 (a new workflow must be inactive). */
        public Build(GroupId group, String owner, Optional<String> diagramGroup,
            List<Artifact> workflows, List<Artifact> modules, SortedMap<String, byte[]> files,
            List<byte[]> targetExports, String reason, String user, String server, String time,
            Set<String> takenNames) {
            this(group, owner, diagramGroup, workflows, modules, files, targetExports, reason,
                user, server, time, takenNames, false);
        }

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

    /**
     * The import archive (with secret values; never logged), the names it holds and the intended
     * {@code IsActive} flag of each workflow (feature 005).
     */
    record Archive(byte[] zip, List<String> workflows, List<String> modules,
        Map<String, Boolean> active) {

        /** An archive without flags (feature 004). */
        public Archive(byte[] zip, List<String> workflows, List<String> modules) {
            this(zip, workflows, modules, Map.of());
        }

        public Archive {
            zip = zip.clone();
            workflows = List.copyOf(workflows);
            modules = List.copyOf(modules);
            active = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(active));
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
     * CheckinComment}, {@code LastUpdate}, UIDs, {@code CheckoutUser}) and the order of the
     * connections of a workflow module (feature 007); an embedded text document (stylesheet,
     * WSDL) as INUBIT stores it, without trailing whitespace and with any line ends (0.4.2);
     * other files byte by byte.
     */
    boolean equivalent(String path, byte[] expected, byte[] actual);

    /**
     * The rendering of the workspace file {@code path} with only the order of the workflow
     * modules' connections normalized (feature 007, contract P-2): INUBIT writes the outgoing
     * connections of a module in a non-deterministic order. XML comes back normalized, with the
     * direct {@code Connection} children of each {@code WorkflowModule} in one order; everything
     * else is kept, the volatile elements too. Any other file, and XML that cannot be read,
     * comes back as an unchanged copy. Only for fingerprints and for keeping a workspace file
     * that differs in that order only — never imported or written.
     */
    byte[] connectionOrdered(String path, byte[] file);

    /**
     * True if {@code existing} (the workspace file {@code path}) and {@code rendered} (a new
     * server rendering of it) are two renderings of a workflow that differ only in the order of
     * the modules' connections (feature 007, contract P-6): then the workspace keeps
     * {@code existing}, which is a genuine server rendering as well. False for any other file
     * or difference, formatting included.
     */
    boolean differsOnlyInConnectionOrder(String path, byte[] existing, byte[] rendered);

    /** The {@code CheckinComment} text of a workflow or module index file, if any. */
    Optional<String> checkinComment(byte[] file);

    /** The {@code IsActive} flag of a workflow file (research D-15), if it has one. */
    Optional<Boolean> active(byte[] workflowFile);

    /** The workflow file with its {@code IsActive} flag set to {@code active}. */
    byte[] withActive(byte[] workflowFile, boolean active);
}
