package de.dadecker.inubit.mcp.adapter.archive.v81;

import java.util.Objects;

/**
 * An {@link ExportArchive} after {@link SecretRedactor#redact}, with its report (data-model.md →
 * Secrets, FR-025). Only the redactor can create one: the constructor needs a
 * {@link SecretRedactor.Seal}, which only the redactor can instantiate. The workspace writer and
 * the assembler accept only this type, so nothing unredacted can be written (T014).
 */
public final class RedactedArchive {

    private final ExportArchive archive;
    private final RedactionReport report;

    RedactedArchive(ExportArchive archive, RedactionReport report, SecretRedactor.Seal seal) {
        Objects.requireNonNull(seal, "seal");
        this.archive = Objects.requireNonNull(archive, "archive");
        this.report = Objects.requireNonNull(report, "report");
    }

    /** The redacted archive. */
    public ExportArchive archive() {
        return archive;
    }

    /** What was replaced. */
    public RedactionReport report() {
        return report;
    }
}
