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
 *   <li>their elements have the same prefixes, namespace URIs and local names, the same in-scope
 *       namespace bindings (prefixes can be used inside attribute values and text, e.g. in XPath
 *       expressions or Demultiplexer conditions, so a binding is content even if no element name
 *       uses it; where a declaration is written does not matter as long as the bindings in scope
 *       are the same) and the same attributes (qualified name, namespace URI and value), in any
 *       order;
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
            && equal(first.root(), second.root(), false, Map.of(), Map.of());
    }

    private static boolean equal(Element a, Element b, boolean preserve,
        Map<String, String> outerA, Map<String, String> outerB) {
        Map<String, String> scopeA = scope(outerA, a);
        Map<String, String> scopeB = scope(outerB, b);
        if (!a.localName().equals(b.localName()) || !a.namespaceUri().equals(b.namespaceUri())
            || !a.prefix().equals(b.prefix()) || !scopeA.equals(scopeB)
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
                ? y instanceof Element ey && equal(ex, ey, keepSpace, scopeA, scopeB)
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

    /** The namespace bindings in scope at {@code element}: the outer ones plus its own. */
    private static Map<String, String> scope(Map<String, String> outer, Element element) {
        if (element.namespaces().isEmpty()) {
            return outer;
        }
        Map<String, String> scope = new HashMap<>(outer);
        element.namespaces().forEach(ns -> scope.put(ns.prefix(), ns.uri()));
        return scope;
    }

    private static Map<String, String> attributes(Element element) {
        Map<String, String> attributes = new HashMap<>();
        for (Attribute attribute : element.attributes()) {
            attributes.put(attribute.qualifiedName() + "{" + attribute.namespaceUri() + "}",
                attribute.value());
        }
        return attributes;
    }
}
