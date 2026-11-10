package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What an import makes of the modules of a workflow file (observed on 8.1): a
 * {@code WorkflowModule} that has no {@code Comment} when it is sent can come back with one,
 * the comment of the module it uses (seen for workflow modules of a globally used module).
 * Only for the verification of an import and of its rollback ({@link V81ImportArchives}); a
 * comment that was sent and comes back changed or missing is still a difference.
 */
final class ModuleCommentRewrite {

    private ModuleCommentRewrite() {
    }

    /**
     * {@code server} (normalized) without the {@code Comment} children of each workflow module
     * that {@code sent} has without a comment, matched by {@code ModuleId}, if {@code path} is
     * an XML file whose root is a {@code Workflow} and there is such a comment; otherwise empty
     * (also for files that cannot be read).
     */
    static Optional<byte[]> withoutAddedComments(String path, byte[] sent, byte[] server) {
        if (!path.endsWith(".xml")) {
            return Optional.empty();
        }
        try {
            Element sentRoot = XmlTree.parse(sent).root();
            Element serverRoot = XmlTree.parse(server).root();
            if (!is(sentRoot, "Workflow") || !is(serverRoot, "Workflow")) {
                return Optional.empty();
            }
            Map<String, Boolean> commented = new HashMap<>();
            for (Element module : modules(sentRoot)) {
                moduleId(module).ifPresent(id -> commented.merge(id, hasComment(module),
                    Boolean::logicalOr));
            }
            List<Node> children = new ArrayList<>();
            boolean removed = false;
            for (Node child : serverRoot.children()) {
                if (child instanceof Element module && is(module, "WorkflowModule")
                    && hasComment(module) && moduleId(module).map(commented::get)
                        .filter(Boolean.FALSE::equals).isPresent()) {
                    children.add(module.withChildren(module.children().stream()
                        .filter(node -> !(node instanceof Element e && is(e, "Comment")))
                        .toList()));
                    removed = true;
                } else {
                    children.add(child);
                }
            }
            return removed ? Optional.of(XmlNormalizer.normalize(serverRoot.withChildren(
                children))) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static List<Element> modules(Element workflow) {
        return workflow.elements().stream().filter(e -> is(e, "WorkflowModule")).toList();
    }

    private static Optional<String> moduleId(Element module) {
        return module.elements().stream().filter(e -> is(e, "ModuleId")).findFirst()
            .map(Element::text).map(String::strip).filter(id -> !id.isEmpty());
    }

    private static boolean hasComment(Element module) {
        return module.elements().stream().anyMatch(e -> is(e, "Comment"));
    }

    /** INUBIT's own elements: no prefix, no namespace. */
    private static boolean is(Element element, String name) {
        return element.localName().equals(name) && element.prefix().isEmpty()
            && element.namespaceUri().isEmpty();
    }
}
