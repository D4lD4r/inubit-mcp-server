package de.dadecker.inubit.mcp.adapter.archive.v81;

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
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parses a StartCLI export ZIP of INUBIT 8.1 into an {@link ExportArchive} (research D-4, spike
 * §2), in memory.
 *
 * <ul>
 *   <li>Known entries only: {@code archive.properties}, {@code Repository.zip},
 *       {@code workflow/workflow.xml}, {@code module/module.xml}, {@code module/<name>.xml} and
 *       the directory entries {@code workflow/} (module-only exports) and {@code module/}.
 *       Anything else, a duplicate, or a module file without index entry is refused, so that
 *       nothing is dropped silently. {@code module/module.xml} is required.
 *   <li>Entry names of the export and of the nested {@code Repository.zip} must stay inside the
 *       archive: no absolute names, drive letters, backslashes, empty, {@code .} or {@code ..}
 *       segments.
 *   <li>Bounded: at most {@value #MAX_BYTES} bytes per entry and in total, the uncompressed
 *       entries of {@code Repository.zip} included.
 *   <li>XML is parsed without document type declarations ({@link XmlTree}).
 * </ul>
 *
 * <p>Every violation is {@code UNEXPECTED_RESPONSE}; the caller adds the server id. Messages name
 * entries, never their content.
 */
public final class ArchiveReader {

    /** Upper bound per entry and in total (128 MiB). */
    public static final long MAX_BYTES = 128L << 20;

    private static final String PROPERTIES = "archive.properties";
    private static final String REPOSITORY = "Repository.zip";
    private static final String WORKFLOWS = "workflow/workflow.xml";
    private static final String MODULE_INDEX = "module/module.xml";

    private final long maxBytes;

    public ArchiveReader() {
        this(MAX_BYTES);
    }

    /** @param maxBytes the bound per entry and in total (tests use small bounds) */
    ArchiveReader(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * The archive {@code zip} holds.
     *
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} if it is not a readable export of
     *     the expected layout (see the class comment)
     */
    public ExportArchive read(byte[] zip) {
        Budget budget = new Budget();
        Map<String, byte[]> entries = unzip(zip, "export", budget, true);
        if (entries.isEmpty()) {
            throw unexpected("The export is not a readable ZIP archive (no entries)");
        }
        List<String> names = List.copyOf(entries.keySet());
        for (String name : names) {
            if (!isKnownEntry(name)) {
                throw unexpected("The export has an unexpected entry " + name);
            }
        }
        byte[] index = entries.get(MODULE_INDEX);
        if (index == null) {
            throw unexpected("The export has no " + MODULE_INDEX);
        }
        List<ModuleIndexEntry> moduleIndex = moduleIndex(parse(MODULE_INDEX, index));
        List<WorkflowGroupXml> groups = entries.containsKey(WORKFLOWS)
            ? workflowGroups(parse(WORKFLOWS, entries.get(WORKFLOWS))) : List.of();
        Map<String, ModuleXml> moduleFiles = moduleFiles(entries, moduleIndex);
        Map<String, RepositoryFile> repository = entries.containsKey(REPOSITORY)
            ? repository(entries.get(REPOSITORY), budget) : Map.of();
        Map<String, String> properties = entries.containsKey(PROPERTIES)
            ? properties(entries.get(PROPERTIES)) : Map.of();
        return new ExportArchive(properties, names, groups, moduleIndex, moduleFiles,
            repository);
    }

    // --- ZIP ---------------------------------------------------------------------------------

    /** The shared byte budget of one export (outer and nested entries). */
    private final class Budget {

        private long used;

        void add(long bytes, String entry) {
            used += bytes;
            if (used > maxBytes) {
                throw tooLarge(entry);
            }
        }
    }

    private Map<String, byte[]> unzip(byte[] zip, String archive, Budget budget,
        boolean directoriesAllowed) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String name = entry.getName();
                checkName(name, archive);
                if (entries.containsKey(name)) {
                    throw unexpected("The " + archive + " has the entry " + name + " twice");
                }
                if (entry.isDirectory()) {
                    if (!directoriesAllowed) {
                        throw unexpected("The " + archive + " has an unexpected directory entry "
                            + name);
                    }
                    entries.put(name, new byte[0]);
                    continue;
                }
                entries.put(name, bounded(in, name, budget));
            }
        } catch (IOException e) {
            throw unexpected("The " + archive + " is not a readable ZIP archive ("
                + e.getClass().getSimpleName() + ")");
        }
        return entries;
    }

    private byte[] bounded(InputStream in, String name, Budget budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long size = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            size += n;
            if (size > maxBytes) {
                throw tooLarge(name);
            }
            budget.add(n, name);
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Names must stay inside the archive (no "zip slip"): not absolute, no drive letter, and no
     * empty, {@code .} or {@code ..} segment, with {@code /} or {@code \} as separator.
     */
    private static void checkName(String name, String archive) {
        String path = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        boolean bad = path.isEmpty() || path.startsWith("/") || path.startsWith("\\")
            || path.matches("^[A-Za-z]:.*") || path.indexOf('\0') >= 0;
        if (!bad) {
            for (String segment : path.split("[/\\\\]", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    bad = true;
                    break;
                }
            }
        }
        if (bad) {
            throw unexpected("The " + archive + " has an entry name that leaves the archive");
        }
    }

    private static boolean isKnownEntry(String name) {
        if (name.equals(PROPERTIES) || name.equals(REPOSITORY) || name.equals(WORKFLOWS)
            || name.equals("workflow/") || name.equals("module/")) {
            return true;
        }
        return name.startsWith("module/") && name.endsWith(".xml")
            && name.indexOf('/', "module/".length()) < 0
            && name.length() > "module/.xml".length();
    }

    // --- XML -----------------------------------------------------------------------------------

    private static XmlTree.Document parse(String entry, byte[] xml) {
        try {
            return XmlTree.parse(xml);
        } catch (IllegalArgumentException e) {
            throw unexpected("The export entry " + entry + " is not well-formed XML");
        }
    }

    private static List<WorkflowGroupXml> workflowGroups(XmlTree.Document document) {
        Element root = expectRoot(document, "IBISWorkflow", WORKFLOWS);
        String version = attribute(root, "version").orElse("");
        List<WorkflowGroupXml> groups = new ArrayList<>();
        for (Element workflows : children(root, WORKFLOWS, "Workflows")) {
            for (Element group : children(workflows, WORKFLOWS, "WorkflowGroup")) {
                int groupPosition = groups.size();
                String groupName = text(group, "WorkflowGroupName", WORKFLOWS);
                List<WorkflowXml> members = new ArrayList<>();
                for (Element workflow : children(group, WORKFLOWS, "WorkflowGroupName",
                    "Workflow")) {
                    if (!workflow.localName().equals("Workflow")) {
                        continue;
                    }
                    members.add(new WorkflowXml(text(workflow, "WorkflowName", WORKFLOWS),
                        groupName, workflow, new WorkflowContext(version, group.attributes(),
                            groupPosition, members.size())));
                }
                groups.add(new WorkflowGroupXml(groupName, group.attributes(), members));
            }
        }
        return groups;
    }

    private static List<ModuleIndexEntry> moduleIndex(XmlTree.Document document) {
        Element root = expectRoot(document, "IBISWorkflow", MODULE_INDEX);
        String version = attribute(root, "version").orElse("");
        List<ModuleIndexEntry> index = new ArrayList<>();
        int groupPosition = 0;
        for (Element modules : children(root, MODULE_INDEX, "Modules")) {
            for (Element group : children(modules, MODULE_INDEX, "ModuleGroup")) {
                String pluginType = text(group, "ModuleGroupName", MODULE_INDEX);
                int position = 0;
                for (Element module : children(group, MODULE_INDEX, "ModuleGroupName",
                    "Module")) {
                    if (module.localName().equals("Module")) {
                        index.add(new ModuleIndexEntry(text(module, "ModuleName", MODULE_INDEX),
                            pluginType, module, version, groupPosition, position++));
                    }
                }
                groupPosition++;
            }
        }
        return index;
    }

    private static Map<String, ModuleXml> moduleFiles(Map<String, byte[]> entries,
        List<ModuleIndexEntry> index) {
        Map<String, ModuleIndexEntry> byFileName = new HashMap<>();
        for (ModuleIndexEntry entry : index) {
            String fileName = "module/" + entry.name().toLowerCase(Locale.ROOT) + ".xml";
            if (byFileName.put(fileName, entry) != null) {
                throw unexpected("The module index names two modules stored as " + fileName);
            }
        }
        Map<String, ModuleXml> files = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith("module/") || name.equals(MODULE_INDEX)
                || name.endsWith("/")) {
                continue;
            }
            ModuleIndexEntry indexed = byFileName.get(name);
            if (indexed == null) {
                throw unexpected("The export has the module file " + name
                    + " without an entry in " + MODULE_INDEX);
            }
            Element properties = expectRoot(parse(name, entry.getValue()), "Properties", name);
            files.put(indexed.name(), new ModuleXml(indexed.name(), indexed.pluginType(), name,
                properties));
        }
        return files;
    }

    private Map<String, RepositoryFile> repository(byte[] zip, Budget budget) {
        Map<String, byte[]> entries = unzip(zip, REPOSITORY, budget, false);
        Map<String, RepositoryFile> files = new LinkedHashMap<>();
        for (String name : entries.keySet()) {
            if (name.endsWith(".dat")) {
                String path = name.substring(0, name.length() - ".dat".length());
                byte[] metadata = entries.get(path + ".xml");
                Optional<Element> parsed = metadata == null ? Optional.empty()
                    : Optional.of(parse(REPOSITORY + "!" + path + ".xml", metadata).root());
                files.put(path, new RepositoryFile(path, entries.get(name), parsed));
            } else if (!name.endsWith(".xml")
                || !entries.containsKey(name.substring(0, name.length() - ".xml".length())
                    + ".dat")) {
                throw unexpected("The " + REPOSITORY + " of the export has an unexpected"
                    + " entry " + name);
            }
        }
        return files;
    }

    private static Map<String, String> properties(byte[] bytes) {
        Properties properties = new Properties();
        try {
            properties.load(new ByteArrayInputStream(bytes));
        } catch (IOException | IllegalArgumentException e) {
            throw unexpected("The export entry " + PROPERTIES + " is not readable");
        }
        Map<String, String> sorted = new TreeMap<>();
        properties.stringPropertyNames().forEach(key -> sorted.put(key,
            properties.getProperty(key)));
        return sorted;
    }

    // --- tree helpers --------------------------------------------------------------------------

    private static Element expectRoot(XmlTree.Document document, String name, String entry) {
        if (!document.root().localName().equals(name)) {
            throw unexpected("The export entry " + entry + " does not start with <" + name
                + ">");
        }
        return document.root();
    }

    /**
     * The child elements of {@code parent}; any child element not named in {@code allowed} and
     * any non-whitespace text is refused, so that nothing is dropped silently.
     */
    private static List<Element> children(Element parent, String entry, String... allowed) {
        List<Element> elements = new ArrayList<>();
        for (Node child : parent.children()) {
            if (child instanceof Element element) {
                if (!List.of(allowed).contains(element.localName())) {
                    throw unexpected("The export entry " + entry + " has an unexpected element <"
                        + element.localName() + "> in <" + parent.localName() + ">");
                }
                elements.add(element);
            } else if (child instanceof Text text && !text.value().isBlank()) {
                throw unexpected("The export entry " + entry + " has unexpected text in <"
                    + parent.localName() + ">");
            }
        }
        return elements;
    }

    /** The text of the single child element {@code name}. */
    private static String text(Element parent, String name, String entry) {
        for (Node child : parent.children()) {
            if (child instanceof Element element && element.localName().equals(name)) {
                StringBuilder text = new StringBuilder();
                element.children().forEach(node -> {
                    if (node instanceof Text t) {
                        text.append(t.value());
                    }
                });
                return text.toString();
            }
        }
        throw unexpected("The export entry " + entry + " has a <" + parent.localName()
            + "> without <" + name + ">");
    }

    private static Optional<String> attribute(Element element, String name) {
        return element.attributes().stream().filter(a -> a.localName().equals(name)
            && a.namespaceUri().isEmpty()).map(Attribute::value).findFirst();
    }

    private ToolErrorException tooLarge(String entry) {
        return unexpected("The entry " + entry + " of the export is too large (more than "
            + maxBytes + " bytes in the entry or the whole export)");
    }

    private static ToolErrorException unexpected(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, message,
            "StartCLI or the export format behaved differently than recorded for INUBIT 8.1",
            "Check the INUBIT client version (cliHome) and retry; the workspace was not"
                + " changed"));
    }
}
