package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double of the tags of one INUBIT 8.1 server (feature 004, research D-16): diagrams with
 * their diagram group, type and versions, modules used by the diagram groups, and StartCLI's
 * tag behaviour as the spike observed it — {@code --tagWorkflowGroup} tags the head versions
 * of the group's technical workflows and their modules, {@code --tagDelete} removes the tag
 * everywhere. Knobs simulate a tag that reaches everything (the spike's ignored
 * {@code --tagDiagram}) and a failing tag command; every call is recorded.
 */
final class FakeTagPort implements TagPort {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    private final Map<String, String[]> diagrams = new LinkedHashMap<>();
    private final Map<String, List<List<String>>> versions = new LinkedHashMap<>();
    private final Map<String, Set<String>> used = new LinkedHashMap<>();
    /** The calls, e.g. {@code history}, {@code history GRP-01}, {@code tag REL GRP-01}. */
    final List<String> calls = new CopyOnWriteArrayList<>();
    /** The next tag command tags every head of the owner. */
    volatile boolean tagEverything;
    /** A tag command for this diagram group fails. */
    volatile String failOn;

    /** A diagram {@code name} of {@code group} and {@code type} with {@code count} versions. */
    FakeTagPort diagram(String name, String group, String type, int count, String... modules) {
        diagrams.put(name, new String[] {group, type});
        versions.put(name, newVersions(count));
        for (String module : modules) {
            used.computeIfAbsent(group, g -> new LinkedHashSet<>()).add(module);
            versions.putIfAbsent("module:" + module, newVersions(2));
        }
        return this;
    }

    /** Puts {@code tag} on version {@code index} (0 = head) of a diagram or module. */
    FakeTagPort tagged(String artifact, int index, String tag) {
        String key = diagrams.containsKey(artifact) ? artifact : "module:" + artifact;
        versions.get(key).get(index).add(tag);
        return this;
    }

    /** A new head version of a diagram (a colleague published). */
    void publish(String diagram) {
        versions.get(diagram).add(0, new ArrayList<>());
    }

    /** The names that carry {@code tag} on any version. */
    Set<String> carrying(String tag) {
        Set<String> names = new LinkedHashSet<>();
        versions.forEach((key, list) -> {
            if (list.stream().anyMatch(tags -> tags.contains(tag))) {
                names.add(key.replace("module:", ""));
            }
        });
        return names;
    }

    @Override
    public void checkAvailable() {
    }

    @Override
    public synchronized History history(String owner) {
        calls.add("history");
        return history(diagrams.keySet(), moduleKeys(null));
    }

    @Override
    public synchronized History history(String owner, String diagramGroup) {
        calls.add("history " + diagramGroup);
        List<String> names = diagrams.entrySet().stream()
            .filter(e -> e.getValue()[0].equals(diagramGroup)
                && e.getValue()[1].equals("technical")).map(Map.Entry::getKey).toList();
        return history(names, moduleKeys(diagramGroup));
    }

    @Override
    public synchronized void tag(String tag, String diagramGroup, String owner) {
        calls.add("tag " + tag + " " + diagramGroup);
        if (diagramGroup.equals(failOn)) {
            throw new ToolErrorException(ToolError.of(ErrorCode.IMPORT_FAILED,
                "StartCLI reported a failed tag on dev/node1: Tag failed.", "c", "s")
                .withNode(DEV));
        }
        versions.forEach((key, list) -> {
            boolean inGroup = key.startsWith("module:")
                ? used.getOrDefault(diagramGroup, Set.of()).contains(key.substring(7))
                : diagrams.get(key)[0].equals(diagramGroup)
                    && diagrams.get(key)[1].equals("technical");
            if (tagEverything || inGroup) {
                list.get(0).add(tag);
            }
        });
    }

    @Override
    public synchronized void deleteTag(String tag, String owner) {
        calls.add("delete " + tag);
        versions.values().forEach(list -> list.forEach(tags -> tags.remove(tag)));
    }

    private List<String> moduleKeys(String group) {
        Set<String> names = new LinkedHashSet<>();
        used.forEach((g, modules) -> {
            if (group == null || g.equals(group)) {
                names.addAll(modules);
            }
        });
        return List.copyOf(names);
    }

    private History history(Iterable<String> diagramNames, List<String> moduleNames) {
        Map<String, Diagram> out = new LinkedHashMap<>();
        for (String name : diagramNames) {
            out.put(name, new Diagram(diagrams.get(name)[0], diagrams.get(name)[1],
                entries(versions.get(name))));
        }
        Map<String, List<VersionEntry>> modules = new LinkedHashMap<>();
        for (String name : moduleNames) {
            modules.put(name, entries(versions.get("module:" + name)));
        }
        return new History(out, modules);
    }

    private static List<VersionEntry> entries(List<List<String>> list) {
        List<VersionEntry> entries = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            entries.add(new VersionEntry(list.size() - i, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.copyOf(list.get(i))));
        }
        return entries;
    }

    private static List<List<String>> newVersions(int count) {
        List<List<String>> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ArrayList<>());
        }
        return list;
    }
}
