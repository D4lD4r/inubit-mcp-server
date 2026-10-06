package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.ImportAssembler.Assembled;
import de.dadecker.inubit.mcp.adapter.archive.v81.ImportAssembler.CheckinComment;
import de.dadecker.inubit.mcp.adapter.archive.v81.ImportAssembler.Request;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The 8.1 {@link ImportArchivePort} (feature 004): {@link ImportAssembler} with the secret
 * values and versions of the target's raw exports, and the comparison of reviewed content.
 */
public final class V81ImportArchives implements ImportArchivePort {

    /** What INUBIT rewrites on every import, or what a workspace file keeps in .meta/. */
    private static final Set<String> VOLATILE = Set.of("CheckinComment", "LastUpdate",
        "WorkflowUId", "ModuleUId", "CheckoutUser");

    @Override
    public Archive assemble(Build build) {
        ArchiveReader reader = new ArchiveReader();
        List<ExportArchive> raws = new ArrayList<>();
        build.targetExports().forEach(zip -> raws.add(reader.read(zip)));
        Request request = new Request(build.group(), build.owner(), build.diagramGroup(),
            build.workflows().stream().map(V81ImportArchives::artifact).toList(),
            build.modules().stream().map(V81ImportArchives::artifact).toList(),
            new CheckinComment(build.reason(), build.user(), build.server(), build.time()),
            ImportAssembler.currentVersions(build.targetExports()), build.takenNames());
        Assembled assembled = ImportAssembler.assemble(new TreeMap<>(build.files()), request,
            SecretValues.of(raws));
        return new Archive(assembled.zip(), assembled.workflows(), assembled.modules());
    }

    @Override
    public boolean equivalent(String path, byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (!path.endsWith(".xml")) {
            return Arrays.equals(expected, actual);
        }
        try {
            return Arrays.equals(XmlNormalizer.normalize(reviewed(XmlTree.parse(expected)
                .root())), XmlNormalizer.normalize(reviewed(XmlTree.parse(actual).root())));
        } catch (RuntimeException e) {
            return Arrays.equals(expected, actual);
        }
    }

    @Override
    public Optional<String> checkinComment(byte[] file) {
        try {
            return XmlTree.parse(file).root().child("CheckinComment").map(Element::text);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The root without the volatile children of a workflow or a module index entry. */
    private static Element reviewed(Element root) {
        if (!root.localName().equals("Workflow") && !root.localName().equals("Module")) {
            return root;
        }
        List<Node> children = new ArrayList<>();
        for (Node child : root.children()) {
            if (!(child instanceof Element element && VOLATILE.contains(element.localName()))) {
                children.add(child);
            }
        }
        return root.withChildren(children);
    }

    private static ImportAssembler.Artifact artifact(Artifact artifact) {
        return new ImportAssembler.Artifact(artifact.name(), artifact.pluginType(),
            artifact.created());
    }
}
