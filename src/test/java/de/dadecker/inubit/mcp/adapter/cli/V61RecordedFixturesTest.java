package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * T034 (feature 011, FR-005, research D-15): a capability that
 * {@link CapabilityMatrix#production()} declares {@code SUPPORTED} on 6.1 has no case left in
 * {@code fixtures/v6_1/cli/RECONSTRUCTED.txt}: its fixtures are recordings of a real 6.1 node, not
 * reconstructions.
 */
class V61RecordedFixturesTest {

    @Test
    void noCapabilitySupportedOn61InProductionHasReconstructedCases() {
        assertThat(reconstructedButSupported(CapabilityMatrix.production(),
            V61FixturesShapeTest.entries()))
            .as("cases in RECONSTRUCTED.txt of capabilities SUPPORTED on 6.1")
            .isEmpty();
    }

    @Test
    void aMatrixThatSupportsACapabilityWhoseCasesAreStillListedFailsTheGuard() {
        CapabilityMatrix flipped = new CapabilityMatrix(
            List.of(new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1),
                Map.of(Capability.MONITORING, SupportState.SUPPORTED,
                    Capability.PROCESS_DIAGNOSIS, SupportState.UNSUPPORTED))),
            Map.of());

        List<String> listed = List.of("health_session_ok monitoring",
            "ps_session_all process_diagnosis");

        assertThat(reconstructedButSupported(flipped, listed))
            .containsExactly("health_session_ok monitoring");
    }

    /**
     * The lines of {@code entries} ({@code <case> <capability>}) whose capability {@code matrix}
     * declares {@code SUPPORTED} on 6.1. A capability name that is not a {@link Capability}
     * throws, so a misspelt line cannot slip past the guard.
     */
    private static List<String> reconstructedButSupported(CapabilityMatrix matrix,
        List<String> entries) {
        return entries.stream()
            .filter(line -> {
                String name = line.substring(line.indexOf(' ') + 1);
                Capability capability = Capability.valueOf(name.toUpperCase(Locale.ROOT));
                return matrix.stateOfLine(6, OptionalInt.of(1), capability)
                    == SupportState.SUPPORTED;
            })
            .toList();
    }
}
