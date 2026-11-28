package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * T042 (feature 025, spec FR-037, SC-010): the guard for the unverified 6.1.
 * {@link CapabilityMatrix#production()} declares no 6.1 capability {@code SUPPORTED} (no 6.1 case
 * is recorded), no capability other than monitoring and process diagnosis {@code UNVERIFIED}, and
 * either of these two {@code UNVERIFIED} only while it has a case in
 * {@code fixtures/v6_1/cli/RECONSTRUCTED.txt}. The shape of that list is
 * {@link V61FixturesShapeTest}'s.
 */
class V61RecordedFixturesTest {

    /** The only capabilities that may be {@code UNVERIFIED} on 6.1. */
    private static final Set<Capability> MAY_BE_UNVERIFIED =
        Set.of(Capability.MONITORING, Capability.PROCESS_DIAGNOSIS);

    private static final List<String> BOTH_RECONSTRUCTED = List.of(
        "health_session_ok monitoring",
        "ps_session_all process_diagnosis");

    @Test
    void theProductionMatrixPassesTheGuard() {
        assertThat(findings(CapabilityMatrix.production(), V61FixturesShapeTest.entries()))
            .as("6.1 capabilities the guard rejects")
            .isEmpty();
    }

    @Test
    void theFixturesBackMonitoringAndProcessDiagnosisAsUnverified() {
        assertThat(findings(matrix(SupportState.UNVERIFIED, SupportState.UNVERIFIED),
            V61FixturesShapeTest.entries()))
            .isEmpty();
    }

    @Test
    void aSupportedMonitoringFailsTheGuard() {
        assertThat(findings(matrix(SupportState.SUPPORTED, SupportState.UNSUPPORTED),
            BOTH_RECONSTRUCTED))
            .containsExactly("MONITORING is SUPPORTED on 6.1");
    }

    @Test
    void anUnverifiedLogDiagnosisFailsTheGuard() {
        CapabilityMatrix matrix = new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
                Map.of(Capability.LOG_DIAGNOSIS, SupportState.UNVERIFIED))),
            Map.of());

        assertThat(findings(matrix, BOTH_RECONSTRUCTED))
            .containsExactly("LOG_DIAGNOSIS is UNVERIFIED on 6.1; only MONITORING and "
                + "PROCESS_DIAGNOSIS may be");
    }

    @Test
    void anUnverifiedMonitoringWithoutAReconstructedMonitoringCaseFailsTheGuard() {
        assertThat(findings(matrix(SupportState.UNVERIFIED, SupportState.UNVERIFIED),
            List.of("ps_session_all process_diagnosis")))
            .containsExactly("MONITORING is UNVERIFIED on 6.1 without a case in "
                + "RECONSTRUCTED.txt");
    }

    @Test
    void unverifiedMonitoringAndProcessDiagnosisBackedByReconstructedCasesPass() {
        assertThat(findings(matrix(SupportState.UNVERIFIED, SupportState.UNVERIFIED),
            BOTH_RECONSTRUCTED))
            .isEmpty();
    }

    /**
     * The guard's findings for the lines of {@code RECONSTRUCTED.txt} ({@code <case>
     * <capability>}) under {@code matrix}; empty when it passes. A capability name that is not a
     * {@link Capability} throws, so a misspelt line cannot back a capability.
     */
    private static List<String> findings(CapabilityMatrix matrix, List<String> reconstructedLines) {
        Set<Capability> reconstructed = reconstructedLines.stream()
            .map(line -> Capability.valueOf(
                line.substring(line.indexOf(' ') + 1).toUpperCase(Locale.ROOT)))
            .collect(Collectors.toSet());
        List<String> findings = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            SupportState state = matrix.stateOfLine(6, OptionalInt.of(1), capability);
            if (state == SupportState.SUPPORTED) {
                findings.add(capability + " is SUPPORTED on 6.1");
            } else if (state == SupportState.UNVERIFIED) {
                if (!MAY_BE_UNVERIFIED.contains(capability)) {
                    findings.add(capability + " is UNVERIFIED on 6.1; only MONITORING and "
                        + "PROCESS_DIAGNOSIS may be");
                } else if (!reconstructed.contains(capability)) {
                    findings.add(capability + " is UNVERIFIED on 6.1 without a case in "
                        + "RECONSTRUCTED.txt");
                }
            }
        }
        return findings;
    }

    /** A test matrix whose 6.1 entry declares monitoring and process diagnosis as given. */
    private static CapabilityMatrix matrix(SupportState monitoring,
        SupportState processDiagnosis) {
        return new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
                Map.of(Capability.MONITORING, monitoring,
                    Capability.PROCESS_DIAGNOSIS, processDiagnosis))),
            Map.of());
    }
}
