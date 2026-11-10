package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.CloudXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.DiagramXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.FieldXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.Leaf;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.PartnerXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.TwfXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.application.PartnerService.CloudWorkflows;
import de.dadecker.inubit.mcp.application.PartnerService.EntryView;
import de.dadecker.inubit.mcp.application.PartnerService.Found;
import de.dadecker.inubit.mcp.application.PartnerService.PartnerView;
import de.dadecker.inubit.mcp.application.PartnerService.SettingView;
import de.dadecker.inubit.mcp.application.PartnerService.WorkflowView;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.partner.CommunicationCloud;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.Partner;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerField;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.domain.model.partner.ProtocolEntry;
import de.dadecker.inubit.mcp.domain.model.partner.Setting;
import de.dadecker.inubit.mcp.domain.model.partner.SettingValue;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import de.dadecker.inubit.mcp.domain.model.partner.WorkflowRef;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T023: list mode of {@link PartnerService#find} on one server (US2-AS1…4, FR-005a, FR-006): the
 * summary per partner, the list order, paging in that order and the entry filters in both modes,
 * over the masked model the reader makes of the synthetic exports of {@link PartnerFixtures};
 * every result is checked as the JSON text of {@link ResultJson} against every synthetic secret.
 */
@Timeout(30)
class PartnerServiceListTest {

    private static final NodeId NODE1 = NodeId.parse("dev/node1");
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final String OWNER = PartnerFixtures.OWNER;
    private static final String GROUP = PartnerFixtures.GROUP;
    private static final String CLOUD = "Module-0901";
    private static final DiagramRef DETAIL = new DiagramRef("SystemDiagram-01", GROUP);
    private static final List<TwfXml> WORKFLOW = List.of(new TwfXml("1", "34", "Workflow-0001"));
    private static final List<FieldXml> ID_AND_NAME =
        List.of(new FieldXml("Id", "String"), new FieldXml("Name", "String"));

    /** The reader's result per system diagram of the owner, in list order. */
    private final Map<DiagramRef, PartnerManagementReader.Result> exports = new LinkedHashMap<>();
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();

    // --- US2-AS1: order and paging --------------------------------------------------------------

    @Test
    void theFirstPageHoldsTheDefaultNumberWithTotalAndNextOffset() {
        export(PartnerFixtures.list(120));

        Found first = find(list());
        Found middle = find(list().offset(40).limit(7).build());
        Found last = find(list().offset(100).limit(30).build());

        assertThat(first.partners()).extracting(PartnerServiceListTest::id)
            .isEqualTo(ids(1, 20));
        assertThat(first.total()).isEqualTo(120);
        assertThat(first.nextOffset()).isEqualTo(OptionalInt.of(20));
        assertThat(first.similarValues()).isEmpty();
        assertThat(middle.partners()).extracting(PartnerServiceListTest::id)
            .isEqualTo(ids(41, 47));
        assertThat(middle.nextOffset()).isEqualTo(OptionalInt.of(47));
        assertThat(last.partners()).extracting(PartnerServiceListTest::id)
            .isEqualTo(ids(101, 120));
        assertThat(last.total()).isEqualTo(120);
        assertThat(last.nextOffset()).isEmpty();
        assertNoSecretIn(first);
    }

    @Test
    void theListIsOrderedByDiagramGroupAndNameCloudIdAndNameBeforePaging() {
        exportOrderFixture();

        Found all = find(list().limit(100).build());
        Found page = find(list().offset(2).limit(3).build());

        assertThat(all.partners()).extracting(PartnerServiceListTest::name).containsExactly(
            "ACME Five", "ACME Nine", "ACME Alpha", "ACME Zeta", "ACME Delta", "ACME Gamma",
            "ACME Nameless", "ACME Beta");
        assertThat(all.partners()).extracting(partner -> partner.systemDiagram().diagramGroup()
                + "/" + partner.systemDiagram().name() + "/" + partner.cloud())
            .containsExactly("GRP-AB/SystemDiagram-03/Module-0901",
                GROUP + "/SystemDiagram-01/Module-0901", GROUP + "/SystemDiagram-02/Module-0901",
                GROUP + "/SystemDiagram-02/Module-0901", GROUP + "/SystemDiagram-02/Module-0901",
                GROUP + "/SystemDiagram-02/Module-0901", GROUP + "/SystemDiagram-02/Module-0901",
                GROUP + "/SystemDiagram-02/Module-0902");
        assertThat(page.partners()).extracting(PartnerServiceListTest::name)
            .containsExactly("ACME Alpha", "ACME Zeta", "ACME Delta");
        assertThat(page.total()).isEqualTo(8);
        assertThat(page.nextOffset()).isEqualTo(OptionalInt.of(5));
        assertThat(page.referencableWorkflows()).as("the one cloud on the page").containsExactly(
            new CloudWorkflows(new DiagramRef("SystemDiagram-02", GROUP), CLOUD,
                Optional.empty(), List.of("Workflow-0001")));
    }

    @Test
    void aSearchKeepsTheExportOrder() {
        exportOrderFixture();

        Found found = find(search("acme").limit(100).build());

        assertThat(found.partners()).extracting(PartnerServiceListTest::name).containsExactly(
            "ACME Beta", "ACME Gamma", "ACME Zeta", "ACME Delta", "ACME Alpha", "ACME Nameless",
            "ACME Nine", "ACME Five");
    }

    // --- US2-AS4: the summary (FR-006) ----------------------------------------------------------

    @Test
    void eachPartnerIsASummaryWithAllFieldsAndPerEntryOnlyTheSummaryKeys() {
        export(PartnerFixtures.detail());

        Found found = find(list());

        assertThat(found.partners()).containsExactly(
            new PartnerView(DETAIL, CLOUD, Optional.empty(),
                List.of(shown("Id", "ACME-0001"), shown("Name", "ACME Corp"),
                    masked(PartnerFixtures.SECRET_FIELD, true)), 3,
                List.of(summary("FTP", resolved("Workflow-0001"),
                        shown("ServerName", "ftp1.example.test"), shown("Login", "login1")),
                    summary("FTP", resolved("Workflow-0002"),
                        shown("ServerName", "ftp1.example.test"), shown("Login", "login1")))),
            new PartnerView(DETAIL, CLOUD, Optional.empty(),
                List.of(shown("Id", "ACME-0002"), shown("Name", "ACME Logistics"),
                    masked(PartnerFixtures.KEY_MATERIAL_FIELD, true)), 3,
                List.of(summary("FTP", resolved("Workflow-0001"),
                        shown("ServerName", "sftp2.example.test"), shown("Login", "login2")),
                    summary("AS4", resolved("Workflow-0003"),
                        shown("ServerName", "https://as4.example.test/msh")))),
            new PartnerView(DETAIL, CLOUD, Optional.empty(),
                List.of(shown("Id", "ACME-0003"), shown("Name", "ACME Retail")), 6,
                List.of(summary("WebService", resolved("Workflow-0003"),
                        shown("ServerName", "http://host3.example.test/path1"),
                        shown("Login", "login3")),
                    summary("WebService", new WorkflowView(Optional.empty(), Optional.of(true)),
                        shown("ServerName", "http://host3.example.test/path2"),
                        shown("Login", "login3")))));
        assertThat(ResultJson.mapper().writeValueAsString(found.partners().get(0).entries()))
            .doesNotContain("format", "additionalParameters", "Password", "FileName");
        assertNoSecretIn(found);
    }

    @Test
    void theFullDetailsStayAvailableThroughTheSearch() {
        export(PartnerFixtures.detail());

        EntryView entry = find(search("acme-0001").build()).partners().get(0).entries().get(0);

        assertThat(entry.format()).contains("format-1");
        assertThat(entry.protocolSettings()).extracting(SettingView::name)
            .containsExactly("ServerName", "Login", "Password", "FileName");
        assertThat(entry.additionalParameters()).hasValueSatisfying(parameters ->
            assertThat(parameters).extracting(SettingView::name)
                .containsExactly("FTPAuth", "FTPProtocol"));
    }

    @Test
    void aMaskedServerNameOrLoginStaysMaskedInTheSummary() {
        Partner partner = new Partner(
            List.of(new Setting("Id", new SettingValue.Shown("ACME-0009"))),
            List.of(new ProtocolEntry("FTP", new WorkflowRef(Optional.of("Workflow-0001")),
                Optional.of("format-1"),
                List.of(new Setting("ServerName", new SettingValue.Masked(true, Optional.empty())),
                    new Setting("Login", new SettingValue.Masked(false, Optional.empty())),
                    new Setting("Password", new SettingValue.Masked(true, Optional.empty()))),
                List.of(), 0)));
        exports.put(DETAIL, new PartnerManagementReader.Result(new SystemDiagramPartners(DETAIL,
            List.of(new CommunicationCloud(CLOUD, "33", List.of(new PartnerField("Id", "String")),
                List.of("Workflow-0001"), List.of(partner)))), List.of()));

        PartnerView found = find(list()).partners().get(0);

        assertThat(found.entries()).containsExactly(summary("FTP", resolved("Workflow-0001"),
            masked("ServerName", true), masked("Login", false)));
        assertThat(found.maskedCount()).isEqualTo(3);
    }

    // --- US2-AS2, AS3: entry filters (FR-005a) --------------------------------------------------

    @Test
    void aProtocolFilterMatchesOnlyTheProtocolAttributeAndShowsOnlyMatchingEntries() {
        export(PartnerFixtures.list(120));
        export(PartnerFixtures.detail());

        Found ftp = find(list().protocol("ftp").limit(100).build());
        Found sftp = find(list().protocol("SFTP").build());
        Found as4 = find(list().protocol("as4").build());

        assertThat(ftp.total()).as("60 odd partners of the list, 2 of the detail diagram")
            .isEqualTo(62);
        assertThat(ftp.partners()).allSatisfy(partner -> assertThat(partner.entries())
            .isNotEmpty().allSatisfy(entry -> assertThat(entry.protocol()).isEqualTo("FTP")));
        assertThat(ftp.partners().subList(0, 2)).extracting(PartnerServiceListTest::id)
            .as("SystemDiagram-01 before SystemDiagram-02")
            .containsExactly("ACME-0001", "ACME-0002");
        assertThat(ftp.partners().get(0).entries()).hasSize(2);
        assertThat(ftp.partners().get(2)).extracting(PartnerServiceListTest::id)
            .isEqualTo("ACME-0001");
        assertThat(sftp.partners()).as("SFTP is the value of FTPProtocol, not a protocol")
            .isEmpty();
        assertThat(sftp.total()).isZero();
        assertThat(sftp.similarValues()).as("no search term").isEmpty();
        assertThat(as4.partners()).singleElement().satisfies(partner -> {
            assertThat(id(partner)).isEqualTo("ACME-0002");
            assertThat(partner.entries()).extracting(EntryView::protocol).containsExactly("AS4");
        });
        assertNoSecretIn(ftp);
    }

    @Test
    void aWorkflowFilterMatchesTheResolvedNameAndNeverAnUnresolvedEntry() {
        export(PartnerFixtures.detail());

        Found found = find(list().workflow("workflow-0003").build());
        Found many = find(list().workflow("Workflow-0001").limit(100).build());
        Found unknown = find(list().workflow("Workflow-0004").build());

        assertThat(found.partners()).extracting(PartnerServiceListTest::id)
            .containsExactly("ACME-0002", "ACME-0003");
        assertThat(found.partners()).extracting(partner -> partner.entries().stream()
                .map(EntryView::protocol).toList())
            .containsExactly(List.of("AS4"), List.of("WebService"));
        assertThat(found.partners().get(1).entries().get(0).workflow())
            .isEqualTo(resolved("Workflow-0003"));
        assertThat(many.partners()).extracting(PartnerServiceListTest::id)
            .containsExactly("ACME-0001", "ACME-0002");
        assertThat(many.partners().get(0).entries()).hasSize(1);
        assertThat(unknown.partners()).isEmpty();
        assertThat(unknown.total()).isZero();
    }

    @Test
    void filtersCombineByAndPerEntryWithTheSystemDiagramAndTheSearch() {
        export(PartnerFixtures.detail());
        export(PartnerFixtures.list(6));

        Found both = find(list().protocol("FTP").workflow("Workflow-0002").build());
        Found noEntryHasBoth = find(list().protocol("ftp").workflow("Workflow-0003")
            .systemDiagram("systemdiagram-01").build());
        Found inDiagram = find(list().protocol("webservice").systemDiagram("SystemDiagram-02")
            .build());
        Found searched = find(search("acme-0002").protocol("AS4").build());

        assertThat(both.partners()).extracting(PartnerServiceListTest::id)
            .containsExactly("ACME-0001", "ACME-0001");
        assertThat(both.partners()).extracting(partner -> partner.systemDiagram().name())
            .containsExactly("SystemDiagram-01", "SystemDiagram-02");
        assertThat(both.partners()).allSatisfy(partner -> assertThat(partner.entries())
            .extracting(EntryView::workflow).containsExactly(resolved("Workflow-0002")));
        assertThat(noEntryHasBoth.partners()).as("ACME-0002 has FTP and Workflow-0003, apart")
            .isEmpty();
        assertThat(inDiagram.partners()).extracting(partner -> partner.systemDiagram().name())
            .containsOnly("SystemDiagram-02");
        assertThat(inDiagram.partners()).extracting(PartnerServiceListTest::id)
            .containsExactly("ACME-0002", "ACME-0004", "ACME-0006");
        assertThat(searched.partners()).singleElement().satisfies(partner -> {
            assertThat(partner.systemDiagram()).isEqualTo(DETAIL);
            assertThat(partner.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.protocol()).isEqualTo("AS4");
                assertThat(entry.format()).as("a search shows the entry in full")
                    .contains("format-1");
                assertThat(entry.additionalParameters()).contains(List.of());
            });
        });
    }

    @Test
    void aSearchWhoseHitsAnEntryFilterRemovesGetsSimilarValues() {
        export(PartnerFixtures.detail());

        // ACME-0003 has WebService entries only
        Found found = find(search("acme-0003").protocol("ftp").build());

        assertThat(found.partners()).isEmpty();
        assertThat(found.total()).isZero();
        assertThat(found.similarValues()).as("the combined query matched no partner (FR-005b)")
            .hasValueSatisfying(values -> assertThat(values).contains("ACME-0003")
                .allMatch(value -> value.startsWith("ACME"))
                .doesNotContainAnyElementsOf(PartnerFixtures.SECRETS)
                .doesNotContain(PartnerFixtures.SECRET_FIELD_VALUE,
                    PartnerFixtures.KEY_MATERIAL));
        assertNoSecretIn(found);
    }

    // --- audit counts (research D-8) ------------------------------------------------------------

    @Test
    void theAuditCountsTheMaskedValuesTheReturnedPageShows() {
        export(PartnerFixtures.detail());

        Found listed = find(list());
        Found filtered = find(search("acme-0003").workflow("Workflow-0003").build());

        assertThat(listed.partners()).extracting(PartnerView::maskedCount)
            .containsExactly(3, 3, 6);
        assertThat(filtered.partners()).extracting(PartnerView::maskedCount).containsExactly(6);
        assertThat(audit).extracting(record -> record.details().get("partnersReturned"))
            .containsExactly(3, 1);
        assertThat(audit).extracting(record -> record.details().get("valuesMasked"))
            .as("the masked fields of the summary; Password, SSLKeyStore, WSSecurityPassword"
                + " of the one entry shown")
            .containsExactly(2, 3);
    }

    // --- support -------------------------------------------------------------------------------

    /**
     * Three system diagrams in a list order unlike the list order of the result, one with two
     * clouds; the partners of {@code Module-0901} out of order, with an Id in lower case, two
     * partners of the same Id and one without an Id.
     */
    private void exportOrderFixture() {
        export(new DiagramXml(GROUP, "SystemDiagram-02", OWNER, List.of(
            cloud("40", "Module-0902", named("ACME-0002", "ACME Beta")),
            cloud("33", CLOUD, named("ACME-0003", "ACME Gamma"), named("ACME-0001", "ACME Zeta"),
                named("acme-0002", "ACME Delta"), named("ACME-0001", "ACME Alpha"),
                new PartnerXml(List.of(Leaf.plain("Name", "ACME Nameless")), List.of())))));
        export(new DiagramXml(GROUP, "SystemDiagram-01", OWNER,
            List.of(cloud("33", CLOUD, named("ACME-0009", "ACME Nine")))));
        export(new DiagramXml("GRP-AB", "SystemDiagram-03", OWNER,
            List.of(cloud("33", CLOUD, named("ACME-0005", "ACME Five")))));
    }

    private static CloudXml cloud(String moduleId, String name, PartnerXml... partners) {
        return new CloudXml(moduleId, name, ID_AND_NAME, WORKFLOW, List.of(partners));
    }

    private static PartnerXml named(String id, String name) {
        return new PartnerXml(List.of(Leaf.plain("Id", id), Leaf.plain("Name", name)),
            List.of());
    }

    /** Reads {@code diagram} with the reader as the adapter does and offers it to the owner. */
    private void export(DiagramXml diagram) {
        DiagramRef ref = new DiagramRef(diagram.name(), diagram.group());
        exports.put(ref, PartnerManagementReader.read(ref,
            XmlTree.parse(PartnerFixtures.workflowXml(diagram)).root()));
    }

    private static Query list() {
        return new Query(Optional.empty());
    }

    private static Query search(String term) {
        return new Query(Optional.of(term));
    }

    /** A query on {@code dev/node1}; default page unless changed. */
    private static final class Query {

        private final Optional<String> search;
        private Optional<String> systemDiagram = Optional.empty();
        private Optional<String> protocol = Optional.empty();
        private Optional<String> workflow = Optional.empty();
        private int offset;
        private int limit = PartnerQuery.DEFAULT_LIMIT;

        Query(Optional<String> search) {
            this.search = search;
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
            return new PartnerQuery("dev/node1", Optional.empty(), Optional.empty(), search,
                systemDiagram, protocol, workflow, offset, limit, false, Optional.empty());
        }
    }

    private Found find(Query query) {
        return find(query.build());
    }

    private Found find(PartnerQuery query) {
        return new PartnerService(new Gateways(), new TargetResolver(List.of(NODE1)),
            new FanOut(), ResultLimiter.withDefaults(),
            new InventoryCache(new MutableClock(T0), node -> Duration.ofMinutes(10)),
            node -> Optional.of(OWNER), node -> Duration.ofSeconds(5),
            node -> Duration.ofSeconds(120), ResultJson::size, Terminology.DEFAULT,
            FakeVersionDetection.pinned81(), SecretTiming.NONE,
            new PartnerAudit.Dependencies(audit::add, Clock.systemUTC(), "default",
                UUID::randomUUID))
            .find(query);
    }

    /**
     * The JSON text of the result holds no synthetic secret, not even its first 12 characters (a
     * fingerprint would make the mapper throw).
     */
    private static void assertNoSecretIn(Found found) {
        String json = ResultJson.mapper().writeValueAsString(found);
        for (String secret : PartnerFixtures.SECRETS) {
            assertThat(json).doesNotContain(secret).doesNotContain(secret.substring(0, 12));
        }
    }

    private static List<String> ids(int first, int last) {
        return IntStream.rangeClosed(first, last).mapToObj("ACME-%04d"::formatted).toList();
    }

    private static String id(PartnerView partner) {
        return field(partner, "Id");
    }

    private static String name(PartnerView partner) {
        return field(partner, "Name");
    }

    private static String field(PartnerView partner, String name) {
        return partner.fields().stream()
            .filter(field -> field.name().equals(name))
            .map(field -> field.value().orElseThrow())
            .findFirst()
            .orElse(null);
    }

    private static SettingView shown(String name, String value) {
        return new SettingView(name, Optional.of(value), Optional.empty(), Optional.empty());
    }

    private static SettingView masked(String name, boolean set) {
        return new SettingView(name, Optional.empty(), Optional.of(true), Optional.of(set));
    }

    private static WorkflowView resolved(String name) {
        return new WorkflowView(Optional.of(name), Optional.empty());
    }

    private static EntryView summary(String protocol, WorkflowView workflow,
        SettingView... settings) {
        return new EntryView(protocol, workflow, Optional.empty(), List.of(settings),
            Optional.empty());
    }

    private PartnerManagementPort.Result portRead(List<DiagramRef> diagrams) {
        return new PartnerManagementPort.Result(
            diagrams.stream().map(diagram -> exports.get(diagram).partners()).toList(), List.of(),
            diagrams.stream().collect(Collectors.toMap(diagram -> diagram,
                diagram -> exports.get(diagram).warnings())));
    }

    /** Only the inventory list and the partner port; every other port is an assertion error. */
    private final class Gateways implements GatewayFactory {

        @Override
        public InventoryPort inventory(NodeId server) {
            return new InventoryPort() {
                @Override
                public List<InventoryItem> listDiagrams(String owner) {
                    return exports.keySet().stream()
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
            return (owner, diagrams) -> portRead(diagrams);
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
