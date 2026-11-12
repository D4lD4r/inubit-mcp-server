package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.domain.model.CoverageState.Covered;
import de.dadecker.inubit.mcp.domain.model.CoverageState.Excluded;
import de.dadecker.inubit.mcp.domain.model.CoverageState.Planned;
import de.dadecker.inubit.mcp.domain.model.CoverageState.Reachable;
import de.dadecker.inubit.mcp.domain.model.CoverageState.Undocumented;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 022 (T006, research D-2, data-model.md → Coverage, contracts/coverage-list.md): the
 * coverage list loader, one test per failure rule, and the gate check {@code offered}.
 */
class InterfaceCoverageTest {

    private static final MajorMinor V8_1 = new MajorMinor(8, 1);
    private static final MajorMinor V9_0 = new MajorMinor(9, 0);

    private static final String HEADER = "channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits";

    /** The example of contracts/coverage-list.md. */
    private static final String EXAMPLE = """
        # source: vendor pages "Command Line Interface" and "REST API", INUBIT 8.1 and 9.0, retrieved 2026-11-12
        # delivered-stories: US1
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        REST\tGET /license\tREAD\tA\tUS1\treachable:get_server_settings/default\tplanned\t9.0-pin
        CLI\tlicense -td\tREAD\tA\t-\tcovered:get_server_settings\tcovered:get_server_settings\t-
        CLI\tlls\tREAD\tA\t-\texcluded:lists the local directory of the StartCLI client\texcluded:lists the local directory of the StartCLI client\t-
        REST\tGET /model/nodeByName/{diagram}/{module}\tREAD\tE\tUS3\tplanned\tundocumented\t-
        REST\tGET /process/processes/{group}\tREAD\tPORTAL\t-\texcluded:portal function\texcluded:portal function\t-
        CLI\ttag --tagDiagram\tWRITE\tE\t-\texcluded:ignored by INUBIT, tags every diagram of the owner\texcluded:ignored by INUBIT, tags every diagram of the owner\t-
        CLI\texport --exportWorkflowGroup\tEXPORT\tE\t-\treachable:export_archives/default\treachable:export_archives/default\t-
        """;

    // --- loading --------------------------------------------------------------------------

    @Test
    void loadsTheContractExampleInFileOrder() {
        InterfaceCoverage coverage = loadText(EXAMPLE);

        assertThat(coverage.entries()).extracting(CoverageEntry::entry).containsExactly(
            "GET /license", "license -td", "lls", "GET /model/nodeByName/{diagram}/{module}",
            "GET /process/processes/{group}", "tag --tagDiagram", "export --exportWorkflowGroup");
        assertThat(coverage.entries().get(0)).isEqualTo(new CoverageEntry(Channel.REST,
            "GET /license", CoverageEntry.Kind.READ, CoverageEntry.Area.A, Optional.of("US1"),
            Map.of(V8_1, new Reachable("get_server_settings", "default"), V9_0, new Planned()),
            List.of("9.0-pin")));
        assertThat(coverage.entries().get(4).area()).isEqualTo(CoverageEntry.Area.PORTAL);
        assertThat(coverage.entries().get(5).kind()).isEqualTo(CoverageEntry.Kind.WRITE);
        assertThat(coverage.entries().get(1).story()).isEmpty();
        assertThat(coverage.entries().get(1).waits()).isEmpty();
    }

    @Test
    void stateReadsEveryStateWordPerLine() {
        InterfaceCoverage coverage = loadText(EXAMPLE);

        assertThat(coverage.state(Channel.REST, "GET /license", V8_1))
            .contains(new Reachable("get_server_settings", "default"));
        assertThat(coverage.state(Channel.REST, "GET /license", V9_0)).contains(new Planned());
        assertThat(coverage.state(Channel.CLI, "license -td", V9_0))
            .contains(new Covered("get_server_settings"));
        assertThat(coverage.state(Channel.CLI, "lls", V8_1))
            .contains(new Excluded("lists the local directory of the StartCLI client"));
        assertThat(coverage.state(Channel.REST, "GET /model/nodeByName/{diagram}/{module}", V9_0))
            .contains(new Undocumented());
    }

    @Test
    void stateIsEmptyForAnUnlistedEntryChannelOrLine() {
        InterfaceCoverage coverage = loadText(EXAMPLE);

        assertThat(coverage.state(Channel.REST, "GET /unknown", V8_1)).isEmpty();
        assertThat(coverage.state(Channel.CLI, "GET /license", V8_1)).isEmpty();
        assertThat(coverage.state(Channel.REST, "GET /license", new MajorMinor(6, 1))).isEmpty();
    }

    @Test
    void waitsItemsAreJoinedByPlus() {
        InterfaceCoverage coverage = load(
            "REST\tGET /license\tREAD\tA\tUS1\tplanned\tplanned\tL-1+9.0-pin",
            "REST\tGET /system/info\tREAD\tA\tUS1\tplanned\tplanned\tL-18");

        assertThat(coverage.entries()).extracting(CoverageEntry::waits)
            .containsExactly(List.of("L-1", "9.0-pin"), List.of("L-18"));
    }

    @Test
    void anExclusionReasonOf120CharactersIsAccepted() {
        String reason = "r".repeat(120);

        InterfaceCoverage coverage = load(
            "CLI\tlls\tREAD\tA\t-\texcluded:" + reason + "\texcluded:" + reason + "\t-");

        assertThat(coverage.state(Channel.CLI, "lls", V8_1)).contains(new Excluded(reason));
    }

    @Test
    void anEmptyDeliveredStoriesLineDeliversNothing() {
        InterfaceCoverage coverage = InterfaceCoverage.load(new StringReader(
            "# delivered-stories:\n" + HEADER + "\n"
                + "REST\tGET /license\tREAD\tA\tUS1\tplanned\tplanned\t-\n"));

        assertThat(coverage.entries()).hasSize(1);
    }

    // --- failure rules ------------------------------------------------------------------------

    @Test
    void failsOnADuplicateKey() {
        assertFails("line 5", "duplicate",
            "CLI\tlls\tREAD\tA\t-\tcovered:t\tcovered:t\t-",
            "CLI\tlls\tWRITE\tB\t-\tcovered:u\tcovered:u\t-");
    }

    @Test
    void theSameEntryOnTheOtherChannelIsNoDuplicate() {
        InterfaceCoverage coverage = load(
            "CLI\tlicense\tREAD\tA\t-\tcovered:t\tcovered:t\t-",
            "REST\tlicense\tREAD\tA\t-\tcovered:t\tcovered:t\t-");

        assertThat(coverage.entries()).hasSize(2);
    }

    @Test
    void failsOnAMissingVersionState() {
        assertFails("line 4", "missing state",
            "CLI\tlls\tREAD\tA\t-\tcovered:t\t\t-");
    }

    @Test
    void failsOnARowWithoutAllColumns() {
        assertFails("line 4", "8 columns",
            "CLI\tlls\tREAD\tA\t-\tcovered:t\t-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"supported", "Planned", "reachable", "reachable:tool",
        "reachable:/action", "reachable:tool/", "covered", "covered:", "excluded", "excluded:"})
    void failsOnAnUnknownOrIncompleteStateWord(String state) {
        assertFails("line 4", "state",
            "CLI\tlls\tREAD\tA\t-\t" + state + "\tcovered:t\t-");
    }

    @Test
    void failsOnAReasonOver120Characters() {
        assertFails("line 4", "120",
            "CLI\tlls\tREAD\tA\t-\texcluded:" + "r".repeat(121) + "\tcovered:t\t-");
    }

    @Test
    void failsOnPlannedWithoutStory() {
        assertFails("line 4", "story",
            "CLI\tlls\tREAD\tA\t-\tcovered:t\tplanned\t-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"L-0", "L-19", "L-01", "L1", "9.0", "pin", "", "L-1+", "+L-1",
        "L-1++9.0-pin", "L-1,9.0-pin", "L-1 + 9.0-pin"})
    void failsOnAnUnknownWaitsValue(String waits) {
        assertFails("line 4", "waits",
            "REST\tGET /license\tREAD\tA\tUS1\tplanned\tplanned\t" + waits);
    }

    @Test
    void failsOnWaitsOnARowThatIsNotPlanned() {
        assertFails("line 4", "waits",
            "REST\tGET /license\tREAD\tA\tUS1\treachable:t/default\tcovered:t\tL-1");
    }

    @Test
    void failsOnWaitsOnARowOfAStoryNotYetDelivered() {
        assertFails("line 4", "not delivered",
            "REST\tGET /license\tREAD\tA\tUS2\tplanned\tplanned\tL-1");
    }

    @Test
    void failsOnAPlannedRowOfADeliveredStoryWithoutWaits() {
        assertFails("line 4", "waits",
            "REST\tGET /license\tREAD\tA\tUS1\treachable:t/default\tplanned\t-");
    }

    @Test
    void failsOnAWrongHeader() {
        assertThatIllegalArgumentException().isThrownBy(() -> InterfaceCoverage.load(
                new StringReader("channel\tentry\tkind\tarea\tstory\tv9_0\tv8_1\twaits\n")))
            .withMessageContaining("line 1").withMessageContaining("header");
    }

    @ParameterizedTest
    @MethodSource("rowsWithAnUnknownField")
    void failsOnAnUnknownChannelKindAreaStoryOrAnEmptyEntry(String reason, String row) {
        assertFails("line 4", reason, row);
    }

    static Stream<Arguments> rowsWithAnUnknownField() {
        return Stream.of(
            Arguments.of("unknown channel", "FTP\tGET /license\tREAD\tA\t-\tcovered:t\tcovered:t\t-"),
            Arguments.of("must be CLI or REST",
                "SOAP\tGET /license\tREAD\tA\t-\tcovered:t\tcovered:t\t-"),
            Arguments.of("unknown kind", "REST\tGET /license\tDELETE\tA\t-\tcovered:t\tcovered:t\t-"),
            Arguments.of("unknown area", "REST\tGET /license\tREAD\tF\t-\tcovered:t\tcovered:t\t-"),
            Arguments.of("story", "REST\tGET /license\tREAD\tA\tUS8\tcovered:t\tcovered:t\t-"),
            Arguments.of("entry", "REST\t\tREAD\tA\t-\tcovered:t\tcovered:t\t-"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USl", "US8", "US 1", "us1"})
    void failsOnAnUnknownDeliveredStory(String story) {
        assertThatIllegalArgumentException().isThrownBy(() -> InterfaceCoverage.load(
                new StringReader("# source: test\n# delivered-stories: US1, " + story + "\n"
                    + HEADER + "\n" + "CLI\tlls\tREAD\tA\t-\tcovered:t\tcovered:t\t-\n")))
            .withMessageContaining("line 2").withMessageContaining("delivered story");
    }

    // --- offered ------------------------------------------------------------------------------

    @Test
    void offeredWhenEveryGivenEntryIsReachableForTheLine() {
        InterfaceCoverage coverage = load(
            "REST\tGET /a\tREAD\tA\t-\treachable:t/x\treachable:t/x\t-",
            "CLI\tb -c\tREAD\tA\tUS1\treachable:t/x\tplanned\t9.0-pin");

        Set<CoverageKey> call = Set.of(new CoverageKey(Channel.REST, "GET /a"),
            new CoverageKey(Channel.CLI, "b -c"));

        assertThat(coverage.offered(call, V8_1)).isTrue();
        assertThat(coverage.offered(call, V9_0)).isFalse();
        assertThat(coverage.offered(Set.of(new CoverageKey(Channel.REST, "GET /a")), V9_0))
            .isTrue();
    }

    @Test
    void notOfferedForCoveredExcludedOrUndocumentedEntries() {
        InterfaceCoverage coverage = loadText(EXAMPLE);

        assertThat(coverage.offered(Set.of(new CoverageKey(Channel.CLI, "license -td")), V8_1))
            .isFalse();
        assertThat(coverage.offered(Set.of(new CoverageKey(Channel.CLI, "lls")), V8_1)).isFalse();
        assertThat(coverage.offered(Set.of(
            new CoverageKey(Channel.REST, "GET /model/nodeByName/{diagram}/{module}")), V9_0))
            .isFalse();
    }

    @Test
    void notOfferedForAnUnlistedEntryAnUnlistedLineOrNoEntry() {
        InterfaceCoverage coverage = loadText(EXAMPLE);
        CoverageKey reachable = new CoverageKey(Channel.CLI, "export --exportWorkflowGroup");

        assertThat(coverage.offered(Set.of(reachable), V8_1)).isTrue();
        assertThat(coverage.offered(Set.of(reachable, new CoverageKey(Channel.CLI, "nope")), V8_1))
            .isFalse();
        assertThat(coverage.offered(Set.of(reachable), new MajorMinor(6, 1))).isFalse();
        assertThat(coverage.offered(Set.of(), V8_1)).isFalse();
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Loads {@code rows} under a comment, {@code delivered-stories: US1} and the header. */
    private static InterfaceCoverage load(String... rows) {
        return InterfaceCoverage.load(new StringReader(document(rows)));
    }

    private static InterfaceCoverage loadText(String text) {
        return InterfaceCoverage.load(new StringReader(text));
    }

    private static String document(String... rows) {
        return "# source: test\n# delivered-stories: US1\n" + HEADER + "\n"
            + String.join("\n", rows) + "\n";
    }

    private static void assertFails(String line, String reason, String... rows) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> InterfaceCoverage.load(new StringReader(document(rows))))
            .withMessageContaining(line).withMessageContaining(reason);
    }
}
