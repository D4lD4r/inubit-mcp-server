package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T040 (feature 025, spec FR-015 as amended 2026-11-26, SC-009): the guard for the unverified 7.4.
 * {@link CapabilityMatrix#production()} declares no 7.4 capability {@code SUPPORTED}, no capability
 * other than monitoring and process diagnosis {@code UNVERIFIED}, and either of these two
 * {@code UNVERIFIED} only while it has a case in {@code fixtures/v7_4/cli/RECONSTRUCTED.txt} and
 * {@code help} is in the README's recorded list. The rules on missing files and on the three lists
 * are {@link V74FixturesShapeTest}'s.
 */
class V74RecordedFixturesTest {

    /** The only capabilities that may be {@code UNVERIFIED} on 7.4. */
    private static final Set<Capability> MAY_BE_UNVERIFIED =
        Set.of(Capability.MONITORING, Capability.PROCESS_DIAGNOSIS);

    @Test
    void theProductionMatrixPassesTheGuard() throws IOException {
        assertThat(findings(CapabilityMatrix.production(), V74FixturesShapeTest.FOLDER))
            .as("7.4 capabilities the guard rejects")
            .isEmpty();
    }

    @Test
    void theFixturesBackMonitoringAndProcessDiagnosisAsUnverified() throws IOException {
        assertThat(findings(matrix(SupportState.UNVERIFIED, SupportState.UNVERIFIED),
            V74FixturesShapeTest.FOLDER))
            .isEmpty();
    }

    @Test
    void aSupportedMonitoringFailsTheGuard(@TempDir Path folder) throws IOException {
        write(folder, "- help attachment", """
            health_session_ok monitoring
            ps_session_all process_diagnosis
            """);

        assertThat(findings(matrix(SupportState.SUPPORTED, SupportState.UNSUPPORTED), folder))
            .containsExactly("MONITORING is SUPPORTED on 7.4");
    }

    @Test
    void anUnverifiedLogDiagnosisFailsTheGuard(@TempDir Path folder) throws IOException {
        write(folder, "- help attachment", "health_session_ok monitoring\n");
        CapabilityMatrix matrix = new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(7, 4, 7, 4),
                Map.of(Capability.LOG_DIAGNOSIS, SupportState.UNVERIFIED))),
            Map.of());

        assertThat(findings(matrix, folder))
            .containsExactly("LOG_DIAGNOSIS is UNVERIFIED on 7.4; only MONITORING and "
                + "PROCESS_DIAGNOSIS may be");
    }

    @Test
    void anUnverifiedMonitoringWithoutAReconstructedMonitoringCaseFailsTheGuard(
        @TempDir Path folder) throws IOException {
        write(folder, "- help attachment", "ps_session_all process_diagnosis\n");

        assertThat(findings(matrix(SupportState.UNVERIFIED, SupportState.UNVERIFIED), folder))
            .containsExactly("MONITORING is UNVERIFIED on 7.4 without a case in "
                + "RECONSTRUCTED.txt");
    }

    @Test
    void anUnverifiedMonitoringWhileHelpIsNotRecordedFailsTheGuard(@TempDir Path folder)
        throws IOException {
        write(folder, "- version v74_version", "health_session_ok monitoring\n");

        assertThat(findings(matrix(SupportState.UNVERIFIED, SupportState.UNSUPPORTED), folder))
            .containsExactly("MONITORING is UNVERIFIED on 7.4 while help is not in the "
                + "README's recorded list");
    }

    @Test
    void unverifiedMonitoringAndProcessDiagnosisBackedByReconstructedCasesAndHelpPass(
        @TempDir Path folder) throws IOException {
        write(folder, "- help attachment", """
            health_session_ok monitoring
            ps_session_all process_diagnosis
            """);

        assertThat(findings(matrix(SupportState.UNVERIFIED, SupportState.UNVERIFIED), folder))
            .isEmpty();
    }

    /**
     * The guard's findings for the fixture lists in {@code folder} under {@code matrix}; empty
     * when it passes. A capability name in {@code RECONSTRUCTED.txt} that is not a
     * {@link Capability} throws, so a misspelt line cannot back a capability.
     */
    private static List<String> findings(CapabilityMatrix matrix, Path folder)
        throws IOException {
        Set<Capability> reconstructed = V74FixturesShapeTest
            .listLines(folder.resolve("RECONSTRUCTED.txt")).stream()
            .map(line -> Capability.valueOf(line.split(" ")[1].toUpperCase(Locale.ROOT)))
            .collect(Collectors.toSet());
        boolean helpRecorded = V74FixturesShapeTest.recordedLines(folder).stream()
            .anyMatch(line -> line.split(" ")[1].equals("help"));
        List<String> findings = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            SupportState state = matrix.stateOfLine(7, OptionalInt.of(4), capability);
            if (state == SupportState.SUPPORTED) {
                findings.add(capability + " is SUPPORTED on 7.4");
            } else if (state == SupportState.UNVERIFIED) {
                if (!MAY_BE_UNVERIFIED.contains(capability)) {
                    findings.add(capability + " is UNVERIFIED on 7.4; only MONITORING and "
                        + "PROCESS_DIAGNOSIS may be");
                    continue;
                }
                if (!reconstructed.contains(capability)) {
                    findings.add(capability + " is UNVERIFIED on 7.4 without a case in "
                        + "RECONSTRUCTED.txt");
                }
                if (!helpRecorded) {
                    findings.add(capability + " is UNVERIFIED on 7.4 while help is not in the "
                        + "README's recorded list");
                }
            }
        }
        return findings;
    }

    /** A test matrix whose 7.4 entry declares monitoring and process diagnosis as given. */
    private static CapabilityMatrix matrix(SupportState monitoring,
        SupportState processDiagnosis) {
        return new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(7, 4, 7, 4),
                Map.of(Capability.MONITORING, monitoring,
                    Capability.PROCESS_DIAGNOSIS, processDiagnosis))),
            Map.of());
    }

    /** The README with {@code recorded} as its recorded list, and {@code RECONSTRUCTED.txt}. */
    private static void write(Path folder, String recorded, String reconstructed)
        throws IOException {
        Files.writeString(folder.resolve("README.md"),
            "# Fixtures\n\n" + V74FixturesShapeTest.RECORDED_HEADING + "\n\n" + recorded + "\n");
        Files.writeString(folder.resolve("RECONSTRUCTED.txt"), "# comment\n" + reconstructed);
    }
}
