package de.dadecker.inubit.mcp.adapter.archive.v81;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An {@link ExportArchive} after {@link SecretRedactor#redact}, with its report (data-model.md →
 * Secrets, FR-025). Only the redactor can create one: the constructor needs a
 * {@link SecretRedactor.Seal}, which only the redactor can instantiate. The workspace writer and
 * the assembler accept only this type, so nothing unredacted can be written (T014).
 */
public final class RedactedArchive {

    private final ExportArchive archive;
    private final RedactionReport report;
    private final Set<String> withheld;

    RedactedArchive(ExportArchive archive, RedactionReport report, Set<String> withheld,
        SecretRedactor.Seal seal) {
        Objects.requireNonNull(seal, "seal");
        this.archive = Objects.requireNonNull(archive, "archive");
        this.report = Objects.requireNonNull(report, "report");
        this.withheld = Collections.unmodifiableSet(new LinkedHashSet<>(withheld));
    }

    /** The redacted archive. */
    public ExportArchive archive() {
        return archive;
    }

    /**
     * The repository paths whose content was withheld as key material (review I3); their
     * entries in {@link ExportArchive#repository()} have no content.
     */
    public Set<String> withheldRepositoryFiles() {
        return withheld;
    }

    /** What was replaced. */
    public RedactionReport report() {
        return report;
    }
}
