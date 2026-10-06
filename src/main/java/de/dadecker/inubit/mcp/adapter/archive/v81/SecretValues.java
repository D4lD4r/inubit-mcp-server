package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowGroupXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.SecretPaths.Artifact;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The secret values of a raw (unredacted) export of the target, by artifact and property path
 * (feature 004, research D-6): found by the walk of {@link SecretPaths}, the same the redactor
 * uses, so that {@code ${secret:<path>}} in a workspace file of an artifact resolves to the value
 * at that position on the target. A path that two different values share is ambiguous and
 * resolves to nothing.
 *
 * <p>The values live only in memory; {@link #toString()} names the count, never a value.
 */
public final class SecretValues {

    private final Map<Artifact, Map<String, String>> values;
    private final Set<Artifact> ambiguousArtifacts;
    private final Map<Artifact, Set<String>> ambiguous;

    private SecretValues(Map<Artifact, Map<String, String>> values,
        Map<Artifact, Set<String>> ambiguous) {
        this.values = values;
        this.ambiguous = ambiguous;
        this.ambiguousArtifacts = ambiguous.keySet();
    }

    /** The secret values of {@code raw}, an export that was not redacted. */
    public static SecretValues of(ExportArchive raw) {
        return of(List.of(raw));
    }

    /** The secret values of several raw exports of one target (e.g. one per module). */
    public static SecretValues of(List<ExportArchive> raws) {
        Objects.requireNonNull(raws, "raws");
        Map<Artifact, Map<String, String>> values = new HashMap<>();
        Map<Artifact, Set<String>> ambiguous = new HashMap<>();
        SecretPaths paths = new SecretPaths((artifact, path, kind, value) -> {
            Map<String, String> byPath = values.computeIfAbsent(artifact, a -> new HashMap<>());
            String previous = byPath.putIfAbsent(path, value);
            if (previous != null && !previous.equals(value)) {
                ambiguous.computeIfAbsent(artifact, a -> new HashSet<>()).add(path);
            }
            return value;
        });
        for (ExportArchive raw : raws) {
            for (WorkflowGroupXml group : raw.workflowGroups()) {
                for (WorkflowXml workflow : group.workflows()) {
                    paths.workflow(workflow.name(), workflow.element());
                }
            }
            for (ModuleXml module : raw.moduleFiles().values()) {
                paths.module(module.name(), module.element());
            }
        }
        return new SecretValues(values, ambiguous);
    }

    /** The value of {@code ${secret:<path>}} in workflow {@code workflow}, if the target has one. */
    public Optional<String> workflow(String workflow, String path) {
        return lookup(new Artifact(true, workflow), path);
    }

    /** The value of {@code ${secret:<path>}} in module {@code module}, if the target has one. */
    public Optional<String> module(String module, String path) {
        return lookup(new Artifact(false, module), path);
    }

    /** The number of values (ambiguous paths included). */
    public int size() {
        return values.values().stream().mapToInt(Map::size).sum();
    }

    private Optional<String> lookup(Artifact artifact, String path) {
        if (ambiguousArtifacts.contains(artifact) && ambiguous.get(artifact).contains(path)) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.getOrDefault(artifact, Map.of()).get(path));
    }

    @Override
    public String toString() {
        return "SecretValues[" + size() + " values]";
    }
}
