package de.dadecker.inubit.mcp.domain.model;

import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of one {@code check_artifacts} call (data-model.md → Checks, research D-10).
 *
 * @param findings   the findings returned, at most {@code resultLimits.maxItems}
 * @param counts     the number of all findings per severity (every severity present), also of
 *                   those not returned
 * @param outputs    workspace-relative output files written below {@code .tests/}
 * @param truncated  whether {@code findings} is not the complete list
 * @param fullReport the workspace-relative file with the complete list; present exactly when
 *                   truncated
 */
public record CheckReport(List<CheckFinding> findings, Map<Severity, Integer> counts,
    List<String> outputs, boolean truncated, Optional<String> fullReport) {

    public CheckReport {
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        Objects.requireNonNull(counts, "counts");
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        fullReport = fullReport == null ? Optional.empty() : fullReport;
        if (truncated != fullReport.isPresent()) {
            throw new IllegalArgumentException("fullReport must be present exactly when the"
                + " report is truncated");
        }
        Map<Severity, Integer> all = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            int count = counts.getOrDefault(severity, 0);
            long listed = findings.stream().filter(f -> f.severity() == severity).count();
            if (count < listed || (!truncated && count != listed)) {
                throw new IllegalArgumentException("counts must equal the listed findings of an"
                    + " untruncated report and cover them in a truncated one (" + severity + ")");
            }
            all.put(severity, count);
        }
        counts = Collections.unmodifiableMap(all);
    }

    /** A complete (untruncated) report of {@code findings}. */
    public static CheckReport of(List<CheckFinding> findings, List<String> outputs) {
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        findings.forEach(f -> counts.merge(f.severity(), 1, Integer::sum));
        return new CheckReport(findings, counts, outputs, false, Optional.empty());
    }
}
