package de.dadecker.inubit.mcp.domain.model;

import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan.ModuleEntry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The result of one {@code export_archives} call (feature 015, data-model.md →
 * ArchiveExportResult, research R-14, FR-011, FR-012): what was written, what failed and what was
 * not attempted, names and counts only, never file content or secret values. Refusals before the
 * first write are tool errors, not results.
 *
 * @param node             the node the diagram group was exported from (a group target: its
 *                         first node)
 * @param owner            the owner in effect
 * @param diagramGroup     the diagram group
 * @param targetFolder     the absolute path of the target folder
 * @param auditId          the id of the call's audit records
 * @param clearTextSecrets the fixed statement {@link #CLEAR_TEXT_SECRETS}
 * @param archives         the archives written completely, in write order
 * @param failed           at most one entry: the archive the writes stopped at (FR-011)
 * @param notWritten       the workflows not attempted after the failure
 * @param truncated        whether the module, missing-module and repository-path lists were cut
 *                         by {@code resultLimits.maxItems}
 * @param warnings         call-level warnings (version admission, leftover temporary file,
 *                         audit outcome not written)
 */
public record ArchiveExportResult(String node, String owner, String diagramGroup,
    String targetFolder, UUID auditId, String clearTextSecrets, List<WrittenArchive> archives,
    List<FailedArchive> failed, List<String> notWritten, boolean truncated,
    List<String> warnings) {

    /** The statement every result carries (contracts/mcp-tools-delta.md). */
    public static final String CLEAR_TEXT_SECRETS = "The archives contain the secrets of these"
        + " workflows (passwords, keys, certificates, saved test messages) in clear text. Do not"
        + " share, attach or commit them as they are; delete them when no longer needed.";

    public ArchiveExportResult {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(diagramGroup, "diagramGroup");
        Objects.requireNonNull(targetFolder, "targetFolder");
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(clearTextSecrets, "clearTextSecrets");
        archives = List.copyOf(archives);
        failed = List.copyOf(failed);
        notWritten = List.copyOf(notWritten);
        warnings = List.copyOf(warnings);
    }

    /**
     * One archive written completely (FR-012).
     *
     * @param workflow            the workflow name
     * @param file                the file name in the target folder
     * @param path                the absolute path of the archive
     * @param size                the size in bytes
     * @param moduleCount         the number of modules in the archive (always complete)
     * @param modules             the modules in the archive (bounded)
     * @param missingModuleCount  the number of node modules the export does not contain
     * @param missingModules      those modules (bounded)
     * @param repositoryFileCount the number of repository files in the archive
     * @param repositoryPaths     their repository paths (bounded)
     * @param warnings            per-archive warnings: missing modules, Workflow Connector
     */
    public record WrittenArchive(String workflow, String file, String path, long size,
        int moduleCount, List<ModuleEntry> modules, int missingModuleCount,
        List<MissingModule> missingModules, int repositoryFileCount, List<String> repositoryPaths,
        List<String> warnings) {

        public WrittenArchive {
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(path, "path");
            modules = List.copyOf(modules);
            missingModules = List.copyOf(missingModules);
            repositoryPaths = List.copyOf(repositoryPaths);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * A node module the export does not contain; its plug-in type is unknown (FR-005, FR-012).
     *
     * @param name the module name
     */
    public record MissingModule(String name) {

        public MissingModule {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * The archive whose writing failed (FR-011).
     *
     * @param workflow the workflow name
     * @param code     the error code ({@code INTERNAL})
     * @param message  what failed, naming the file, never content
     */
    public record FailedArchive(String workflow, ErrorCode code, String message) {

        public FailedArchive {
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
