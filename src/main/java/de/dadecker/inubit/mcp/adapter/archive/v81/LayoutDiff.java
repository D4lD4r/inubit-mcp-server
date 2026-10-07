package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Layout-only differences of workflows (feature 005, research D-5): the Workbench keeps a
 * workflow's layout only in {@code <StyleSheet …/>} elements — node positions ({@code xPos},
 * {@code yPos}) below a {@code WorkflowModule}, label positions ({@code labelPosition}) below a
 * {@code Connection}. Two rendered workflow files differ <em>only in layout</em> if they differ
 * and are equal ({@link XmlNormalizer}, i.e. {@link XmlEquality}) once every {@code StyleSheet}
 * element without namespace is removed. Nodes, edges, conditions, properties and variables are
 * never layout.
 */
public final class LayoutDiff {

    private static final String STYLE_SHEET = "StyleSheet";

    private LayoutDiff() {
    }

    /**
     * True if {@code a} and {@code b} differ and every difference lies inside
     * {@code <StyleSheet>} elements (including one that only one side has).
     *
     * @throws IllegalArgumentException if either is not well-formed XML
     */
    public static boolean layoutOnly(byte[] a, byte[] b) {
        Element left = XmlTree.parse(a).root();
        Element right = XmlTree.parse(b).root();
        if (Arrays.equals(XmlNormalizer.normalize(left), XmlNormalizer.normalize(right))) {
            return false;
        }
        return Arrays.equals(XmlNormalizer.normalize(withoutLayout(left)),
            XmlNormalizer.normalize(withoutLayout(right)));
    }

    /** INUBIT's own {@code StyleSheet}: no prefix, no namespace (stage 1 review #10). */
    private static boolean isLayout(Element element) {
        return element.localName().equals(STYLE_SHEET) && element.prefix().isEmpty()
            && element.namespaceUri().isEmpty();
    }

    private static Element withoutLayout(Element element) {
        if (!element.hasElements()) {
            return element;
        }
        List<Node> children = new ArrayList<>();
        for (Node node : element.children()) {
            if (node instanceof Element child) {
                if (!isLayout(child)) {
                    children.add(withoutLayout(child));
                }
            } else {
                children.add(node);
            }
        }
        return element.withChildren(children);
    }
}
