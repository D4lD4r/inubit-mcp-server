package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.application.PartnerService.Found;
import de.dadecker.inubit.mcp.application.PartnerService.PartnerView;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.AuditLog;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * T021: the audit and the INFO log lines of {@link PartnerService#find} (FR-020, FR-021, research
 * D-8, Op-3, Op-4): one record and one line per targeted server with a shared call id, also for a
 * cache hit or a failed server; an audit write failure ends the call in {@code INTERNAL} without
 * partner data; neither the audit nor the log holds a partner setting or a secret.
 */
@Timeout(30)
class PartnerServiceAuditTest {

    private static final NodeId NODE1 = NodeId.parse("dev/node1");
    private static final NodeId NODE2 = NodeId.parse("dev/node2");
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final String OWNER = PartnerFixtures.OWNER;
    private static final DiagramRef ONE = new DiagramRef("SystemDiagram-01", PartnerFixtures.GROUP);
    private static final PartnerManagementReader.Result DETAIL = PartnerManagementReader.read(ONE,
        XmlTree.parse(PartnerFixtures.workflowXml(PartnerFixtures.detail())).root());

    private final MutableClock clock = new MutableClock(T0);
    private final InventoryCache cache = new InventoryCache(clock, node -> Duration.ofMinutes(10));
    private final Map<NodeId, Optional<String>> owners =
        Map.of(NODE1, Optional.of(OWNER), NODE2, Optional.empty());
    private final RecordingAudit port = new RecordingAudit();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger serviceLog = (Logger) LoggerFactory.getLogger(PartnerService.class);
    private Level previousLevel;
    private volatile ToolErrorException portFailure;

    @TempDir
    Path directory;

    @BeforeEach
    void captureLogs() {
        logs.start();
        serviceLog.addAppender(logs);
        previousLevel = serviceLog.getLevel();
        serviceLog.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseLogs() {
        serviceLog.detachAppender(logs);
        serviceLog.setLevel(previousLevel);
        logs.stop();
    }

    @Test
    void oneRecordAndOneLinePerServerShareTheCallId() {
        Found found = service(port).find(query("dev", Optional.of("acme"), Optional.empty()));

        int masked = found.partners().stream().mapToInt(PartnerView::maskedCount).sum();
        assertThat(port.records).extracting(AuditRecord::node)
            .containsExactly(NODE1.value(), NODE2.value());
        UUID callId = port.records.get(0).callId().orElseThrow();
        assertThat(port.records).extracting(AuditRecord::callId).containsOnly(Optional.of(callId));
        assertThat(found.callId()).as("the result names the call id").isEqualTo(callId);
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.EXECUTED, AuditOutcome.REFUSED);
        assertThat(port.records).extracting(AuditRecord::reason)
            .containsExactly(Optional.empty(), Optional.of("NOT_CONFIGURED"));
        assertThat(port.records.get(0).details()).containsExactly(Map.entry("fromCache", false),
            Map.entry("systemDiagramsRead", 1), Map.entry("partnersReturned", 3),
            Map.entry("valuesMasked", masked));
        assertThat(masked).isPositive();
        assertThat(port.records.get(1).details()).containsExactly(Map.entry("fromCache", false),
            Map.entry("systemDiagramsRead", 0), Map.entry("partnersReturned", 0),
            Map.entry("valuesMasked", 0));
        assertThat(port.records.get(0).inputs()).containsExactly(Map.entry("target", "dev"),
            Map.entry("search", "acme"));
        assertThat(lines()).containsExactly(
            "find_partners on dev/node1: EXECUTED (owner " + OWNER + ", systemDiagramsRead 1,"
                + " partnersReturned 3, valuesMasked " + masked + ", fromCache false; call "
                + callId + ")",
            "find_partners on dev/node2: REFUSED NOT_CONFIGURED (owner none, systemDiagramsRead 0,"
                + " partnersReturned 0, valuesMasked 0, fromCache false; call " + callId + ")");
    }

    @Test
    void aCacheHitIsAuditedWithFromCacheUnderItsOwnCallId() {
        PartnerService service = service(port);

        service.find(query("dev/node1", Optional.of("acme-0001"), Optional.empty()));
        clock.advance(Duration.ofMinutes(2));
        service.find(query("dev/node1", Optional.of("acme-0001"), Optional.empty()));

        assertThat(port.records).hasSize(2);
        assertThat(port.records).extracting(record -> record.details().get("fromCache"))
            .containsExactly(false, true);
        assertThat(port.records).extracting(record -> record.details().get("partnersReturned"))
            .containsExactly(1, 1);
        assertThat(port.records.get(0).callId()).isNotEqualTo(port.records.get(1).callId());
        assertThat(lines().get(1)).contains("fromCache true");
    }

    @Test
    void anUnknownTargetIsOneRefusedRecordWithoutServerAndOneLine() {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service(port).find(query("dev/node9", Optional.of("acme"), Optional.empty())));

        assertThat(e.error().code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(port.records).singleElement().satisfies(record -> {
            assertThat(record.node()).isEqualTo("dev/node9");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.details()).isEmpty();
        });
        assertThat(lines()).containsExactly("find_partners on dev/node9: REFUSED TARGET_UNKNOWN"
            + " (call " + port.records.get(0).callId().orElseThrow() + ")");
    }

    @Test
    void aRefusalLineBoundsTheTargetAndCannotForgeALine() {
        // the resolver refuses a malformed target as INVALID_INPUT; the record is not written
        port.failing = true;
        String target = "dev/node9\nfind_partners on dev/node1: EXECUTED" + "x".repeat(600);

        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service(port).find(query(target, Optional.empty(), Optional.empty())));

        assertThat(e.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(lines()).singleElement().asString()
            .startsWith("find_partners on dev/node9?find_partners on dev/node1")
            .doesNotContain("\n").doesNotContain("x".repeat(501))
            .endsWith(": REFUSED INVALID_INPUT (call " + port.ids.get(0)
                + ", no audit record)");
    }

    @Test
    void aFailedServerIsAuditedAsFailedWithItsCodeOnly() {
        portFailure = new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
            "dev/node1 did not answer", "cause", "next step").withNode(NODE1));

        Found found = service(port).find(query("dev/node1", Optional.empty(), Optional.empty()));

        assertThat(found.servers().get(0).error()).map(ToolError::code)
            .contains(ErrorCode.UNREACHABLE);
        assertThat(port.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.reason()).contains("UNREACHABLE");
        });
        assertThat(lines()).singleElement().asString()
            .contains("FAILED UNREACHABLE (owner " + OWNER).doesNotContain("did not answer");
    }

    @Test
    void anUnknownSystemDiagramIsAuditedForTheServerThatAnswered() {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service(port).find(query("dev", Optional.empty(), Optional.of("Nothing-01"))));

        assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(port.records).extracting(AuditRecord::outcome, AuditRecord::reason)
            .containsExactly(tuple(AuditOutcome.FAILED, Optional.of("NOT_FOUND")),
                tuple(AuditOutcome.REFUSED, Optional.of("NOT_CONFIGURED")));
    }

    // --- Op-4 ----------------------------------------------------------------------------------

    @Test
    void anAuditWriteFailureEndsInInternalWithoutPartnerData() {
        port.failing = true;

        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service(port).find(query("dev/node1", Optional.of("acme"), Optional.empty())));

        assertThat(e.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(e.error().message()).contains(port.ids.get(0).toString())
            .contains("no partner data");
        assertThat(lines()).singleElement().asString().endsWith(", no audit record)");
    }

    @Test
    void aFailureOnTheSecondServerStillAuditsTheFirstAndEndsInInternal() {
        port.failOn = NODE2.value();

        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service(port).find(query("dev", Optional.of("acme"), Optional.empty())));

        assertThat(e.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(port.records).extracting(AuditRecord::node).containsExactly(NODE1.value());
        assertThat(lines()).hasSize(2);
        assertThat(lines().get(1)).endsWith(", no audit record)");
    }

    // --- leak check ----------------------------------------------------------------------------

    @Test
    void neitherTheAuditLinesNorTheLogHoldASettingOrASecret() throws IOException {
        PartnerService service = service(new AuditLog(directory, new SecretScrubber(),
            PrivateFiles.forHost()));

        service.find(query("dev", Optional.of("acme"), Optional.empty()));
        service.find(query("dev", Optional.of("no-such-partner"), Optional.empty()));
        catchThrowableOfType(ToolErrorException.class,
            () -> service.find(query("dev", Optional.empty(), Optional.of("Nothing-01"))));
        catchThrowableOfType(ToolErrorException.class,
            () -> service.find(query("dev/node9", Optional.of("acme"), Optional.empty())));

        List<String> audit;
        try (Stream<Path> files = Files.list(directory)) {
            audit = files.filter(file -> file.toString().endsWith(".jsonl"))
                .flatMap(PartnerServiceAuditTest::readLines).toList();
        }
        assertThat(audit).hasSize(7);
        assertThat(lines()).hasSize(7);
        assertThat(lines().get(6)).doesNotContain("acme");
        List<String> forbidden = Stream.concat(
            PartnerFixtures.SECRETS.stream().flatMap(PartnerServiceAuditTest::derived),
            Stream.of("ftp1.example.test", "login1", "ACME Corp", "ACME-0001", "format-1"))
            .toList();
        assertThat(audit).allSatisfy(line -> assertThat(line).doesNotContain(forbidden));
        assertThat(lines()).allSatisfy(line -> assertThat(line).doesNotContain(forbidden));
    }

    // --- support -------------------------------------------------------------------------------

    private List<String> lines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static Stream<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code secret}, its 12-character prefix, its unkeyed SHA-256 and its Base64 form. */
    private static Stream<String> derived(String secret) {
        try {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
            return Stream.of(secret, secret.substring(0, 12), sha256,
                Base64.getEncoder().encodeToString(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PartnerQuery query(String target, Optional<String> search,
        Optional<String> systemDiagram) {
        return new PartnerQuery(target, Optional.empty(), Optional.empty(), search,
            systemDiagram, Optional.empty(), Optional.empty(), 0, PartnerQuery.DEFAULT_LIMIT,
            false, Optional.empty());
    }

    private PartnerService service(AuditPort audit) {
        PartnerAudit.Dependencies dependencies = new PartnerAudit.Dependencies(audit, clock,
            "default", () -> {
                UUID id = UUID.randomUUID();
                port.ids.add(id);
                return id;
            });
        return new PartnerService(new Gateways(), new TargetResolver(List.of(NODE1, NODE2)),
            new FanOut(), cache, owners::get, node -> Duration.ofSeconds(5),
            node -> Duration.ofSeconds(120), Terminology.DEFAULT, FakeVersionDetection.pinned81(),
            SecretTiming.NONE, dependencies);
    }

    /** Records every appended record; fails every append while {@code failing} or for a node. */
    private static final class RecordingAudit implements AuditPort {

        final List<AuditRecord> records = new CopyOnWriteArrayList<>();
        /** Every id drawn, in order: each call's id, then its records' audit ids. */
        final List<UUID> ids = new CopyOnWriteArrayList<>();
        volatile boolean failing;
        volatile String failOn;

        @Override
        public void append(AuditRecord record) {
            if (failing || record.node().equals(failOn)) {
                throw new UncheckedIOException(new IOException("disk full"));
            }
            records.add(record);
        }
    }

    /** Only the inventory list and the partner port; every other port is an assertion error. */
    private final class Gateways implements GatewayFactory {

        @Override
        public InventoryPort inventory(NodeId server) {
            return new InventoryPort() {
                @Override
                public List<InventoryItem> listDiagrams(String owner) {
                    return List.of(InventoryItem.diagram(server, ONE.name(), "systemdiagram",
                        ONE.diagramGroup(), owner));
                }

                @Override
                public DiagramDetail diagramDetail(String owner, String name) {
                    throw new AssertionError("not used");
                }

                @Override
                public DiagramMetadata diagramMetadata(String name) {
                    throw new AssertionError("not used");
                }

                @Override
                public VersionHistory versionHistory(String owner, String type, String group) {
                    throw new AssertionError("not used");
                }

                @Override
                public List<ModuleEntry> listModules(String owner) {
                    throw new AssertionError("not used");
                }
            };
        }

        @Override
        public PartnerManagementPort partners(NodeId server) {
            return (owner, diagrams) -> {
                if (portFailure != null) {
                    throw portFailure;
                }
                return new PartnerManagementPort.Result(List.of(DETAIL.partners()), List.of(),
                    Map.of(ONE, DETAIL.warnings()));
            };
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public InubitVersion detect(NodeId server) {
            throw new AssertionError("the version gate detects through its own factory");
        }

        @Override
        public MonitoringPort monitoring(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public ProcessQueryPort processes(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public LogPort logs(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            return Optional.empty();
        }
    }
}
