package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.CheckFinding.Check;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T005: check findings and the bounded report (data-model.md → Checks). */
class CheckFindingTest {

    private static final String WORKFLOW = "dev/OWNERS/workflows/GRP-01/Workflow-0001.xml";

    private static CheckFinding finding(Severity severity, String code) {
        return new CheckFinding(severity, Check.STRUCTURE, WORKFLOW,
            Optional.of("WorkflowModule[ModuleId=2]/Connection"), code, "message");
    }

    @Test
    void aFindingKeepsItsFields() {
        CheckFinding finding = finding(Severity.ERROR, "EDGE_TARGET_MISSING");

        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.check()).isEqualTo(Check.STRUCTURE);
        assertThat(finding.path()).isEqualTo(WORKFLOW);
        assertThat(finding.location()).contains("WorkflowModule[ModuleId=2]/Connection");
        assertThat(finding.code()).isEqualTo("EDGE_TARGET_MISSING");
        assertThat(new CheckFinding(Severity.INFO, Check.XSLT, "a.xsl", null, "XSLT_STANDINS_USED",
            "m").location()).isEmpty();
    }

    @Test
    void codesAreStableUpperCaseIdentifiers() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> finding(Severity.ERROR, "edge target missing"))
            .withMessageContaining("code");
        assertThatIllegalArgumentException().isThrownBy(() -> finding(Severity.ERROR, ""));
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThatNullPointerException().isThrownBy(() -> new CheckFinding(null, Check.XML,
            "a.xml", Optional.empty(), "XML_NOT_WELL_FORMED", "m"));
        assertThatNullPointerException().isThrownBy(() -> new CheckFinding(Severity.ERROR, null,
            "a.xml", Optional.empty(), "XML_NOT_WELL_FORMED", "m"));
        assertThatNullPointerException().isThrownBy(() -> new CheckFinding(Severity.ERROR,
            Check.XML, "a.xml", Optional.empty(), "XML_NOT_WELL_FORMED", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new CheckFinding(Severity.ERROR,
            Check.XML, " ", Optional.empty(), "XML_NOT_WELL_FORMED", "m"))
            .withMessageContaining("path");
    }

    @Test
    void aCompleteReportCountsItsFindingsPerSeverity() {
        CheckReport report = CheckReport.of(List.of(finding(Severity.ERROR, "ID_COLLISION"),
            finding(Severity.WARNING, "VARIABLE_UNRESOLVED"),
            finding(Severity.ERROR, "EDGE_TARGET_MISSING")), List.of(".tests/x.out"));

        assertThat(report.counts()).containsExactly(Map.entry(Severity.ERROR, 2),
            Map.entry(Severity.WARNING, 1), Map.entry(Severity.INFO, 0));
        assertThat(report.truncated()).isFalse();
        assertThat(report.fullReport()).isEmpty();
        assertThat(report.outputs()).containsExactly(".tests/x.out");
    }

    @Test
    void reportListsAreImmutableCopies() {
        List<CheckFinding> findings = new ArrayList<>(List.of(finding(Severity.INFO,
            "XSLT_STANDINS_USED")));
        CheckReport report = CheckReport.of(findings, List.of());

        findings.clear();

        assertThat(report.findings()).hasSize(1);
        assertThatThrownBy(() -> report.findings().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.counts().put(Severity.INFO, 5))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aTruncatedReportNamesItsFullReportAndCountsAtLeastWhatItLists() {
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        counts.put(Severity.ERROR, 250);
        List<CheckFinding> shown = List.of(finding(Severity.ERROR, "ID_COLLISION"));

        CheckReport report = new CheckReport(shown, counts, List.of(), true,
            Optional.of(".reports/check-20261006T101500Z.json"));

        assertThat(report.counts()).containsEntry(Severity.ERROR, 250)
            .containsEntry(Severity.WARNING, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> new CheckReport(shown, counts,
            List.of(), true, Optional.empty())).withMessageContaining("fullReport");
        assertThatIllegalArgumentException().isThrownBy(() -> new CheckReport(shown, counts,
            List.of(), false, Optional.of(".reports/x.json"))).withMessageContaining("fullReport");
        assertThatIllegalArgumentException().isThrownBy(() -> new CheckReport(shown,
            Map.of(Severity.ERROR, 0), List.of(), true, Optional.of(".reports/x.json")))
            .withMessageContaining("counts");
        assertThatIllegalArgumentException().isThrownBy(() -> new CheckReport(shown,
            Map.of(Severity.ERROR, 2), List.of(), false, Optional.empty()))
            .withMessageContaining("counts");
    }
}
