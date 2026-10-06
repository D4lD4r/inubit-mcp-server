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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * person stays. A workflow's comment also has history segments separated by {@code ###}, which
 * INUBIT extends on every export (live acceptance): for workflows only the text before the first
 * {@code ###} stays, the segments go to {@link #CHECKIN_HISTORY}. The export time at its end
 * ({@code Export/Deployment: <time>@@@}) changes on every export and is not kept at all (SC-001: an unchanged re-export changes no file); a rebuild writes
 * a new one. Everything else — {@code CheckoutUser}, {@code IsActive}, layout,
 * {@code LastUpdate}, {@code ExportUser} — stays in the reviewed file. {@link #restore} puts
 * the values back. Callers add further records (archive properties, the enclosing XML context,
 * repository metadata).
 */
public final class MetaStore {

    /** The key of the export suffix of {@code CheckinComment}. */
    public static final String CHECKIN_SUFFIX = "CheckinComment.exportSuffix";
    /**
     * The key of the history segments of a workflow's {@code CheckinComment}: from the first
     * {@code ###} up to the export suffix. INUBIT appends to them on every export, so a record
     * that differs from the stored one only here is not rewritten (SC-001).
     */
    public static final String CHECKIN_HISTORY = "CheckinComment.history";
    private static final String HISTORY_SEPARATOR = "###";
    private static final String DEPLOYING_USER = "@@@Deploying User:";
    private static final Set<String> UIDS = Set.of("WorkflowUId", "ModuleUId");
    private static final String EXPORT_TIME = "Export/Deployment: ";
    private static final Pattern EXPORT_TIME_FIELD =
        Pattern.compile("Export/Deployment: ([^@]*)@@@$");

    private static final DefaultIndenter LF = new DefaultIndenter("  ", "\n");
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .defaultPrettyPrinter(new DefaultPrettyPrinter().withObjectIndenter(LF)
            .withArrayIndenter(LF))
        .build();

    /**
     * An element without its volatile values, those values (for {@code .meta/}) and the export
     * time (dropped).
     */
    public record Split(Element element, Map<String, Object> values,
        Optional<String> exportTime) {

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

    /**
     * The record in {@code json}.
     *
     * @throws IllegalArgumentException if it is not a JSON object
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deserialize(byte[] json) {
        try {
            return JSON.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("A record in .meta is not readable JSON");
        }
    }

    /** The bytes of a record: JSON with sorted keys, two-space indentation, LF, final LF. */
    public static byte[] serialize(Map<String, Object> values) {
        return (JSON.writeValueAsString(values) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The start of the history segments of a workflow comment: the first {@code ###} before the
     * export suffix, or -1.
     */
    private static int history(String comment) {
        int deploying = comment.indexOf(DEPLOYING_USER);
        int hashes = comment.indexOf(HISTORY_SEPARATOR);
        return hashes >= 0 && (deploying < 0 || hashes < deploying) ? hashes : -1;
    }

    /** Takes the volatile values out of a {@code Workflow} or index {@code Module} element. */
    public static Split split(Element element) {
        boolean workflow = element.localName().equals("Workflow");
        Map<String, Object> values = new LinkedHashMap<>();
        Optional<String> exportTime = Optional.empty();
        List<Node> children = new ArrayList<>();
        for (Node child : element.children()) {
            if (child instanceof Element e && UIDS.contains(e.localName())
                && !e.text().isEmpty()) {
                values.put(e.localName(), e.text());
                children.add(e.withText(""));
            } else if (child instanceof Element e && e.localName().equals("CheckinComment")
                && workflow && history(e.text()) >= 0) {
                // live acceptance: a workflow's history segments grow with every export
                String text = e.text();
                int start = history(text);
                int deploying = text.indexOf(DEPLOYING_USER, start);
                int end = deploying < 0 ? text.length() : deploying;
                values.put(CHECKIN_HISTORY, text.substring(start, end));
                if (deploying >= 0) {
                    String suffix = text.substring(deploying);
                    Matcher time = EXPORT_TIME_FIELD.matcher(suffix);
                    if (time.find()) {
                        exportTime = Optional.of(time.group(1));
                        suffix = suffix.substring(0, time.start());
                    }
                    values.put(CHECKIN_SUFFIX, suffix);
                }
                children.add(e.withText(text.substring(0, start)));
            } else if (child instanceof Element e && e.localName().equals("CheckinComment")
                && e.text().contains(DEPLOYING_USER)) {
                int at = e.text().indexOf(DEPLOYING_USER);
                String suffix = e.text().substring(at);
                Matcher time = EXPORT_TIME_FIELD.matcher(suffix);
                if (time.find()) {
                    exportTime = Optional.of(time.group(1));
                    suffix = suffix.substring(0, time.start());
                }
                values.put(CHECKIN_SUFFIX, suffix);
                children.add(e.withText(e.text().substring(0, at)));
            } else {
                children.add(child);
            }
        }
        return new Split(element.withChildren(children), values, exportTime);
    }

    /** Puts the values of {@link #split} back into {@code element}. */
    public static Element restore(Element element, Map<String, Object> values,
        Optional<String> exportTime) {
        List<Node> children = new ArrayList<>();
        for (Node child : element.children()) {
            if (child instanceof Element e && UIDS.contains(e.localName())
                && values.containsKey(e.localName())) {
                children.add(e.withText(String.valueOf(values.get(e.localName()))));
            } else if (child instanceof Element e && e.localName().equals("CheckinComment")
                && (values.containsKey(CHECKIN_SUFFIX) || values.containsKey(CHECKIN_HISTORY))) {
                children.add(e.withText(e.text() + values.getOrDefault(CHECKIN_HISTORY, "")
                    + (values.containsKey(CHECKIN_SUFFIX) ? values.get(CHECKIN_SUFFIX)
                        + exportTime.map(time -> EXPORT_TIME + time + "@@@").orElse("") : "")));
            } else {
                children.add(child);
            }
        }
        return element.withChildren(children);
    }
}
