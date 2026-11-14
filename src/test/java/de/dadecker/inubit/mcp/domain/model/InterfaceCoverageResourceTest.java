package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.CoverageEntry.Area;
import de.dadecker.inubit.mcp.domain.model.CoverageState.Covered;
import de.dadecker.inubit.mcp.domain.model.CoverageState.Excluded;
import de.dadecker.inubit.mcp.domain.model.CoverageState.Planned;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 (T007, contracts/coverage-list.md): the coverage list shipped as the resource
 * {@code coverage/interface-coverage.tsv} loads and holds the vendor's entries.
 */
class InterfaceCoverageResourceTest {

    private static final InterfaceCoverage COVERAGE = InterfaceCoverage.production();
    private static final MajorMinor V8_1 = new MajorMinor(8, 1);
    private static final MajorMinor V9_0 = new MajorMinor(9, 0);

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

    /** T045b: the process listing options are covered by the tools that read the same data. */
    @Test
    void psDashFIsCoveredByFindProcessesAndTheCountOptionsByGetHealth() {
        for (MajorMinor line : CoverageEntry.LINES) {
            assertThat(COVERAGE.state(Channel.CLI, "ps -f", line))
                .contains(new Covered("find_processes"));
            for (String count : new String[] {"-ne", "-nx", "-nw", "-nq"}) {
                assertThat(COVERAGE.state(Channel.CLI, count, line)).as(count)
                    .contains(new Covered("get_health"));
            }
        }
    }

    /**
     * T045b (research D-21): {@code find_processes userColumn} reads on 8.1 through REST what
     * {@code ps -u} shows; 9.0 stays planned until the US3 9.0 follow-up (T046a).
     */
    @Test
    void psDashUIsCoveredByFindProcessesOn81AndPlannedOn90() {
        assertThat(COVERAGE.state(Channel.CLI, "ps -u", V8_1))
            .contains(new Covered("find_processes"));
        assertThat(COVERAGE.state(Channel.CLI, "ps -u", V9_0)).contains(new Planned());
    }
}
