package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Document;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Text;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Treated as identical" for XML entries (research D-4, FR-015, SC-002), used by the round-trip
 * tests: two documents are equal when
 *
 * <ul>
 *   <li>their elements have the same namespace URIs and local names (prefixes and the place of
 *       namespace declarations do not matter) and the same attributes, in any order;
 *   <li>their children are equal in order: comments and processing instructions are kept and
 *       compared, text is compared exactly — except that whitespace-only text in element-only
 *       content (between elements, comments or processing instructions) is ignored, unless
 *       {@code xml:space="preserve"} applies;
 *   <li>the XML declaration, quoting, escaping and CDATA versus escaped text do not matter.
 * </ul>
 *
 * <p>This is the equality that {@link XmlNormalizer} preserves.
 */
public final class XmlEquality {

    private XmlEquality() {
    }

    /**
     * True if {@code a} and {@code b} are equal as described above.
     *
     * @throws IllegalArgumentException if either is not well-formed
     */
    public static boolean equal(byte[] a, byte[] b) {
        Document first = XmlTree.parse(a);
        Document second = XmlTree.parse(b);
        return first.prolog().equals(second.prolog()) && first.epilog().equals(second.epilog())
            && equal(first.root(), second.root(), false);
    }

    private static boolean equal(Element a, Element b, boolean preserve) {
        if (!a.localName().equals(b.localName()) || !a.namespaceUri().equals(b.namespaceUri())
            || !attributes(a).equals(attributes(b))) {
            return false;
        }
        boolean keepSpace = preserve || a.preservesSpace();
        List<Node> left = significant(a, keepSpace);
        List<Node> right = significant(b, keepSpace || b.preservesSpace());
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            Node x = left.get(i);
            Node y = right.get(i);
            boolean same = x instanceof Element ex
                ? y instanceof Element ey && equal(ex, ey, keepSpace)
                : x.equals(y);
            if (!same) {
                return false;
            }
        }
        return true;
    }

    /** The children that count: without whitespace-only text in element-only content. */
    private static List<Node> significant(Element element, boolean preserve) {
        if (preserve || !element.elementOnly()) {
            return element.children();
        }
        return element.children().stream().filter(child -> !(child instanceof Text)).toList();
    }

    private static Map<String, String> attributes(Element element) {
        Map<String, String> attributes = new HashMap<>();
        for (Attribute attribute : element.attributes()) {
            attributes.put("{" + attribute.namespaceUri() + "}" + attribute.localName(),
                attribute.value());
        }
        return attributes;
    }
}
