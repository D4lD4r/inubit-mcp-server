package de.dadecker.inubit.mcp.adapter.rest;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.StreamReaderDelegate;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Secure XML parsing for INUBIT responses (research R-4) and namespace-agnostic lookups.
 *
 * <p>INUBIT responses never carry a DTD, so every document with a {@code DOCTYPE} is rejected:
 * this rules out external entities (XXE), external DTDs and entity-expansion bombs ("billion
 * laughs") without any network or file access. {@code FEATURE_SECURE_PROCESSING} is on and all
 * external access properties are empty as a second line of defence.
 *
 * <p>The lookups match elements and attributes by their <em>local name</em> in any namespace, so
 * that prefixes like {@code ns4:ModelList} or a default namespace do not matter.
 */
public final class XmlSupport {

    private static final String DISALLOW_DOCTYPE =
        "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES =
        "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
        "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD =
        "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY = documentBuilderFactory();
    private static final XMLInputFactory INPUT_FACTORY = inputFactory();

    private static final ErrorHandler SILENT = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
            // ignored
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    private XmlSupport() {
    }

    /**
     * Parses a namespace-aware DOM document.
     *
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} if the document is not well-formed or
     *     declares a DTD; the content is never echoed
     */
    public static Document parse(byte[] xml) {
        return parse(new ByteArrayInputStream(xml));
    }

    /** As {@link #parse(byte[])}; the stream is not closed. */
    public static Document parse(InputStream in) {
        try {
            DocumentBuilder builder = newDocumentBuilder();
            return builder.parse(in);
        } catch (SAXException | IOException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
                "The INUBIT response is not acceptable XML (" + e.getClass().getSimpleName()
                    + ")",
                "The response is not well-formed, or it declares a DTD, which INUBIT responses"
                    + " never do",
                "Check the INUBIT version and the endpoint; DTDs are rejected for security"));
        }
    }

    /** A secure, namespace-aware {@link DocumentBuilder} (not thread-safe; one per use). */
    public static DocumentBuilder newDocumentBuilder() {
        try {
            DocumentBuilder builder;
            synchronized (DOCUMENT_BUILDER_FACTORY) {
                builder = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
            }
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are not allowed");
            });
            builder.setErrorHandler(SILENT);
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Secure XML parser not available", e);
        }
    }

    /**
     * A secure StAX reader. A {@code DOCTYPE} or entity makes {@code next()}, {@code nextTag()}
     * and {@code getElementText()} throw {@link XMLStreamException}; entities are never
     * resolved. ({@code StreamReaderDelegate} would forward {@code nextTag()} and
     * {@code getElementText()} to the wrapped reader, bypassing the check, so both are
     * re-implemented on top of {@code next()} as specified by {@link XMLStreamReader}.)
     */
    public static XMLStreamReader newStreamReader(InputStream in) throws XMLStreamException {
        return new SecureStreamReader(INPUT_FACTORY.createXMLStreamReader(in));
    }

    private static final class SecureStreamReader extends StreamReaderDelegate {

        SecureStreamReader(XMLStreamReader reader) {
            super(reader);
        }

        @Override
        public int next() throws XMLStreamException {
            int event = super.next();
            if (event == XMLStreamConstants.DTD
                || event == XMLStreamConstants.ENTITY_REFERENCE
                || event == XMLStreamConstants.ENTITY_DECLARATION) {
                throw new XMLStreamException("DTDs and entities are not allowed");
            }
            return event;
        }

        @Override
        public int nextTag() throws XMLStreamException {
            int event = next();
            while ((event == XMLStreamConstants.CHARACTERS && isWhiteSpace())
                || (event == XMLStreamConstants.CDATA && isWhiteSpace())
                || event == XMLStreamConstants.SPACE
                || event == XMLStreamConstants.PROCESSING_INSTRUCTION
                || event == XMLStreamConstants.COMMENT) {
                event = next();
            }
            if (event != XMLStreamConstants.START_ELEMENT
                && event != XMLStreamConstants.END_ELEMENT) {
                throw new XMLStreamException("expected start or end tag", getLocation());
            }
            return event;
        }

        @Override
        public String getElementText() throws XMLStreamException {
            if (getEventType() != XMLStreamConstants.START_ELEMENT) {
                throw new XMLStreamException("not at a start element", getLocation());
            }
            StringBuilder text = new StringBuilder();
            int event = next();
            while (event != XMLStreamConstants.END_ELEMENT) {
                if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                    || event == XMLStreamConstants.SPACE) {
                    text.append(getText());
                } else if (event == XMLStreamConstants.START_ELEMENT) {
                    throw new XMLStreamException("element text contains an element",
                        getLocation());
                } else if (event == XMLStreamConstants.END_DOCUMENT) {
                    throw new XMLStreamException("unexpected end of document", getLocation());
                }
                event = next();
            }
            return text.toString();
        }
    }

    /** The local name of an element or attribute, without prefix. */
    public static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) {
            return local;
        }
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /** All descendant elements of {@code root} with the given local name, in document order. */
    public static List<Element> descendants(Node root, String localName) {
        NodeList nodes = root instanceof Document document
            ? document.getElementsByTagNameNS("*", localName)
            : ((Element) root).getElementsByTagNameNS("*", localName);
        List<Element> result = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add((Element) nodes.item(i));
        }
        return result;
    }

    /** The direct child elements of {@code parent} with the given local name. */
    public static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName(element).equals(localName)) {
                result.add(element);
            }
        }
        return result;
    }

    /** The first direct child element with the given local name. */
    public static Optional<Element> firstChild(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName(element).equals(localName)) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    /** The value of the attribute with the given local name, in any namespace. */
    public static Optional<String> attribute(Element element, String localName) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            if (localName(attribute).equals(localName)
                && !XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                return Optional.of(attribute.getValue());
            }
        }
        return Optional.empty();
    }

    /** The trimmed text content of an element. */
    public static String text(Element element) {
        return element.getTextContent().strip();
    }

    private static DocumentBuilderFactory documentBuilderFactory() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setValidating(false);
            return factory;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Secure XML parser not available", e);
        }
    }

    private static XMLInputFactory inputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External entities are not allowed");
        });
        return factory;
    }
}
