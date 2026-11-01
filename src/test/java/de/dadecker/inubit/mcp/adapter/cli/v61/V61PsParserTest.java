package de.dadecker.inubit.mcp.adapter.cli.v61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand.Listing;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T031 (feature 011, FR-010, FR-017, research D-10): the {@code ps} listings of a StartCLI 6.1
 * process session, against the reconstructed T029 fixtures and malformed variants of them.
 */
class V61PsParserTest {

    private static final NodeId NODE = NodeId.of("legacy", "node1");
    /** 09:00 in Berlin (CET, daylight saving time ended on 2026-10-25). */
    private static final Instant NOW = Instant.parse("2026-11-01T08:00:00Z");
    private static final Duration THRESHOLD = Duration.ofHours(2);

    private static final List<CliSessionCommand> ALL =
        List.of(CliSessionCommand.psList(Listing.ALL, CliSessionCommand.MAX_ROWS));
    private static final List<CliSessionCommand> ERROR =
        List.of(CliSessionCommand.psList(Listing.ERROR, CliSessionCommand.MAX_ROWS));
    private static final List<CliSessionCommand> WAITING_QUEUED = List.of(
        CliSessionCommand.psList(Listing.WAITING, CliSessionCommand.MAX_ROWS),
        CliSessionCommand.psList(Listing.QUEUED, CliSessionCommand.MAX_ROWS));

    private static final String HEADER = "NODE                 UID             PID        "
        + "PRIO       STATE      DATE                 WORKFLOW             MODULE";

    private static String stdout(String fixtureCase) {
        return FakeProcessLauncher.fixtureText("v6_1", fixtureCase + ".stdout");
    }

    private static List<V61PsParser.Listing> parse(String stdout,
        List<CliSessionCommand> commands) {
        return V61PsParser.parse(stdout, commands, NODE, NOW, THRESHOLD);
    }

    private static V61PsParser.Listing parseOne(String stdout) {
        List<V61PsParser.Listing> listings = parse(stdout, ALL);
        assertThat(listings).hasSize(1);
        return listings.getFirst();
    }

    private static void assertUnexpected(String stdout) {
        assertThatThrownBy(() -> parse(stdout, ALL))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    /** A process session with one listing of {@code lines}. */
    private static String session(String... lines) {
        return "Password: \ncli> " + String.join("\n", lines) + "\ncli> \n";
    }

    @Test
    void anEnglishRowIsReadIntoEveryField() {
        V61PsParser.Listing listing = parseOne(stdout("ps_session_all"));

        assertThat(listing.total()).isEqualTo(9);
        assertThat(listing.instances().getFirst()).isEqualTo(new ProcessInstance(NODE,
            "400101", Optional.empty(), ProcessState.ERROR, "Error",
            Instant.parse("2026-10-30T07:15:42Z"),
            Duration.ofDays(2).plusMinutes(44).plusSeconds(18), false,
            Optional.of("OrderImport"), Optional.of("Mapping(10234)"), Optional.empty(),
            Optional.empty(), Optional.of("node1"), Optional.of("jdoe"),
            Optional.of("normal")));
    }

    @Test
    void everyRawStateIsMappedAndKeptInListingOrder() {
        assertThat(parseOne(stdout("ps_session_all")).instances())
            .extracting(ProcessInstance::processId, ProcessInstance::state,
                ProcessInstance::rawState)
            .containsExactly(
                tuple("400101", ProcessState.ERROR, "Error"),
                tuple("400102", ProcessState.WAITING, "Waiting"),
                tuple("400103", ProcessState.ERROR, "Error"),
                tuple("400104", ProcessState.ACTIVE, "Processing"),
                tuple("400105", ProcessState.WAITING, "Retry"),
                tuple("400106", ProcessState.ERROR, "Error"),
                tuple("400107", ProcessState.WAITING, "Waiting"),
                tuple("400108", ProcessState.QUEUED, "Queued"),
                tuple("400109", ProcessState.OTHER, "Suspended"));
    }

    @Test
    void timeInStateAndHangingFollowNowAndTheThreshold() {
        assertThat(parseOne(stdout("ps_session_all")).instances())
            .filteredOn(i -> List.of("400104", "400107", "400108", "400109")
                .contains(i.processId()))
            .extracting(ProcessInstance::processId, ProcessInstance::since,
                ProcessInstance::timeInState, ProcessInstance::hanging)
            .containsExactly(
                tuple("400104", Instant.parse("2026-11-01T05:58:31Z"),
                    Duration.parse("PT2H1M29S"), true),
                tuple("400107", Instant.parse("2026-11-01T05:30:12Z"),
                    Duration.parse("PT2H29M48S"), true),
                tuple("400108", Instant.parse("2026-11-01T06:01:44Z"),
                    Duration.parse("PT1H58M16S"), false),
                tuple("400109", Instant.parse("2026-10-29T15:20:00Z"),
                    Duration.parse("PT64H40M"), false));
    }

    @Test
    void germanListingGivesTheSameInstancesWithGermanRawStates() {
        List<ProcessInstance> english = parseOne(stdout("ps_session_all")).instances();
        V61PsParser.Listing german = parseOne(stdout("ps_session_de"));

        assertThat(german.total()).isEqualTo(9);
        assertThat(german.instances()).usingRecursiveFieldByFieldElementComparatorIgnoringFields(
            "rawState").containsExactlyElementsOf(english);
        assertThat(german.instances()).extracting(ProcessInstance::rawState).containsExactly(
            "Fehler", "Wartend", "Fehler", "Aktiv", "Wiederholt", "Fehler", "Wartend",
            "Eingereiht", "Angehalten");
    }

    @Test
    void anErrorListingHoldsTheErrorInstances() {
        List<V61PsParser.Listing> listings = parse(stdout("ps_session_error"), ERROR);

        assertThat(listings).singleElement().satisfies(listing -> {
            assertThat(listing.total()).isEqualTo(3);
            assertThat(listing.instances()).extracting(ProcessInstance::processId)
                .containsExactly("400101", "400103", "400106");
            assertThat(listing.instances()).extracting(ProcessInstance::state)
                .containsOnly(ProcessState.ERROR);
        });
    }

    @Test
    void severalListingsOfOneSessionAreReadInCommandOrder() {
        List<V61PsParser.Listing> listings = parse(stdout("ps_session_waiting_queued"),
            WAITING_QUEUED);

        assertThat(listings).hasSize(2);
        assertThat(listings.get(0).total()).isEqualTo(2);
        assertThat(listings.get(0).instances())
            .extracting(ProcessInstance::processId, ProcessInstance::state)
            .containsExactly(tuple("400102", ProcessState.WAITING),
                tuple("400107", ProcessState.WAITING));
        assertThat(listings.get(1).total()).isEqualTo(1);
        assertThat(listings.get(1).instances())
            .extracting(ProcessInstance::processId, ProcessInstance::state)
            .containsExactly(tuple("400108", ProcessState.QUEUED));
    }

    @Test
    void anEmptyListingHasNoInstances() {
        V61PsParser.Listing listing = parse(stdout("ps_session_empty"), ERROR).getFirst();

        assertThat(listing.instances()).isEmpty();
        assertThat(listing.total()).isZero();
    }

    @Test
    void notAssignedHasNoInubitNodeAndAMissingModuleIsAbsent() {
        List<ProcessInstance> instances = parseOne(stdout("ps_session_unassigned")).instances();

        assertThat(instances).extracting(ProcessInstance::processId, ProcessInstance::inubitNode,
            ProcessInstance::workflow, ProcessInstance::module).containsExactly(
                tuple("400201", Optional.empty(), Optional.of("OrderApproval"),
                    Optional.of("TaskListEntry(10301)")),
                tuple("400202", Optional.of("node1"), Optional.of("OrderImport"),
                    Optional.empty()));
    }

    @Test
    void aModuleTextWithBlanksIsKeptToTheEndOfTheLine() {
        String row = "node1                jdoe            400101     normal     Error      "
            + "2026-10-30T08:15:42  OrderImport          Map to target (10234)";

        assertThat(parseOne(session(HEADER, row, "Total: 1")).instances().getFirst().module())
            .contains("Map to target (10234)");
    }

    @Test
    void windowsLineEndingsAreAccepted() {
        assertThat(parseOne(stdout("ps_session_all").replace("\n", "\r\n")))
            .isEqualTo(parseOne(stdout("ps_session_all")));
    }

    @Test
    void aListingAtTheRowCapIsReadCompletely() {
        String row = "node1                jdoe            %d     normal     Waiting    "
            + "2026-10-28T09:00:00  OrderApproval        TaskListEntry(10301)";
        StringBuilder lines = new StringBuilder(HEADER);
        for (int i = 0; i < CliSessionCommand.MAX_ROWS; i++) {
            lines.append('\n').append(row.formatted(500000 + i));
        }
        lines.append("\nTotal: ").append(CliSessionCommand.MAX_ROWS);

        V61PsParser.Listing listing = parseOne(session(lines.toString()));

        assertThat(listing.instances()).hasSize(CliSessionCommand.MAX_ROWS);
        assertThat(listing.instances().getLast().processId()).isEqualTo("509999");
    }

    @Test
    void aGermanStateNameInAnEnglishListingIsNotTranslated() {
        String stdout = stdout("ps_session_error").replaceFirst("Error     ", "Fehler    ");

        assertThat(parse(stdout, ERROR).getFirst().instances().getFirst())
            .extracting(ProcessInstance::state, ProcessInstance::rawState)
            .containsExactly(ProcessState.OTHER, "Fehler");
    }

    @Test
    void aSessionWithAnotherNumberOfListingsIsUnexpected() {
        assertThatThrownBy(() -> parse(stdout("ps_session_waiting_queued"), ALL))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"login_failed", "unreachable", "health_session_ok"})
    void otherSessionsAreUnexpected(String fixtureCase) {
        assertUnexpected(stdout(fixtureCase));
    }

    @Test
    void anEmptyBlockIsUnexpected() {
        assertUnexpected("Password: \ncli> \ncli> \n");
    }

    @Test
    void aHeaderWithoutTotalIsUnexpected() {
        assertUnexpected(session(HEADER));
    }

    @Test
    void aMissingTotalLineIsUnexpected() {
        assertUnexpected(stdout("ps_session_all").replace("Total: 9\n", ""));
    }

    @Test
    void aTotalBelowTheNumberOfRowsIsUnexpected() {
        assertUnexpected(stdout("ps_session_all").replace("Total: 9", "Total: 8"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // unknown or mixed headers
        "NODE |KNOTEN ",
        "MODULE|MODULES",
        "WORKFLOW             MODULE|WORKFLOW",
        "STATE      DATE |DATE       STATE",
        "NODE    |",
        // Total line
        "Total: 9|Total: nine",
        "Total: 9|Gesamt: 9",
        "Total: 9|Total: 9\nTotal: 9",
        "Total: 9|Total: 99999999999999999999",
        // rows
        "node1                jdoe            400101 |node1                jdoe            400101x",
        "400101     normal|400101  normalnormal",
        "OrderImport          Mapping(10234)|OrderImportImportImport Mapping(10234)",
        "2026-10-30T08:15:42|2026-10-30 08:15:42",
        "2026-10-30T08:15:42|2026-13-30T08:15:42",
        "     Error      |                ",
        "Processing 2026-11-01T06:58:31  PartnerSync          HttpCall(10777)|Processing",
        "jdoe            400103     high       Error|jdoe                       high       Error",
        "node2                jdoe            400109|                     jdoe            400109",
        "Total: 9|java.lang.NullPointerException\nTotal: 9",
    })
    void anUnknownHeaderTotalOrRowIsUnexpected(String replacement) {
        String[] parts = replacement.split("\\|", -1);
        String ok = stdout("ps_session_all");
        assertThat(ok).contains(parts[0]);

        assertUnexpected(ok.replaceFirst(Pattern.quote(parts[0]),
            Matcher.quoteReplacement(parts[1])));
    }

    @Test
    void anEnglishTotalInAGermanListingIsUnexpected() {
        assertUnexpected(stdout("ps_session_de").replace("Gesamt: 9", "Total: 9"));
    }
}
