package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceWriter.Rendered;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The 8.1 {@link ArchiveCodecPort} (research D-4): each archive is read ({@link ArchiveReader}),
 * redacted ({@link SecretRedactor}) and rendered ({@link WorkspaceWriter#render}) in memory; the
 * renderings of one request are merged (a module used by two exported diagram groups is one set
 * of files) and checked for case-only collisions as a whole. Nothing is written before
 * {@link PreparedExport#writeTo}.
 */
public final class ArchiveCodec implements ArchiveCodecPort {

    @Override
    public PreparedExport prepare(GroupId group, String owner, List<byte[]> archives) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(owner, "owner");
        ArchiveReader reader = new ArchiveReader();
        SecretRedactor redactor = new SecretRedactor();
        List<Rendered> renderings = new ArrayList<>();
        int secrets = 0;
        int suspicious = 0;
        for (byte[] archive : archives) {
            RedactedArchive redacted = redactor.redact(reader.read(archive));
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
            List.of(ownerRoot, WorkspacePath.META_DIRECTORY + "/" + ownerRoot));
    }

    private record Prepared(Rendered rendered, int secretsReplaced, List<String> warnings,
        List<String> scope) implements PreparedExport {

        @Override
        public void writeTo(Path root) {
            WorkspaceWriter.write(root, rendered);
        }
    }
}
