package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Comment;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Document;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Instruction;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Namespace;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Text;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/**
 * The normalized serialization of the workspace's XML files (research D-5), so that exporting
 * unchanged artifacts twice yields identical bytes (FR-013):
 *
 * <ul>
 *   <li>UTF-8, LF line ends, the declaration {@code <?xml version="1.0" encoding="UTF-8"?>};
 *   <li>element-only content (child elements, comments or processing instructions and only
 *       whitespace between them) indented by two spaces per level; that whitespace is dropped,
 *       which {@link XmlEquality} ignores (D-4);
 *   <li>text and mixed content, and everything below {@code xml:space="preserve"}, written
 *       exactly as read: no character of a text is added, removed or changed;
 *   <li>namespace declarations in document order, then the attributes sorted by qualified name;
 *       {@code <x/>} for an element without content;
 *   <li>escaping as INUBIT writes embedded XML (spike §5): in text {@code &} and {@code <} are
 *       escaped, {@code >} only after {@code ]]}, and a carriage return as {@code &#13;}; in
 *       attribute values additionally {@code "}, tab and line feed; CDATA sections become
 *       escaped text.
 * </ul>
 *
 * <p>Normalizing is idempotent. Document type declarations are refused.
 */
public final class XmlNormalizer {

    static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String INDENT = "  ";

    private XmlNormalizer() {
    }

    /**
     * The normalized form of {@code xml}.
     *
     * @throws IllegalArgumentException if {@code xml} is not well-formed or has a document type
     *     declaration
     */
    public static byte[] normalize(byte[] xml) {
        Document document = XmlTree.parse(xml);
        StringBuilder out = new StringBuilder(xml.length + xml.length / 4 + 64);
        out.append(DECLARATION).append('\n');
        for (Node node : document.prolog()) {
            write(out, node, 0, true);
            out.append('\n');
        }
        write(out, document.root(), 0, true);
        out.append('\n');
        for (Node node : document.epilog()) {
            write(out, node, 0, true);
            out.append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The normalized document whose root is {@code root} (D-5), e.g. one workflow file. */
    public static byte[] normalize(Element root) {
        StringBuilder out = new StringBuilder(4096);
        out.append(DECLARATION).append('\n');
        write(out, root, 0, true);
        out.append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(StringBuilder out, Node node, int depth, boolean indent) {
        switch (node) {
            case Element element -> writeElement(out, element, depth, indent);
            case Text text -> escapeText(out, text.value());
            case Comment comment -> out.append("<!--").append(comment.value()).append("-->");
            case Instruction pi -> out.append("<?").append(pi.target())
                .append(pi.data().isEmpty() ? "" : " " + pi.data()).append("?>");
        }
    }

    private static void writeElement(StringBuilder out, Element element, int depth,
        boolean indent) {
        out.append('<').append(element.qualifiedName());
        for (Namespace namespace : element.namespaces()) {
            out.append(namespace.prefix().isEmpty() ? " xmlns" : " xmlns:" + namespace.prefix())
                .append("=\"");
            escapeAttribute(out, namespace.uri());
            out.append('"');
        }
        List<Attribute> sorted = element.attributes().stream()
            .sorted(Comparator.comparing(Attribute::qualifiedName)).toList();
        for (Attribute attribute : sorted) {
            out.append(' ').append(attribute.qualifiedName()).append("=\"");
            escapeAttribute(out, attribute.value());
            out.append('"');
        }
        if (element.children().isEmpty()) {
            out.append("/>");
            return;
        }
        out.append('>');
        if (indent && element.elementOnly()) {
            for (Node child : element.children()) {
                if (!(child instanceof Text)) {
                    out.append('\n').append(INDENT.repeat(depth + 1));
                    write(out, child, depth + 1, true);
                }
            }
            out.append('\n').append(INDENT.repeat(depth));
        } else {
            // text, mixed content or xml:space="preserve": exactly as read, also below
            for (Node child : element.children()) {
                write(out, child, depth + 1, false);
            }
        }
        out.append("</").append(element.qualifiedName()).append('>');
    }

    private static void escapeText(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append(i >= 2 && text.charAt(i - 1) == ']'
                    && text.charAt(i - 2) == ']' ? "&gt;" : ">");
                case '\r' -> out.append("&#13;");
                default -> out.append(c);
            }
        }
    }

    private static void escapeAttribute(StringBuilder out, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '"' -> out.append("&quot;");
                case '\t' -> out.append("&#9;");
                case '\n' -> out.append("&#10;");
                case '\r' -> out.append("&#13;");
                default -> out.append(c);
            }
        }
    }
}
