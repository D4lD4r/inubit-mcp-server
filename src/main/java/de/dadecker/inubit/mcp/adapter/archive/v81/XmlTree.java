package de.dadecker.inubit.mcp.adapter.archive.v81;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A minimal, immutable XML tree read with StAX, shared by {@link XmlNormalizer} and
 * {@link XmlEquality}: elements with their namespace declarations (in document order) and
 * attributes, text (CDATA merged into text), comments and processing instructions. Document type
 * declarations and entity references are refused; no external resource is ever read.
 */
public final class XmlTree {

    /** A node of the tree. */
    public sealed interface Node permits Element, Text, Comment, Instruction {
    }

    /** One namespace declaration; {@code prefix} is empty for the default namespace. */
    public record Namespace(String prefix, String uri) {
    }

    /** One attribute; {@code prefix} and {@code namespaceUri} are empty if it has none. */
    public record Attribute(String prefix, String localName, String namespaceUri, String value) {

        public String qualifiedName() {
            return prefix.isEmpty() ? localName : prefix + ":" + localName;
        }
    }

    /** An element with its children in document order. */
    public record Element(String prefix, String localName, String namespaceUri,
        List<Namespace> namespaces, List<Attribute> attributes, List<Node> children)
        implements Node {

        public Element {
            namespaces = List.copyOf(namespaces);
            attributes = List.copyOf(attributes);
            children = List.copyOf(children);
        }

        public String qualifiedName() {
            return prefix.isEmpty() ? localName : prefix + ":" + localName;
        }

        /** {@code xml:space="preserve"} on this element. */
        public boolean preservesSpace() {
            return attributes.stream().anyMatch(a -> a.localName().equals("space")
                && a.namespaceUri().equals(XMLConstants.XML_NS_URI)
                && a.value().equals("preserve"));
        }

        /**
         * Element-only content: at least one child that is not text, and every text child is
         * whitespace only (and the element does not preserve space).
         */
        public boolean elementOnly() {
            return !preservesSpace() && children.stream().anyMatch(c -> !(c instanceof Text))
                && children.stream().allMatch(c -> !(c instanceof Text text)
                    || text.value().isBlank());
        }
    }

    /** Character data (CDATA sections included). */
    public record Text(String value) implements Node {
    }

    /** A comment. */
    public record Comment(String value) implements Node {
    }

    /** A processing instruction. */
    public record Instruction(String target, String data) implements Node {
    }

    /** The document: comments and instructions before and after the root element. */
    public record Document(List<Node> prolog, Element root, List<Node> epilog) {

        public Document {
            prolog = List.copyOf(prolog);
            Objects.requireNonNull(root, "root");
            epilog = List.copyOf(epilog);
        }
    }

    private static final XMLInputFactory FACTORY = factory();

    private XmlTree() {
    }

    /**
     * Parses {@code xml} (encoding from its declaration, UTF-8 by default).
     *
     * @throws IllegalArgumentException if it is not well-formed, empty, or has a document type
     *     declaration or entity references
     */
    public static Document parse(byte[] xml) {
        Objects.requireNonNull(xml, "xml");
        XMLStreamReader reader = null;
        try {
            reader = FACTORY.createXMLStreamReader(new ByteArrayInputStream(xml));
            return read(reader);
        } catch (XMLStreamException e) {
            throw notWellFormed(e.getLocation());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException e) {
                    // nothing to release
                }
            }
        }
    }

    private static Document read(XMLStreamReader reader) throws XMLStreamException {
        List<Node> prolog = new ArrayList<>();
        List<Node> epilog = new ArrayList<>();
        Element root = null;
        Deque<Builder> open = new ArrayDeque<>();
        while (reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> open.push(new Builder(reader));
                case XMLStreamConstants.END_ELEMENT -> {
                    Element element = open.pop().build();
                    if (open.isEmpty()) {
                        root = element;
                    } else {
                        open.peek().children.add(element);
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA,
                    XMLStreamConstants.SPACE -> {
                    if (!open.isEmpty()) {
                        open.peek().addText(reader.getText());
                    }
                }
                case XMLStreamConstants.COMMENT -> add(open, root == null ? prolog : epilog,
                    new Comment(reader.getText()));
                case XMLStreamConstants.PROCESSING_INSTRUCTION -> add(open,
                    root == null ? prolog : epilog, new Instruction(reader.getPITarget(),
                        reader.getPIData() == null ? "" : reader.getPIData()));
                case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE,
                    XMLStreamConstants.ENTITY_DECLARATION,
                    XMLStreamConstants.NOTATION_DECLARATION ->
                    throw new IllegalArgumentException("XML with a document type declaration or"
                        + " entity references is not accepted");
                default -> {
                    // START_DOCUMENT, END_DOCUMENT
                }
            }
        }
        if (root == null) {
            throw notWellFormed(null);
        }
        return new Document(prolog, root, epilog);
    }

    private static void add(Deque<Builder> open, List<Node> outside, Node node) {
        if (open.isEmpty()) {
            outside.add(node);
        } else {
            open.peek().children.add(node);
        }
    }

    /** Collects one element while its children are read. */
    private static final class Builder {

        private final String prefix;
        private final String localName;
        private final String namespaceUri;
        private final List<Namespace> namespaces = new ArrayList<>();
        private final List<Attribute> attributes = new ArrayList<>();
        private final List<Node> children = new ArrayList<>();

        Builder(XMLStreamReader reader) {
            prefix = nonNull(reader.getPrefix());
            localName = reader.getLocalName();
            namespaceUri = nonNull(reader.getNamespaceURI());
            for (int i = 0; i < reader.getNamespaceCount(); i++) {
                namespaces.add(new Namespace(nonNull(reader.getNamespacePrefix(i)),
                    nonNull(reader.getNamespaceURI(i))));
            }
            for (int i = 0; i < reader.getAttributeCount(); i++) {
                attributes.add(new Attribute(nonNull(reader.getAttributePrefix(i)),
                    reader.getAttributeLocalName(i), nonNull(reader.getAttributeNamespace(i)),
                    reader.getAttributeValue(i)));
            }
        }

        /** Adjacent character data (e.g. text followed by CDATA) becomes one text node. */
        void addText(String text) {
            if (!children.isEmpty() && children.get(children.size() - 1) instanceof Text last) {
                children.set(children.size() - 1, new Text(last.value() + text));
            } else {
                children.add(new Text(text));
            }
        }

        Element build() {
            return new Element(prefix, localName, namespaceUri, namespaces, attributes,
                children);
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static IllegalArgumentException notWellFormed(Location location) {
        return new IllegalArgumentException("Not well-formed XML" + (location == null
            || location.getLineNumber() < 0 ? "" : " (line " + location.getLineNumber()
            + ", column " + location.getColumnNumber() + ")"));
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory;
    }
}
