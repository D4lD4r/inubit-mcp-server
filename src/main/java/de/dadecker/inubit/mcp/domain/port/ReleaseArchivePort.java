package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.RepositoryUpload;
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
     * Repository path ({@code /Root/…}) → metadata XML (the {@code <Property
     * type="RepositoryFile">} element as exported) of the repository files of an archive read
     * like {@link #repositoryFiles} (feature 008, research D-7: an EXISTING file is sent with the
     * node's metadata).
     */
    Map<String, String> repositoryMetadata(byte[] archive);

    /**
     * Repository path ({@code /Root/…}) → the workspace {@code .meta} record of the repository
     * files of an archive read like {@link #repositoryFiles}, as an export writes it (feature 008,
     * research D-8 step 5: the write-back after a verified repository import).
     */
    Map<String, byte[]> repositoryMetaRecords(byte[] archive);

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

    /**
     * The archive of a repository import into {@code /Root/<owner>} of workspace files (feature
     * 008, research D-7, D-8), shaped like {@link #repositoryArchive}. An upload with metadata
     * keeps it except {@code contentSize} and {@code contentMD5} (of the content written); one
     * without gets the synthesized metadata of a new file, with the content type from the
     * extension.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException {@code INVALID_INPUT} for no
     *     upload, a path outside the owner's root, a duplicate, metadata of another path or a
     *     new file whose extension has no known content type; {@code PRECONDITION_FAILED} for key
     *     material ({@link #keyMaterial})
     */
    byte[] repositoryArchiveFromFiles(String owner, List<RepositoryUpload> uploads);
}

