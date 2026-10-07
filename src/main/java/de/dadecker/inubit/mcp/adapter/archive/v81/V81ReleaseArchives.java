package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The 8.1 {@link ReleaseArchivePort} (feature 005, research D-1, D-4, D-5).
 *
 * <ul>
 *   <li>{@link #normalize}: a release export (an export by tag) becomes the shape of a diagram
 *       group export, which {@link ArchiveReader} and the workspace codec read:
 *       {@code usertags.xml} is dropped, every {@code Workflow} and index {@code Module} gets
 *       {@code version="head"} (the tagged version is kept in
 *       {@link ReleaseExport#taggedVersions()}), {@code tag} attributes of the nodes and the
 *       {@code @@@Tag: <tag>@@@} segment of the check-in comments are removed. Everything else
 *       stays, also {@code Repository.zip} (whose metadata may name the tag; it is volatile).
 *   <li>{@link #equivalent} compares reviewed content: XML normalized, without the check-in
 *       comment, last update, UIDs and edit mode of a workflow or module entry, and without a
 *       workflow's {@code IsActive} (an existing workflow keeps the target's flag, a new one has
 *       no counterpart); other files byte by byte.
 *   <li>{@link #layoutOnly} is {@link LayoutDiff} on the reviewed forms.
 *   <li>{@link #keyMaterial} is {@link RepositoryArchive#isKeyMaterial}.
 * </ul>
 */
public final class V81ReleaseArchives implements ReleaseArchivePort {

    private static final String WORKFLOWS = "workflow/workflow.xml";
    private static final String INDEX = "module/module.xml";
    private static final String USER_TAGS = "usertags.xml";
    private static final Pattern TAG_SEGMENT = Pattern.compile("@@@Tag: [^@]*@@@");
    private static final Pattern NUMBER = Pattern.compile("^\\d{1,9}$");
    private static final Set<String> VOLATILE = Set.of("CheckinComment", "LastUpdate",
        "WorkflowUId", "ModuleUId", "CheckoutUser");

    @Override
    public ReleaseExport normalize(byte[] releaseExport) {
        Map<String, byte[]> entries = entries(releaseExport);
        entries.remove(USER_TAGS);
        Map<String, Integer> versions = new HashMap<>();
        SortedSet<String> groups = new TreeSet<>();
        try {
            if (entries.containsKey(WORKFLOWS)) {
                Element root = XmlTree.parse(entries.get(WORKFLOWS)).root();
                entries.put(WORKFLOWS, XmlNormalizer.normalize(untagged(root, "Workflow",
                    versions, groups)));
            }
            if (entries.containsKey(INDEX)) {
                Element root = XmlTree.parse(entries.get(INDEX)).root();
                entries.put(INDEX, XmlNormalizer.normalize(untagged(root, "Module", versions,
                    new TreeSet<>())));
            }
        } catch (IllegalArgumentException e) {
            throw unexpected("The release export is not well-formed XML");
        }
        byte[] export = zip(entries);
        new ArchiveReader().read(export); // UNEXPECTED_RESPONSE if it is no export
        return new ReleaseExport(export, groups, versions);
    }

    @Override
    public Map<String, Integer> versions(byte[] export) {
        return ImportAssembler.currentVersions(List.of(export));
    }

    @Override
    public byte[] canonical(String path, byte[] file) {
        try {
            return XmlNormalizer.normalize(reviewed(XmlTree.parse(file).root(), false));
        } catch (RuntimeException e) {
            return file.clone();
        }
    }

    @Override
    public boolean equivalent(String path, byte[] release, byte[] target) {
        Element left;
        Element right;
        try {
            left = XmlTree.parse(release).root();
            right = XmlTree.parse(target).root();
        } catch (RuntimeException e) {
            return Arrays.equals(release, target);
        }
        return Arrays.equals(XmlNormalizer.normalize(reviewed(left, true)),
            XmlNormalizer.normalize(reviewed(right, true)));
    }

    @Override
    public boolean layoutOnly(String path, byte[] release, byte[] target) {
        try {
            return LayoutDiff.layoutOnly(XmlNormalizer.normalize(reviewed(XmlTree.parse(release)
                .root(), true)), XmlNormalizer.normalize(reviewed(XmlTree.parse(target).root(),
                true)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean keyMaterial(String repositoryPath, byte[] content) {
        return RepositoryArchive.isKeyMaterial(repositoryPath, content);
    }

    @Override
    public List<String> modulesOf(byte[] workflowFile) {
        Set<String> names = new LinkedHashSet<>();
        for (Element node : XmlTree.parse(workflowFile).root().elements()) {
            if (node.localName().equals("WorkflowModule")) {
                node.child("ModuleName").map(Element::text).ifPresent(names::add);
            }
        }
        return List.copyOf(names);
    }

    @Override
    public List<PropertyChange> changedProperties(String path, byte[] release, byte[] target) {
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        try {
            properties(XmlTree.parse(release).root(), before);
            properties(XmlTree.parse(target).root(), after);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<PropertyChange> changes = new ArrayList<>();
        before.forEach((name, value) -> {
            String other = after.get(name);
            if (other != null && !other.equals(value)) {
                changes.add(new PropertyChange(name, value, other));
            }
        });
        return List.copyOf(changes);
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Simple properties ({@code <Property name="…">text</Property>}) below {@code element}. */
    private static void properties(Element element, Map<String, String> into) {
        for (Element child : element.elements()) {
            if (child.localName().equals("Property") && !child.hasElements()
                && child.attribute("name").isPresent()) {
                into.putIfAbsent(child.attribute("name").get(), child.text());
            } else {
                properties(child, into);
            }
        }
    }

    /**
     * The root without the volatile children of a workflow or module entry; with
     * {@code withoutActive} also without a workflow's {@code IsActive}.
     */
    private static Element reviewed(Element root, boolean withoutActive) {
        boolean workflow = root.localName().equals("Workflow");
        if (!workflow && !root.localName().equals("Module")) {
            return root;
        }
        List<Node> children = new ArrayList<>();
        for (Node child : root.children()) {
            if (child instanceof Element element && (VOLATILE.contains(element.localName())
                || (withoutActive && workflow && element.localName().equals("IsActive")))) {
                continue;
            }
            children.add(child);
        }
        return root.withChildren(children);
    }

    /**
     * {@code root} with every {@code artifact} element ({@code Workflow} or {@code Module}) in
     * head shape; their tagged versions go to {@code versions}, the diagram groups to
     * {@code groups}.
     */
    private static Element untagged(Element element, String artifact,
        Map<String, Integer> versions, Set<String> groups) {
        if (element.localName().equals("WorkflowGroup")) {
            element.child("WorkflowGroupName").map(Element::text).ifPresent(groups::add);
        }
        if (element.localName().equals(artifact)) {
            String name = element.child(artifact + "Name").map(Element::text).orElse("");
            List<Attribute> attributes = new ArrayList<>();
            for (Attribute attribute : element.attributes()) {
                if (attribute.namespaceUri().isEmpty() && attribute.localName().equals(
                    "version")) {
                    if (NUMBER.matcher(attribute.value()).matches()) {
                        versions.put(name, Integer.parseInt(attribute.value()));
                    }
                    attributes.add(new Attribute("", "version", "", "head"));
                } else {
                    attributes.add(attribute);
                }
            }
            List<Node> children = new ArrayList<>();
            for (Node child : element.children()) {
                if (child instanceof Element e && e.localName().equals("CheckinComment")) {
                    children.add(e.withText(TAG_SEGMENT.matcher(e.text()).replaceAll("@@@")));
                } else if (child instanceof Element e && e.localName().equals("WorkflowModule")) {
                    children.add(e.withAttributes(e.attributes().stream().filter(a ->
                        !(a.namespaceUri().isEmpty() && a.localName().equals("tag"))).toList()));
                } else {
                    children.add(child);
                }
            }
            return element.withAttributes(attributes).withChildren(children);
        }
        if (!element.hasElements()) {
            return element;
        }
        List<Node> children = new ArrayList<>();
        for (Node child : element.children()) {
            children.add(child instanceof Element e ? untagged(e, artifact, versions, groups)
                : child);
        }
        return element.withChildren(children);
    }

    private static Map<String, byte[]> entries(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8,
                    ArchiveReader.MAX_BYTES + 1));
                total += bytes.length;
                if (total > ArchiveReader.MAX_BYTES) {
                    throw unexpected("The release export is too large");
                }
                entries.put(entry.getName(), entry.isDirectory() ? new byte[0] : bytes);
            }
        } catch (IOException e) {
            throw unexpected("The release export is not a readable ZIP archive ("
                + e.getClass().getSimpleName() + ")");
        }
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static ToolErrorException unexpected(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, message,
            "StartCLI or the export format behaved differently than recorded for INUBIT 8.1",
            "Check the INUBIT client version (cliHome) and retry"));
    }
}
