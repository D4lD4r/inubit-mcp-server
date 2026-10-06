package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.SecretPlaceholder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The documents embedded in a module file, as files of their own (research D-4, FR-012).
 *
 * <ul>
 *   <li>Extracted are the direct {@code Property} children of the module's {@code Properties}
 *       with a non-blank value that is no placeholder: {@code type="XmlDocument"} and the untyped
 *       {@code WsdlData}/{@code ValidWsdlData} (entity-escaped XML; the file holds the XML exactly
 *       as decoded), and {@code type="InternalDocument"} (base64, gzip-compressed or not —
 *       decided by the content, not by the {@code xslt.base64Zipped} flag). Values nested in
 *       maps (e.g. {@code xslt.sourceVariables}) stay inline.
 *   <li>File names: {@code <property>.<ext>} with {@code xslt.stylesheet → xsl},
 *       {@code WsdlData}/{@code ValidWsdlData → wsdl}, an InternalDocument by its
 *       {@code documentName}/{@code documentContentType} ({@code xsd}, {@code xml}, otherwise
 *       {@code bin}), any other XmlDocument {@code xml}. The property text becomes
 *       {@code @file:<file name>}.
 *   <li>Re-embedding puts the XML text back (the serializer escapes it as INUBIT does) and
 *       re-encodes InternalDocuments like INUBIT (Java's {@code GZIPOutputStream} and plain
 *       base64, which reproduces the recorded values byte for byte); it recomputes
 *       {@code documentSize} and a sibling {@code <property>MD5} (e.g.
 *       {@code JSONStaticSchemaMD5}, the MD5 of the decoded document).
 * </ul>
 */
public final class EmbeddedDocuments {

    /** The prefix of a property text that refers to an extracted file. */
    public static final String FILE_REFERENCE = "@file:";

    /** How a document is stored in the property. */
    public enum Encoding {
        ESCAPED_XML,
        GZIP_BASE64,
        BASE64
    }

    /** One extracted document. */
    public record Document(String property, String fileName, Encoding encoding, byte[] content) {

        public Document {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(encoding, "encoding");
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /** The properties with file references, and the documents they refer to. */
    public record Extracted(Element properties, List<Document> documents) {

        public Extracted {
            Objects.requireNonNull(properties, "properties");
            documents = List.copyOf(documents);
        }
    }

    private EmbeddedDocuments() {
    }

    /** Extracts the embedded documents of a module's {@code Properties} element. */
    public static Extracted extract(Element properties) {
        List<Document> documents = new ArrayList<>();
        List<Node> children = new ArrayList<>();
        for (Node child : properties.children()) {
            if (child instanceof Element property && property.localName().equals("Property")) {
                Optional<Document> document = document(property);
                document.ifPresent(documents::add);
                children.add(document.map(d -> property.withText(FILE_REFERENCE + d.fileName()))
                    .orElse(property));
            } else {
                children.add(child);
            }
        }
        return new Extracted(properties.withChildren(children), documents);
    }

    /**
     * Puts {@code documents} back into the properties that refer to them.
     *
     * @throws IllegalArgumentException if a property refers to a file that is not given
     */
    public static Element embed(Element properties, List<Document> documents) {
        Map<String, Document> byFile = new HashMap<>();
        documents.forEach(document -> byFile.put(document.fileName(), document));
        Map<String, String> checksums = new HashMap<>();
        List<Node> children = new ArrayList<>();
        for (Node child : properties.children()) {
            if (child instanceof Element property && property.localName().equals("Property")
                && !property.hasElements() && property.text().startsWith(FILE_REFERENCE)) {
                String file = property.text().substring(FILE_REFERENCE.length());
                Document document = byFile.get(file);
                if (document == null) {
                    throw new IllegalArgumentException("The module refers to the missing file "
                        + file);
                }
                children.add(embedded(property, document));
                if (document.encoding() != Encoding.ESCAPED_XML) {
                    checksums.put(document.property() + "MD5", md5(document.content()));
                }
            } else {
                children.add(child);
            }
        }
        List<Node> withChecksums = new ArrayList<>();
        for (Node child : children) {
            if (child instanceof Element property && property.localName().equals("Property")
                && checksums.containsKey(property.attribute("name").orElse(""))
                && !property.hasElements()) {
                withChecksums.add(property.withText(checksums.get(
                    property.attribute("name").orElse(""))));
            } else {
                withChecksums.add(child);
            }
        }
        return properties.withChildren(withChecksums);
    }

    private static Element embedded(Element property, Document document) {
        byte[] content = document.content();
        return switch (document.encoding()) {
            case ESCAPED_XML -> property.withText(new String(content, StandardCharsets.UTF_8));
            case GZIP_BASE64 -> withSize(property, content)
                .withText(Base64.getEncoder().encodeToString(gzip(content)));
            case BASE64 -> withSize(property, content)
                .withText(Base64.getEncoder().encodeToString(content));
        };
    }

    private static Element withSize(Element property, byte[] content) {
        List<Attribute> attributes = new ArrayList<>();
        for (Attribute attribute : property.attributes()) {
            attributes.add(attribute.namespaceUri().isEmpty()
                && attribute.localName().equals("documentSize")
                ? new Attribute(attribute.prefix(), attribute.localName(),
                    attribute.namespaceUri(), String.valueOf(content.length))
                : attribute);
        }
        return property.withAttributes(attributes);
    }

    private static Optional<Document> document(Element property) {
        if (property.hasElements()) {
            return Optional.empty();
        }
        String name = property.attribute("name").orElse("");
        String type = property.attribute("type").orElse("");
        String value = property.text();
        if (value.isBlank() || SecretPlaceholder.isPlaceholder(value.strip())
            || value.startsWith(FILE_REFERENCE) || name.isEmpty()) {
            return Optional.empty();
        }
        boolean wsdl = name.equals("WsdlData") || name.equals("ValidWsdlData");
        if (type.equals("XmlDocument") || wsdl) {
            String extension = name.equals("xslt.stylesheet") ? "xsl" : wsdl ? "wsdl" : "xml";
            return Optional.of(new Document(name, fileName(name, extension),
                Encoding.ESCAPED_XML, value.getBytes(StandardCharsets.UTF_8)));
        }
        if (!type.equals("InternalDocument")) {
            return Optional.empty();
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        boolean gzipped = decoded.length > 2 && (decoded[0] & 0xff) == 0x1f
            && (decoded[1] & 0xff) == 0x8b;
        byte[] content = gzipped ? gunzip(decoded) : decoded;
        if (content == null) {
            return Optional.empty();
        }
        return Optional.of(new Document(name, fileName(name, extension(property)),
            gzipped ? Encoding.GZIP_BASE64 : Encoding.BASE64, content));
    }

    private static String extension(Element property) {
        String documentName = property.attribute("documentName").orElse("")
            .toLowerCase(Locale.ROOT);
        String contentType = property.attribute("documentContentType").orElse("")
            .toLowerCase(Locale.ROOT);
        if (documentName.endsWith(".xsd") || contentType.contains("xsd")) {
            return "xsd";
        }
        if (documentName.endsWith(".xml") || contentType.contains("xml")) {
            return "xml";
        }
        return "bin";
    }

    private static String fileName(String property, String extension) {
        return NameCodec.encode(property) + "." + extension;
    }

    private static byte[] gunzip(byte[] bytes) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] gzip(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
