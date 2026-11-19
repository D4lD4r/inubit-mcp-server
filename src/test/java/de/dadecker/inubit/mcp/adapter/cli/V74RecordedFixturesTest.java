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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T019 (feature 025, spec FR-015, FR-016a, FR-030): a capability that
 * {@link CapabilityMatrix#production()} declares {@code SUPPORTED} on 7.4 has (a) no case left in
 * {@code fixtures/v7_4/cli/RECONSTRUCTED.txt} and (b) no case in {@code DERIVED.txt} whose source
 * case is missing from the README's recorded list; and (c) while any 7.4 capability is
 * {@code SUPPORTED}, the README's recorded line for {@code help} names {@code v74_help} as its
 * source.
 */
class V74RecordedFixturesTest {

    @Test
    void noCapabilitySupportedOn74InProductionRestsOnUnrecordedFixtures() throws IOException {
        assertThat(unrecordedButSupported(CapabilityMatrix.production(),
            V74FixturesShapeTest.FOLDER))
            .as("fixtures of capabilities SUPPORTED on 7.4 that are not recorded")
            .isEmpty();
    }

    @Test
    void aSupportedCapabilityWithAReconstructedCaseFailsTheGuard(@TempDir Path folder)
        throws IOException {
        write(folder, "- help v74_help", """
            health_session_ok monitoring
            ps_session_all process_diagnosis
            """, "");

        assertThat(unrecordedButSupported(monitoring(SupportState.SUPPORTED), folder))
            .containsExactly("RECONSTRUCTED.txt: health_session_ok monitoring");
    }

    @Test
    void aSupportedCapabilityWithADerivedCaseOfAnUnrecordedSourceFailsTheGuard(
        @TempDir Path folder) throws IOException {
        write(folder, """
            - help v74_help
            - v74_health_session v74_health_session
            """, "", """
            health_session_maintenance v74_health_session monitoring maintenance switched on
            health_session_alive_failed health_session_ok monitoring alive check fails
            ps_session_empty ps_session_error process_diagnosis rows removed
            """);

        assertThat(unrecordedButSupported(monitoring(SupportState.SUPPORTED), folder))
            .containsExactly("DERIVED.txt: health_session_alive_failed health_session_ok "
                + "monitoring alive check fails");
    }

    @Test
    void aSupportedCapabilityWhileHelpIsNotTheV74HelpRecordingFailsTheGuard(
        @TempDir Path folder) throws IOException {
        write(folder, "- help attachment", "", "");

        assertThat(unrecordedButSupported(monitoring(SupportState.SUPPORTED), folder))
            .containsExactly("README.md: help is not recorded from v74_help: - help attachment");
    }

    @Test
    void withoutASupportedCapabilityTheSameListsPassTheGuard(@TempDir Path folder)
        throws IOException {
        write(folder, "- help attachment", "health_session_ok monitoring\n",
            "health_session_alive_failed health_session_ok monitoring alive check fails\n");

        assertThat(unrecordedButSupported(monitoring(SupportState.UNSUPPORTED), folder)).isEmpty();
    }

    /**
     * The guard's findings for the fixture lists in {@code folder} under {@code matrix}; empty
     * when no 7.4 capability is {@code SUPPORTED}. A capability name that is not a
     * {@link Capability} throws, so a misspelt line cannot slip past the guard.
     */
    private static List<String> unrecordedButSupported(CapabilityMatrix matrix, Path folder)
        throws IOException {
        List<String> findings = new ArrayList<>();
        for (String line : V74FixturesShapeTest.listLines(folder.resolve("RECONSTRUCTED.txt"))) {
            if (supported(matrix, line.split(" ")[1])) {
                findings.add("RECONSTRUCTED.txt: " + line);
            }
        }
        List<String> recordedLines = V74FixturesShapeTest.recordedLines(folder);
        Set<String> recorded = recordedLines.stream()
            .map(line -> line.split(" ")[1])
            .collect(Collectors.toSet());
        for (String line : V74FixturesShapeTest.listLines(folder.resolve("DERIVED.txt"))) {
            String[] fields = line.split(" ");
            if (supported(matrix, fields[2]) && !recorded.contains(fields[1])) {
                findings.add("DERIVED.txt: " + line);
            }
        }
        boolean anySupported = Arrays.stream(Capability.values())
            .anyMatch(capability -> supported(matrix, capability));
        if (anySupported && !recordedLines.contains("- help v74_help")) {
            findings.add("README.md: help is not recorded from v74_help: " + recordedLines.stream()
                .filter(line -> line.startsWith("- help ")).findFirst().orElse("no help line"));
        }
        return findings;
    }

    private static boolean supported(CapabilityMatrix matrix, String capabilityName) {
        return supported(matrix, Capability.valueOf(capabilityName.toUpperCase(Locale.ROOT)));
    }

    private static boolean supported(CapabilityMatrix matrix, Capability capability) {
        return matrix.stateOfLine(7, OptionalInt.of(4), capability) == SupportState.SUPPORTED;
    }

    /**
     * A test matrix whose 7.4 entry declares monitoring {@code monitoring} and process diagnosis
     * {@code UNSUPPORTED}.
     */
    private static CapabilityMatrix monitoring(SupportState monitoring) {
        return new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(7, 4, 7, 4),
                Map.of(Capability.MONITORING, monitoring,
                    Capability.PROCESS_DIAGNOSIS, SupportState.UNSUPPORTED))),
            Map.of());
    }

    /** The README with {@code recorded} as its recorded list, and both list files. */
    private static void write(Path folder, String recorded, String reconstructed, String derived)
        throws IOException {
        Files.writeString(folder.resolve("README.md"),
            "# Fixtures\n\n" + V74FixturesShapeTest.RECORDED_HEADING + "\n\n" + recorded + "\n");
        Files.writeString(folder.resolve("RECONSTRUCTED.txt"), "# comment\n" + reconstructed);
        Files.writeString(folder.resolve("DERIVED.txt"), "# comment\n" + derived);
    }
}
