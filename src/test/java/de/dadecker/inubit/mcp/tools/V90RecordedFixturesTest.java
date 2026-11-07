package de.dadecker.inubit.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.V90Cases;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * T036 (feature 017, research D-10, SC-006): names every case of the 9.0 catalogue of
 * {@code tools/record-fixtures.sh} whose {@code .origin} is not {@code recorded …}, i.e. still
 * documentation-derived. The test is skipped with that list and never fails, for release builds
 * neither: the person releases 0.8.0 without the live check and accepted the risk; the live
 * recording after the release turns every catalogue origin into {@code recorded} (quickstart
 * step 12). The cases of {@code NOT_RECORDABLE.txt} are not part of the catalogue.
 */
class V90RecordedFixturesTest {

    @Test
    void everyCatalogueCaseIsRecordedElseTheCasesAreListed() throws IOException {
        List<String> pending = notYetRecorded(RecordFixturesCatalogueTest.v90CasesOfTheScript(),
            V90Cases::origin);

        Assumptions.assumeTrue(pending.isEmpty(), () -> message(pending));
    }

    @Test
    void onlyARecordedOriginCounts() {
        Map<String, String> origins = Map.of(
            "ready", "recorded 2026-11-20 9.0.7",
            "help", "reconstructed docs/research/inubit-interfaces.md §2.1",
            "ps_all", "reconstructed copied from a recorded 8.1 case");

        assertThat(notYetRecorded(List.of("ready", "help", "ps_all"), origins::get))
            .containsExactly("help", "ps_all");
        assertThat(message(List.of("help", "ps_all")))
            .isEqualTo("INUBIT 9.0: 2 catalogue cases are not recorded yet (documentation-derived,"
                + " to be recorded after the release with RECORD_INUBIT_LINE=9.0"
                + " tools/record-fixtures.sh): help, ps_all");
    }

    /** The catalogue cases whose origin does not start with {@code recorded }, in order. */
    static List<String> notYetRecorded(Collection<String> catalogue,
        Function<String, String> origin) {
        return catalogue.stream().filter(name -> !origin.apply(name).startsWith("recorded "))
            .toList();
    }

    static String message(List<String> pending) {
        return "INUBIT 9.0: " + pending.size() + " catalogue cases are not recorded yet"
            + " (documentation-derived, to be recorded after the release with"
            + " RECORD_INUBIT_LINE=9.0 tools/record-fixtures.sh): " + String.join(", ", pending);
    }
}
