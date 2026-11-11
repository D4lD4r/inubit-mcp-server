package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.ImportAssembler.Assembled;
import de.dadecker.inubit.mcp.adapter.archive.v81.ImportAssembler.CheckinComment;
import de.dadecker.inubit.mcp.adapter.archive.v81.ImportAssembler.Request;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.domain.model.SecretPlaceholder;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The 8.1 {@link ImportArchivePort} (feature 004): {@link ImportAssembler} with the secret
 * values, identities (UIDs, module file names, diagram group context) and versions of the
 * target's raw exports, and the comparison of reviewed content — embedded text documents as
 * INUBIT stores them (0.4.2), the connections of a workflow module (feature 007) and the
 * variables of a workflow in any order ({@link WorkflowComparison}).
 */
public final class V81ImportArchives implements ImportArchivePort {

    @Override
    public Archive assemble(Build build) {
        ArchiveReader reader = new ArchiveReader();
        List<ExportArchive> raws = new ArrayList<>();
        build.targetExports().forEach(zip -> raws.add(reader.read(zip)));
        Request request = new Request(build.group(), build.owner(), build.diagramGroup(),
            build.workflows().stream().map(V81ImportArchives::artifact).toList(),
            build.modules().stream().map(V81ImportArchives::artifact).toList(),
            new CheckinComment(build.reason(), build.user(), build.server(), build.time()),
            ImportAssembler.currentVersions(build.targetExports()), build.takenNames(),
            build.fromRelease() ? ImportAssembler.NewWorkflowFlag.FROM_RELEASE
                : ImportAssembler.NewWorkflowFlag.MUST_BE_INACTIVE);
        Assembled assembled = ImportAssembler.assemble(new TreeMap<>(build.files()), request,
            ImportAssembler.Target.of(raws));
        return new Archive(assembled.zip(), assembled.workflows(), assembled.modules(),
            assembled.active());
    }

    @Override
    public boolean equivalent(String path, byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (path.endsWith(".xsl") || path.endsWith(".wsdl")) {
            return Arrays.equals(stored(expected), stored(actual))
                || sameDocument(expected, actual);
        }
        if (!path.endsWith(".xml")) {
            return Arrays.equals(expected, actual);
        }
        try {
            return Arrays.equals(compared(expected), compared(actual));
        } catch (RuntimeException e) {
            return Arrays.equals(expected, actual);
        }
    }

    /**
     * {@link #equivalent}, or equivalent after the rewrite of {@link SaxonFactoryRewrite}
     * (issue #55), or after the comments of {@link ModuleCommentRewrite} that the import added
     * to workflow modules sent without one — each with the secrets of {@code sent} as the
     * re-export renders them ({@link #redacted}): a plain value at a secret path is imported as
     * its placeholder.
     */
    @Override
    public boolean importedAs(String path, byte[] sent, byte[] server) {
        byte[] rendered = redacted(path, sent);
        return equivalent(path, rendered, server) || SaxonFactoryRewrite.asImported(path,
            rendered, server).filter(imported -> equivalent(path, imported, server)).isPresent()
            || ModuleCommentRewrite.withoutAddedComments(path, rendered, server)
                .filter(stored -> equivalent(path, rendered, stored)).isPresent();
    }

    /**
     * The workspace file {@code path} with its secrets as a re-export renders them: in a workflow
     * file and a {@code module.xml} every value at a secret path ({@link SecretPaths}, the walk
     * of {@link SecretRedactor}) replaced by its placeholder, so that a plain value the workspace
     * sends at such a path (e.g. a variable name in a map entry named like a certificate) compares
     * with the placeholder the re-export shows. A placeholder stays itself; a reference to an
     * extracted document ({@code @file:}) stays a reference, as the export extracts documents
     * after the redaction. Any other file, XML that cannot be read and a file without such a
     * value come back unchanged.
     */
    static byte[] redacted(String path, byte[] file) {
        if (file == null || !path.endsWith(".xml")) {
            return file;
        }
        WorkspacePath.Kind kind;
        try {
            kind = WorkspacePath.parse(Path.of(path)).kind();
        } catch (RuntimeException e) {
            return file; // not the path of a workspace artifact file
        }
        if (kind != WorkspacePath.Kind.WORKFLOW && kind != WorkspacePath.Kind.MODULE) {
            return file;
        }
        boolean[] replaced = {false};
        SecretPaths paths = new SecretPaths((artifact, secret, secretKind, value) -> {
            if (value.startsWith(EmbeddedDocuments.FILE_REFERENCE)) {
                return value;
            }
            String placeholder = new SecretPlaceholder(secret).render();
            replaced[0] |= !placeholder.equals(value);
            return placeholder;
        });
        try {
            Element root = XmlTree.parse(file).root();
            Element walked = kind == WorkspacePath.Kind.WORKFLOW ? paths.workflow("", root)
                : paths.module("", root);
            return replaced[0] ? XmlNormalizer.normalize(walked) : file;
        } catch (RuntimeException e) {
            return file;
        }
    }

    @Override
    public byte[] connectionOrdered(String path, byte[] file) {
        if (!path.endsWith(".xml")) {
            return file.clone();
        }
        try {
            return XmlNormalizer.normalize(WorkflowComparison.connectionsOrdered(
                XmlTree.parse(file).root()));
        } catch (RuntimeException e) {
            return file.clone();
        }
    }

    @Override
    public boolean differsOnlyInConnectionOrder(String path, byte[] existing, byte[] rendered) {
        return path.endsWith(".xml") && WorkflowComparison.differsOnlyInConnectionOrder(existing,
            rendered);
    }

    /**
     * The check-in comment without the Workbench user comment: when an artifact has a
     * {@code UserComment}, the export appends it to {@code CheckinComment} as one more
     * {@code ###} segment, which is not part of the check-in comment of the version.
     */
    @Override
    public Optional<String> checkinComment(byte[] file) {
        try {
            Element root = XmlTree.parse(file).root();
            String user = root.child("UserComment").map(Element::text).orElse("");
            return root.child("CheckinComment").map(Element::text).map(comment ->
                !user.isEmpty() && comment.endsWith("###" + user)
                    ? comment.substring(0, comment.length() - user.length()) : comment);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Boolean> active(byte[] workflowFile) {
        try {
            return XmlTree.parse(workflowFile).root().child("IsActive").map(Element::text)
                .map(String::strip).filter(text -> text.equals("true") || text.equals("false"))
                .map(Boolean::parseBoolean);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Sets (or adds) {@code IsActive}; the file comes back normalized like a workspace file. */
    @Override
    public byte[] withActive(byte[] workflowFile, boolean active) {
        Element root = XmlTree.parse(workflowFile).root();
        String text = String.valueOf(active);
        List<Node> children = new ArrayList<>();
        boolean set = false;
        for (Node child : root.children()) {
            if (child instanceof Element element && element.localName().equals("IsActive")) {
                children.add(element.withText(text));
                set = true;
            } else {
                children.add(child);
            }
        }
        if (!set) {
            children.add(new Element("", "IsActive", "", List.of(), List.of(),
                List.of(new XmlTree.Text(text))));
        }
        return XmlNormalizer.normalize(root.withChildren(children));
    }

    /**
     * An embedded text document (stylesheet, WSDL) as INUBIT stores it (0.4.2): without the
     * trailing whitespace that INUBIT drops (observed on 8.1: the final line break of a
     * stylesheet), and with LF line ends: whether INUBIT keeps a CR of a CRLF line end is not
     * observed, so either is accepted.
     */
    private static byte[] stored(byte[] document) {
        return new String(document, StandardCharsets.UTF_8).replace("\r\n", "\n")
            .stripTrailing().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * An embedded document as INUBIT re-serializes it when it stores it (observed on 8.1): the
     * same XML with every text node kept ({@link XmlEquality#equalKeepingText}), so that start
     * tags joined onto one line, the line break between a leading comment and the root, and
     * line breaks in attribute values written as spaces do not count. False if either is not
     * well-formed XML.
     */
    private static boolean sameDocument(byte[] expected, byte[] actual) {
        try {
            return XmlEquality.equalKeepingText(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The comparison form of an XML file: normalized, without the volatile children of a
     * workflow or a module index entry, the connections of the workflow modules (feature 007)
     * and the variables of a workflow in one order ({@link WorkflowComparison#contentOrdered}).
     */
    private static byte[] compared(byte[] xml) {
        return XmlNormalizer.normalize(WorkflowComparison.contentOrdered(
            WorkflowComparison.reviewed(XmlTree.parse(xml).root(), false)));
    }

    private static ImportAssembler.Artifact artifact(Artifact artifact) {
        return new ImportAssembler.Artifact(artifact.name(), artifact.pluginType(),
            artifact.created());
    }
}
