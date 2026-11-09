package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.CloudXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.DiagramXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.EntryXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.FieldXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.Leaf;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.PartnerXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.TwfXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.partner.CommunicationCloud;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.Partner;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerField;
import de.dadecker.inubit.mcp.domain.model.partner.ProtocolEntry;
import de.dadecker.inubit.mcp.domain.model.partner.Setting;
import de.dadecker.inubit.mcp.domain.model.partner.SettingValue;
import de.dadecker.inubit.mcp.domain.model.partner.WorkflowRef;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T011 (feature 018 FR-007 … FR-011, FR-013, research D-3): {@link PartnerManagementReader}
 * against {@link PartnerFixtures} and the recorded system diagram exports.
 */
class PartnerManagementReaderTest {

    static final String REST_FIXTURE = "/fixtures/v8_1/rest/model_export_systemdiagram.zip";
    static final String CLI_FIXTURE = "/fixtures/v8_1/cli/export_systemdiagram.zip";

    private static final DiagramRef DETAIL =
        new DiagramRef("SystemDiagram-01", PartnerFixtures.GROUP);

    @Test
    void theDetailSetIsReadInExportOrderWithItsSecretsMasked() {
        PartnerManagementReader.Result result = read(DETAIL, PartnerFixtures.detail());

        assertThat(result.partners().diagram()).isEqualTo(DETAIL);
        assertThat(result.partners().clouds()).hasSize(1);
        CommunicationCloud cloud = result.partners().clouds().get(0);
        assertThat(cloud.moduleName()).isEqualTo("Module-0901");
        assertThat(cloud.moduleId()).isEqualTo("33");
        assertThat(cloud.partnerFields()).containsExactly(new PartnerField("Id", "String"),
            new PartnerField("Name", "String"),
            new PartnerField(PartnerFixtures.KEY_MATERIAL_FIELD, "String"),
            new PartnerField(PartnerFixtures.SECRET_FIELD, "Password"));
        assertThat(cloud.referencableWorkflows())
            .containsExactly("Workflow-0001", "Workflow-0002", "Workflow-0003");
        assertThat(cloud.partners()).extracting(p -> shown(p.fields(), "Id"))
            .containsExactly("ACME-0001", "ACME-0002", "ACME-0003");

        Partner ftp = cloud.partners().get(0);
        assertThat(ftp.fields()).extracting(Setting::name)
            .containsExactly("Id", "Name", PartnerFixtures.SECRET_FIELD);
        assertThat(shown(ftp.fields(), "Name")).isEqualTo("ACME Corp");
        assertThat(value(ftp.fields(), PartnerFixtures.SECRET_FIELD)).as("not a plain fieldType")
            .isInstanceOfSatisfying(SettingValue.Masked.class, m -> assertThat(m.set()).isTrue());
        ProtocolEntry first = ftp.entries().get(0);
        assertThat(first.protocol()).isEqualTo("FTP");
        assertThat(first.workflow()).isEqualTo(new WorkflowRef(Optional.of("Workflow-0001")));
        assertThat(first.format()).hasValue("format-1");
        assertThat(first.protocolSettings()).extracting(Setting::name)
            .containsExactly("ServerName", "Login", "Password", "FileName");
        assertThat(shown(first.protocolSettings(), "ServerName")).isEqualTo("ftp1.example.test");
        assertThat(shown(first.protocolSettings(), "Login")).isEqualTo("login1");
        assertThat(value(first.protocolSettings(), "Password"))
            .isInstanceOf(SettingValue.Masked.class);
        assertThat(first.additionalParameters()).extracting(Setting::name)
            .containsExactly("FTPAuth", "FTPProtocol");
        assertThat(shown(first.additionalParameters(), "FTPAuth")).isEqualTo("false");
        assertThat(ftp.entries().get(1).workflow().name()).hasValue("Workflow-0002");

        Partner sftp = cloud.partners().get(1);
        assertThat(value(sftp.fields(), PartnerFixtures.KEY_MATERIAL_FIELD))
            .as("key material in a plain field").isInstanceOf(SettingValue.Masked.class);
        assertThat(value(sftp.entries().get(0).protocolSettings(), "Password"))
            .as("clear-text legacy password").isInstanceOf(SettingValue.Masked.class);
        assertThat(shown(sftp.entries().get(0).additionalParameters(), "FTPProtocol"))
            .isEqualTo("SFTP");

        Partner webService = cloud.partners().get(2);
        List<Setting> wsParameters = webService.entries().get(0).additionalParameters();
        assertThat(shown(wsParameters, "WSSecurityUsername")).isEqualTo("ws-login3");
        assertThat(value(wsParameters, "WSSecurityPassword")).as("without type")
            .isInstanceOf(SettingValue.Masked.class);
        assertThat(value(webService.entries().get(0).protocolSettings(), "SSLKeyStore"))
            .as("masked by name, empty").isEqualTo(new SettingValue.Masked(false,
                Optional.empty()));
    }

    @Test
    void maskedCountCountsEveryMaskedFieldAndSetting() {
        CommunicationCloud cloud = read(DETAIL, PartnerFixtures.detail()).partners().clouds().get(0);

        // ACME-0001: secret field, two passwords; ACME-0002: key material field, legacy
        // password, AS4 password; ACME-0003: per entry Password, SSLKeyStore, WSSecurityPassword
        assertThat(cloud.partners()).extracting(Partner::maskedCount).containsExactly(3, 3, 6);
    }

    @Test
    void anUnknownProtocolAndSettingAreKeptWithTheSameMaskingRules() {
        Partner sftp = read(DETAIL, PartnerFixtures.detail()).partners().clouds().get(0)
            .partners().get(1);

        ProtocolEntry as4 = sftp.entries().get(1);
        assertThat(as4.protocol()).isEqualTo("AS4");
        assertThat(as4.workflow().name()).hasValue("Workflow-0003");
        assertThat(as4.protocolSettings()).extracting(Setting::name)
            .containsExactly("ServerName", PartnerFixtures.UNKNOWN_SETTING, "Password");
        assertThat(shown(as4.protocolSettings(), PartnerFixtures.UNKNOWN_SETTING))
            .isEqualTo("profile-1");
        assertThat(value(as4.protocolSettings(), "Password"))
            .isInstanceOfSatisfying(SettingValue.Masked.class, m -> {
                assertThat(m.set()).isTrue();
                assertThat(m.fingerprint()).isPresent();
            });
    }

    @Test
    void anUnresolvedWorkflowIsKeptEmptyWithAWarningNamingPartnerAndCloud() {
        PartnerManagementReader.Result result = read(DETAIL, PartnerFixtures.detail());
        Partner webService = result.partners().clouds().get(0).partners().get(2);

        assertThat(webService.entries()).extracting(e -> e.workflow().name())
            .containsExactly(Optional.of("Workflow-0003"), Optional.empty());
        assertThat(webService.entries().get(1).protocolSettings()).extracting(Setting::name)
            .contains("ServerName", "Password");
        assertThat(result.warnings()).singleElement().satisfies(warning -> assertThat(warning)
            .contains("Id ACME-0003", "Module-0901", "GRP-PM/SystemDiagram-01", "WebService",
                "unresolved"));
    }

    @Test
    void aPartnerWithoutAShownIdIsNamedByItsPositionInTheWarning() {
        DiagramXml diagram = cloudDiagram(List.of(new FieldXml("Name", "String")), List.of(
            new PartnerXml(List.of(Leaf.plain("Name", "ACME Corp")), List.of(
                entry("FTP", PartnerFixtures.UNRESOLVED_TWF_ID)))));

        assertThat(read(DETAIL, diagram).warnings()).singleElement()
            .satisfies(warning -> assertThat(warning).startsWith("The partner at position 1 ")
                .contains("Module-0901"));
    }

    @Test
    void aReferencableWorkflowWithoutANameLeavesItsEntryUnresolvedWithAWarning() {
        DiagramXml diagram = new DiagramXml(PartnerFixtures.GROUP, "SystemDiagram-01",
            PartnerFixtures.OWNER, List.of(new CloudXml("33", "Module-0901",
                List.of(new FieldXml("Id", "String")),
                List.of(new TwfXml("1", "34", "Workflow-0001"), new TwfXml("7", "40", " ")),
                List.of(new PartnerXml(List.of(Leaf.plain("Id", "ACME-0001")),
                    List.of(entry("FTP", "1"), entry("FTP", "7")))))));

        PartnerManagementReader.Result result = read(DETAIL, diagram);

        assertThat(result.partners().clouds().get(0).partners().get(0).entries())
            .extracting(e -> e.workflow().name())
            .containsExactly(Optional.of("Workflow-0001"), Optional.empty());
        assertThat(result.warnings()).singleElement().satisfies(warning -> assertThat(warning)
            .contains("Id ACME-0001", "Module-0901", "FTP", "unresolved"));
    }

    @Test
    void aSettingWithChildElementsIsMaskedWithAWarningNamingItButNoValue() {
        String nested = "<Property name=\"Url\">https://host.example.test</Property>"
            + "<Property name=\"Account\">nested-secret-0001</Property>";
        DiagramXml diagram = cloudDiagram(List.of(new FieldXml("Id", "String")), List.of(
            new PartnerXml(List.of(Leaf.plain("Id", "ACME-0001")), List.of(new EntryXml("FTP",
                "1", List.of(Leaf.plain("Endpoints", "NESTED-WITH-TEXT")),
                List.of(Leaf.plain("Headers", "NESTED-BLANK")))))));
        String xml = new String(PartnerFixtures.workflowXml(diagram), StandardCharsets.UTF_8)
            .replace("NESTED-WITH-TEXT", nested)
            .replace("NESTED-BLANK", "<Property name=\"Header\"> </Property>");

        PartnerManagementReader.Result result = PartnerManagementReader.read(DETAIL,
            root(xml.getBytes(StandardCharsets.UTF_8)));

        ProtocolEntry ftp = result.partners().clouds().get(0).partners().get(0).entries().get(0);
        assertThat(ftp.protocolSettings()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("Endpoints");
            assertThat(s.value()).isEqualTo(new SettingValue.Masked(true, Optional.empty()));
        });
        assertThat(ftp.additionalParameters()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("Headers");
            assertThat(s.value()).isEqualTo(new SettingValue.Masked(false, Optional.empty()));
        });
        assertThat(result.warnings()).hasSize(2).allSatisfy(warning -> assertThat(warning)
            .contains("Id ACME-0001", "Module-0901", "GRP-PM/SystemDiagram-01", "masked")
            .doesNotContain("host.example.test", "nested-secret-0001", "Url", "Account"));
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("Endpoints"))
            .anySatisfy(w -> assertThat(w).contains("Headers"));
    }

    @Test
    void entriesForTheSameProtocolAndWorkflowAreNumberedNotMerged() {
        DiagramXml diagram = cloudDiagram(List.of(new FieldXml("Id", "String")), List.of(
            new PartnerXml(List.of(Leaf.plain("Id", "ACME-0001")), List.of(entry("FTP", "1"),
                entry("WebService", "1"), entry("FTP", "1"), entry("FTP", "2"),
                entry("FTP", "98"), entry("FTP", "99")))));

        List<ProtocolEntry> entries = read(DETAIL, diagram).partners().clouds().get(0)
            .partners().get(0).entries();

        assertThat(entries).extracting(e -> e.protocol() + "/" + e.workflow().name().orElse("-")
                + "#" + e.position())
            .containsExactly("FTP/Workflow-0001#0", "WebService/Workflow-0001#0",
                "FTP/Workflow-0001#1", "FTP/Workflow-0002#0", "FTP/-#0", "FTP/-#1");
    }

    @Test
    void aFieldThatPartnerFieldsDoesNotDeclareIsMasked() {
        DiagramXml diagram = cloudDiagram(List.of(new FieldXml("Id", "String")), List.of(
            new PartnerXml(List.of(Leaf.plain("Id", "ACME-0001"), Leaf.plain("Region", "north")),
                List.of())));

        Partner partner = read(DETAIL, diagram).partners().clouds().get(0).partners().get(0);

        assertThat(shown(partner.fields(), "Id")).isEqualTo("ACME-0001");
        assertThat(value(partner.fields(), "Region")).isInstanceOf(SettingValue.Masked.class);
        assertThat(partner.maskedCount()).isEqualTo(1);
    }

    @Test
    void equalStoredSecretsHaveEqualFingerprintsAndOthersDiffer() {
        List<Partner> partners = read(DETAIL, PartnerFixtures.detail()).partners().clouds().get(0)
            .partners();
        SettingValue aes = value(partners.get(0).entries().get(0).protocolSettings(), "Password");
        List<ProtocolEntry> webService = partners.get(2).entries();

        assertThat(value(webService.get(0).protocolSettings(), "Password")).isEqualTo(aes);
        assertThat(value(webService.get(1).additionalParameters(), "WSSecurityPassword"))
            .isEqualTo(aes);
        assertThat(value(webService.get(0).additionalParameters(), "WSSecurityPassword"))
            .isNotEqualTo(aes);
    }

    @Test
    void twoCloudsOfOneDiagramAreReadSeparately() {
        DiagramRef diagram = new DiagramRef("SystemDiagram-03", PartnerFixtures.GROUP);
        List<CommunicationCloud> clouds =
            read(diagram, PartnerFixtures.twoClouds()).partners().clouds();

        assertThat(clouds).extracting(CommunicationCloud::moduleName)
            .containsExactly("Module-0901", "Module-0902");
        assertThat(clouds).allSatisfy(cloud -> assertThat(cloud.partners()).singleElement()
            .satisfies(p -> assertThat(shown(p.fields(), "Id"))
                .isEqualTo(PartnerFixtures.SHARED_ID)));
        assertThat(clouds).extracting(cloud -> shown(
                cloud.partners().get(0).entries().get(0).protocolSettings(), "ServerName"))
            .containsExactly("cloud1.example.test", "cloud2.example.test");
        assertThat(clouds.get(0).keyOf(diagram, clouds.get(0).partners().get(0)))
            .isNotEqualTo(clouds.get(1).keyOf(diagram, clouds.get(1).partners().get(0)));
    }

    @Test
    void aLargeListKeepsEveryPartnerInExportOrder() {
        List<Partner> partners = read(new DiagramRef("SystemDiagram-02", PartnerFixtures.GROUP),
            PartnerFixtures.list(120)).partners().clouds().get(0).partners();

        assertThat(partners).hasSize(120);
        assertThat(partners).extracting(p -> shown(p.fields(), "Id"))
            .isEqualTo(Stream.iterate(1, n -> n + 1).limit(120).map("ACME-%04d"::formatted)
                .toList());
    }

    @Test
    void onlyTheRequestedDiagramOfTheRequestedGroupIsRead() {
        DiagramXml sameNameOtherGroup = new DiagramXml("GRP-OTHER", "SystemDiagram-01",
            PartnerFixtures.OWNER, PartnerFixtures.twoClouds().clouds());
        Element root = root(PartnerFixtures.workflowXml(sameNameOtherGroup,
            PartnerFixtures.detail(), PartnerFixtures.list(3)));

        assertThat(PartnerManagementReader.read(DETAIL, root).partners().clouds())
            .singleElement().satisfies(c -> assertThat(c.partners()).hasSize(3));
        assertThat(PartnerManagementReader.read(
                new DiagramRef("SystemDiagram-01", "GRP-OTHER"), root).partners().clouds())
            .hasSize(2);
        assertThatThrownBy(() -> PartnerManagementReader.read(
                new DiagramRef("SystemDiagram-09", PartnerFixtures.GROUP), root))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("GRP-PM/SystemDiagram-09");
    }

    @Test
    void aDiagramWithoutPartnerManagementHasNoClouds() {
        DiagramXml empty = new DiagramXml(PartnerFixtures.GROUP, "SystemDiagram-01",
            PartnerFixtures.OWNER, List.of());

        PartnerManagementReader.Result result = read(DETAIL, empty);

        assertThat(result.partners().clouds()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void theRestFixtureHoldsTwoCloudsAndTheCliFixtureThree() {
        DiagramRef first = new DiagramRef("SystemDiagram-01", "GRP-PM");
        DiagramRef second = new DiagramRef("SystemDiagram-02", "GRP-PM");
        Element rest = recordedWorkflowXml(REST_FIXTURE);
        Element cli = recordedWorkflowXml(CLI_FIXTURE);

        List<PartnerManagementReader.Result> results = List.of(
            PartnerManagementReader.read(first, rest), PartnerManagementReader.read(first, cli),
            PartnerManagementReader.read(second, cli));

        assertThat(results.get(0).partners().clouds()).extracting(CommunicationCloud::moduleName)
            .containsExactly("Module-0901", "Module-0902");
        assertThat(results.get(1).partners().clouds()).hasSize(2);
        assertThat(results.get(2).partners().clouds()).hasSize(1);
        Set<String> masked = new TreeSet<>();
        Set<String> shown = new TreeSet<>();
        for (PartnerManagementReader.Result result : results) {
            assertThat(result.warnings()).isEmpty();
            for (CommunicationCloud cloud : result.partners().clouds()) {
                assertThat(cloud.partners()).isNotEmpty().allSatisfy(partner -> {
                    assertThat(partner.fields()).extracting(Setting::name)
                        .containsExactly("Id", "Name");
                    assertThat(partner.fields()).extracting(Setting::value)
                        .allMatch(SettingValue.Shown.class::isInstance);
                    assertThat(partner.entries()).allSatisfy(
                        e -> assertThat(e.workflow().name()).isPresent());
                });
                settings(cloud).forEach(s -> (s.value() instanceof SettingValue.Masked
                    ? masked : shown).add(s.name()));
            }
        }
        assertThat(masked).containsExactly("Password", "SSLKeyStore", "WSSecurityPassword");
        assertThat(shown).contains("Auth", "Login", "ServerName", "WSSecurityUsername")
            .doesNotContainAnyElementsOf(masked);
    }

    @Test
    void noProducedObjectShowsASecretOrAnythingDerivedFromIt() {
        List<String> secrets = new ArrayList<>(PartnerFixtures.SECRETS);
        List<Object> produced = new ArrayList<>();
        DiagramRef recorded = new DiagramRef("SystemDiagram-01", "GRP-PM");
        for (String fixture : List.of(REST_FIXTURE, CLI_FIXTURE)) {
            Element root = recordedWorkflowXml(fixture);
            secrets.addAll(recordedSecrets(root));
            collect(PartnerManagementReader.read(recorded, root), produced);
        }
        collect(read(DETAIL, PartnerFixtures.detail()), produced);
        collect(read(new DiagramRef("SystemDiagram-03", PartnerFixtures.GROUP),
            PartnerFixtures.twoClouds()), produced);
        PartnerFixtures.stages().values().forEach(xml -> collect(
            PartnerManagementReader.read(DETAIL, root(xml)), produced));

        assertThat(secrets).hasSizeGreaterThan(PartnerFixtures.SECRETS.size());
        List<String> forbidden = secrets.stream().flatMap(PartnerManagementReaderTest::derived)
            .toList();
        assertThat(produced).allSatisfy(object -> assertThat(String.valueOf(object))
            .doesNotContain(forbidden));
    }

    /** {@code secret}, its 12-character prefix, its unkeyed SHA-256 and its Base64 form. */
    private static Stream<String> derived(String secret) {
        try {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
            return Stream.of(secret, secret.substring(0, Math.min(12, secret.length())), sha256,
                Base64.getEncoder().encodeToString(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code result}, its model, every cloud, partner, entry, setting and warning. */
    private static void collect(PartnerManagementReader.Result result, List<Object> produced) {
        produced.add(result);
        produced.add(result.partners());
        produced.addAll(result.warnings());
        for (CommunicationCloud cloud : result.partners().clouds()) {
            produced.add(cloud);
            produced.addAll(cloud.partners());
            cloud.partners().forEach(p -> produced.addAll(p.entries()));
            produced.addAll(settings(cloud).toList());
            settings(cloud).forEach(s -> produced.add(s.value()));
        }
    }

    /** The non-empty texts of the recorded leaves the masking rules call secret. */
    private static List<String> recordedSecrets(Element root) {
        List<String> secrets = new ArrayList<>();
        FixtureScan.elements(root, e -> {
            if (e.localName().equals("Property") && !e.hasElements() && !e.text().isEmpty()
                && PartnerSecretRules.masked(e)) {
                secrets.add(e.text());
            }
        });
        return secrets;
    }

    private static Stream<Setting> settings(CommunicationCloud cloud) {
        return cloud.partners().stream().flatMap(p -> Stream.concat(p.fields().stream(),
            p.entries().stream().flatMap(e -> Stream.concat(e.protocolSettings().stream(),
                e.additionalParameters().stream()))));
    }

    /** One diagram {@code SystemDiagram-01} with one cloud of Workflow-0001 and -0002. */
    private static DiagramXml cloudDiagram(List<FieldXml> fields, List<PartnerXml> partners) {
        return new DiagramXml(PartnerFixtures.GROUP, "SystemDiagram-01", PartnerFixtures.OWNER,
            List.of(new CloudXml("33", "Module-0901", fields, List.of(
                new TwfXml("1", "34", "Workflow-0001"), new TwfXml("2", "86", "Workflow-0002")),
                partners)));
    }

    private static EntryXml entry(String protocol, String twfId) {
        return new EntryXml(protocol, twfId, List.of(Leaf.plain("ServerName",
            "host-" + twfId + ".example.test")), List.of());
    }

    private static SettingValue value(List<Setting> settings, String name) {
        return settings.stream().filter(s -> s.name().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("no setting " + name)).value();
    }

    private static String shown(List<Setting> settings, String name) {
        SettingValue value = value(settings, name);
        assertThat(value).as(name).isInstanceOf(SettingValue.Shown.class);
        return ((SettingValue.Shown) value).value();
    }

    private static PartnerManagementReader.Result read(DiagramRef diagram, DiagramXml xml) {
        return PartnerManagementReader.read(diagram, root(PartnerFixtures.workflowXml(xml)));
    }

    private static Element root(byte[] xml) {
        return XmlTree.parse(xml).root();
    }

    /** The root element of the {@code workflow/workflow.xml} of a recorded export ZIP. */
    static Element recordedWorkflowXml(String zipResource) {
        try (InputStream in = PartnerManagementReaderTest.class.getResourceAsStream(zipResource)) {
            Objects.requireNonNull(in, zipResource);
            return root(ArtifactFixtures.entries(in.readAllBytes()).get("workflow/workflow.xml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
