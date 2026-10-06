package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The volatile values of the workspace files (research D-6, FR-013, FR-014): JSON records with
 * sorted keys at {@code .meta/<same relative path>.json} ({@link WorkspacePath#metaPath()}).
 *
 * <p>{@link #split} takes the volatile values out of a {@code Workflow} or index {@code Module}
 * element: the text of {@code WorkflowUId}/{@code ModuleUId} (the element stays, empty, so that
 * its place is kept) and the export suffix of {@code CheckinComment} — from the first
 * {@code @@@Deploying User:} to the end, written by INUBIT at export time; the part written by a
 * person stays. Everything else — {@code CheckoutUser}, {@code IsActive}, layout,
 * {@code LastUpdate}, {@code ExportUser} — stays in the reviewed file. {@link #restore} puts
 * the values back. Callers add further records (archive properties, the enclosing XML context,
 * repository metadata).
 */
public final class MetaStore {

    /** The key of the export suffix of {@code CheckinComment}. */
    public static final String CHECKIN_SUFFIX = "CheckinComment.exportSuffix";
    private static final String DEPLOYING_USER = "@@@Deploying User:";
    private static final Set<String> UIDS = Set.of("WorkflowUId", "ModuleUId");

    private static final DefaultIndenter LF = new DefaultIndenter("  ", "\n");
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .defaultPrettyPrinter(new DefaultPrettyPrinter().withObjectIndenter(LF)
            .withArrayIndenter(LF))
        .build();

    /** An element without its volatile values, and those values. */
    public record Split(Element element, Map<String, Object> values) {

        public Split {
            Objects.requireNonNull(element, "element");
            values = Map.copyOf(values);
        }
    }

    private final Path root;

    /** @param root the workspace root */
    public MetaStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** Writes the record of {@code path} (creating its directories). */
    public void write(WorkspacePath path, Map<String, Object> values) {
        Path file = root.resolve(path.metaPath());
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, serialize(values));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The record of {@code path}, if there is one.
     *
     * @throws IllegalArgumentException if it is not a JSON object
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> read(WorkspacePath path) {
        Path file = root.resolve(path.metaPath());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(Files.readAllBytes(file), Map.class));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("The record " + path.metaPath()
                + " in .meta is not readable JSON");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The bytes of a record: JSON with sorted keys, two-space indentation, LF, final LF. */
    public static byte[] serialize(Map<String, Object> values) {
        return (JSON.writeValueAsString(values) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Takes the volatile values out of a {@code Workflow} or index {@code Module} element. */
    public static Split split(Element element) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<Node> children = new ArrayList<>();
        for (Node child : element.children()) {
            if (child instanceof Element e && UIDS.contains(e.localName())
                && !e.text().isEmpty()) {
                values.put(e.localName(), e.text());
                children.add(e.withText(""));
            } else if (child instanceof Element e && e.localName().equals("CheckinComment")
                && e.text().contains(DEPLOYING_USER)) {
                int at = e.text().indexOf(DEPLOYING_USER);
                values.put(CHECKIN_SUFFIX, e.text().substring(at));
                children.add(e.withText(e.text().substring(0, at)));
            } else {
                children.add(child);
            }
        }
        return new Split(element.withChildren(children), values);
    }

    /** Puts the values of {@link #split} back into {@code element}. */
    public static Element restore(Element element, Map<String, Object> values) {
        List<Node> children = new ArrayList<>();
        for (Node child : element.children()) {
            if (child instanceof Element e && UIDS.contains(e.localName())
                && values.containsKey(e.localName())) {
                children.add(e.withText(String.valueOf(values.get(e.localName()))));
            } else if (child instanceof Element e && e.localName().equals("CheckinComment")
                && values.containsKey(CHECKIN_SUFFIX)) {
                children.add(e.withText(e.text() + values.get(CHECKIN_SUFFIX)));
            } else {
                children.add(child);
            }
        }
        return element.withChildren(children);
    }
}
