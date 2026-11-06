package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What INUBIT 8.1.17 rewrites when it imports a module (issue #55): an {@code xslt.transformer}
 * in {@code module.xml} that names the plain Saxon factory
 * ({@code net.sf.saxon.TransformerFactoryImpl}) is stored as INUBIT's Saxon-EE factory, a class
 * of {@code com.saxonica.config}. Only this direction; a module that names a factory of
 * {@code com.saxonica.config} keeps it.
 */
final class SaxonFactoryRewrite {

    private static final String TRANSFORMER = "xslt.transformer";
    private static final String PLAIN_SAXON_FACTORY = "net.sf.saxon.TransformerFactoryImpl";
    private static final String SAXONICA_FACTORIES = "com.saxonica.config.";

    private SaxonFactoryRewrite() {
    }

    /**
     * {@code sent} (normalized) with the factory of {@code server}, if {@code path} is a
     * {@code module.xml}, {@code sent} names the plain Saxon factory and {@code server} a factory
     * of {@code com.saxonica.config}; otherwise empty (also for files that cannot be read).
     */
    static Optional<byte[]> asImported(String path, byte[] sent, byte[] server) {
        if (sent == null || server == null || !path.endsWith("/module.xml")) {
            return Optional.empty();
        }
        try {
            Element intended = XmlTree.parse(sent).root();
            Optional<String> rewritten = transformer(XmlTree.parse(server).root())
                .filter(factory -> factory.startsWith(SAXONICA_FACTORIES));
            if (rewritten.isEmpty()
                || transformer(intended).filter(PLAIN_SAXON_FACTORY::equals).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(XmlNormalizer.normalize(withTransformer(intended,
                rewritten.get())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The value of the {@code xslt.transformer} property of a module's properties. */
    private static Optional<String> transformer(Element properties) {
        return properties.elements().stream().filter(SaxonFactoryRewrite::isTransformer)
            .map(Element::text).map(String::strip).findFirst();
    }

    private static boolean isTransformer(Element property) {
        return property.localName().equals("Property")
            && property.attribute("name").filter(TRANSFORMER::equals).isPresent();
    }

    private static Element withTransformer(Element properties, String factory) {
        List<Node> children = new ArrayList<>();
        properties.children().forEach(child -> children.add(child instanceof Element property
            && isTransformer(property) ? property.withText(factory) : child));
        return properties.withChildren(children);
    }
}
