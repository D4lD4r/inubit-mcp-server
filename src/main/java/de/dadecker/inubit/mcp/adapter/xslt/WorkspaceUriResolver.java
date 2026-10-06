package de.dadecker.inubit.mcp.adapter.xslt;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.sax.SAXSource;
import net.sf.saxon.Configuration;
import net.sf.saxon.lib.UnparsedTextURIResolver;
import net.sf.saxon.trans.XPathException;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Resolves every document a stylesheet run reads — {@code xsl:import}/{@code xsl:include},
 * {@code document()}/{@code doc()}, {@code unparsed-text()} — inside the workspace only (FR-032,
 * FR-033):
 *
 * <ul>
 *   <li>{@code inubitrepository:/<path>} → {@code <group>/<owner>/repository/<path>} of the
 *       stylesheet's group and owner ({@code %XX} escapes decoded, the names stored like the
 *       workspace writer stores them);
 *   <li>other references must be {@code file:} URIs (also relative ones, resolved against the
 *       referring document) of existing files whose real path is inside the workspace;
 *   <li>everything else (other schemes, network, files outside, symbolic links leaving the
 *       workspace) is refused.
 * </ul>
 *
 * <p>XML is parsed without document type declarations (no external entities or DTDs).
 */
public final class WorkspaceUriResolver implements URIResolver, UnparsedTextURIResolver {

    static final String REPOSITORY_SCHEME = "inubitrepository:";

    private final Path root;
    private final Path realRoot;
    private final GroupId group;
    private final String owner;

    /**
     * @param root  the workspace root
     * @param group the group of the stylesheet ({@code inubitrepository:} references)
     * @param owner the owner of the stylesheet
     */
    public WorkspaceUriResolver(Path root, GroupId group, String owner) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        try {
            this.realRoot = this.root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("The workspace " + root + " does not exist");
        }
        this.group = group;
        this.owner = owner;
    }

    @Override
    public Source resolve(String href, String base) throws TransformerException {
        return source(file(href, base));
    }

    @Override
    public Reader resolve(URI absoluteURI, String encoding, Configuration config)
        throws XPathException {
        try {
            Path file = file(absoluteURI.toString(), null);
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new InputStreamReader(Files.newInputStream(file), charset);
        } catch (TransformerException | IOException | IllegalArgumentException e) {
            throw new XPathException(e.getMessage(), "FOUT1170");
        }
    }

    /** A secure source for {@code file} inside the workspace. */
    Source source(Path file) throws TransformerException {
        InputSource input = new InputSource(file.toUri().toString());
        return new SAXSource(secureReader(), input);
    }

    /** The workspace file {@code href} (relative to {@code base}) names; refused otherwise. */
    Path file(String href, String base) throws TransformerException {
        if (href.startsWith(REPOSITORY_SCHEME)) {
            if (group == null || owner == null) {
                throw refused(href, "the stylesheet belongs to no group and owner");
            }
            List<String> segments = Stream.of(href.substring(REPOSITORY_SCHEME.length())
                .replaceFirst("^/+", "").split("/")).map(WorkspaceUriResolver::unescape).toList();
            if (segments.stream().anyMatch(s -> s.isEmpty() || s.equals("..") || s.equals("."))) {
                throw refused(href, "not a repository path");
            }
            return inside(href, root.resolve(new WorkspacePath(group, owner,
                WorkspacePath.Kind.REPOSITORY, segments).toRelativePath()));
        }
        URI uri;
        try {
            URI reference = uri(href);
            uri = reference.isAbsolute() || base == null || base.isEmpty() ? reference
                : new URI(base).resolve(reference);
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw refused(href, "not a valid URI");
        }
        if (!"file".equals(uri.getScheme())) {
            throw refused(href, "only files of the workspace can be read");
        }
        return inside(href, Path.of(uri));
    }

    /** {@code %XX} escapes decoded as UTF-8 (a {@code +} stays a plus). */
    static String unescape(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        try {
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }

    /** {@code href} as URI; a reference with spaces is encoded as a path. */
    private static URI uri(String href) throws URISyntaxException {
        try {
            return new URI(href);
        } catch (URISyntaxException e) {
            return new URI(null, null, href, null);
        }
    }

    /** The real path of {@code file}, which is what is read (review M1: no check-then-use gap). */
    private Path inside(String href, Path file) throws TransformerException {
        Path real;
        try {
            real = file.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw refused(href, "not a readable file of the workspace");
        }
        if (!Files.isRegularFile(real) || !real.startsWith(realRoot)) {
            throw refused(href, "not a file of the workspace");
        }
        return real;
    }

    private static TransformerException refused(String href, String reason) {
        return new TransformerException("The reference " + href + " is refused: " + reason);
    }

    /** An XML reader that refuses document type declarations and external entities. */
    static XMLReader secureReader() throws TransformerException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are not resolved: " + systemId);
            });
            return reader;
        } catch (ParserConfigurationException | SAXException e) {
            throw new TransformerException("No secure XML parser: " + e.getMessage());
        }
    }
}
