package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Document;
import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Extracted;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.RepositoryFile;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.RepositoryClosure;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The repository references of exported artifacts and their closure (feature 015, research R-6),
 * shared by {@link WorkspaceWriter} (its redacted elements) and the per-workflow archives (the
 * unredacted elements of the export), so that both reach the same repository files.
 *
 * <p>Every function takes the bytes to scan and the workspace path they are the content of, the
 * source of the references ({@link RepositoryReferences#scan}); each caller serializes its own
 * elements, e.g. with {@link XmlNormalizer#normalize(Element)}.
 */
final class ArchiveReferences {

    private ArchiveReferences() {
    }

    /** The references of a workflow file {@code content} at {@code from}. */
    static List<RepositoryReference> workflow(WorkspacePath from, byte[] content) {
        return RepositoryReferences.scan(from, content);
    }

    /** The references of a module index entry {@code content} at {@code from}. */
    static List<RepositoryReference> indexEntry(WorkspacePath from, byte[] content) {
        return RepositoryReferences.scan(from, content);
    }

    /**
     * The references of a whole module file ({@code Properties}, e.g. the export's
     * {@code module/<name>.xml} or its normalized element) at the module path {@code from}: the
     * documents are extracted as the workspace writes them ({@link EmbeddedDocuments#extract});
     * the normalized properties (with {@code @file:} references) count as {@code from}, each
     * embedded document as its own {@link #embedded} path. Content that is not well-formed XML
     * has no references.
     *
     * @throws IllegalArgumentException if {@code from} is no module path
     */
    static List<RepositoryReference> moduleFile(WorkspacePath from, byte[] content) {
        if (from.kind() != WorkspacePath.Kind.MODULE) {
            throw new IllegalArgumentException("A module file needs a module path");
        }
        Element module;
        try {
            module = XmlTree.parse(content).root();
        } catch (IllegalArgumentException e) {
            return List.of(); // not well-formed XML
        }
        Extracted extracted = EmbeddedDocuments.extract(module);
        List<RepositoryReference> references = new ArrayList<>(RepositoryReferences.scan(from,
            XmlNormalizer.normalize(extracted.properties())));
        for (Document document : extracted.documents()) {
            references.addAll(RepositoryReferences.scan(embedded(from, document),
                document.content()));
        }
        return references;
    }

    /** The workspace path of {@code document}, extracted from the module file at {@code module}. */
    static WorkspacePath embedded(WorkspacePath module, Document document) {
        int dot = document.fileName().lastIndexOf('.');
        return WorkspacePath.embedded(module.group(), module.owner(), module.segments().get(0),
            module.segments().get(1), document.property(), document.fileName().substring(dot + 1));
    }

    /**
     * The closure of {@code references} within {@code /Root/<owner>/}
     * ({@link RepositoryClosure#of}), following the files of {@code repository} (the export's
     * {@code Repository.zip}); a reached file is scanned as {@code <group>/<owner>/repository/…}.
     */
    static RepositoryClosure closure(String owner, Collection<RepositoryReference> references,
        Collection<RepositoryFile> repository, GroupId group) {
        Map<String, RepositoryFile> byPath = new HashMap<>();
        repository.forEach(file -> byPath.put("/" + file.path(), file));
        return RepositoryClosure.of(owner, references, reference ->
            Optional.ofNullable(byPath.get(reference)).map(file -> RepositoryReferences.scan(
                WorkspacePath.repository(group, owner, reference), file.content())));
    }
}
