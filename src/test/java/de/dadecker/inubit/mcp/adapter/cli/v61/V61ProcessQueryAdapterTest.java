package de.dadecker.inubit.mcp.adapter.cli.v61;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.environmentLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T032 (feature 011, FR-010, research D-10): {@code find_processes} on a 6.1 node over the
 * reconstructed T029 {@code ps} session fixtures (US2-1 … US2-7). Every call that reaches the node
 * runs exactly one StartCLI session; the local {@code startcli.sh -h} run of the client probe is
 * not a session and is counted apart.
 *
 * <p>The fixture dates are Europe/Berlin local times; on 2026-11-01 Berlin is UTC+1, so
 * {@code 2026-11-01T06:58:31} is {@code 05:58:31Z}. {@link #NOW} is {@code 08:00} in Berlin.
 */
@Timeout(60)
class V61ProcessQueryAdapterTest {

    private static final NodeId ID = NodeId.parse("legacy/node1");
    private static final String PASSWORD = "S3cr3t-v61-ps-pw";
    private static final Instant NOW = Instant.parse("2026-11-01T07:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(60);
    private static final String HEADER = "NODE                 UID             PID        "
        + "PRIO       STATE      DATE                 WORKFLOW             MODULE";

    @TempDir
    Path cliHome;

    @TempDir
    Path javaHome;

    private final MutableClock clock = new MutableClock(NOW);
    private final CredentialGuard guard =
        new CredentialGuard(environmentLabels("INUBIT", ID), clock);

    @BeforeEach
    void install() throws IOException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n");
    }

    /** A 6.1 node with StartCLI over http, so that no TLS handshake precedes the session. */
    private EffectiveNodeConfig node() {
        return TestNodeConfig.node().id(ID.toString()).versionLine(VersionSetting.V6_1)
            .baseUrl("http://inubit-legacy-1.example.test:8000").allowInsecureHttp(true)
            .cliHome(cliHome).cliJavaHome(javaHome).cliTimeout(Duration.ofSeconds(20)).build();
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(ID,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_LEGACY_NODE1_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_LEGACY_NODE1_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private V61ProcessQueryAdapter adapter(Launchers launchers) {
        EffectiveNodeConfig server = node();
        CliRunner runner = new CliRunner(launchers, Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
        V61Connection connection = new V61Connection(server, credentials(), guard, runner,
            new CliClientProbe(runner, id -> server, CapabilityMatrix.production(), clock),
            new CliConcurrencyLimiter(CapabilityMatrix.production()));
        return new V61ProcessQueryAdapter(ID, connection,
            new CliOutputClassifier(new SecretScrubber(), server.secretLabels()));
    }

    /** The reconstructed 6.1 {@code -h} for the probe, {@code session} for every session. */
    private static final class Launchers implements ProcessLauncher {

        final FakeProcessLauncher help = FakeProcessLauncher.replaying("v6_1", "help");
        final FakeProcessLauncher session;

        Launchers(FakeProcessLauncher session) {
            this.session = session;
        }

        static Launchers replaying(String fixtureCase) {
            return new Launchers(FakeProcessLauncher.replaying("v6_1", fixtureCase));
        }

        static Launchers answering(String stdout) {
            return new Launchers(FakeProcessLauncher.of(stdout, "", 0));
        }

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            boolean probe = spec.command().size() == 2 && spec.command().get(1).equals("-h");
            return probe ? help.launch(spec) : session.launch(spec);
        }

        int sessions() {
            return session.launchCount();
        }

        /** The lines the last session sent after the password, without {@code exit}. */
        List<String> commands() {
            List<String> lines = List.of(new String(session.last().stdinBytes(),
                StandardCharsets.UTF_8).split("\n"));
            assertThat(lines.getFirst()).isEqualTo(PASSWORD);
            assertThat(lines.getLast()).isEqualTo("exit");
            return lines.subList(1, lines.size() - 1);
        }
    }

    // --- queries ------------------------------------------------------------------------------

    private static ProcessQuery query(Set<ProcessState> states) {
        return query(states, false, THRESHOLD, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), 0, 50);
    }

    private static ProcessQuery query(Set<ProcessState> states, boolean hangingOnly,
        Duration threshold, Optional<String> workflow, Optional<String> tag,
        Optional<Instant> since, Optional<Instant> until, int offset, int limit) {
        return new ProcessQuery(states, hangingOnly, threshold, workflow, tag, since, until,
            offset, limit, NOW);
    }

    private static List<String> ids(Page<ProcessInstance> page) {
        return page.items().stream().map(ProcessInstance::processId).toList();
    }

    private static ToolError errorOf(ThrowingCallable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class, call);
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    /** A process session with one listing of {@code lines}. */
    private static String session(String... lines) {
        return "Password: \ncli> " + String.join("\n", lines) + "\ncli> \n";
    }

    /**
     * A listing of {@code rows} Error rows of workflow {@code Bulk} plus one of workflow
     * {@code Rare} (the last one), all without module.
     */
    private static String errorListing(int rows) {
        return errorListing(rows, "");
    }

    /** As {@link #errorListing(int)}, with {@code module} in every row (empty: none). */
    private static String errorListing(int rows, String module) {
        String row = "node1                jdoe            %d     normal     Error      "
            + "2026-10-30T08:15:42  %-21s" + module;
        StringBuilder lines = new StringBuilder(HEADER);
        for (int i = 0; i < rows - 1; i++) {
            lines.append('\n').append(row.formatted(500000 + i, "Bulk").stripTrailing());
        }
        lines.append('\n').append(row.formatted(600000, "Rare").stripTrailing());
        return lines.append("\nTotal: ").append(rows).toString();
    }

    // --- US2-1: one state, from its own listing, newest first ---------------------------------

    @Test
    void errorIsAnsweredFromTheErrorListingNewestFirstWithTheFieldsOfFr010() {
        Launchers launchers = Launchers.replaying("ps_session_error");

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(ProcessState.ERROR)));

        assertThat(launchers.commands()).containsExactly("ps -e -l 10000");
        assertThat(ids(page)).containsExactly("400106", "400103", "400101");
        assertThat(page.items().getFirst()).isEqualTo(new ProcessInstance(ID, "400106",
            Optional.empty(), ProcessState.ERROR, "Error", Instant.parse("2026-10-31T20:47:05Z"),
            Duration.ofHours(10).plusMinutes(12).plusSeconds(55), false,
            Optional.of("InvoiceExport"), Optional.of("Sender(10511)"), Optional.empty(),
            Optional.empty(), Optional.of("node2"), Optional.of("jdoe"), Optional.of("normal")));
        assertThat(page.total()).isEqualTo(OptionalLong.of(3));
        assertThat(page.totalIsLowerBound()).isFalse();
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextOffset()).isEmpty();
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void severalStatesWithoutActiveAreOneListingEachInOneSessionMerged() {
        Launchers launchers = Launchers.replaying("ps_session_waiting_queued");

        Page<ProcessInstance> page = adapter(launchers).find(
            query(Set.of(ProcessState.QUEUED, ProcessState.WAITING)));

        assertThat(launchers.commands()).containsExactly("ps -w -l 10000", "ps -q -l 10000");
        assertThat(ids(page)).containsExactly("400108", "400107", "400102");
        assertThat(page.total()).isEqualTo(OptionalLong.of(3));
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void noStateIsTheFullListingNewestFirst() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of()));

        assertThat(launchers.commands()).containsExactly("ps -a -l 10000");
        assertThat(ids(page)).containsExactly("400108", "400104", "400107", "400105",
            "400106", "400103", "400101", "400109", "400102");
        assertThat(page.items()).extracting(ProcessInstance::state).contains(ProcessState.OTHER);
        assertThat(page.total()).isEqualTo(OptionalLong.of(9));
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    // --- US2-2: hanging ------------------------------------------------------------------------

    @Test
    void hangingOnlyKeepsTheNonFinalInstancesLongerInTheirStateThanTheThreshold() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(), true, THRESHOLD,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 50));

        // Queued 400108 has waited 58 min; Error and Suspended (OTHER) never hang
        assertThat(launchers.commands()).containsExactly("ps -a -l 10000");
        assertThat(ids(page)).containsExactly("400104", "400107", "400105", "400102");
        assertThat(page.items()).allMatch(ProcessInstance::hanging);
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void anInstanceExactlyAtTheThresholdIsNotHangingOneSecondLongerIs() {
        // Queued 400108 entered its state at 06:01:44Z, 58 min 16 s before NOW
        Duration exactly = Duration.ofMinutes(58).plusSeconds(16);

        Page<ProcessInstance> atThreshold = adapter(Launchers.replaying("ps_session_all")).find(
            query(Set.of(ProcessState.QUEUED, ProcessState.ACTIVE), true, exactly,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 50));
        Page<ProcessInstance> beyond = adapter(Launchers.replaying("ps_session_all")).find(
            query(Set.of(ProcessState.QUEUED, ProcessState.ACTIVE), true,
                exactly.minusSeconds(1), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0, 50));

        assertThat(ids(atThreshold)).containsExactly("400104");
        assertThat(ids(beyond)).containsExactly("400108", "400104");
    }

    @Test
    void hangingOnlyDoesNotChangeWhichListingsRun() {
        Launchers launchers = Launchers.replaying("ps_session_waiting_queued");

        Page<ProcessInstance> page = adapter(launchers).find(
            query(Set.of(ProcessState.WAITING, ProcessState.QUEUED), true, THRESHOLD,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 50));

        assertThat(launchers.commands()).containsExactly("ps -w -l 10000", "ps -q -l 10000");
        assertThat(ids(page)).containsExactly("400107", "400102");
    }

    // --- US2-3: workflow and time range ---------------------------------------------------------

    @Test
    void theWorkflowFilterKeepsOnlyThatWorkflow() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(), false, THRESHOLD,
            Optional.of("OrderImport"), Optional.empty(), Optional.empty(), Optional.empty(), 0,
            50));

        assertThat(ids(page)).containsExactly("400108", "400103", "400101");
        assertThat(page.total()).isEqualTo(OptionalLong.of(3));
    }

    @Test
    void theTimeRangeIsInclusiveOnSince() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        // 400103 entered Error at 2026-10-31T13:02:10Z, 400105 Retry at 2026-11-01T04:45:00Z
        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(), false, THRESHOLD,
            Optional.empty(), Optional.empty(), Optional.of(Instant.parse("2026-10-31T13:02:10Z")),
            Optional.of(Instant.parse("2026-11-01T04:45:00Z")), 0, 50));

        assertThat(ids(page)).containsExactly("400105", "400106", "400103");
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    // --- US2-4: tag ---------------------------------------------------------------------------

    @Test
    void validateRefusesTagForTheNodeWithoutLaunching() {
        Launchers launchers = Launchers.replaying("ps_session_all");
        ProcessQuery withTag = query(Set.of(), false, THRESHOLD, Optional.empty(),
            Optional.of("release-1"), Optional.empty(), Optional.empty(), 0, 50);

        ToolError error = errorOf(() -> adapter(launchers).validate(withTag));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).contains("tag").contains("nothing was sent");
        assertThat(error.node()).contains(ID);
        assertThat(launchers.sessions()).isZero();
    }

    @Test
    void findRefusesTagAgainWithoutLaunching() {
        Launchers launchers = Launchers.replaying("ps_session_all");
        ProcessQuery withTag = query(Set.of(ProcessState.ERROR), false, THRESHOLD,
            Optional.empty(), Optional.of("release-1"), Optional.empty(), Optional.empty(), 0, 50);

        ToolError error = errorOf(() -> adapter(launchers).find(withTag));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).contains("tag");
        assertThat(launchers.sessions()).isZero();
    }

    @Test
    void aQueryWithoutTagPassesValidation() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        adapter(launchers).validate(query(Set.of(ProcessState.ERROR)));

        assertThat(launchers.sessions()).isZero();
    }

    // --- US2-5: ACTIVE ------------------------------------------------------------------------

    @Test
    void activeIsTheFullListingFilteredForProcessing() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(ProcessState.ACTIVE)));

        assertThat(launchers.commands()).containsExactly("ps -a -l 10000");
        assertThat(ids(page)).containsExactly("400104");
        assertThat(page.items().getFirst().rawState()).isEqualTo("Processing");
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void activeAmongSeveralStatesIsTheFullListingFiltered() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        Page<ProcessInstance> page = adapter(launchers).find(
            query(Set.of(ProcessState.ERROR, ProcessState.ACTIVE)));

        assertThat(launchers.commands()).containsExactly("ps -a -l 10000");
        assertThat(ids(page)).containsExactly("400104", "400106", "400103", "400101");
    }

    @Test
    void waitingFromTheFullListingIncludesRetry() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        Page<ProcessInstance> page = adapter(launchers).find(
            query(Set.of(ProcessState.WAITING, ProcessState.ACTIVE)));

        assertThat(ids(page)).containsExactly("400104", "400107", "400105", "400102");
        assertThat(page.items()).extracting(ProcessInstance::rawState)
            .containsExactly("Processing", "Waiting", "Retry", "Waiting");
    }

    // --- US2-6: row cap -------------------------------------------------------------------------

    @Test
    void aListingAtTheRowCapMarksThePageTruncatedWithALowerBoundTotal() {
        Launchers launchers = Launchers.answering(session(errorListing(10_000)));

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(ProcessState.ERROR),
            false, THRESHOLD, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), 9_950, 50));

        assertThat(page.items()).hasSize(50);
        assertThat(page.total()).isEqualTo(OptionalLong.of(10_000));
        assertThat(page.totalIsLowerBound()).isTrue();
        assertThat(page.truncated()).isTrue();
        assertThat(page.nextOffset()).isEmpty();
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void theCapMarkersStayWhenFiltersReduceTheRows() {
        Launchers launchers = Launchers.answering(session(errorListing(10_000)));

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(ProcessState.ERROR),
            false, THRESHOLD, Optional.of("Rare"), Optional.empty(), Optional.empty(),
            Optional.empty(), 0, 50));

        assertThat(ids(page)).containsExactly("600000");
        assertThat(page.total()).isEqualTo(OptionalLong.of(1));
        assertThat(page.totalIsLowerBound()).isTrue();
        assertThat(page.truncated()).isTrue();
    }

    @Test
    void oneCappedListingAmongSeveralMarksTheMergedPage() {
        String waiting = String.join("\n", HEADER,
            "node1                jdoe            400107     low        Waiting    "
                + "2026-11-01T06:30:12  PartnerSync          Timer(10790)",
            "Total: 1");
        Launchers launchers = Launchers.answering("Password: \ncli> " + errorListing(10_000)
            + "\ncli> " + waiting + "\ncli> \n");

        Page<ProcessInstance> page = adapter(launchers).find(
            query(Set.of(ProcessState.ERROR, ProcessState.WAITING)));

        assertThat(launchers.commands()).containsExactly("ps -e -l 10000", "ps -w -l 10000");
        assertThat(ids(page).getFirst()).isEqualTo("400107");
        assertThat(page.total()).isEqualTo(OptionalLong.of(10_001));
        assertThat(page.totalIsLowerBound()).isTrue();
        assertThat(page.truncated()).isTrue();
    }

    @Test
    void aListingOneRowBelowTheCapIsComplete() {
        Launchers launchers = Launchers.answering(session(errorListing(9_999)));

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(ProcessState.ERROR)));

        assertThat(page.total()).isEqualTo(OptionalLong.of(9_999));
        assertThat(page.totalIsLowerBound()).isFalse();
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextOffset()).isEqualTo(OptionalInt.of(50));
    }

    // --- output bound per listing (T046, research D-10) ----------------------------------------

    @Test
    void aTenThousandRowListingWithModulesAboveOneMebibyteIsAnsweredAsCapped() {
        String stdout = session(errorListing(10_000, "Sender(10511)"));
        assertThat(stdout.getBytes(StandardCharsets.UTF_8).length)
            .isGreaterThan(CliRunner.MAX_OUTPUT_BYTES);
        Launchers launchers = Launchers.answering(stdout);

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(ProcessState.ERROR)));

        assertThat(page.items()).hasSize(50)
            .allSatisfy(item -> assertThat(item.module()).contains("Sender(10511)"));
        assertThat(page.total()).isEqualTo(OptionalLong.of(10_000));
        assertThat(page.totalIsLowerBound()).isTrue();
        assertThat(page.truncated()).isTrue();
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void aSessionAboveItsBoundOfFourMebibytesPerListingIsUnexpectedResponseNamingIt() {
        Launchers launchers =
            new Launchers(FakeProcessLauncher.of("", "", 0).producing((4 << 20) + 1));

        ToolError error = errorOf(() -> adapter(launchers).find(
            query(Set.of(ProcessState.ERROR))));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("exceeded 4 MiB");
        assertThat(error.node()).contains(ID);
    }

    @Test
    void theSessionBoundGrowsWithTheNumberOfListings() {
        Launchers launchers =
            new Launchers(FakeProcessLauncher.of("", "", 0).producing((8 << 20) + 1));

        ToolError error = errorOf(() -> adapter(launchers).find(
            query(Set.of(ProcessState.ERROR, ProcessState.WAITING))));

        assertThat(launchers.commands()).containsExactly("ps -e -l 10000", "ps -w -l 10000");
        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("exceeded 8 MiB");
    }

    @Test
    void aTotalAboveThePrintedRowsAlsoMakesTheTotalALowerBound() {
        String stdout = FakeProcessLauncher.fixtureText("v6_1", "ps_session_error.stdout")
            .replace("Total: 3", "Total: 12");
        Launchers launchers = Launchers.answering(stdout);

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(ProcessState.ERROR)));

        assertThat(ids(page)).containsExactly("400106", "400103", "400101");
        assertThat(page.total()).isEqualTo(OptionalLong.of(3));
        assertThat(page.totalIsLowerBound()).isTrue();
        assertThat(page.truncated()).isTrue();
    }

    // --- US2-7: empty listing ------------------------------------------------------------------

    @Test
    void anEmptyListingIsAnEmptyPageNotAnError() {
        Launchers launchers = Launchers.replaying("ps_session_empty");

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(ProcessState.ERROR)));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(OptionalLong.of(0));
        assertThat(page.totalIsLowerBound()).isFalse();
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextOffset()).isEmpty();
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    // --- paging ---------------------------------------------------------------------------------

    @Test
    void pagingSlicesTheSortedMatches() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(), false, THRESHOLD,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 2, 3));

        assertThat(ids(page)).containsExactly("400107", "400105", "400106");
        assertThat(page.offset()).isEqualTo(2);
        assertThat(page.limit()).isEqualTo(3);
        assertThat(page.total()).isEqualTo(OptionalLong.of(9));
        assertThat(page.nextOffset()).isEqualTo(OptionalInt.of(5));
    }

    @Test
    void anOffsetBeyondTheMatchesIsAnEmptyPage() {
        Page<ProcessInstance> page = adapter(Launchers.replaying("ps_session_error")).find(
            query(Set.of(ProcessState.ERROR), false, THRESHOLD, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 5, 10));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(OptionalLong.of(3));
        assertThat(page.nextOffset()).isEmpty();
    }

    @Test
    void offsetPlusLimitAboveTenThousandIsInvalidInputWithoutLaunch() {
        Launchers launchers = Launchers.replaying("ps_session_all");
        ProcessQuery deep = query(Set.of(), false, THRESHOLD, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), 9_951, 50);

        ToolError validated = errorOf(() -> adapter(launchers).validate(deep));
        ToolError found = errorOf(() -> adapter(launchers).find(deep));

        assertThat(validated.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(validated.node()).as("server-independent").isEmpty();
        assertThat(found.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(launchers.sessions()).isZero();
    }

    // --- other listings -------------------------------------------------------------------------

    @Test
    void aGermanListingGivesTheSameInstancesInTheSameOrder() {
        Page<ProcessInstance> german = adapter(Launchers.replaying("ps_session_de"))
            .find(query(Set.of(ProcessState.WAITING, ProcessState.ACTIVE)));

        // rawState keeps the German name (research D-10)
        assertThat(ids(german)).containsExactly("400104", "400107", "400105", "400102");
        assertThat(german.items()).extracting(ProcessInstance::rawState)
            .containsExactly("Aktiv", "Wartend", "Wiederholt", "Wartend");
    }

    @Test
    void anUnassignedInstanceHasNoInubitNode() {
        Page<ProcessInstance> page = adapter(Launchers.replaying("ps_session_unassigned"))
            .find(query(Set.of(ProcessState.WAITING, ProcessState.ACTIVE)));

        assertThat(ids(page)).containsExactly("400201");
        assertThat(page.items().getFirst().inubitNode()).isEmpty();
    }

    // --- size bounds ------------------------------------------------------------------------------

    @Test
    void aLongModuleTextIsCutAndMarksThePageTruncated() {
        String module = "M".repeat(300);
        Launchers launchers = Launchers.answering(session(HEADER,
            "node1                jdoe            400101     normal     Error      "
                + "2026-10-30T08:15:42  OrderImport          " + module,
            "Total: 1"));

        Page<ProcessInstance> page = adapter(launchers).find(query(Set.of(ProcessState.ERROR)));

        String cut = page.items().getFirst().module().orElseThrow();
        assertThat(cut).hasSize(ItemBounds.MAX_FIELD_CHARS).endsWith(ItemBounds.TRUNCATION_MARKER);
        assertThat(page.truncated()).isTrue();
        assertThat(page.totalIsLowerBound()).isFalse();
    }

    @Test
    void anItemThatEscapesBeyondTheItemBoundLosesItsOptionalTexts() {
        String escaped = "\u0001".repeat(300);
        ProcessInstance instance = new ProcessInstance(ID, "400101", Optional.empty(),
            ProcessState.OTHER, escaped, Instant.parse("2026-10-30T07:15:42Z"), Duration.ZERO,
            false, Optional.of(escaped), Optional.of(escaped), Optional.empty(), Optional.empty(),
            Optional.of(escaped), Optional.of(escaped), Optional.of(escaped));

        ProcessInstance bounded = V61ProcessQueryAdapter.bounded(instance);

        assertThat(ResultJson.size(bounded)).isLessThanOrEqualTo(ItemBounds.MAX_ITEM_CHARS);
        assertThat(bounded.processId()).isEqualTo("400101");
        assertThat(bounded.rawState()).hasSize(ItemBounds.MAX_FIELD_CHARS);
        assertThat(bounded.workflow()).isEmpty();
        assertThat(bounded.module()).isEmpty();
        assertThat(bounded.inubitNode()).isEmpty();
        assertThat(bounded.owner()).isEmpty();
        assertThat(bounded.priority()).isEmpty();
    }

    @Test
    void anItemWithinTheBoundsIsKeptAsItIs() {
        ProcessInstance instance = adapter(Launchers.replaying("ps_session_error"))
            .find(query(Set.of(ProcessState.ERROR))).items().getFirst();

        assertThat(V61ProcessQueryAdapter.bounded(instance)).isEqualTo(instance);
    }

    // --- refusals and failures ----------------------------------------------------------------

    @Test
    void findByProcessIdIsRefusedWithoutLaunching() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        ToolError error = errorOf(() -> adapter(launchers).findByProcessId("400101", NOW,
            THRESHOLD));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.node()).contains(ID);
        assertThat(launchers.sessions()).isZero();
    }

    @Test
    void aRejectedLoginIsAuthFailedForTheNode() {
        Launchers launchers = Launchers.replaying("login_failed");

        ToolError error = errorOf(() -> adapter(launchers).find(query(Set.of())));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.node()).contains(ID);
        assertThat(error.message()).doesNotContain(PASSWORD);
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void anUnknownListingIsUnexpectedResponseForTheNodeNeverAnEmptyPage() {
        Launchers launchers = Launchers.answering(session("PID STATE", "Total: 0"));

        ToolError error = errorOf(() -> adapter(launchers).find(query(Set.of())));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(ID);
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void aSessionWithAnotherExitCodeIsNeverParsed() {
        String ok = FakeProcessLauncher.fixtureText("v6_1", "ps_session_all.stdout");
        Launchers launchers = new Launchers(FakeProcessLauncher.of(ok, "", 2));

        ToolError error = errorOf(() -> adapter(launchers).find(query(Set.of())));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(ID);
    }
}
