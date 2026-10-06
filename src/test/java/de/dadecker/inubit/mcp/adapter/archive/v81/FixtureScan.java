package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Text;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Everything a fixture export contains, for the guard tests (review of stage 1): the raw texts of
 * all ZIP entries, nested {@code Repository.zip} entries and decoded {@code InternalDocument}
 * values ({@link #sources()}), and every XML document among them, including XML embedded as
 * escaped text in property values, literals and inline stylesheets ({@link #documents()}).
 */
final class FixtureScan {

    /** A raw text and where it came from. */
    record Source(String location, String text) {
    }

    /** A parsed XML document and where it came from. */
    record Doc(String location, XmlTree.Document document) {
    }

    private static final int MAX_DEPTH = 3;

    private final List<Source> sources = new ArrayList<>();
    private final List<Doc> documents = new ArrayList<>();

    private FixtureScan() {
    }

    static FixtureScan of(String fixture) {
        FixtureScan scan = new FixtureScan();
        scan.zip(ArtifactFixtures.bytes(fixture), fixture);
        return scan;
    }

    List<Source> sources() {
        return List.copyOf(sources);
    }

    List<Doc> documents() {
        return List.copyOf(documents);
    }

    /** Every element of {@code document}, depth first. */
    static void elements(Element element, Consumer<Element> action) {
        action.accept(element);
        for (Node child : element.children()) {
            if (child instanceof Element e) {
                elements(e, action);
            }
        }
    }

    /** The text of an element without element children, or empty if it has some. */
    static Optional<String> value(Element element) {
        StringBuilder text = new StringBuilder();
        for (Node child : element.children()) {
            if (child instanceof Element) {
                return Optional.empty();
            }
            if (child instanceof Text t) {
                text.append(t.value());
            }
        }
        return Optional.of(text.toString());
    }

    static Optional<String> attribute(Element element, String name) {
        return element.attributes().stream().filter(a -> a.qualifiedName().equals(name))
            .map(XmlTree.Attribute::value).findFirst();
    }

    private void zip(byte[] bytes, String location) {
        ArtifactFixtures.entries(bytes).forEach((name, data) -> {
            if (name.endsWith(".zip")) {
                zip(data, location + "!" + name);
            } else if (!name.endsWith("/")) {
                source(location + "!" + name, new String(data, StandardCharsets.UTF_8), 0);
            }
        });
    }

    private void source(String location, String text, int depth) {
        sources.add(new Source(location, text));
        parse(location, text, depth);
    }

    private void parse(String location, String text, int depth) {
        XmlTree.Document document;
        try {
            document = XmlTree.parse(text.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return; // not XML (e.g. archive.properties, a JSON schema)
        }
        documents.add(new Doc(location, document));
        elements(document.root(), element -> value(element).ifPresent(value -> {
            String at = location + "#" + attribute(element, "name").orElse(element.localName());
            if (attribute(element, "type").filter("InternalDocument"::equals).isPresent()
                && !value.isBlank()) {
                source(at, gunzip(value.strip()), depth + 1);
            } else if (value.strip().startsWith("<") && depth < MAX_DEPTH) {
                parse(at, value, depth + 1);
            }
        }));
    }

    private static String gunzip(String base64) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(
            Base64.getMimeDecoder().decode(base64)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
