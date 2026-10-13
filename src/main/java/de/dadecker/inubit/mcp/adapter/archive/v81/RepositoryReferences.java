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
 * </ul>
 *
 * <p>{@code %XX} escapes are decoded per path segment as UTF-8; a {@code +} stays a plus. Content
 * that is not well-formed XML has no references.
 */
final class RepositoryReferences {

    private static final String XSLT_NAMESPACE = "http://www.w3.org/1999/XSL/Transform";
    private static final Set<String> XSLT_IMPORTS = Set.of("import", "include");
    private static final Set<String> SCHEMA_IMPORTS = Set.of("import", "include", "redefine");
    private static final String SCHEME = "inubitrepository:";
    private static final Pattern OTHER_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

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

    private static void document(WorkspacePath from, Element root,
        Set<RepositoryReference> references) {
        boolean importsOnly = root.namespaceUri().equals(XSLT_NAMESPACE)
            || root.namespaceUri().equals(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        walk(from, root, importsOnly, references);
    }

    private static void walk(WorkspacePath from, Element element, boolean importsOnly,
        Set<RepositoryReference> references) {
        importLocation(element).ifPresent(location -> location(from, location, references));
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
                    path.add(unescape(segment));
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
        Matcher matcher = WorkspaceInspector.REPOSITORY_REFERENCE.matcher(text);
        while (matcher.find()) {
            List<String> segments = new ArrayList<>();
            for (String segment : matcher.group(1).split("/")) {
                segments.add(unescape(segment));
            }
            references.add(new RepositoryReference(from, "/" + String.join("/", segments),
                Kind.URI));
        }
    }

    /** {@code %XX} escapes decoded as UTF-8 (a {@code +} stays a plus); unchanged if invalid. */
    private static String unescape(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        try {
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }
}
