package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.CoverageDocs;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 (T009, contracts/coverage-list.md → "Generated page"): docs/interface-coverage.md
 * is the rendering of the coverage list by {@link CoverageDocs}. Run with
 * {@code -Dcoverage.write=true} to rewrite the page from the list.
 */
class InterfaceCoverageDocsTest {

    private static final Path PAGE = Path.of("docs/interface-coverage.md");

    /** The command that rewrites the page; named in the failure message. */
    private static final String WRITE_COMMAND =
        "mvn -q -Dcoverage.write=true test -Dtest=InterfaceCoverageDocsTest";

    @Test
    void thePageIsTheRenderingOfTheCoverageList() throws IOException {
        String rendered = CoverageDocs.render(InterfaceCoverage.production());
        if (Boolean.getBoolean("coverage.write")) {
            Files.writeString(PAGE, rendered);
        }

        String page = Files.exists(PAGE) ? Files.readString(PAGE) : "";

        assertThat(page)
            .as("%s differs from the coverage list; rewrite it with: %s", PAGE, WRITE_COMMAND)
            .isEqualTo(rendered);
    }

    @Test
    void rendersTheSourceLineTheCountsAndOneTablePerAreaInAreaOrder() {
        InterfaceCoverage coverage = InterfaceCoverage.load(new StringReader("""
            # source: test pages
            # delivered-stories: US1
            channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
            CLI\ttag --tagDiagram\tWRITE\tE\t-\texcluded:ignored|really\texcluded:ignored|really\t-
            CLI\tmaintenance on|off\tWRITE\tA\tUS4\tplanned\tplanned\t-
            REST\tGET /license\tREAD\tA\tUS1\treachable:get_server_settings/default\tplanned\t9.0-pin
            CLI\tlicense -td\tREAD\tA\t-\tcovered:get_server_settings\tcovered:get_server_settings\t-
            REST\tGET /model/nodeByName/{diagram}/{module}\tREAD\tE\tUS3\tplanned\tundocumented\t-
            """));

        String page = CoverageDocs.render(coverage);

        assertThat(page).startsWith("# Interface coverage\n\n");
        assertThat(page.substring(page.indexOf("Source: "))).isEqualTo("""
            Source: test pages

            ## Counts

            | State | 8.1 | 9.0 |
            | --- | ---: | ---: |
            | reachable | 1 | 0 |
            | covered | 1 | 1 |
            | planned | 2 | 2 |
            | undocumented | 0 | 1 |
            | excluded | 1 | 1 |
            | all entries | 5 | 5 |

            ## A. Server state and settings

            | Entry | Kind | Story | 8.1 | 9.0 |
            | --- | --- | --- | --- | --- |
            | CLI `maintenance on\\|off` | WRITE | US4 | planned | planned |
            | REST `GET /license` | READ | US1 | reachable `get_server_settings/default` \
            | planned, waits `9.0-pin` |
            | CLI `license -td` | READ | - | covered `get_server_settings` \
            | covered `get_server_settings` |

            ## E. Diagrams, modules, repository, tags

            | Entry | Kind | Story | 8.1 | 9.0 |
            | --- | --- | --- | --- | --- |
            | CLI `tag --tagDiagram` | WRITE | - | excluded: ignored\\|really \
            | excluded: ignored\\|really |
            | REST `GET /model/nodeByName/{diagram}/{module}` | READ | US3 | planned | undocumented |
            """);
    }

    @Test
    void leavesOutTheSourceLineWhenTheListHasNone() {
        InterfaceCoverage coverage = InterfaceCoverage.load(new StringReader(
            "channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits\n"
                + "CLI\tlls\tREAD\tA\t-\texcluded:local\texcluded:local\t-\n"));

        assertThat(CoverageDocs.render(coverage)).doesNotContain("Source:").contains("## Counts");
    }
}
