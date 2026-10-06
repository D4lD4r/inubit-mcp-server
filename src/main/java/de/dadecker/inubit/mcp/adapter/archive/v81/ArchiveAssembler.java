package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Document;
import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Encoding;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleIndexEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.RepositoryFile;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowContext;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowGroupXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Text;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Rebuilds an INUBIT 8.1 export archive from the workspace and {@code .meta/} (research D-4,
 * FR-015): the inverse of {@link WorkspaceWriter}. Feature 003 uses it in the round-trip tests;
 * feature 004 will import what it builds.
 *
 * <ul>
 *   <li>The entries and their order come from the export record of the diagram group or module
 *       ({@code .meta/<group>/<owner>/exports/…}); workflows, index entries and module files from
 *       the workspace files with their meta records (UIDs, check-in suffix plus the given export
 *       time, context and positions, embedded documents re-embedded by
 *       {@link EmbeddedDocuments}); repository files that the artifacts reference, with
 *       {@code contentSize}/{@code contentMD5} recomputed from the content;
 *       {@code archive.properties} with the recorded {@code sourceVersion} and a new
 *       {@code operationId}.
 *   <li>Everything passes through {@link SecretRedactor} again, so placeholders stay
 *       placeholders and nothing unredacted is built; {@link #entries} serializes a
 *       {@link RedactedArchive}.
 * </ul>
 */
public final class ArchiveAssembler {

    private static final String PROPERTIES = "archive.properties";
    private static final String REPOSITORY = "Repository.zip";
    private static final String WORKFLOWS = "workflow/workflow.xml";
    private static final String MODULE_INDEX = "module/module.xml";

    private ArchiveAssembler() {
    }

    /** The ZIP of the diagram group export {@code diagramGroup} rebuilt from the workspace. */
    public static byte[] assembleDiagramGroup(Path root, GroupId group, String owner,
        String diagramGroup, String exportTime) {
        Map<String, Object> record = record(root, WorkspaceWriter.exportRecord(group, owner,
            "workflows", diagramGroup));
        Path directory = root.resolve(WorkspacePath.workflow(group, owner, diagramGroup, "x")
            .toRelativePath().getParent());
        MetaStore meta = new MetaStore(root);
        List<WorkflowXml> workflows = new ArrayList<>();
        List<Attribute> groupAttributes = List.of();
        for (Path file : files(directory)) {
            WorkspacePath path = WorkspacePath.parse(root.relativize(file));
            Map<String, Object> values = meta.read(path).orElse(Map.of());
            Map<String, Object> context = map(values.get("context"));
            groupAttributes = attributes(context.get("groupAttributes"));
            Element element = MetaStore.restore(parse(file), values, Optional.of(exportTime));
            workflows.add(new WorkflowXml(path.segments().get(1), diagramGroup, element,
                new WorkflowContext(String.valueOf(context.getOrDefault("documentVersion",
                    "5.3")), groupAttributes, integer(context.get("groupPosition")),
                    integer(context.get("position")))));
        }
        workflows.sort(Comparator.comparingInt(w -> w.context().position()));
        List<WorkflowGroupXml> groups = List.of(new WorkflowGroupXml(diagramGroup,
            groupAttributes, workflows));
        return assemble(root, group, owner, record, groups, exportTime);
    }

    /** The ZIP of the module-only export of {@code module} rebuilt from the workspace. */
    public static byte[] assembleModule(Path root, GroupId group, String owner,
        String pluginType, String module, String exportTime) {
        Map<String, Object> record = record(root, WorkspaceWriter.exportRecord(group, owner,
            "modules", pluginType + "/" + module));
        return assemble(root, group, owner, record, List.of(), exportTime);
    }

    private static byte[] assemble(Path root, GroupId group, String owner,
        Map<String, Object> record, List<WorkflowGroupXml> groups, String exportTime) {
        List<String> entries = strings(record.get("entries"));
        Map<String, Path> moduleDirectories = moduleDirectories(root, group, owner);
        MetaStore meta = new MetaStore(root);
        List<ModuleIndexEntry> index = new ArrayList<>();
        Map<String, ModuleXml> modules = new LinkedHashMap<>();
        List<String> texts = new ArrayList<>();
        groups.forEach(g -> g.workflows().forEach(w -> texts.add(text(w.element()))));
        for (String entry : entries) {
            if (!entry.startsWith("module/") || entry.equals(MODULE_INDEX) || entry.endsWith("/")) {
                continue;
            }
            Path directory = moduleDirectories.get(entry);
            if (directory == null) {
                throw missing("The module file " + entry + " of the export record is not in the"
                    + " workspace");
            }
            WorkspacePath indexPath = WorkspacePath.parse(root.relativize(
                directory.resolve("index.xml")));
            String pluginType = indexPath.segments().get(0);
            String name = indexPath.segments().get(1);
            Map<String, Object> indexMeta = meta.read(indexPath).orElse(Map.of());
            Element indexElement = MetaStore.restore(parse(directory.resolve("index.xml")),
                indexMeta, Optional.of(exportTime));
            Map<String, Object> context = map(indexMeta.get("context"));
            for (Object position : list(context.get("positions"))) {
                Map<String, Object> at = map(position);
                index.add(new ModuleIndexEntry(name, pluginType, indexElement,
                    String.valueOf(context.getOrDefault("documentVersion", "5.3")),
                    integer(at.get("groupPosition")), integer(at.get("position"))));
            }
            WorkspacePath modulePath = WorkspacePath.module(group, owner, pluginType, name);
            Map<String, Object> moduleMeta = meta.read(modulePath).orElse(Map.of());
            List<Document> documents = new ArrayList<>();
            map(moduleMeta.get("embedded")).forEach((fileName, value) -> {
                Map<String, Object> embedded = map(value);
                documents.add(new Document(String.valueOf(embedded.get("property")), fileName,
                    Encoding.valueOf(String.valueOf(embedded.get("encoding"))),
                    read(directory.resolve(fileName))));
                texts.add(new String(read(directory.resolve(fileName)),
                    StandardCharsets.UTF_8));
            });
            Element properties = parse(directory.resolve("module.xml"));
            texts.add(text(properties));
            modules.put(name, new ModuleXml(name, pluginType, String.valueOf(moduleMeta
                .getOrDefault("entryName", entry)), EmbeddedDocuments.embed(properties,
                    documents)));
        }
        index.sort(Comparator.comparingInt(ModuleIndexEntry::groupPosition)
            .thenComparingInt(ModuleIndexEntry::position));
        Map<String, RepositoryFile> repository = repository(root, group, owner, texts);
        Map<String, String> properties = new TreeMap<>();
        properties.put("sourceVersion", String.valueOf(record.getOrDefault("sourceVersion", "")));
        properties.put("operationId", UUID.randomUUID().toString());
        RedactedArchive archive = new SecretRedactor().redact(new ExportArchive(properties,
            entries, groups, index, modules, repository));
        return zip(entries(archive), entries);
    }

    /** The archive entries of {@code archive}, in its entry order. */
    public static Map<String, byte[]> entries(RedactedArchive archive) {
        ExportArchive export = archive.archive();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : export.entries()) {
            if (name.endsWith("/")) {
                entries.put(name, new byte[0]);
            } else if (name.equals(PROPERTIES)) {
                StringBuilder text = new StringBuilder("#\n");
                export.properties().forEach((key, value) -> text.append(key).append('=')
                    .append(value).append('\n'));
                entries.put(name, text.toString().getBytes(StandardCharsets.ISO_8859_1));
            } else if (name.equals(REPOSITORY)) {
                entries.put(name, repositoryZip(export.repository()));
            } else if (name.equals(WORKFLOWS)) {
                entries.put(name, XmlNormalizer.normalize(workflowDocument(archive)));
            } else if (name.equals(MODULE_INDEX)) {
                entries.put(name, XmlNormalizer.normalize(indexDocument(archive)));
            } else {
                export.moduleFiles().values().stream()
                    .filter(module -> module.entryName().equals(name)).findFirst()
                    .ifPresent(module -> entries.put(name,
                        XmlNormalizer.normalize(module.element())));
            }
        }
        return entries;
    }

    private static Element workflowDocument(RedactedArchive archive) {
        ExportArchive export = archive.archive();
        String version = export.workflowGroups().stream().flatMap(g -> g.workflows().stream())
            .map(w -> w.context().documentVersion()).findFirst().orElse("5.3");
        List<Node> groups = new ArrayList<>();
        for (WorkflowGroupXml group : export.workflowGroups()) {
            List<Node> children = new ArrayList<>();
            children.add(element("WorkflowGroupName", List.of(), List.of(new Text(
                group.name()))));
            group.workflows().forEach(workflow -> children.add(workflow.element()));
            groups.add(element("WorkflowGroup", group.attributes(), children));
        }
        return element("IBISWorkflow", List.of(new Attribute("", "version", "", version)),
            List.of(element("Workflows", List.of(), groups)));
    }

    private static Element indexDocument(RedactedArchive archive) {
        ExportArchive export = archive.archive();
        String version = export.moduleIndex().stream().map(ModuleIndexEntry::documentVersion)
            .findFirst().orElse("5.3");
        Map<Integer, List<ModuleIndexEntry>> byGroup = new TreeMap<>();
        export.moduleIndex().forEach(entry -> byGroup.computeIfAbsent(entry.groupPosition(),
            position -> new ArrayList<>()).add(entry));
        List<Node> groups = new ArrayList<>();
        for (List<ModuleIndexEntry> entries : byGroup.values()) {
            entries.sort(Comparator.comparingInt(ModuleIndexEntry::position));
            List<Node> children = new ArrayList<>();
            children.add(element("ModuleGroupName", List.of(), List.of(new Text(
                entries.get(0).pluginType()))));
            entries.forEach(entry -> children.add(entry.element()));
            groups.add(element("ModuleGroup", List.of(), children));
        }
        return element("IBISWorkflow", List.of(new Attribute("", "version", "", version)),
            List.of(element("Modules", List.of(), groups)));
    }

    private static Element element(String name, List<Attribute> attributes,
        List<? extends Node> children) {
        return new Element("", name, "", List.of(), attributes, List.copyOf(children));
    }

    private static byte[] repositoryZip(Map<String, RepositoryFile> repository) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (RepositoryFile file : repository.values()) {
            file.metadata().ifPresent(metadata -> entries.put(file.path() + ".xml",
                XmlNormalizer.normalize(metadata)));
            entries.put(file.path() + ".dat", file.content());
        }
        return zip(entries, List.copyOf(entries.keySet()));
    }

    private static Map<String, RepositoryFile> repository(Path root, GroupId group,
        String owner, List<String> texts) {
        Path directory = root.resolve(WorkspacePath.repository(group, owner, "x")
            .toRelativePath().getParent());
        MetaStore meta = new MetaStore(root);
        Map<String, RepositoryFile> files = new LinkedHashMap<>();
        for (Path file : files(directory)) {
            WorkspacePath path = WorkspacePath.parse(root.relativize(file));
            String repositoryPath = String.join("/", path.segments());
            if (texts.stream().noneMatch(text -> text.contains("/" + repositoryPath))) {
                continue;
            }
            byte[] content = read(file);
            Optional<Element> metadata = Optional.ofNullable(meta.read(path).orElse(Map.of())
                .get("metadata")).map(String::valueOf)
                .map(xml -> withContentValues(XmlTree.parse(xml.getBytes(
                    StandardCharsets.UTF_8)).root(), content));
            files.put(repositoryPath, new RepositoryFile(repositoryPath, content, metadata));
        }
        return files;
    }

    /** {@code contentSize} and {@code contentMD5} describe {@code content}. */
    private static Element withContentValues(Element metadata, byte[] content) {
        List<Attribute> attributes = new ArrayList<>();
        for (Attribute attribute : metadata.attributes()) {
            String value = switch (attribute.qualifiedName()) {
                case "contentSize" -> String.valueOf(content.length);
                case "contentMD5" -> md5(content);
                default -> attribute.value();
            };
            attributes.add(new Attribute(attribute.prefix(), attribute.localName(),
                attribute.namespaceUri(), value));
        }
        return metadata.withAttributes(attributes);
    }

    /** {@code module/<lower-case name>.xml} → the module directory in the workspace. */
    private static Map<String, Path> moduleDirectories(Path root, GroupId group, String owner) {
        Path modules = root.resolve(group.value()).resolve(NameCodec.encode(owner))
            .resolve("modules");
        Map<String, Path> directories = new LinkedHashMap<>();
        for (Path file : files(modules)) {
            if (file.getFileName().toString().equals("index.xml")) {
                WorkspacePath path = WorkspacePath.parse(root.relativize(file));
                directories.put("module/" + path.segments().get(1).toLowerCase(Locale.ROOT)
                    + ".xml", file.getParent());
            }
        }
        return directories;
    }

    private static Map<String, Object> record(Path root, String relative) {
        Path file = root.resolve(relative);
        if (!Files.isRegularFile(file)) {
            throw missing("No export record " + relative + " in the workspace");
        }
        return MetaStore.deserialize(read(file));
    }

    private static List<Path> files(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Element parse(Path file) {
        return XmlTree.parse(read(file)).root();
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(Element element) {
        return new String(XmlNormalizer.normalize(element), StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> entries, List<String> order) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String name : order) {
                byte[] data = entries.get(name);
                if (data == null) {
                    continue;
                }
                out.putNextEntry(new ZipEntry(name));
                out.write(data);
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static List<String> strings(Object value) {
        return list(value).stream().map(String::valueOf).toList();
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static List<Attribute> attributes(Object value) {
        List<Attribute> attributes = new ArrayList<>();
        for (Object item : list(value)) {
            Map<String, Object> attribute = map(item);
            String qualified = String.valueOf(attribute.get("name"));
            int colon = qualified.indexOf(':');
            attributes.add(new Attribute(colon < 0 ? "" : qualified.substring(0, colon),
                colon < 0 ? qualified : qualified.substring(colon + 1),
                String.valueOf(attribute.getOrDefault("namespace", "")),
                String.valueOf(attribute.get("value"))));
        }
        return attributes;
    }

    private static ToolErrorException missing(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED, message,
            "The workspace does not hold the complete export", "Export it again"));
    }
}
