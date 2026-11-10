package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.DiagramXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.application.PartnerService.Found;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T031 (SC-005): a system diagram of 1,000 partners is parsed, masked, fingerprinted and paged in
 * at most five seconds beyond the read; a repeated call within the cache TTL makes no request.
 * The port answers at once with the bytes of {@link PartnerFixtures#list(int)} and parses them on
 * every read as {@code V81PartnerManagementAdapter} does (parse, mask, fingerprint), so the
 * measured time holds everything but the HTTP request, the unpacking of its ZIP and the final
 * JSON text of the tool.
 */
@Timeout(60)
class PartnerServicePerformanceTest {

    private static final NodeId NODE1 = NodeId.parse("dev/node1");
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final int PARTNERS = 1_000;

    private final DiagramXml diagram = PartnerFixtures.list(PARTNERS);
    private final DiagramRef ref = new DiagramRef(diagram.name(), diagram.group());
    private final byte[] workflowXml = PartnerFixtures.workflowXml(diagram);
    private final AtomicInteger reads = new AtomicInteger();
    private final MutableClock clock = new MutableClock(T0);
    private final PartnerService service = new PartnerService(new Gateways(),
        new TargetResolver(List.of(NODE1)), new FanOut(), ResultLimiter.withDefaults(),
        new InventoryCache(clock, node -> TTL), node -> Optional.of(PartnerFixtures.OWNER),
        node -> Duration.ofSeconds(5), node -> Duration.ofSeconds(120), ResultJson::size,
        Terminology.DEFAULT, FakeVersionDetection.pinned81(), SecretTiming.NONE,
        new PartnerAudit.Dependencies(record -> { }, Clock.systemUTC(), "default",
            UUID::randomUUID));

    @Test
    void aThousandPartnersAreParsedMaskedFingerprintedAndPagedWithinFiveSeconds() {
        long start = System.nanoTime();
        Found first = find(Optional.empty(), 0);
        Found last = find(Optional.empty(), PARTNERS - 20);
        Found searched = find(Optional.of("ACME Partner 999"), 0);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(reads).as("one read, then the cache").hasValue(1);
        assertThat(first.total()).isEqualTo(PARTNERS);
        assertThat(first.partners()).hasSize(PartnerQuery.DEFAULT_LIMIT);
        assertThat(first.nextOffset()).isEqualTo(OptionalInt.of(PartnerQuery.DEFAULT_LIMIT));
        assertThat(last.partners()).hasSize(20);
        assertThat(last.nextOffset()).isEmpty();
        assertThat(searched.total()).isEqualTo(1);
        assertThat(ResultJson.mapper().writeValueAsString(first))
            .doesNotContain(PartnerFixtures.AES_PASSWORD);
        assertThat(elapsed).isLessThanOrEqualTo(BUDGET);
    }

    @Test
    void aRepeatedCallWithinTheTtlMakesNoRequest() {
        Found first = find(Optional.empty(), 0);
        clock.advance(TTL.minusSeconds(1));
        long start = System.nanoTime();
        Found again = find(Optional.empty(), 0);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(reads).hasValue(1);
        assertThat(again.partners()).isEqualTo(first.partners());
        assertThat(again.servers()).singleElement()
            .satisfies(server -> assertThat(server.fromCache()).contains(true));
        assertThat(elapsed).isLessThanOrEqualTo(BUDGET);

        clock.advance(Duration.ofSeconds(2));
        find(Optional.empty(), 0);

        assertThat(reads).as("read again after the TTL").hasValue(2);
    }

    private Found find(Optional<String> search, int offset) {
        return (Found) service.answer(new PartnerQuery("dev/node1", Optional.empty(),
            Optional.empty(), search, Optional.empty(), Optional.empty(), Optional.empty(),
            offset, PartnerQuery.DEFAULT_LIMIT, false, Optional.empty()));
    }

    /** Parses and reads the export as the adapter does, counting the reads. */
    private PartnerManagementPort.Result read(List<DiagramRef> diagrams) {
        reads.incrementAndGet();
        assertThat(diagrams).containsExactly(ref);
        PartnerManagementReader.Result result =
            PartnerManagementReader.read(ref, XmlTree.parse(workflowXml).root());
        return new PartnerManagementPort.Result(List.of(result.partners()), List.of(),
            Map.of(ref, result.warnings()));
    }

    /** Only the inventory list and the partner port; every other port is an assertion error. */
    private final class Gateways implements GatewayFactory {

        @Override
        public InventoryPort inventory(NodeId server) {
            return new InventoryPort() {
                @Override
                public List<InventoryItem> listDiagrams(String owner) {
                    return List.of(InventoryItem.diagram(server, ref.name(), "systemdiagram",
                        ref.diagramGroup(), owner));
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
            return (owner, diagrams) -> read(diagrams);
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
