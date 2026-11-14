package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.CoverageEntry.Area;
import de.dadecker.inubit.mcp.domain.model.CoverageState.Excluded;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 (T007, contracts/coverage-list.md): the coverage list shipped as the resource
 * {@code coverage/interface-coverage.tsv} loads and holds the vendor's entries.
 */
class InterfaceCoverageResourceTest {

    private static final InterfaceCoverage COVERAGE = InterfaceCoverage.production();

    @Test
    void theResourceLoads() {
        assertThat(COVERAGE.entries()).isNotEmpty();
    }

    @Test
    void everyAreaAToEHasARow() {
        assertThat(COVERAGE.entries()).extracting(CoverageEntry::area)
            .contains(Area.A, Area.B, Area.C, Area.D, Area.E);
    }

    @Test
    void noPlannedRowIsWithoutAStory() {
        assertThat(COVERAGE.entries()).filteredOn(CoverageEntry::planned)
            .allSatisfy(entry -> assertThat(entry.story()).isPresent());
    }

    @Test
    void llsIsExcludedOnBothLines() {
        for (MajorMinor line : CoverageEntry.LINES) {
            assertThat(COVERAGE.state(Channel.CLI, "lls", line))
                .hasValueSatisfying(state -> assertThat(state).isInstanceOf(Excluded.class));
        }
    }
}
