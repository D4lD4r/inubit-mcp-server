package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Text;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference.Kind;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;

/**
 * The repository files a workspace file references (research D-3), as INUBIT and the XSLT and
 * schema resolvers see them:
 *
 * <ul>
 *   <li>In a stylesheet or schema (root element in the XSLT or XML Schema namespace) only the
 *       {@code href} of {@code xsl:import}/{@code xsl:include} and the {@code schemaLocation} of
 *       {@code xs:import}/{@code xs:include}/{@code xs:redefine} count. A URI in a string literal,
 *       e.g. in an {@code xsl:variable}, is no reference (spike section 10).
 *   <li>In any other XML document (workflow, {@code module.xml}, WSDL) every
 *       {@code inubitrepository:} URI in an attribute or text counts, plus those import elements.
 *       A text that is itself an XML document (an escaped inline stylesheet) is scanned as one; a
 *       text that does not parse is searched for URIs.
 *   <li>{@code inubitrepository:/<path>} gives {@link Kind#URI} {@code /<path>}. In a repository
 *       file a relative location (no scheme, no leading {@code /}) gives {@link Kind#RELATIVE},
 *       resolved against the file's own repository path; one that climbs above the repository
 *       root is none.
 *   <li>In a module index entry or {@code module.xml} the value of an
 *       {@code InputMessageTypeSchema} or {@code OutputMessageTypeSchema} element, a repository
 *       path without a scheme ({@code /Root/<owner>/…}), gives {@link Kind#URI} too: INUBIT's
 *       export carries such a schema like an import (research D-3, decided 2026-10-14). A value
 *       without a leading {@code /} is none.
 * </ul>
 *
 * <p>{@code %XX} escapes are decoded per path segment as UTF-8; a {@code +} stays a plus. A
 * segment that decodes to a {@code /} is no reference: it would hide a path separator from the
 * closure. Content that is not well-formed XML has no references.
 *
 * <p>{@link #uriPaths} is the second mode, for {@code check_artifacts}: every
 * {@code inubitrepository:} URI of a text, structural or not.
 */
final class RepositoryReferences {

    /** {@code inubitrepository:/Root/…} up to a quote, bracket, ampersand or white space. */
    private static final Pattern REPOSITORY_URI =
        Pattern.compile("inubitrepository:/+([^\"'<>&\\s]+)");
    private static final String XSLT_NAMESPACE = "http://www.w3.org/1999/XSL/Transform";
    private static final Set<String> XSLT_IMPORTS = Set.of("import", "include");
    private static final Set<String> SCHEMA_IMPORTS = Set.of("import", "include", "redefine");
    private static final String SCHEME = "inubitrepository:";
    private static final Pattern OTHER_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final Set<String> MESSAGE_TYPE_SCHEMAS =
        Set.of("InputMessageTypeSchema", "OutputMessageTypeSchema");

    private RepositoryReferences() {
    }

    /** The references of {@code content}, the content of {@code from}; each once, in order. */
    static List<RepositoryReference> scan(WorkspacePath from, byte[] content) {
        Set<RepositoryReference> references = new LinkedHashSet<>();
        try {
            document(from, XmlTree.parse(content).root(), references);
        } catch (IllegalArgumentException e) {
            return List.of(); // not well-formed XML
        }
        return List.copyOf(references);
    }

    /**
     * The repository paths of every {@code inubitrepository:} URI in {@code text}, wherever it
     * stands (also in a string literal, an escaped stylesheet or text that is no XML); each once,
     * in order. The wider coverage of {@code check_artifacts} (research D-3).
     */
    static List<String> uriPaths(String text) {
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = REPOSITORY_URI.matcher(text);
        while (matcher.find()) {
            Optional.ofNullable(path(matcher.group(1))).ifPresent(paths::add);
        }
        return List.copyOf(paths);
    }

    /**
     * {@code /} plus the segments of {@code path} (without leading {@code /}), each
     * {@link #unescape unescaped}; {@code null} if a segment decodes to a {@code /}.
     */
    private static String path(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            segments.add(unescape(segment));
        }
        return segments.contains(null) ? null : "/" + String.join("/", segments);
    }

    private static void document(WorkspacePath from, Element root,
        Set<RepositoryReference> references) {
        boolean importsOnly = root.namespaceUri().equals(XSLT_NAMESPACE)
            || root.namespaceUri().equals(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        walk(from, root, importsOnly, references);
    }

    private static void walk(WorkspacePath from, Element element, boolean importsOnly,
        Set<RepositoryReference> references) {
        importLocation(element).ifPresent(location -> location(from, location, references));
        messageTypeSchema(from, element).ifPresent(path ->
            references.add(new RepositoryReference(from, path, Kind.URI)));
        if (!importsOnly) {
            for (Attribute attribute : element.attributes()) {
                uris(from, attribute.value(), references);
            }
        }
        for (Node child : element.children()) {
            if (child instanceof Element e) {
                walk(from, e, importsOnly, references);
            } else if (!importsOnly && child instanceof Text text) {
                text(from, text.value(), references);
            }
        }
    }

    /** The {@code href}/{@code schemaLocation} of an XSLT or schema import element. */
    private static Optional<String> importLocation(Element element) {
        if (element.namespaceUri().equals(XSLT_NAMESPACE)
            && XSLT_IMPORTS.contains(element.localName())) {
            return element.attribute("href").map(String::strip);
        }
        if (element.namespaceUri().equals(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            && SCHEMA_IMPORTS.contains(element.localName())) {
            return element.attribute("schemaLocation").map(String::strip);
        }
        return Optional.empty();
    }

    /** The decoded path of a message-type schema element of a module file, if it is one. */
    private static Optional<String> messageTypeSchema(WorkspacePath from, Element element) {
        if ((from.kind() != WorkspacePath.Kind.MODULE_INDEX
            && from.kind() != WorkspacePath.Kind.MODULE)
            || !MESSAGE_TYPE_SCHEMAS.contains(element.localName())) {
            return Optional.empty();
        }
        String value = element.text().strip();
        String path = value.replaceFirst("^/+", "");
        return value.startsWith("/") && !path.isEmpty()
            ? Optional.ofNullable(path(path)) : Optional.empty();
    }

    private static void location(WorkspacePath from, String location,
        Set<RepositoryReference> references) {
        if (location.startsWith(SCHEME)) {
            uris(from, location, references);
        } else if (from.kind() == WorkspacePath.Kind.REPOSITORY && !location.isEmpty()
            && !location.startsWith("/") && !OTHER_SCHEME.matcher(location).find()) {
            List<String> path = new ArrayList<>(from.segments().subList(0,
                from.segments().size() - 1));
            for (String segment : location.split("/")) {
                if (segment.equals("..")) {
                    if (path.isEmpty()) {
                        return; // above the repository root
                    }
                    path.remove(path.size() - 1);
                } else if (!segment.isEmpty() && !segment.equals(".")) {
                    String name = unescape(segment);
                    if (name == null) {
                        return;
                    }
                    path.add(name);
                }
            }
            if (!path.isEmpty()) {
                references.add(new RepositoryReference(from, "/" + String.join("/", path),
                    Kind.RELATIVE));
            }
        }
    }

    private static void text(WorkspacePath from, String text,
        Set<RepositoryReference> references) {
        if (text.strip().startsWith("<")) {
            try {
                document(from, XmlTree.parse(text.strip().getBytes(StandardCharsets.UTF_8))
                    .root(), references);
                return;
            } catch (IllegalArgumentException e) {
                // a fragment: search it as text
            }
        }
        uris(from, text, references);
    }

    private static void uris(WorkspacePath from, String text,
        Set<RepositoryReference> references) {
        for (String path : uriPaths(text)) {
            references.add(new RepositoryReference(from, path, Kind.URI));
        }
    }

    /**
     * {@code %XX} escapes decoded as UTF-8 (a {@code +} stays a plus); unchanged if invalid;
     * {@code null} if the decoded segment holds a {@code /}.
     */
    private static String unescape(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
        return decoded.indexOf('/') < 0 ? decoded : null;
    }
}
