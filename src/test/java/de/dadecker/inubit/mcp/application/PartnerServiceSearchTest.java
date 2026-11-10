package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.CloudXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.DiagramXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.FieldXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.Leaf;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.PartnerXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.TwfXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.application.PartnerService.Answer;
import de.dadecker.inubit.mcp.application.PartnerService.CloudCount;
import de.dadecker.inubit.mcp.application.PartnerService.CloudWorkflows;
import de.dadecker.inubit.mcp.application.PartnerService.Compared;
import de.dadecker.inubit.mcp.application.PartnerService.DiagramRead;
import de.dadecker.inubit.mcp.application.PartnerService.EntryView;
import de.dadecker.inubit.mcp.application.PartnerService.Found;
import de.dadecker.inubit.mcp.application.PartnerService.PartnerView;
import de.dadecker.inubit.mcp.application.PartnerService.ServerView;
import de.dadecker.inubit.mcp.application.PartnerService.SettingView;
import de.dadecker.inubit.mcp.application.PartnerService.WorkflowView;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
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
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T019: search and details of {@link PartnerService#answer} on one server (US1-AS1…5, Op-1, FR-004,
 * FR-005, FR-007, FR-011) over the masked model the reader makes of the synthetic exports of
 * {@link PartnerFixtures}; every result is checked as the JSON text of {@link ResultJson} against
 * every synthetic secret.
 */
@Timeout(30)
class PartnerServiceSearchTest {

    /** The audit is checked by {@code PartnerServiceAuditTest}. */
    private static final PartnerAudit.Dependencies NO_AUDIT = new PartnerAudit.Dependencies(
        record -> { }, Clock.systemUTC(), "default", UUID::randomUUID);

    private static final NodeId NODE1 = NodeId.parse("dev/node1");
    private static final NodeId NODE2 = NodeId.parse("dev/node2");
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final String OWNER = PartnerFixtures.OWNER;
    private static final String GROUP = PartnerFixtures.GROUP;
    private static final String CLOUD = "Module-0901";
    private static final DiagramRef ONE = new DiagramRef("SystemDiagram-01", GROUP);
    private static final DiagramRef WITHOUT = new DiagramRef("SystemDiagram-02", GROUP);
    private static final DiagramRef TWO_CLOUDS = new DiagramRef("SystemDiagram-03", GROUP);
    private static final DiagramRef EMPTY_CLOUD = new DiagramRef("SystemDiagram-04", GROUP);
    private static final List<String> WORKFLOWS =
        List.of("Workflow-0001", "Workflow-0002", "Workflow-0003");

    /** The reader's result per system diagram of the owner, in list order. */
    private final Map<DiagramRef, PartnerManagementReader.Result> exports = new LinkedHashMap<>();
    private final Map<NodeId, Optional<String>> owners =
        new LinkedHashMap<>(Map.of(NODE1, Optional.of(OWNER), NODE2, Optional.empty()));

    // --- US1 ---------------------------------------------------------------------------------

    @Test
    void searchByIdShowsThePartnerInFullWithBothEntriesUnmerged() {
        export(PartnerFixtures.detail());

        Found found = find("acme-0001");

        ServerView server = found.servers().get(0);
        assertThat(found.servers()).hasSize(1);
        assertThat(server.node()).isEqualTo(NODE1);
        assertThat(server.group()).isEqualTo(NODE1.group());
        assertThat(server.owner()).contains(OWNER);
        assertThat(server.collectedAt()).contains(T0);
        assertThat(server.fromCache()).contains(false);
        assertThat(server.systemDiagramsRead()).containsExactly(new DiagramRead(ONE.name(), GROUP,
            List.of(new CloudCount(CLOUD, Optional.empty(), 3))));
        assertThat(server.withoutPartnerManagement()).isEmpty();
        assertThat(server.unreadableDiagrams()).isEmpty();
        assertThat(server.error()).isEmpty();
        assertThat(found.partners()).containsExactly(new PartnerView(ONE, CLOUD, Optional.empty(),
            List.of(shown("Id", "ACME-0001"), shown("Name", "ACME Corp"),
                masked(PartnerFixtures.SECRET_FIELD, true)),
            3,
            List.of(ftp("Workflow-0001", "ftp1.example.test", "login1", "FTP"),
                ftp("Workflow-0002", "ftp1.example.test", "login1", "FTP"))));
        assertThat(found.referencableWorkflows())
            .containsExactly(new CloudWorkflows(ONE, CLOUD, Optional.empty(), WORKFLOWS));
        assertNoSecretIn(found);
    }

    @Test
    void searchByAPartOfTheNameIgnoresCase() {
        export(PartnerFixtures.detail());
        List<PartnerView> byId = find("acme-0001").partners();

        assertThat(find("corp").partners()).isEqualTo(byId);
        assertThat(find("aCmE cOrP").partners()).isEqualTo(byId);
        assertThat(find("logistics").partners()).extracting(PartnerServiceSearchTest::id)
            .containsExactly("ACME-0002");
        assertThat(find("ACME").partners()).extracting(PartnerServiceSearchTest::id)
            .containsExactly("ACME-0001", "ACME-0002", "ACME-0003");
    }

    @Test
    void searchMatchesCaseInsensitivelyAlsoWhereLowerCasingChangesTheLength() {
        // U+0130 lower-cases to "i" plus a combining dot in Locale.ROOT, i.e. to two chars
        export(new DiagramXml(GROUP, ONE.name(), OWNER, List.of(new CloudXml("44", CLOUD,
            List.of(new FieldXml("Id", "String"), new FieldXml("Name", "String")),
            List.of(new TwfXml("1", "35", "Workflow-0009")),
            List.of(named("ACME-0010", "ACME \u0130nternational"),
                named("ACME-0011", "ACME International"))))));

        assertThat(find("acme international").partners()).extracting(PartnerServiceSearchTest::id)
            .containsExactly("ACME-0010", "ACME-0011");
        assertThat(find("\u0130NTERNATIONAL").partners())
            .extracting(PartnerServiceSearchTest::id).containsExactly("ACME-0010", "ACME-0011");
        assertThat(find("a.me").partners()).as("the term is literal, not a pattern").isEmpty();
    }

    @Test
    void clearTextAndUntypedSecretsAreMaskedWithNothingDerived() {
        export(PartnerFixtures.detail());

        PartnerView sftp = find("acme-0002").partners().get(0);
        PartnerView webService = find("acme-0003").partners().get(0);

        assertThat(sftp.fields()).containsExactly(shown("Id", "ACME-0002"),
            shown("Name", "ACME Logistics"), masked(PartnerFixtures.KEY_MATERIAL_FIELD, true));
        assertThat(sftp.entries().get(0).protocolSettings())
            .contains(masked("Password", true)); // legacy clear text, no `encrypted`
        assertThat(sftp.entries().get(1).protocol()).isEqualTo("AS4");
        assertThat(sftp.entries().get(1).protocolSettings()).containsExactly(
            shown("ServerName", "https://as4.example.test/msh"),
            shown(PartnerFixtures.UNKNOWN_SETTING, "profile-1"), masked("Password", true));
        assertThat(sftp.maskedCount()).isEqualTo(3);
        assertThat(webService.entries().get(0)).isEqualTo(new EntryView("WebService",
            resolved("Workflow-0003"), Optional.of("format-1"),
            List.of(shown("ServerName", "http://host3.example.test/path1"),
                shown("Auth", "true"), shown("Login", "login3"), masked("Password", true),
                masked("SSLKeyStore", false)),
            Optional.of(List.of(shown("WSSecurityUsername", "ws-login3"),
                masked("WSSecurityPassword", true))))); // no type, no `encrypted`
        assertNoSecretIn(find("acme"));
    }

    @Test
    void anUnresolvedWorkflowIsShownAsSuchWithTheReadersWarning() {
        List<String> readerWarnings = export(PartnerFixtures.detail()).warnings();

        Found found = find("acme-0003");

        assertThat(found.partners().get(0).entries()).extracting(EntryView::workflow)
            .containsExactly(resolved("Workflow-0003"),
                new WorkflowView(Optional.empty(), Optional.of(true)));
        assertThat(readerWarnings).isNotEmpty();
        assertThat(found.servers().get(0).warnings()).isEqualTo(readerWarnings);
    }

    @Test
    void matchesInTwoSystemDiagramsAndTwoCloudsAreEachNamed() {
        export(PartnerFixtures.detail());
        export(PartnerFixtures.twoClouds());

        Found found = find("acme corp");

        assertThat(found.partners())
            .extracting(PartnerView::systemDiagram, PartnerView::cloud,
                PartnerServiceSearchTest::id)
            .containsExactly(tuple(ONE, CLOUD, "ACME-0001"),
                tuple(TWO_CLOUDS, CLOUD, "ACME-0001"),
                tuple(TWO_CLOUDS, "Module-0902", "ACME-0001"));
        assertThat(found.partners()).extracting(PartnerView::moduleId)
            .containsOnly(Optional.empty());
        assertThat(found.referencableWorkflows()).containsExactly(
            new CloudWorkflows(ONE, CLOUD, Optional.empty(), WORKFLOWS),
            new CloudWorkflows(TWO_CLOUDS, CLOUD, Optional.empty(), WORKFLOWS),
            new CloudWorkflows(TWO_CLOUDS, "Module-0902", Optional.empty(), WORKFLOWS));
    }

    @Test
    void cloudsOfTheSameNameInOneDiagramAreToldApartByModuleId() {
        List<FieldXml> fields = List.of(new FieldXml("Id", "String"),
            new FieldXml("Name", "String"));
        List<TwfXml> workflows = List.of(new TwfXml("1", "34", "Workflow-0001"));
        List<CloudXml> clouds = List.of(
            new CloudXml("33", CLOUD, fields, workflows, List.of(acme("ACME-0001"))),
            new CloudXml("213", CLOUD, fields, workflows, List.of(acme("ACME-0002"))),
            new CloudXml("44", "Module-0902", fields, workflows, List.of()));
        export(new DiagramXml(GROUP, ONE.name(), OWNER, clouds));

        Found found = find("acme");

        assertThat(found.partners())
            .extracting(PartnerView::cloud, PartnerView::moduleId, PartnerServiceSearchTest::id)
            .containsExactly(tuple(CLOUD, Optional.of("33"), "ACME-0001"),
                tuple(CLOUD, Optional.of("213"), "ACME-0002"));
        assertThat(found.servers().get(0).systemDiagramsRead().get(0).clouds()).containsExactly(
            new CloudCount(CLOUD, Optional.of("33"), 1),
            new CloudCount(CLOUD, Optional.of("213"), 1),
            new CloudCount("Module-0902", Optional.empty(), 0));
        assertThat(found.referencableWorkflows()).containsExactly(
            new CloudWorkflows(ONE, CLOUD, Optional.of("33"), List.of("Workflow-0001")),
            new CloudWorkflows(ONE, CLOUD, Optional.of("213"), List.of("Workflow-0001")));
    }

    // --- Op-1, FR-007 -------------------------------------------------------------------------

    @Test
    void diagramsWithoutPartnerManagementAndEmptyCloudsAreNamed() {
        export(PartnerFixtures.detail());
        export(new DiagramXml(GROUP, WITHOUT.name(), OWNER, List.of()));
        export(new DiagramXml(GROUP, EMPTY_CLOUD.name(), OWNER, List.of(new CloudXml("44",
            "Module-0903", List.of(new FieldXml("Id", "String")),
            List.of(new TwfXml("1", "35", "Workflow-0009")), List.of()))));

        Found found = find("acme-0002");

        ServerView server = found.servers().get(0);
        assertThat(server.systemDiagramsRead()).containsExactly(
            new DiagramRead(ONE.name(), GROUP, List.of(new CloudCount(CLOUD, Optional.empty(), 3))),
            new DiagramRead(WITHOUT.name(), GROUP, List.of()),
            new DiagramRead(EMPTY_CLOUD.name(), GROUP,
                List.of(new CloudCount("Module-0903", Optional.empty(), 0))));
        assertThat(server.withoutPartnerManagement()).containsExactly(WITHOUT);
        assertThat(found.referencableWorkflows())
            .containsExactly(new CloudWorkflows(ONE, CLOUD, Optional.empty(), WORKFLOWS));
        assertThat(found.partners()).extracting(PartnerServiceSearchTest::id)
            .containsExactly("ACME-0002");
    }

    @Test
    void aSearchWithoutHitsStillNamesTheDiagramsRead() {
        export(PartnerFixtures.detail());

        Found found = find("no-such-partner");

        assertThat(found.partners()).isEmpty();
        assertThat(found.referencableWorkflows()).isEmpty();
        assertThat(found.servers().get(0).systemDiagramsRead())
            .extracting(DiagramRead::name).containsExactly(ONE.name());
    }

    // --- T020: similar values (FR-005b) and an unknown system diagram (FR-019) ---------------

    @Test
    void aSearchWithoutHitsListsSimilarIdsAndNamesClosestFirst() {
        export(PartnerFixtures.detail());

        Found found = find("acme-0004");

        assertThat(found.partners()).isEmpty();
        assertThat(found.servers().get(0).systemDiagramsRead())
            .extracting(DiagramRead::name).containsExactly(ONE.name());
        assertThat(found.similarValues())
            .contains(List.of("ACME-0001", "ACME-0002", "ACME-0003"));
        assertThat(find("acme logistix").similarValues()).contains(List.of("ACME Logistics"));
        assertThat(find("zzzz").similarValues()).contains(List.of());
    }

    @Test
    void atMostFiveSimilarValuesAreListed() {
        export(PartnerFixtures.list(12));

        assertThat(find("acme-0000").similarValues()).contains(
            List.of("ACME-0001", "ACME-0002", "ACME-0003", "ACME-0004", "ACME-0005"));
    }

    @Test
    void similarValuesOnlyWhenASearchFoundNothing() {
        export(PartnerFixtures.detail());

        assertThat(find("acme-0001").similarValues()).isEmpty();
        Found listed = (Found) service().answer(query("dev/node1", Optional.empty(),
            Optional.empty()));

        assertThat(listed.similarValues()).isEmpty();
    }

    @Test
    void noSimilarValuesWhenNoServerAnswered() {
        export(PartnerFixtures.detail());

        Found found = (Found) service().answer(query("dev/node2", Optional.of("acme-0004")));

        assertThat(found.servers()).extracting(server -> server.error().map(ToolError::code))
            .containsExactly(Optional.of(ErrorCode.NOT_CONFIGURED));
        assertThat(found.similarValues()).isEmpty();
    }

    @Test
    void aMaskedNameIsNeverASimilarValue() {
        // a cloud whose Name field is not plain: every Name in it is masked
        export(new DiagramXml(GROUP, ONE.name(), OWNER, List.of(new CloudXml("44", CLOUD,
            List.of(new FieldXml("Id", "String"), new FieldXml("Name", "Password")),
            List.of(new TwfXml("1", "35", "Workflow-0009")),
            List.of(new PartnerXml(List.of(Leaf.plain("Id", "ACME-0007"),
                Leaf.plain("Name", "Hidden Partner Name")), List.of()))))));

        Found nearMaskedName = find("hidden partner nam");

        assertThat(nearMaskedName.partners()).isEmpty();
        assertThat(nearMaskedName.similarValues()).contains(List.of());
        assertThat(find("acme-0008").similarValues()).contains(List.of("ACME-0007"));
        assertThat(ResultJson.mapper().writeValueAsString(nearMaskedName))
            .doesNotContain("Hidden");
    }

    @Test
    void aSecretIsNeverASimilarValue() {
        export(PartnerFixtures.detail());

        for (String secret : List.of(PartnerFixtures.SECRET_FIELD_VALUE,
            PartnerFixtures.LEGACY_PASSWORD, PartnerFixtures.WS_SECURITY_PASSWORD,
            PartnerFixtures.AES_PASSWORD)) {
            Found nearSecret = find(secret.substring(0, secret.length() - 1) + "9");

            assertThat(nearSecret.partners()).as(secret).isEmpty();
            assertThat(nearSecret.similarValues()).as(secret).contains(List.of());
            assertNoSecretIn(nearSecret);
        }
    }

    @Test
    void anUnknownSystemDiagramIsNotFoundWithAtMostFiveSimilarNames() {
        for (int n = 1; n <= 7; n++) {
            export(new DiagramXml(GROUP, "SystemDiagram-0" + n, OWNER, List.of()));
        }

        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service().answer(query("dev/node1", Optional.empty(),
                Optional.of("SystemDiagram-00"))));

        assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(e.error().message()).contains("'SystemDiagram-00'", NODE1.toString(), OWNER);
        assertThat(e.error().nextStep()).contains("'SystemDiagram-01'", "'SystemDiagram-05'")
            .doesNotContain("SystemDiagram-06").doesNotContain("SystemDiagram-07");
    }

    @Test
    void aSystemDiagramFilterIgnoresCaseAndIsNotFoundOnlyIfNoAnsweringServerHasIt() {
        export(PartnerFixtures.detail());

        Found found = (Found) service().answer(query("dev/node1", Optional.of("acme-0001"),
            Optional.of("systemdiagram-01")));
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service().answer(query("dev", Optional.empty(), Optional.of("SystemDiagram-1"))));
        Found noneAnswered = (Found) service().answer(query("dev/node2", Optional.empty(),
            Optional.of("SystemDiagram-1")));

        assertThat(found.partners()).extracting(PartnerView::systemDiagram).containsExactly(ONE);
        assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(e.error().nextStep()).contains("'SystemDiagram-01'");
        assertThat(noneAnswered.servers()).extracting(server -> server.error().map(ToolError::code))
            .containsExactly(Optional.of(ErrorCode.NOT_CONFIGURED));
    }

    // --- FR-011 -------------------------------------------------------------------------------

    @Test
    void aSecretOrAPartOfItNeverFindsAPartner() {
        export(PartnerFixtures.detail());
        List<String> probes = List.of(PartnerFixtures.AES_PASSWORD,
            PartnerFixtures.AES_PASSWORD.substring(4, 16), PartnerFixtures.LEGACY_PASSWORD,
            "legacy-password", PartnerFixtures.WS_SECURITY_PASSWORD, "ws-security",
            PartnerFixtures.SECRET_FIELD_VALUE, "portal-pin", "BEGIN PRIVATE KEY", "MIIBfixture");

        for (String probe : probes) {
            assertThat(find(probe).partners()).as(probe).isEmpty();
        }
    }

    @Test
    void entrySettingsAreNotSearched() {
        export(PartnerFixtures.detail());

        assertThat(find("ftp1.example.test").partners()).isEmpty();
        assertThat(find("login1").partners()).isEmpty();
        assertThat(find("format-1").partners()).isEmpty();
    }

    // --- secrets in the result ----------------------------------------------------------------

    @Test
    void aSetSecretWithoutFingerprintIsShownAsSet() {
        // a setting with child elements: Masked(set) without a fingerprint (T011 round 2)
        Setting structured = new Setting("ClientCertificate",
            new SettingValue.Masked(true, Optional.empty()));
        Partner partner = new Partner(
            List.of(new Setting("Id", new SettingValue.Shown("ACME-0009"))),
            List.of(new ProtocolEntry("AS4", new WorkflowRef(Optional.of("Workflow-0001")),
                Optional.empty(), List.of(structured), List.of(), 0)));
        exports.put(ONE, new PartnerManagementReader.Result(new SystemDiagramPartners(ONE,
            List.of(new CommunicationCloud(CLOUD, "33", List.of(new PartnerField("Id", "String")),
                List.of("Workflow-0001"), List.of(partner)))), List.of()));

        Found found = find("acme-0009");

        assertThat(found.partners().get(0).entries().get(0).protocolSettings())
            .containsExactly(masked("ClientCertificate", true));
        assertThat(found.partners().get(0).maskedCount()).isEqualTo(1);
    }

    @Test
    void aFailedServerIsAnsweredWithItsErrorAndNothingLeaks() {
        export(PartnerFixtures.detail());

        Compared compared = (Compared) service().answer(query("dev", Optional.of("acme")));

        assertThat(compared.servers()).extracting(ServerView::node)
            .containsExactly(NODE1, NODE2);
        ServerView failed = compared.servers().get(1);
        assertThat(failed.error()).map(ToolError::code).contains(ErrorCode.NOT_CONFIGURED);
        assertThat(failed.owner()).isEmpty();
        assertThat(failed.collectedAt()).isEmpty();
        assertThat(failed.systemDiagramsRead()).isEmpty();
        assertThat(compared.partners()).hasSize(3);
        assertThat(compared.incomplete()).isTrue();
        assertNoSecretIn(compared);
    }

    // --- support -------------------------------------------------------------------------------

    /** Reads {@code diagram} with the reader as the adapter does and offers it to the owner. */
    private PartnerManagementReader.Result export(DiagramXml diagram) {
        DiagramRef ref = new DiagramRef(diagram.name(), diagram.group());
        PartnerManagementReader.Result result = PartnerManagementReader.read(ref,
            XmlTree.parse(PartnerFixtures.workflowXml(diagram)).root());
        exports.put(ref, result);
        return result;
    }

    private static PartnerXml named(String id, String name) {
        return new PartnerXml(List.of(Leaf.plain("Id", id), Leaf.plain("Name", name)),
            List.of());
    }

    private static PartnerXml acme(String id) {
        return new PartnerXml(List.of(Leaf.plain("Id", id), Leaf.plain("Name", "ACME Corp")),
            List.of());
    }

    private Found find(String search) {
        return (Found) service().answer(query("dev/node1", Optional.of(search)));
    }

    private static PartnerQuery query(String target, Optional<String> search) {
        return query(target, search, Optional.empty());
    }

    private static PartnerQuery query(String target, Optional<String> search,
        Optional<String> systemDiagram) {
        return new PartnerQuery(target, Optional.empty(), Optional.empty(), search,
            systemDiagram, Optional.empty(), Optional.empty(), 0, PartnerQuery.DEFAULT_LIMIT,
            false, Optional.empty());
    }

    /**
     * The JSON text of the result holds no synthetic secret, not even its first 12 characters (a
     * fingerprint would make the mapper throw).
     */
    private static void assertNoSecretIn(Answer answer) {
        String json = ResultJson.mapper().writeValueAsString(answer);
        for (String secret : PartnerFixtures.SECRETS) {
            assertThat(json).doesNotContain(secret).doesNotContain(secret.substring(0, 12));
        }
    }

    private static String id(PartnerView partner) {
        return partner.fields().get(0).value().orElseThrow();
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

    /** An FTP entry of {@link PartnerFixtures} with its encrypted password. */
    private static EntryView ftp(String workflow, String server, String login,
        String ftpProtocol) {
        return new EntryView("FTP", resolved(workflow), Optional.of("format-1"),
            List.of(shown("ServerName", server), shown("Login", login), masked("Password", true),
                shown("FileName", "FileName-1")),
            Optional.of(List.of(shown("FTPAuth", "false"),
                shown("FTPProtocol", ftpProtocol))));
    }

    private PartnerService service() {
        return new PartnerService(new Gateways(), new TargetResolver(List.of(NODE1, NODE2)),
            new FanOut(), ResultLimiter.withDefaults(),
            new InventoryCache(new MutableClock(T0), node -> Duration.ofMinutes(10)),
            owners::get, node -> Duration.ofSeconds(5), node -> Duration.ofSeconds(120),
            ResultJson::size, Terminology.DEFAULT, FakeVersionDetection.pinned81(),
            SecretTiming.NONE, NO_AUDIT);
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
