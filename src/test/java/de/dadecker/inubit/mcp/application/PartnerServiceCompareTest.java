package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.CloudXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.DiagramXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.EntryXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.FieldXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.Leaf;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.PartnerXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.TwfXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.application.PartnerService.Answer;
import de.dadecker.inubit.mcp.application.PartnerService.Compared;
import de.dadecker.inubit.mcp.application.PartnerService.ComparedEntryView;
import de.dadecker.inubit.mcp.application.PartnerService.ComparedPartnerView;
import de.dadecker.inubit.mcp.application.PartnerService.ComparedSettingView;
import de.dadecker.inubit.mcp.application.PartnerService.Found;
import de.dadecker.inubit.mcp.application.PartnerService.GroupedCount;
import de.dadecker.inubit.mcp.application.PartnerService.KeyView;
import de.dadecker.inubit.mcp.application.PartnerService.NotCompared;
import de.dadecker.inubit.mcp.application.PartnerService.WorkflowView;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort.UnreadableDiagram;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T026: the comparison of {@link PartnerService#answer} over a stage target or
 * {@code compareWith} (US3, FR-005a in a comparison, FR-012…FR-014, FR-012c), over the
 * synthetic exports of {@link PartnerFixtures#stages()} read per server by the reader: matched
 * partners counted and paged across the servers, a search hit on one server shown from all, list
 * mode with the summary and the differences only, "only on" for a system diagram on some servers,
 * entry filters per entry key, failed servers not compared, one audit record per server. Every
 * result is checked as the JSON text of {@link ResultJson} against every synthetic secret.
 */
@Timeout(30)
class PartnerServiceCompareTest {

    private static final NodeId T1 = NodeId.parse("test/node1");
    private static final NodeId T2 = NodeId.parse("test/node2");
    private static final NodeId P1 = NodeId.parse("prod/node1");
    private static final NodeId P2 = NodeId.parse("prod/node2");
    private static final List<NodeId> NODES = List.of(T1, T2, P1, P2);
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final String OWNER = PartnerFixtures.OWNER;
    private static final String GROUP = PartnerFixtures.GROUP;
    private static final String CLOUD = "Module-0901";
    private static final DiagramRef DIAGRAM = new DiagramRef("SystemDiagram-01", GROUP);
    private static final List<TwfXml> WORKFLOWS = List.of(new TwfXml("1", "34", "Workflow-0001"),
        new TwfXml("2", "86", "Workflow-0002"));
    private static final List<FieldXml> ID_AND_NAME =
        List.of(new FieldXml("Id", "String"), new FieldXml("Name", "String"));

    /** Per server, the reader's result per system diagram of the owner, in list order. */
    private final Map<NodeId, Map<DiagramRef, PartnerManagementReader.Result>> exports =
        new LinkedHashMap<>();
    private final Map<NodeId, String> owners = new HashMap<>(Map.of(T1, OWNER, T2, OWNER,
        P1, OWNER, P2, OWNER));
    private final Map<NodeId, VersionSetting> versions = new HashMap<>();
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    /** Per server, the failure its inventory and partner ports throw once contacted. */
    private final Map<NodeId, ToolErrorException> failures = new HashMap<>();
    /** Per server, the listed system diagrams whose export gives no data (D-4). */
    private final Map<NodeId, Map<DiagramRef, UnreadableDiagram>> unreadable = new HashMap<>();
    /** The servers whose inventory or partner port was called. */
    private final List<NodeId> contacted = new CopyOnWriteArrayList<>();
    private ResultLimiter limiter = ResultLimiter.withDefaults();

    // --- what is compared ------------------------------------------------------------------------

    @Test
    void aStageTargetOfTwoServersIsComparedWithTheSettingsGrouped() {
        exportStages();

        Compared compared = compared(search("ACME Corp").target("test").compareWith(null));

        assertThat(compared.compared()).isTrue();
        assertThat(compared.mode()).isEqualTo("search");
        assertThat(compared.servers()).extracting(PartnerService.ServerView::node)
            .containsExactly(T1, T2);
        ComparedPartnerView partner = single(compared);
        assertThat(partner.systemDiagram()).isEqualTo(DIAGRAM);
        assertThat(partner.cloud()).isEqualTo(CLOUD);
        assertThat(partner.key()).contains(new KeyView("Id", "ACME-0001"));
        assertThat(partner.onlyOn()).isEmpty();
        assertThat(partner.fields()).containsExactly(equal("Id", "ACME-0001"),
            equal("Name", "ACME Corp"));
        assertThat(partner.maskedCount()).isEqualTo(new GroupedCount(Optional.of(1),
            Optional.empty()));
        assertThat(partner.ambiguous()).isEmpty();
        assertThat(partner.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.protocol()).isEqualTo("FTP");
            assertThat(entry.workflow()).isEqualTo(resolved("Workflow-0001"));
            assertThat(entry.position()).isZero();
            assertThat(entry.onlyOn()).isEmpty();
            assertThat(entry.format()).contains(equal(PartnerComparison.FORMAT, "format-1"));
            assertThat(entry.protocolSettings()).containsExactly(
                equal("ServerName", "ftp.test.example.test"),
                differs("Login", Map.of(T1, "login-a", T2, "login-b")),
                masked("Password", Map.of(T1, true, T2, true), List.of(List.of(T1, T2))),
                equal("FileName", "FileName-1"));
            assertThat(entry.additionalParameters()).contains(List.of(
                equal("FTPAuth", "false"), equal("FTPProtocol", "FTP")));
        });
        assertThat(compared.referencableWorkflows()).singleElement().satisfies(cloud -> {
            assertThat(cloud.systemDiagram()).isEqualTo(DIAGRAM);
            assertThat(cloud.cloud()).isEqualTo(CLOUD);
            assertThat(cloud.workflows()).containsExactly("Workflow-0001", "Workflow-0002",
                "Workflow-0003");
        });
        assertThat(compared.incomplete()).isFalse();
        assertThat(compared.notCompared()).isEmpty();
        assertNoSecretIn(compared);
    }

    @Test
    void compareWithAddsTheServersOfTheOtherStageWithSecretsGroupedByStoredValue() {
        exportStages();

        Compared compared = compared(search("ACME"));

        assertThat(compared.servers()).extracting(PartnerService.ServerView::node)
            .containsExactly(T1, T2, P1, P2);
        assertThat(compared.partners()).extracting(PartnerServiceCompareTest::id)
            .containsExactly("ACME-0001", "ACME-0002", "ACME-0003");
        assertThat(compared.total()).isEqualTo(3);
        ComparedEntryView ftp = partner(compared, "ACME-0001").entries().getFirst();
        assertThat(ftp.protocolSettings()).containsExactly(
            differs("ServerName", Map.of(T1, "ftp.test.example.test", T2, "ftp.test.example.test",
                P1, "ftp.prod.example.test", P2, "ftp.prod.example.test")),
            differs("Login", Map.of(T1, "login-a", T2, "login-b", P1, "login-a", P2, "login-a")),
            masked("Password", Map.of(T1, true, T2, true, P1, true, P2, true),
                List.of(List.of(T1, T2, P1, P2))),
            equal("FileName", "FileName-1"));
        ComparedPartnerView logistics = partner(compared, "ACME-0002");
        assertThat(logistics.maskedCount()).isEqualTo(new GroupedCount(Optional.of(3),
            Optional.empty()));
        ComparedEntryView webService = logistics.entries().getFirst();
        assertThat(webService.protocolSettings()).contains(
            masked("Password", Map.of(T1, true, T2, true, P1, true, P2, true),
                List.of(List.of(T1, T2, P1), List.of(P2))),
            masked("SSLKeyStore", Map.of(T1, false, T2, false, P1, false, P2, false),
                List.of()));
        assertThat(webService.additionalParameters().orElseThrow()).contains(
            masked("WSSecurityPassword", Map.of(T1, true, T2, true, P1, true, P2, true),
                List.of(List.of(T1, T2, P1, P2))));
        assertThat(partner(compared, "ACME-0003").onlyOn()).contains(List.of(T1, T2));
        assertNoSecretIn(compared);
    }

    @Test
    void aServerOfAnotherVersionLineGetsNoEqualityStatementButSaysWhy() {
        exportStages();
        versions.put(P2, VersionSetting.parse("9.0.3").orElseThrow());

        Compared compared = compared(search("ACME Logistics"));

        assertThat(single(compared).entries().getFirst().protocolSettings())
            .filteredOn(setting -> setting.name().equals("Password"))
            .singleElement()
            .satisfies(setting -> {
                assertThat(setting.sameOn()).isEmpty();
                assertThat(setting.equality()).contains("not verified on 9.0.3");
            });
    }

    @Test
    void aSingleServerTargetWithoutCompareWithIsNoComparison() {
        exportStages();

        Answer answer = service().answer(search("ACME Corp").target("test/node1")
            .compareWith(null).build());

        assertThat(answer).isInstanceOfSatisfying(Found.class,
            found -> assertThat(found.compared()).isFalse());
    }

    @Test
    void aSearchHitOnOneServerShowsThePartnerFromAllServers() {
        export(T1, renamed("ACME Corp"));
        export(T2, renamed("ACME Corp"));
        export(P1, renamed("ACME Corp Renamed"));
        export(P2, renamed("ACME Corp Renamed"));

        Compared compared = compared(search("renamed"));

        ComparedPartnerView partner = single(compared);
        assertThat(compared.total()).isEqualTo(1);
        assertThat(partner.onlyOn()).isEmpty();
        assertThat(partner.fields()).containsExactly(equal("Id", "ACME-0001"),
            differs("Name", Map.of(T1, "ACME Corp", T2, "ACME Corp", P1, "ACME Corp Renamed",
                P2, "ACME Corp Renamed")));
        assertThat(partner.entries()).extracting(ComparedEntryView::onlyOn)
            .containsExactly(Optional.empty());
    }

    // --- list mode and paging (US3-AS7, FR-012c) ----------------------------------------------

    @Test
    void listModeShowsTheSummaryAndOnlyTheDifferences() {
        exportStages();

        Compared compared = compared(list());

        assertThat(compared.mode()).isEqualTo("list");
        assertThat(compared.partners()).extracting(PartnerServiceCompareTest::id)
            .containsExactly("ACME-0001", "ACME-0002", "ACME-0003");
        ComparedPartnerView corp = partner(compared, "ACME-0001");
        assertThat(corp.fields()).containsExactly(equal("Id", "ACME-0001"),
            equal("Name", "ACME Corp"));
        assertThat(corp.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.protocolSettings()).as("the summary keys, the same Password not")
                .extracting(ComparedSettingView::name).containsExactly("ServerName", "Login");
            assertThat(entry.format()).as("an equal format is no difference").isEmpty();
            assertThat(entry.additionalParameters()).isEmpty();
        });
        ComparedEntryView webService = partner(compared, "ACME-0002").entries().getFirst();
        assertThat(webService.protocolSettings()).as("a Password with two stored values")
            .extracting(ComparedSettingView::name)
            .containsExactly("ServerName", "Login", "Password");
        assertThat(webService.additionalParameters()).isEmpty();
        assertThat(partner(compared, "ACME-0003").onlyOn()).contains(List.of(T1, T2));
        assertNoSecretIn(compared);
    }

    @Test
    void listModeShowsOnlyTheDifferingAdditionalParameters() {
        for (NodeId node : NODES) {
            String protocol = node.equals(P2) ? "SFTP" : "FTP";
            export(node, new DiagramXml(GROUP, DIAGRAM.name(), OWNER, List.of(new CloudXml("33",
                CLOUD, ID_AND_NAME, WORKFLOWS, List.of(new PartnerXml(fields("ACME-0001",
                    "ACME Corp"), List.of(new EntryXml("FTP", "1",
                        List.of(Leaf.plain("ServerName", "ftp.example.test"),
                            Leaf.plain("Port", "21")),
                        List.of(Leaf.plain("FTPProtocol", protocol),
                            Leaf.typed("FTPAuth", "Boolean", "false"))))))))));
        }

        ComparedEntryView entry = single(compared(list())).entries().getFirst();

        assertThat(entry.protocolSettings()).extracting(ComparedSettingView::name)
            .containsExactly("ServerName");
        assertThat(entry.additionalParameters()).contains(List.of(differs("FTPProtocol",
            Map.of(T1, "FTP", T2, "FTP", P1, "FTP", P2, "SFTP"))));
    }

    @Test
    void totalAndNextOffsetCountMatchedPartnersAcrossTheServers() {
        exportStages();

        Compared first = compared(list().limit(2));
        Compared last = compared(list().offset(2).limit(2));

        assertThat(first.partners()).extracting(PartnerServiceCompareTest::id)
            .containsExactly("ACME-0001", "ACME-0002");
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.nextOffset()).isEqualTo(OptionalInt.of(2));
        assertThat(first.truncated()).isFalse();
        assertThat(last.partners()).extracting(PartnerServiceCompareTest::id)
            .containsExactly("ACME-0003");
        assertThat(last.total()).isEqualTo(3);
        assertThat(last.nextOffset()).isEmpty();
    }

    @Test
    void listModeOrdersByDiagramCloudIdAndNameAcrossTheServers() {
        for (NodeId node : List.of(T1, T2)) {
            export(node, new DiagramXml(GROUP, "SystemDiagram-02", OWNER, List.of(new CloudXml(
                "33", CLOUD, ID_AND_NAME, WORKFLOWS, List.of(named("ACME-0009", "ACME Nine"),
                    named("ACME-0002", "ACME Two"))))));
            export(node, new DiagramXml(GROUP, "SystemDiagram-01", OWNER, List.of(new CloudXml(
                "33", CLOUD, ID_AND_NAME, WORKFLOWS, List.of(named("ACME-0005", "ACME Five"))))));
        }
        for (NodeId node : List.of(P1, P2)) {
            export(node, new DiagramXml(GROUP, "SystemDiagram-02", OWNER, List.of(new CloudXml(
                "33", CLOUD, ID_AND_NAME, WORKFLOWS, List.of(named("ACME-0001", "ACME One"),
                    named("ACME-0009", "ACME Nine"))))));
        }

        Compared compared = compared(list());

        assertThat(compared.partners())
            .extracting(partner -> partner.systemDiagram().name() + "/" + id(partner))
            .containsExactly("SystemDiagram-01/ACME-0005", "SystemDiagram-02/ACME-0001",
                "SystemDiagram-02/ACME-0002", "SystemDiagram-02/ACME-0009");
    }

    // --- FR-005a in a comparison ----------------------------------------------------------------

    @Test
    void aSystemDiagramOnSomeServersMarksItsPartnersOnlyOn() {
        exportStages();
        for (NodeId node : List.of(T1, T2)) {
            export(node, new DiagramXml(GROUP, "SystemDiagram-02", OWNER, List.of(new CloudXml(
                "33", CLOUD, ID_AND_NAME, WORKFLOWS, List.of(named("ACME-0007", "ACME Seven"))))));
        }

        Compared compared = compared(list().systemDiagram("systemdiagram-02"));

        assertThat(compared.partners()).singleElement().satisfies(partner -> {
            assertThat(id(partner)).isEqualTo("ACME-0007");
            assertThat(partner.onlyOn()).contains(List.of(T1, T2));
        });
        assertThat(audit).extracting(AuditRecord::outcome).containsOnly(AuditOutcome.EXECUTED);
    }

    @Test
    void aSystemDiagramOnNoAnsweringServerIsNotFound() {
        exportStages();

        assertThatThrownBy(() -> compared(list().systemDiagram("SystemDiagram-09")))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(audit).hasSize(4)
            .extracting(record -> record.reason().orElseThrow()).containsOnly("NOT_FOUND");
    }

    @Test
    void entryFiltersApplyPerEntryKeyAndShowTheEntryWithItsOnlyOnServers() {
        for (NodeId node : NODES) {
            List<EntryXml> entries = new ArrayList<>(List.of(ftp("ftp.example.test")));
            if (node.group().equals(P1.group())) {
                entries.add(new EntryXml("WebService", "2",
                    List.of(Leaf.plain("ServerName", "http://" + node.name() + ".example.test")),
                    List.of()));
            }
            export(node, new DiagramXml(GROUP, DIAGRAM.name(), OWNER, List.of(new CloudXml("33",
                CLOUD, ID_AND_NAME, WORKFLOWS, List.of(
                    new PartnerXml(fields("ACME-0001", "ACME Corp"), entries),
                    new PartnerXml(fields("ACME-0002", "ACME Logistics"),
                        List.of(ftp("ftp2.example.test"))))))));
        }

        Compared byProtocol = compared(list().protocol("webservice"));
        Compared byWorkflow = compared(search("ACME").workflow("WORKFLOW-0002"));
        Compared none = compared(list().protocol("AS2"));

        assertThat(byProtocol.partners()).singleElement().satisfies(partner -> {
            assertThat(id(partner)).isEqualTo("ACME-0001");
            assertThat(partner.onlyOn()).isEmpty();
            assertThat(partner.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.protocol()).isEqualTo("WebService");
                assertThat(entry.onlyOn()).contains(List.of(P1, P2));
                assertThat(entry.protocolSettings()).containsExactly(differs("ServerName",
                    Map.of(P1, "http://node1.example.test", P2, "http://node2.example.test")));
            });
        });
        assertThat(byProtocol.total()).isEqualTo(1);
        assertThat(byWorkflow.partners()).extracting(PartnerServiceCompareTest::id)
            .containsExactly("ACME-0001");
        assertThat(byWorkflow.partners().getFirst().entries())
            .extracting(ComparedEntryView::protocol).containsExactly("WebService");
        assertThat(none.partners()).isEmpty();
        assertThat(none.total()).isZero();
    }

    // --- failed servers (FR-014) ---------------------------------------------------------------

    @Test
    void aFailedServerIsNotComparedAndTheComparisonIsIncomplete() {
        exportStages();
        owners.remove(P2);

        Compared compared = compared(list());

        assertThat(compared.incomplete()).isTrue();
        assertThat(compared.notCompared()).containsExactly(
            new NotCompared(P2, ErrorCode.NOT_CONFIGURED));
        assertThat(compared.servers().get(3).error()).hasValueSatisfying(
            error -> assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED));
        ComparedEntryView webService = partner(compared, "ACME-0002").entries().getFirst();
        assertThat(webService.protocolSettings()).as("the one other stored value was on P2")
            .extracting(ComparedSettingView::name).containsExactly("ServerName", "Login");
        assertThat(partner(compared, "ACME-0001").onlyOn()).isEmpty();
        assertThat(partner(compared, "ACME-0003").onlyOn()).contains(List.of(T1, T2));
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.EXECUTED,
            AuditOutcome.EXECUTED, AuditOutcome.EXECUTED, AuditOutcome.REFUSED);
        assertNoSecretIn(compared);
    }

    @Test
    void aServerFailingAfterContactIsFailedAndContributesNothing() {
        exportStages();
        failures.put(P1, new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
            "prod/node1 did not answer", "cause", "next step").withNode(P1)));

        Compared compared = compared(search("ACME"));

        assertThat(compared.incomplete()).isTrue();
        assertThat(compared.notCompared()).containsExactly(
            new NotCompared(P1, ErrorCode.UNREACHABLE));
        assertThat(partner(compared, "ACME-0003").onlyOn()).contains(List.of(T1, T2));
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.EXECUTED,
            AuditOutcome.EXECUTED, AuditOutcome.FAILED, AuditOutcome.EXECUTED);
        assertThat(audit.get(2).reason()).contains("UNREACHABLE");
        assertThat(audit.get(2).details()).containsEntry("partnersReturned", 0)
            .containsEntry("valuesMasked", 0);
        assertThat(audit.get(3).details()).containsEntry("partnersReturned", 2);
    }

    @Test
    void aSystemDiagramOneServerCouldNotReadLeavesItOutOfThatDiagramsStatementsOnly() {
        exportStages();
        exports.get(P2).remove(DIAGRAM);
        UnreadableDiagram gone = new UnreadableDiagram(DIAGRAM, ErrorCode.NOT_FOUND,
            "SystemDiagram-01 was deleted after listing");
        unreadable.put(P2, Map.of(DIAGRAM, gone));
        for (NodeId node : NODES) {
            export(node, new DiagramXml(GROUP, "SystemDiagram-02", OWNER, List.of(new CloudXml(
                "33", CLOUD, ID_AND_NAME, WORKFLOWS, List.of(named("ACME-0007", "ACME Seven"))))));
        }

        Compared compared = compared(list());

        assertThat(compared.incomplete()).isTrue();
        assertThat(compared.notCompared()).containsExactly(
            new NotCompared(P2, ErrorCode.NOT_FOUND, Optional.of(DIAGRAM)));
        assertThat(partner(compared, "ACME-0001").onlyOn()).as("P2 neither holds nor lacks it")
            .isEmpty();
        assertThat(partner(compared, "ACME-0003").onlyOn()).contains(List.of(T1, T2));
        assertThat(partner(compared, "ACME-0007").onlyOn()).isEmpty();
        assertThat(audit).extracting(AuditRecord::outcome).containsOnly(AuditOutcome.EXECUTED);
        assertThat(audit.get(3).details()).as("only ACME-0007 is held by P2")
            .containsEntry("partnersReturned", 1);
        assertNoSecretIn(compared);
    }

    @Test
    void manyLongNamedSystemDiagramsOneServerCouldNotReadStayWithinTheBound() {
        exportStages();
        Map<DiagramRef, UnreadableDiagram> gone = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) {
            DiagramRef diagram = new DiagramRef("Gone-%03d-".formatted(i) + "x".repeat(400), GROUP);
            gone.put(diagram, new UnreadableDiagram(diagram, ErrorCode.UNEXPECTED_RESPONSE,
                "no answer"));
        }
        unreadable.put(P2, gone);
        limiter = new ResultLimiter(100, 20_000);

        Compared compared = compared(list());

        assertWithin(compared, 20_000, "300 unreadable diagrams");
        assertThat(compared.truncated()).isTrue();
        assertThat(compared.incomplete()).isTrue();
        assertThat(compared.partners()).isNotEmpty();
        assertThat(compared.notCompared()).as("only the diagrams listed under servers, cut")
            .isNotEmpty()
            .allSatisfy(entry -> assertThat(entry.systemDiagram().orElseThrow().name())
                .hasSizeLessThanOrEqualTo(ItemBounds.MAX_FIELD_CHARS))
            .extracting(NotCompared::systemDiagram)
            .containsExactlyElementsOf(compared.servers().get(3).unreadableDiagrams().stream()
                .map(diagram -> Optional.of(diagram.systemDiagram())).toList());
    }

    @Test
    void theUnreadableSystemDiagramOfAPartnerOnThePageIsKeptFirst() {
        exportStages();
        exports.get(P2).remove(DIAGRAM);
        Map<DiagramRef, UnreadableDiagram> gone = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) {
            DiagramRef diagram = i == 250 ? DIAGRAM
                : new DiagramRef("Gone-%03d-".formatted(i) + "x".repeat(400), GROUP);
            gone.put(diagram, new UnreadableDiagram(diagram, ErrorCode.UNEXPECTED_RESPONSE,
                "no answer"));
        }
        unreadable.put(P2, gone);
        limiter = new ResultLimiter(100, 20_000);

        Compared compared = compared(list());

        assertWithin(compared, 20_000, "300 unreadable diagrams");
        assertThat(compared.truncated()).isTrue();
        assertThat(compared.partners()).isNotEmpty()
            .allSatisfy(partner -> assertThat(partner.systemDiagram()).isEqualTo(DIAGRAM));
        assertThat(compared.notCompared()).as("P2 neither holds nor lacks the page's partners")
            .contains(new NotCompared(P2, ErrorCode.UNEXPECTED_RESPONSE, Optional.of(DIAGRAM)));
    }

    @Test
    void everyServerFailingIsAnIncompleteEmptyComparisonNotNotFound() {
        exportStages();
        for (NodeId node : NODES) {
            failures.put(node, new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
                node + " did not answer", "cause", "next step").withNode(node)));
        }

        Compared compared = compared(search("ACME").systemDiagram("SystemDiagram-09"));

        assertThat(compared.partners()).isEmpty();
        assertThat(compared.total()).isZero();
        assertThat(compared.similarValues()).isEmpty();
        assertThat(compared.incomplete()).isTrue();
        assertThat(compared.notCompared()).extracting(NotCompared::node)
            .containsExactlyElementsOf(NODES);
        assertThat(audit).extracting(AuditRecord::outcome).containsOnly(AuditOutcome.FAILED);
    }

    @Test
    void anUnknownCompareWithIsRefusedBeforeAnyServerIsContacted() {
        exportStages();

        assertThatThrownBy(() -> compared(list().compareWith("qa")))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TARGET_UNKNOWN));
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.reason()).hasValueSatisfying(
                reason -> assertThat(reason).startsWith("TARGET_UNKNOWN"));
        });
        assertThat(contacted).isEmpty();
    }

    // --- masked values in a comparison (FR-011) ------------------------------------------------

    @Test
    void aFieldMaskedOnOneServerIsNeitherSearchedNorShownNorSuggested() {
        for (NodeId node : NODES) {
            String name = node.equals(T1) ? PartnerFixtures.KEY_MATERIAL : "Zeta Unique";
            export(node, new DiagramXml(GROUP, DIAGRAM.name(), OWNER, List.of(new CloudXml("33",
                CLOUD, ID_AND_NAME, WORKFLOWS, List.of(new PartnerXml(fields("ACME-0001", name),
                    List.of(ftp("ftp.example.test"))))))));
        }

        Compared searched = compared(search("Zeta"));
        Compared typo = compared(search("Zeta Uniqve"));
        Compared listed = compared(list());

        assertThat(searched.partners()).as("Name is masked on test/node1").isEmpty();
        assertThat(typo.similarValues().orElse(List.of())).doesNotContain("Zeta Unique");
        assertThat(single(listed).fields()).containsExactly(equal("Id", "ACME-0001"),
            masked("Name", Map.of(T1, true, T2, true, P1, true, P2, true), List.of()));
        assertThat(ResultJson.mapper().writeValueAsString(listed)).doesNotContain("Zeta Unique");
        assertNoSecretIn(listed);
    }

    // --- similar values and paging edges --------------------------------------------------------

    @Test
    void aComparisonSearchWithoutHitsSuggestsSimilarShownValues() {
        exportStages();

        Compared compared = compared(search("ACME Corb"));

        assertThat(compared.partners()).isEmpty();
        assertThat(compared.similarValues()).hasValueSatisfying(
            similar -> assertThat(similar).contains("ACME Corp"));
    }

    @Test
    void anOffsetBeyondTheMatchesReturnsAnEmptyPageWithTheTotal() {
        exportStages();

        Compared compared = compared(list().offset(10));

        assertThat(compared.partners()).isEmpty();
        assertThat(compared.total()).isEqualTo(3);
        assertThat(compared.offset()).isEqualTo(10);
        assertThat(compared.nextOffset()).isEmpty();
        assertThat(audit).extracting(record -> record.details().get("partnersReturned"))
            .containsOnly(0);
    }

    @Test
    void aSearchKeepsTheComparisonOrderWhileTheListIsSorted() {
        for (NodeId node : NODES) {
            export(node, new DiagramXml(GROUP, DIAGRAM.name(), OWNER, List.of(new CloudXml("33",
                CLOUD, ID_AND_NAME, WORKFLOWS, List.of(named("ACME-0009", "ACME Nine"),
                    named("ACME-0002", "ACME Two"))))));
        }

        assertThat(compared(search("ACME")).partners()).extracting(PartnerServiceCompareTest::id)
            .containsExactly("ACME-0009", "ACME-0002");
        assertThat(compared(list()).partners()).extracting(PartnerServiceCompareTest::id)
            .containsExactly("ACME-0002", "ACME-0009");
    }

    // --- audit (FR-020) ------------------------------------------------------------------------

    @Test
    void oneAuditRecordPerServerSharesTheCallIdAndCountsWhatTheServerContributed() {
        exportStages();

        Compared listed = compared(list());
        List<AuditRecord> listAudit = List.copyOf(audit);
        audit.clear();
        Compared searched = compared(search("ACME"));

        assertThat(listAudit).extracting(AuditRecord::node)
            .containsExactly("test/node1", "test/node2", "prod/node1", "prod/node2");
        assertThat(listAudit).extracting(AuditRecord::callId)
            .containsOnly(Optional.of(listed.callId()));
        assertThat(listAudit).extracting(record -> record.details().get("partnersReturned"))
            .containsExactly(3, 3, 2, 2);
        assertThat(listAudit).extracting(record -> record.details().get("valuesMasked"))
            .as("the Password of ACME-0002 shown in the list")
            .containsExactly(1, 1, 1, 1);
        assertThat(audit).extracting(AuditRecord::callId)
            .containsOnly(Optional.of(searched.callId()));
        assertThat(audit).extracting(record -> record.details().get("valuesMasked"))
            .as("Password; Password, SSLKeyStore, WSSecurityPassword; Password on test only")
            .containsExactly(5, 5, 4, 4);
    }

    // --- size bounds (T027, FR-017, SC-004) ---------------------------------------------------

    @Test
    void everyPageOfAThousandPartnerComparisonOfTwoStagesStaysWithinTheBound() {
        NODES.forEach(node -> export(node, PartnerFixtures.list(1000)));
        List<String> all = IntStream.rangeClosed(1, 1000)
            .mapToObj("ACME-%04d"::formatted).toList();

        for (int maxChars : List.of(10_000, 50_000)) {
            for (Optional<String> search : List.of(Optional.of("acme"), Optional.<String>empty())) {
                limiter = new ResultLimiter(100, maxChars);
                List<String> seen = new ArrayList<>();
                boolean anyTruncated = false;
                OptionalInt offset = OptionalInt.of(0);
                while (offset.isPresent()) {
                    Compared page = compared(new Query(search).offset(offset.getAsInt())
                        .limit(100));
                    String what = maxChars + " " + search + " offset " + offset.getAsInt();
                    assertWithin(page, maxChars, what);
                    assertThat(page.total()).as(what).isEqualTo(1000);
                    assertThat(page.partners()).as(what).isNotEmpty();
                    if (page.partners().size() < Math.min(100, 1000 - offset.getAsInt())) {
                        assertThat(page.truncated()).as(what).isTrue();
                        assertThat(page.nextOffset()).as(what)
                            .hasValue(offset.getAsInt() + page.partners().size());
                    }
                    anyTruncated |= page.truncated();
                    page.partners().forEach(partner -> seen.add(id(partner)));
                    offset = page.nextOffset();
                }
                assertThat(seen).as(maxChars + " " + search).isEqualTo(all);
                if (maxChars == 10_000) {
                    assertThat(anyTruncated).as(maxChars + " " + search).isTrue();
                }
            }
        }
    }

    @Test
    void comparedDetailIsCutBeforeThePartnersOfThePageFromTheLastPartner() {
        NODES.forEach(node -> export(node, PartnerFixtures.list(40)));
        Query query = search("acme").limit(20);
        int full = ResultJson.size(compared(query));

        for (int budget = ItemBounds.MAX_ITEM_CHARS; budget <= full + 500; budget += 500) {
            limiter = new ResultLimiter(100, budget);
            Compared compared = compared(query);
            String what = "budget " + budget;
            assertWithin(compared, budget, what);
            List<Integer> levels = compared.partners().stream()
                .map(PartnerServiceCompareTest::level).toList();
            assertThat(levels).as(what + ": cut from the last partner").isSorted();
            if (levels.contains(2)) {
                assertThat(levels).as(what + ": parameters of all first").doesNotContain(0);
            }
            if (compared.partners().size() < 20) {
                assertThat(levels).as(what + ": detail cut before partners").containsOnly(2);
                assertThat(compared.nextOffset()).hasValue(compared.partners().size());
            }
            assertThat(compared.partners()).as(what + ": no entry cut while partners can go")
                .allSatisfy(partner -> assertThat(partner.entries()).hasSize(1));
            assertThat(compared.truncated()).as(what).isEqualTo(budget < full);
            assertNoSecretIn(compared);
        }
    }

    @Test
    void aSingleComparedPartnerThatDoesNotFitLosesItsLastEntries() {
        List<EntryXml> entries = IntStream.rangeClosed(1, 80)
            .mapToObj(n -> new EntryXml("FTP", "1", List.of(
                Leaf.plain("ServerName", "host" + n + ".example.test"),
                Leaf.plain("Login", "login-" + n)), List.of()))
            .toList();
        NODES.forEach(node -> export(node, new DiagramXml(GROUP, DIAGRAM.name(), OWNER,
            List.of(new CloudXml("33", CLOUD, ID_AND_NAME, WORKFLOWS, List.of(
                new PartnerXml(fields("ACME-0001", "ACME Corp"), entries)))))));
        limiter = new ResultLimiter(100, ItemBounds.MAX_ITEM_CHARS);

        Compared compared = compared(list());

        assertWithin(compared, ItemBounds.MAX_ITEM_CHARS, "one large partner");
        ComparedPartnerView partner = single(compared);
        assertThat(partner.entries()).isNotEmpty().hasSizeLessThan(80);
        assertThat(partner.entries()).extracting(ComparedEntryView::position)
            .as("the first entries are kept")
            .containsExactlyElementsOf(IntStream
                .range(0, partner.entries().size()).boxed().toList());
        assertThat(compared.truncated()).isTrue();
    }

    @Test
    void comparedTextsAreCutWithTheMarkerAndTheSearchStillMatchesTheFullValue() {
        String name = "ACME Long " + "n".repeat(280) + " TAIL-0001";
        for (NodeId node : NODES) {
            export(node, new DiagramXml(GROUP, DIAGRAM.name(), OWNER, List.of(new CloudXml("33",
                CLOUD, ID_AND_NAME, WORKFLOWS, List.of(new PartnerXml(fields("ACME-0001",
                    node.equals(P2) ? name + " " + "p".repeat(300) : name),
                    List.of(ftp("ftp-" + "h".repeat(300) + ".example.test"))))))));
        }

        Compared compared = compared(search("tail-0001"));

        ComparedPartnerView partner = single(compared);
        ComparedSettingView named = partner.fields().get(1);
        assertThat(named.differs()).hasValueSatisfying(values -> assertThat(values.values())
            .allSatisfy(value -> assertThat(value).hasSize(ItemBounds.MAX_FIELD_CHARS)
                .startsWith("ACME Long nnn").endsWith(ItemBounds.TRUNCATION_MARKER)));
        assertThat(partner.entries().getFirst().protocolSettings().getFirst().equal())
            .hasValueSatisfying(value -> assertThat(value).hasSize(ItemBounds.MAX_FIELD_CHARS)
                .endsWith(ItemBounds.TRUNCATION_MARKER));
        assertThat(compared.truncated()).isTrue();
    }

    @Test
    void everyComparedPartnerHasItsSystemDiagramListedOnEveryServerHoldingIt() {
        // one partner per system diagram, listed in reverse name order; the last ten only on test
        for (NodeId node : NODES) {
            for (int n = 40; n >= 1; n--) {
                if (n > 30 && node.group().equals("prod")) {
                    continue;
                }
                int number = n;
                List<CloudXml> clouds = IntStream.rangeClosed(1, 4)
                    .mapToObj(c -> new CloudXml(String.valueOf(30 + c), "Module-090" + c,
                        ID_AND_NAME, List.of(), c > 1 ? List.of()
                            : List.of(named("ACME-%04d".formatted(number),
                                "ACME Partner " + number))))
                    .toList();
                export(node, new DiagramXml(GROUP,
                    "SystemDiagram-%03d-".formatted(n) + "d".repeat(120), OWNER, clouds));
            }
        }
        limiter = new ResultLimiter(100, 16_000);

        for (Query query : List.of(search("acme"), list())) {
            List<String> seen = new ArrayList<>();
            OptionalInt offset = OptionalInt.of(0);
            while (offset.isPresent()) {
                Compared compared = compared(query.offset(offset.getAsInt()).limit(100));
                String what = query.search + " offset " + offset.getAsInt();
                assertWithin(compared, 16_000, what);
                assertThat(compared.partners()).as(what).isNotEmpty();
                for (ComparedPartnerView partner : compared.partners()) {
                    for (NodeId holder : partner.onlyOn().orElse(NODES)) {
                        assertThat(compared.servers().get(NODES.indexOf(holder))
                            .systemDiagramsRead())
                            .extracting(PartnerService.DiagramRead::name).as(what + " " + holder)
                            .contains(partner.systemDiagram().name());
                    }
                }
                if (offset.getAsInt() == 0) {
                    assertThat(compared.truncated()).as(what).isTrue();
                }
                compared.partners().forEach(partner -> seen.add(id(partner)));
                offset = compared.nextOffset();
            }
            assertThat(seen).as(query.search + ": every partner on some page")
                .containsExactlyInAnyOrderElementsOf(IntStream
                    .rangeClosed(1, 40).mapToObj("ACME-%04d"::formatted).toList());
        }
    }

    @Test
    void theAuditCountsWhatTheBoundedComparisonHolds() {
        NODES.forEach(node -> export(node, PartnerFixtures.list(40)));
        limiter = new ResultLimiter(100, 10_000);

        Compared compared = compared(search("acme").limit(20));

        assertThat(compared.partners()).isNotEmpty().hasSizeLessThan(20);
        assertThat(audit).hasSize(4).allSatisfy(record -> {
            String node = record.node();
            assertThat(record.details())
                .containsEntry("partnersReturned", compared.partners().size())
                .containsEntry("valuesMasked", (int) compared.partners().stream()
                    .flatMap(partner -> Stream.concat(partner.fields().stream(),
                        partner.entries().stream().flatMap(entry -> Stream.of(
                                entry.format().stream(), entry.protocolSettings().stream(),
                                entry.additionalParameters().stream().flatMap(List::stream))
                            .flatMap(Function.identity()))))
                    .filter(setting -> setting.set().filter(set -> set.containsKey(node))
                        .isPresent())
                    .count());
        });
    }

    // --- support -------------------------------------------------------------------------------

    /**
     * 0: in full; 1: without additional parameters; 2: protocol settings down to the summary
     * keys.
     */
    private static int level(ComparedPartnerView partner) {
        boolean parameters = partner.entries().stream()
            .allMatch(entry -> entry.additionalParameters().isPresent());
        boolean summary = partner.entries().stream()
            .flatMap(entry -> entry.protocolSettings().stream())
            .allMatch(setting -> PartnerService.SUMMARY_KEYS.contains(setting.name()));
        return parameters ? 0 : summary ? 2 : 1;
    }

    /** The result, which the tool returns as it is, within {@code maxChars}. */
    private static void assertWithin(Compared compared, int maxChars, String what) {
        assertThat(ResultJson.size(compared)).as(what).isLessThanOrEqualTo(maxChars);
    }

    /** {@link PartnerFixtures#stages()} read per server. */
    private void exportStages() {
        PartnerFixtures.stages().forEach((node, xml) -> exports
            .computeIfAbsent(NodeId.parse(node), n -> new LinkedHashMap<>())
            .put(DIAGRAM, PartnerManagementReader.read(DIAGRAM, XmlTree.parse(xml).root())));
    }

    /** Reads {@code diagram} with the reader as the adapter does and offers it on {@code node}. */
    private void export(NodeId node, DiagramXml diagram) {
        DiagramRef ref = new DiagramRef(diagram.name(), diagram.group());
        exports.computeIfAbsent(node, n -> new LinkedHashMap<>()).put(ref,
            PartnerManagementReader.read(ref,
                XmlTree.parse(PartnerFixtures.workflowXml(diagram)).root()));
    }

    private static DiagramXml renamed(String name) {
        return new DiagramXml(GROUP, DIAGRAM.name(), OWNER, List.of(new CloudXml("33", CLOUD,
            ID_AND_NAME, WORKFLOWS, List.of(new PartnerXml(fields("ACME-0001", name),
                List.of(ftp("ftp.example.test")))))));
    }

    private static PartnerXml named(String id, String name) {
        return new PartnerXml(fields(id, name), List.of());
    }

    private static List<Leaf> fields(String id, String name) {
        return List.of(Leaf.plain("Id", id), Leaf.plain("Name", name));
    }

    private static EntryXml ftp(String serverName) {
        return new EntryXml("FTP", "1", List.of(Leaf.plain("ServerName", serverName),
            Leaf.plain("Login", "login1")), List.of());
    }

    private static Query list() {
        return new Query(Optional.empty());
    }

    private static Query search(String term) {
        return new Query(Optional.of(term));
    }

    /** A query on stage {@code test} compared with {@code prod}; default page unless changed. */
    private static final class Query {

        private final Optional<String> search;
        private String target = "test";
        private Optional<String> compareWith = Optional.of("prod");
        private Optional<String> systemDiagram = Optional.empty();
        private Optional<String> protocol = Optional.empty();
        private Optional<String> workflow = Optional.empty();
        private int offset;
        private int limit = PartnerQuery.DEFAULT_LIMIT;

        Query(Optional<String> search) {
            this.search = search;
        }

        Query target(String value) {
            target = value;
            return this;
        }

        Query compareWith(String value) {
            compareWith = Optional.ofNullable(value);
            return this;
        }

        Query systemDiagram(String name) {
            systemDiagram = Optional.of(name);
            return this;
        }

        Query protocol(String name) {
            protocol = Optional.of(name);
            return this;
        }

        Query workflow(String name) {
            workflow = Optional.of(name);
            return this;
        }

        Query offset(int value) {
            offset = value;
            return this;
        }

        Query limit(int value) {
            limit = value;
            return this;
        }

        PartnerQuery build() {
            return new PartnerQuery(target, compareWith, Optional.empty(), search, systemDiagram,
                protocol, workflow, offset, limit, false, Optional.empty());
        }
    }

    private Compared compared(Query query) {
        Answer answer = service().answer(query.build());
        assertThat(answer).isInstanceOf(Compared.class);
        return (Compared) answer;
    }

    private PartnerService service() {
        NodeVersions nodeVersions = new NodeVersions(Clock.systemUTC(),
            node -> versions.getOrDefault(node, VersionSetting.V8_1),
            node -> Duration.ofMinutes(10));
        return new PartnerService(new Gateways(), new TargetResolver(NODES), new FanOut(),
            limiter,
            new InventoryCache(new MutableClock(T0), node -> Duration.ofMinutes(10)),
            node -> Optional.ofNullable(owners.get(node)), node -> Duration.ofSeconds(5),
            node -> Duration.ofSeconds(120), ResultJson::size, Terminology.DEFAULT,
            FakeVersionDetection.gateWithoutClients(nodeVersions,
                new FakeVersionDetection(nodeVersions), CapabilityMatrix.production()),
            SecretTiming.NONE, new PartnerAudit.Dependencies(audit::add, Clock.systemUTC(),
                "default", UUID::randomUUID));
    }

    /**
     * The JSON text of the result holds no synthetic secret, not even its first 12 characters (a
     * fingerprint would make the mapper throw).
     */
    private static void assertNoSecretIn(Compared compared) {
        String json = ResultJson.mapper().writeValueAsString(compared);
        for (String secret : PartnerFixtures.SECRETS) {
            assertThat(json).doesNotContain(secret).doesNotContain(secret.substring(0, 12));
        }
    }

    private static ComparedPartnerView single(Compared compared) {
        assertThat(compared.partners()).hasSize(1);
        return compared.partners().getFirst();
    }

    private static ComparedPartnerView partner(Compared compared, String id) {
        return compared.partners().stream()
            .filter(partner -> id.equals(id(partner)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no partner " + id));
    }

    private static String id(ComparedPartnerView partner) {
        return partner.key().map(KeyView::value).orElse(null);
    }

    private static ComparedSettingView equal(String name, String value) {
        return new ComparedSettingView(name, Optional.of(value), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
    }

    private static ComparedSettingView differs(String name, Map<NodeId, String> values) {
        return new ComparedSettingView(name, Optional.empty(), Optional.of(byNode(values)),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
    }

    private static ComparedSettingView masked(String name, Map<NodeId, Boolean> set,
        List<List<NodeId>> sameOn) {
        return new ComparedSettingView(name, Optional.empty(), Optional.empty(),
            Optional.of(true), Optional.of(byNode(set)),
            sameOn.isEmpty() ? Optional.empty() : Optional.of(sameOn), Optional.empty(),
            Optional.empty());
    }

    private static <T> Map<String, T> byNode(Map<NodeId, T> values) {
        return values.entrySet().stream()
            .collect(Collectors.toMap(entry -> entry.getKey().toString(), Map.Entry::getValue));
    }

    private static WorkflowView resolved(String name) {
        return new WorkflowView(Optional.of(name), Optional.empty());
    }

    /** Only the inventory list and the partner port, per server; every other port fails. */
    private final class Gateways implements GatewayFactory {

        @Override
        public InventoryPort inventory(NodeId server) {
            return new InventoryPort() {
                @Override
                public List<InventoryItem> listDiagrams(String owner) {
                    contacted.add(server);
                    if (failures.containsKey(server)) {
                        throw failures.get(server);
                    }
                    return Stream.concat(exports.getOrDefault(server, Map.of()).keySet().stream(),
                            unreadable.getOrDefault(server, Map.of()).keySet().stream())
                        .map(diagram -> InventoryItem.diagram(server, diagram.name(),
                            "systemdiagram", diagram.diagramGroup(), owner))
                        .toList();
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
                contacted.add(server);
                if (failures.containsKey(server)) {
                    throw failures.get(server);
                }
                Map<DiagramRef, PartnerManagementReader.Result> read = exports.get(server);
                Map<DiagramRef, UnreadableDiagram> gone = unreadable.getOrDefault(server,
                    Map.of());
                List<DiagramRef> readable = diagrams.stream()
                    .filter(diagram -> !gone.containsKey(diagram))
                    .toList();
                return new PartnerManagementPort.Result(
                    readable.stream().map(diagram -> read.get(diagram).partners()).toList(),
                    diagrams.stream().filter(gone::containsKey).map(gone::get).toList(),
                    readable.stream().collect(Collectors.toMap(diagram -> diagram,
                        diagram -> read.get(diagram).warnings())));
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
