package de.dadecker.inubit.mcp.domain.model;

import java.util.List;

/**
 * Renders the coverage list as the page docs/interface-coverage.md (feature 022,
 * contracts/coverage-list.md → "Generated page"): the source line, the counts per state and
 * version line, and one table per area in the order A–E, portal, start, with the rows in file
 * order. Areas without a row get no table.
 */
public final class CoverageDocs {

    private static final String INTRO = """
        # Interface coverage

        <!-- Generated from src/main/resources/coverage/interface-coverage.tsv by
        InterfaceCoverageDocsTest; change the list, not this page. -->

        Every documented entry of the INUBIT StartCLI and REST interfaces with its state on each
        version line: `reachable` (a tool sends it, shown as tool/action), `covered` (a tool reads
        the same facts through another entry), `planned` (not yet verified with a recording, with
        what it waits for), `undocumented` (not documented by the vendor for this version) or
        `excluded` (never sent, with the reason).
        """;

    /** The state words in the order of the counts table. */
    private static final List<String> STATES =
        List.of("reachable", "covered", "planned", "undocumented", "excluded");

    private CoverageDocs() {
    }

    /** The page for {@code coverage}, with LF line ends. */
    public static String render(InterfaceCoverage coverage) {
        StringBuilder page = new StringBuilder(INTRO);
        coverage.source().ifPresent(source -> page.append("\nSource: ").append(source)
            .append('\n'));
        page.append("\n## Counts\n\n| State |");
        CoverageEntry.LINES.forEach(line -> page.append(' ').append(line.text()).append(" |"));
        page.append("\n| --- |").append(" ---: |".repeat(CoverageEntry.LINES.size())).append('\n');
        for (String state : STATES) {
            page.append("| ").append(state).append(" |");
            for (MajorMinor line : CoverageEntry.LINES) {
                long count = coverage.entries().stream()
                    .filter(entry -> word(entry.states().get(line)).equals(state)).count();
                page.append(' ').append(count).append(" |");
            }
            page.append('\n');
        }
        page.append("| all entries |");
        CoverageEntry.LINES.forEach(line ->
            page.append(' ').append(coverage.entries().size()).append(" |"));
        page.append('\n');
        for (CoverageEntry.Area area : CoverageEntry.Area.values()) {
            List<CoverageEntry> rows = coverage.entries().stream()
                .filter(entry -> entry.area() == area).toList();
            if (!rows.isEmpty()) {
                table(page, area, rows);
            }
        }
        return page.toString();
    }

    private static void table(StringBuilder page, CoverageEntry.Area area,
        List<CoverageEntry> rows) {
        page.append("\n## ").append(title(area)).append("\n\n| Entry | Kind | Story |");
        CoverageEntry.LINES.forEach(line -> page.append(' ').append(line.text()).append(" |"));
        page.append("\n| --- | --- | --- |").append(" --- |".repeat(CoverageEntry.LINES.size()))
            .append('\n');
        for (CoverageEntry entry : rows) {
            page.append("| ").append(entry.channel().name()).append(" `")
                .append(escape(entry.entry())).append("` | ").append(entry.kind().name())
                .append(" | ").append(entry.story().orElse("-")).append(" |");
            for (MajorMinor line : CoverageEntry.LINES) {
                page.append(' ').append(cell(entry, entry.states().get(line))).append(" |");
            }
            page.append('\n');
        }
    }

    /** The area names of the spec Background. */
    private static String title(CoverageEntry.Area area) {
        return switch (area) {
            case A -> "A. Server state and settings";
            case B -> "B. Users and groups";
            case C -> "C. Libraries, plug-ins, EDI rules, keys";
            case D -> "D. Processes and run-time data";
            case E -> "E. Diagrams, modules, repository, tags";
            case PORTAL -> "Portal functions";
            case START -> "Start options";
        };
    }

    private static String word(CoverageState state) {
        return switch (state) {
            case CoverageState.Reachable reachable -> "reachable";
            case CoverageState.Covered covered -> "covered";
            case CoverageState.Planned planned -> "planned";
            case CoverageState.Undocumented undocumented -> "undocumented";
            case CoverageState.Excluded excluded -> "excluded";
        };
    }

    private static String cell(CoverageEntry entry, CoverageState state) {
        return switch (state) {
            case CoverageState.Reachable reachable ->
                "reachable `" + reachable.tool() + "/" + reachable.action() + "`";
            case CoverageState.Covered covered -> "covered `" + covered.tool() + "`";
            case CoverageState.Planned planned -> entry.waits().isEmpty() ? "planned"
                : "planned, waits `" + String.join("+", entry.waits()) + "`";
            case CoverageState.Undocumented undocumented -> "undocumented";
            case CoverageState.Excluded excluded -> "excluded: " + escape(excluded.reason());
        };
    }

    /** A {@code |} would end the table cell, also inside a code span. */
    private static String escape(String text) {
        return text.replace("|", "\\|");
    }
}
