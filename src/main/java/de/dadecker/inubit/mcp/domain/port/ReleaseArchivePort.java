package de.dadecker.inubit.mcp.domain.port;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The version-specific archive work of a deployment (feature 005, research D-4, D-5): the
 * release export in the shape the workspace codec reads, the versions an export states, and the
 * comparisons of rendered workspace files the classification needs. The application never reads
 * the formats itself. Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException}.
 */
public interface ReleaseArchivePort {

    /**
     * A release export without the traces of the tag.
     *
     * @param export         the archive in the shape of a diagram group export (every tagged
     *                       diagram group in it): {@code version="head"}, no {@code tag}
     *                       attribute, no {@code @@@Tag: …@@@}, no {@code usertags.xml}
     * @param diagramGroups  the diagram groups of the release
     * @param taggedVersions workflow or module name → its tagged version
     */
    record ReleaseExport(byte[] export, SortedSet<String> diagramGroups,
        Map<String, Integer> taggedVersions) {
        public ReleaseExport {
            export = Objects.requireNonNull(export, "export").clone();
            diagramGroups = new TreeSet<>(diagramGroups);
            taggedVersions = Map.copyOf(taggedVersions);
        }

        @Override
        public byte[] export() {
            return export.clone();
        }

        @Override
        public String toString() {
            return "ReleaseExport[" + diagramGroups + ", " + taggedVersions.size()
                + " artifacts]";
        }
    }

    /** One property whose value differs between the release and the target (names a value). */
    record PropertyChange(String property, String releaseValue, String targetValue) {
        public PropertyChange {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(releaseValue, "releaseValue");
            Objects.requireNonNull(targetValue, "targetValue");
        }

        /** The property name only; never the values. */
        @Override
        public String toString() {
            return "PropertyChange[" + property + "]";
        }
    }

    /**
     * The release export {@code releaseExport} (an owner-wide export by tag, research D-1) in
     * the shape of a diagram group export.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code UNEXPECTED_RESPONSE}
     *     if it is not a readable export
     */
    ReleaseExport normalize(byte[] releaseExport);

    /** Workflow or module name → its current version, from a (head) export's comments. */
    Map<String, Integer> versions(byte[] export);

    /**
     * The reviewed content of a rendered workspace file, for fingerprints: XML normalized and
     * without what differs between nodes or exports of the same content (check-in comment, last
     * update, UIDs, edit mode, the order of the connections of a workflow module); other files
     * unchanged. Only compared or hashed, never imported or written.
     */
    byte[] canonical(String path, byte[] file);

    /**
     * The reviewed content as {@link #canonical} computed it up to 0.5.0, with the connections
     * of the workflow modules in the order of the file (feature 007, contract P-3, P-5): only to
     * recognise fingerprints that 0.5.0 or earlier recorded (deploy backups, the deployment
     * ledger); new fingerprints always use {@link #canonical}.
     *
     * @deprecated only for fingerprints recorded by ≤ 0.5.0; remove after 0.5.x
     */
    @Deprecated
    byte[] legacyCanonical(String path, byte[] file);

    /**
     * True if two versions of the rendered workspace file {@code path} have the same reviewed
     * content (XML normalized; check-in comment, last update, UIDs, edit mode, the order of the
     * connections of a workflow module and — for workflows — the {@code IsActive} flag
     * ignored).
     */
    boolean equivalent(String path, byte[] release, byte[] target);

    /**
     * True if the rendered workflow files differ only in layout ({@code StyleSheet}), with the
     * connections of each workflow module in one order.
     */
    boolean layoutOnly(String path, byte[] release, byte[] target);

    /** True if the repository file is key material or a certificate (FR-015a). */
    boolean keyMaterial(String repositoryPath, byte[] content);

    /** The modules the nodes of a rendered workflow file run, in node order, without repeats. */
    List<String> modulesOf(byte[] workflowFile);

    /**
     * The simple properties (with a text value) whose value differs between two versions of a
     * rendered workflow or module file, in document order.
     */
    List<PropertyChange> changedProperties(String path, byte[] release, byte[] target);

    /**
     * Repository path ({@code /Root/…}) → content of the repository files of a repository
     * export or of the {@code Repository.zip} of a diagram group or release export, key material
     * included (memory only).
     */
    Map<String, byte[]> repositoryFiles(byte[] archive);

    /**
     * True if {@code archive} is a repository export ({@code export --exportRepositoryPath}),
     * false for a diagram group, module or release export (they carry a
     * {@code Repository.zip}).
     */
    boolean repositoryExport(byte[] archive);

    /**
     * The archive of a repository import into {@code /Root/<owner>} (research D-1, D-7) with the
     * files {@code paths}, each taken from the first of {@code archives} (repository exports or
     * diagram group / release exports) that has it: entries relative to the owner's root,
     * metadata stating the content written, never key material.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INTERNAL} if a path
     *     is in none of the archives; {@code PRECONDITION_FAILED} for key material;
     *     {@code INVALID_INPUT} for a path outside the owner's root
     */
    byte[] repositoryArchive(List<byte[]> archives, String owner,
        java.util.Collection<String> paths);
}

