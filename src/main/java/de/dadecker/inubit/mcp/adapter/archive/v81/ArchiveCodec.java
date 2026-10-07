package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceWriter.Rendered;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The 8.1 {@link ArchiveCodecPort} (research D-4): each archive is read ({@link ArchiveReader}),
 * redacted ({@link SecretRedactor}) and rendered ({@link WorkspaceWriter#render}) in memory; the
 * renderings of one request are merged (a module used by two exported diagram groups is one set
 * of files) and checked for case-only collisions as a whole. Nothing is written before
 * {@link PreparedExport#writeTo}; {@link PreparedExport#files()} is a read-only view of the
 * rendering and {@link PreparedExport#inEditMode()} names the workflows with a
 * {@code CheckoutUser} (feature 004, research D-5).
 */
public final class ArchiveCodec implements ArchiveCodecPort {

    @Override
    public PreparedExport prepare(GroupId group, String owner, List<byte[]> archives) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(owner, "owner");
        ArchiveReader reader = new ArchiveReader();
        SecretRedactor redactor = new SecretRedactor();
        List<Rendered> renderings = new ArrayList<>();
        Map<String, String> editMode = new TreeMap<>();
        int secrets = 0;
        int suspicious = 0;
        for (byte[] archive : archives) {
            RedactedArchive redacted = redactor.redact(reader.read(archive));
            redacted.archive().workflowGroups().forEach(diagramGroup -> diagramGroup.workflows()
                .forEach(workflow -> workflow.element().child("CheckoutUser")
                    .map(XmlTree.Element::text)
                    .filter(user -> !user.isBlank())
                    .ifPresent(user -> editMode.put(workflow.name(), user))));
            renderings.add(WorkspaceWriter.render(redacted, group, owner));
            secrets += redacted.report().total();
            suspicious += redacted.report().suspicious();
        }
        Rendered merged = WorkspaceWriter.merge(renderings);
        List<String> warnings = new ArrayList<>(merged.warnings());
        if (suspicious > 0) {
            warnings.add(suspicious + " value(s) with a secret-like property name were kept"
                + " because their type is not a secret type; please check them");
        }
        String ownerRoot = group.value() + "/" + NameCodec.encode(owner);
        return new Prepared(merged, secrets, warnings,
            List.of(ownerRoot, WorkspacePath.META_DIRECTORY + "/" + ownerRoot),
            Map.copyOf(editMode));
    }

    private record Prepared(Rendered rendered, int secretsReplaced, List<String> warnings,
        List<String> scope, Map<String, String> inEditMode) implements PreparedExport {

        @Override
        public void writeTo(Path root) {
            WorkspaceWriter.write(root, rendered);
        }

        @Override
        public SortedMap<String, byte[]> files() {
            return new TreeMap<>(rendered.files());
        }
    }
}
