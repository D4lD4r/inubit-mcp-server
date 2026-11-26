package de.dadecker.inubit.mcp.adapter.cli.v74;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.PortSetRegistry;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestMatrices;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 025 T032 (spec FR-024, FR-025, FR-028): the 7.4 process query port of
 * {@link V74PortSet#factory} — the 6.1 process query adapter with the 7.4 profile — against the
 * reconstructed 7.4 {@code ps} session fixtures ({@code fixtures/v7_4/cli/}) and variants built
 * here from {@code ps_session_all}: CRLF line ends and German header and state names exactly as
 * {@code V61PsParser} accepts them (no German 7.4 fixture, FR-025). The client options come from
 * the {@code -h} probe replaying the real 7.4 help; probe and limiter get the T038 test matrix, as
 * the production matrix runs no {@code -h} on 7.4 before the flip (T035).
 *
 * <p>The fixture dates are Europe/Berlin local times; on 2026-11-01 Berlin is UTC+1, so
 * {@code 2026-11-01T06:58:31} is {@code 05:58:31Z}. {@link #NOW} is {@code 08:00} in Berlin.
 */
@Timeout(60)
class V74ProcessQueryContractTest {

    private static final NodeId ID = NodeId.parse("legacy74/node1");
    private static final String PASSWORD = "S3cr3t-v74-ps-pw";
    private static final Instant NOW = Instant.parse("2026-11-01T07:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(60);
    private static final CapabilityMatrix MATRIX = TestMatrices.unverified74();
    private static final String HEADER = "NODE                 UID             PID        "
        + "PRIO       STATE      DATE                 WORKFLOW             MODULE";
    private static final String GERMAN_HEADER = "KNOTEN               BENUTZER        PID        "
        + "PRIO       STATUS     DATUM                WORKFLOW             MODUL";

    @TempDir
    Path cliHome;

    @TempDir
    Path javaHome;

    private final MutableClock clock = new MutableClock(NOW);
    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", ID), clock);

    @BeforeEach
    void install() throws IOException {
        Path bin = Files.createDirectories(cliHome.resolve("bin"));
        Files.writeString(bin.resolve("startcli.sh"), "#!/bin/sh\n");
        Files.writeString(bin.resolve("startcli.bat"), "@echo off\r\n");
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n");
    }

    /** A 7.4 node with StartCLI over http, so that no TLS handshake precedes the session. */
    private EffectiveNodeConfig node() {
        return TestNodeConfig.node().id(ID.toString()).versionLine(VersionSetting.V7_4)
            .baseUrl("http://inubit-legacy74-1.example.test:8000").allowInsecureHttp(true)
            .cliHome(cliHome).cliJavaHome(javaHome).cliTimeout(Duration.ofSeconds(20)).build();
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(ID,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_LEGACY74_NODE1_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_LEGACY74_NODE1_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    /** The process query port as {@code Wiring} builds it for a 7.4 node. */
    private ProcessQueryPort port(Launchers launchers) {
        EffectiveNodeConfig server = node();
        CliRunner runner = TestCliRunners.forHost(launchers, Map.of("PATH", "/usr/bin"),
            new CliResources("acme", PrivateFiles.forHost()));
        return V74PortSet.factory(new CliClientProbe(runner, id -> server, MATRIX, clock),
                new CliConcurrencyLimiter(MATRIX))
            .create(new PortSetRegistry.NodeResources(server, credentials(), guard, null, runner,
                new SecretScrubber()))
            .processes();
    }

    /** The real 7.4 {@code -h} for the probe, {@code session} for every session. */
    private static final class Launchers implements ProcessLauncher {

        final FakeProcessLauncher help = FakeProcessLauncher.replaying("v7_4", "help");
        final FakeProcessLauncher session;

        Launchers(FakeProcessLauncher session) {
            this.session = session;
        }

        static Launchers replaying(String fixtureCase) {
            return new Launchers(FakeProcessLauncher.replaying("v7_4", fixtureCase));
        }

        static Launchers answering(String stdout) {
            return new Launchers(FakeProcessLauncher.of(stdout, "", 0));
        }

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            boolean probe = spec.startCliArguments().equals(List.of("-h"));
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
        return query(states, false, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ProcessQuery query(Set<ProcessState> states, boolean hangingOnly,
        Optional<String> tag, Optional<Instant> since, Optional<Instant> until) {
        return new ProcessQuery(states, hangingOnly, THRESHOLD, Optional.empty(), tag, since,
            until, 0, 50, NOW, Optional.empty());
    }

    private static List<String> ids(Page<ProcessInstance> page) {
        return page.items().stream().map(ProcessInstance::processId).toList();
    }

    private static ToolError errorOf(ThrowingCallable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class, call);
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    private static String allSession() {
        return FakeProcessLauncher.fixtureText("v7_4", "ps_session_all.stdout");
    }

    // --- the listings and the fields of FR-028 ------------------------------------------------

    @Test
    void errorIsAnsweredFromTheErrorListingNewestFirstWithTheSixOneFields() {
        Launchers launchers = Launchers.replaying("ps_session_error");

        Page<ProcessInstance> page = port(launchers).find(query(Set.of(ProcessState.ERROR)));

        assertThat(launchers.commands()).containsExactly("ps -e -l 10000");
        assertThat(ids(page)).containsExactly("400106", "400103", "400101");
        assertThat(page.items().getFirst()).isEqualTo(new ProcessInstance(ID, "400106",
            Optional.empty(), ProcessState.ERROR, "Error", Instant.parse("2026-10-31T20:47:05Z"),
            Duration.ofHours(10).plusMinutes(12).plusSeconds(55), false,
            Optional.of("InvoiceExport"), Optional.of("Sender(10511)"), Optional.empty(),
            Optional.empty(), Optional.of("node2"), Optional.of("jdoe"), Optional.of("normal"),
            Optional.empty()));
        assertThat(page.total()).isEqualTo(OptionalLong.of(3));
        assertThat(page.totalIsLowerBound()).isFalse();
        assertThat(page.truncated()).isFalse();
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    @Test
    void waitingIsAnsweredFromTheWaitingListing() {
        Launchers launchers = Launchers.replaying("ps_session_waiting");

        Page<ProcessInstance> page = port(launchers).find(query(Set.of(ProcessState.WAITING)));

        assertThat(launchers.commands()).containsExactly("ps -w -l 10000");
        assertThat(ids(page)).containsExactly("400107", "400102");
        assertThat(page.items()).extracting(ProcessInstance::state)
            .containsOnly(ProcessState.WAITING);
    }

    @Test
    void queuedIsAnsweredFromTheQueuedListing() {
        Launchers launchers = Launchers.replaying("ps_session_queued");

        Page<ProcessInstance> page = port(launchers).find(query(Set.of(ProcessState.QUEUED)));

        assertThat(launchers.commands()).containsExactly("ps -q -l 10000");
        assertThat(ids(page)).containsExactly("400108");
        assertThat(page.total()).isEqualTo(OptionalLong.of(1));
    }

    @Test
    void noStateIsTheFullListingNewestFirst() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        Page<ProcessInstance> page = port(launchers).find(query(Set.of()));

        assertThat(launchers.commands()).containsExactly("ps -a -l 10000");
        assertThat(ids(page)).containsExactly("400108", "400104", "400107", "400105",
            "400106", "400103", "400101", "400109", "400102");
        assertThat(page.items()).extracting(ProcessInstance::state).contains(ProcessState.OTHER);
        assertThat(page.total()).isEqualTo(OptionalLong.of(9));
    }

    @Test
    void theStateFilterKeepsOnlyTheRequestedStatesOfTheFullListing() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        Page<ProcessInstance> page = port(launchers).find(
            query(Set.of(ProcessState.ERROR, ProcessState.ACTIVE)));

        assertThat(launchers.commands()).containsExactly("ps -a -l 10000");
        assertThat(ids(page)).containsExactly("400104", "400106", "400103", "400101");
    }

    @Test
    void anUnassignedInstanceHasNoInubitNode() {
        Page<ProcessInstance> page = port(Launchers.replaying("ps_session_unassigned"))
            .find(query(Set.of()));

        assertThat(ids(page)).containsExactly("400202", "400201");
        assertThat(page.items().get(1).inubitNode()).isEmpty();
        assertThat(page.items().getFirst().inubitNode()).contains("node1");
        assertThat(page.items().getFirst().module()).as("a row without module").isEmpty();
    }

    // --- hanging and time range on the DATE column, as on 6.1 ------------------------------------

    @Test
    void hangingOnlyKeepsTheNonFinalInstancesLongerInTheirStateThanTheThreshold() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        Page<ProcessInstance> page = port(launchers).find(query(Set.of(), true,
            Optional.empty(), Optional.empty(), Optional.empty()));

        // Queued 400108 has waited 58 min; Error and Suspended (OTHER) never hang
        assertThat(launchers.commands()).containsExactly("ps -a -l 10000");
        assertThat(ids(page)).containsExactly("400104", "400107", "400105", "400102");
        assertThat(page.items()).allMatch(ProcessInstance::hanging);
    }

    @Test
    void theTimeRangeIsInclusiveOnTheDateColumn() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        // 400103 entered Error at 2026-10-31T13:02:10Z, 400105 Retry at 2026-11-01T04:45:00Z
        Page<ProcessInstance> page = port(launchers).find(query(Set.of(), false,
            Optional.empty(), Optional.of(Instant.parse("2026-10-31T13:02:10Z")),
            Optional.of(Instant.parse("2026-11-01T04:45:00Z"))));

        assertThat(ids(page)).containsExactly("400105", "400106", "400103");
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    // --- row cap and empty listing --------------------------------------------------------------

    @Test
    void aCutListingWhoseTotalCountsBeyondItsRowsIsTruncatedWithALowerBoundTotal() {
        // ps_session_limit5 is ps_session_all cut to five rows ("ps -a -l 5"); its total line says
        // 5, which the adapter cannot tell from a complete listing (its own cap is 10000 rows).
        // Whether StartCLI counts the instances beyond the limit is open as on 6.1 (research
        // D-10); this variant takes the full count of ps_session_all.
        String cut = FakeProcessLauncher.fixtureText("v7_4", "ps_session_limit5.stdout")
            .replace("Total: 5", "Total: 9");

        Page<ProcessInstance> page = port(Launchers.answering(cut)).find(query(Set.of()));

        assertThat(ids(page)).containsExactly("400104", "400105", "400103", "400101", "400102");
        assertThat(page.total()).isEqualTo(OptionalLong.of(5));
        assertThat(page.totalIsLowerBound()).isTrue();
        assertThat(page.truncated()).isTrue();
    }

    @Test
    void aListingAtTheRowCapIsTruncatedWithALowerBoundTotal() {
        String row = "node1                jdoe            %d     normal     Error      "
            + "2026-10-30T08:15:42  OrderImport";
        StringBuilder listing = new StringBuilder("Password: \ncli> ").append(HEADER);
        for (int i = 0; i < 10_000; i++) {
            listing.append('\n').append(row.formatted(500_000 + i));
        }
        listing.append("\nTotal: 10000\ncli> \n");

        Page<ProcessInstance> page = port(Launchers.answering(listing.toString()))
            .find(query(Set.of(ProcessState.ERROR)));

        assertThat(page.items()).hasSize(50);
        assertThat(page.total()).isEqualTo(OptionalLong.of(10_000));
        assertThat(page.totalIsLowerBound()).isTrue();
        assertThat(page.truncated()).isTrue();
    }

    @Test
    void anEmptyListingIsAnEmptyPageNotAnError() {
        Launchers launchers = Launchers.replaying("ps_session_empty");

        Page<ProcessInstance> page = port(launchers).find(query(Set.of(ProcessState.ERROR)));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(OptionalLong.of(0));
        assertThat(page.totalIsLowerBound()).isFalse();
        assertThat(page.truncated()).isFalse();
        assertThat(launchers.sessions()).isEqualTo(1);
    }

    // --- variants built from ps_session_all (FR-025) ---------------------------------------------

    @Test
    void crlfLineEndsGiveTheSamePage() {
        String crlf = allSession().replace("\n", "\r\n");

        assertThat(port(Launchers.answering(crlf)).find(query(Set.of())))
            .isEqualTo(port(Launchers.replaying("ps_session_all")).find(query(Set.of())));
    }

    @Test
    void aGermanHeaderAndGermanStateNamesGiveTheSameInstances() {
        String german = allSession().replace(HEADER, GERMAN_HEADER)
            .replace(state("Error"), state("Fehler"))
            .replace(state("Processing"), state("Aktiv"))
            .replace(state("Waiting"), state("Wartend"))
            .replace(state("Retry"), state("Wiederholt"))
            .replace(state("Queued"), state("Eingereiht"))
            .replace("Total:", "Gesamt:");
        assertThat(german).as("every English name replaced but Suspended")
            .doesNotContain("NODE", "STATE", "Error", "Processing", "Waiting", "Retry", "Queued",
                "Total");

        Page<ProcessInstance> page = port(Launchers.answering(german)).find(query(Set.of()));
        Page<ProcessInstance> english =
            port(Launchers.replaying("ps_session_all")).find(query(Set.of()));

        assertThat(ids(page)).isEqualTo(ids(english));
        assertThat(page.items()).extracting(ProcessInstance::state)
            .isEqualTo(english.items().stream().map(ProcessInstance::state).toList());
        // rawState keeps the German name (feature 012 research D-10)
        assertThat(page.items()).extracting(ProcessInstance::rawState)
            .startsWith("Eingereiht", "Aktiv", "Wartend", "Wiederholt", "Fehler");
        assertThat(page.total()).isEqualTo(english.total());
    }

    /** {@code name} in the STATE column, padded to the DATE column that follows it. */
    private static String state(String name) {
        return "%-11s2026".formatted(name);
    }

    // --- refusals and failures name INUBIT 7.4 ---------------------------------------------------

    @Test
    void validateRefusesTagForTheNodeNamingSevenFourWithoutLaunching() {
        Launchers launchers = Launchers.replaying("ps_session_all");
        ProcessQuery withTag = query(Set.of(), false, Optional.of("release-1"), Optional.empty(),
            Optional.empty());

        ToolError error = errorOf(() -> port(launchers).validate(withTag));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo(
            "find_processes: INUBIT 7.4 does not support the input tag; nothing was sent");
        assertThat(error.likelyCause())
            .isEqualTo("The process listing of StartCLI 7.4 has no tag column");
        assertThat(error.node()).contains(ID);
        assertThat(errorOf(() -> port(launchers).find(withTag)).message())
            .as("find refuses tag again").isEqualTo(error.message());
        assertThat(launchers.sessions()).isZero();
    }

    @Test
    void findByProcessIdIsRefusedNamingSevenFourWithoutLaunching() {
        Launchers launchers = Launchers.replaying("ps_session_all");

        ToolError error = errorOf(() -> port(launchers).findByProcessId("400101", NOW,
            THRESHOLD));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.node()).contains(ID);
        assertThat(error.message()).isEqualTo("This operation is not available on "
            + "legacy74/node1 (INUBIT 7.4, StartCLI only); nothing was sent");
        assertThat(error.likelyCause()).isEqualTo(
            "INUBIT 7.4 is supported read-only through StartCLI, for a few reads only");
        assertThat(launchers.sessions()).isZero();
    }

    @Test
    void anUnrecognizedListingIsUnexpectedResponseNamingSevenFourNeverAnEmptyPage() {
        Launchers launchers = Launchers.answering(
            "Password: \ncli> PID STATE\nTotal: 0\ncli> \n");

        ToolError error = errorOf(() -> port(launchers).find(query(Set.of())));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(ID);
        assertThat(error.message()).isEqualTo("StartCLI answered ps with an unknown header");
        assertThat(error.likelyCause())
            .isEqualTo("StartCLI's session output differs from the expected INUBIT 7.4 format");
        assertThat(launchers.sessions()).isEqualTo(1);
    }
}
