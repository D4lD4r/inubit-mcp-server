package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * T010 (feature 018): the synthetic Partner Management exports of {@link PartnerFixtures} have the
 * recorded structure and the sizes, secrets and differences later tests rely on.
 */
class PartnerFixturesTest {

    private static final List<String> RECORDED = List.of(
        "/fixtures/v8_1/rest/model_export_systemdiagram.zip",
        "/fixtures/v8_1/cli/export_systemdiagram.zip");

    @Test
    void everyGeneratedElementHasAShapeOfTheRecordedExports() {
        Set<String> recorded = new TreeSet<>();
        for (String fixture : RECORDED) {
            shapes(recordedWorkflowXml(fixture), "", recorded);
        }
        List<byte[]> generated = new ArrayList<>(List.of(
            PartnerFixtures.workflowXml(PartnerFixtures.detail()),
            PartnerFixtures.workflowXml(PartnerFixtures.list(3)),
            PartnerFixtures.workflowXml(PartnerFixtures.twoClouds())));
        generated.addAll(PartnerFixtures.stages().values());

        for (byte[] xml : generated) {
            Set<String> shapes = new TreeSet<>();
            shapes(root(xml), "", shapes);
            assertThat(recorded).containsAll(shapes);
        }
    }

    @Test
    void theDetailSetHasThreePartnersWithTheRecordedProtocolsAndTheOddEntries() {
        Element root = root(PartnerFixtures.workflowXml(PartnerFixtures.detail()));
        List<Element> clouds = clouds(root);

        assertThat(clouds).hasSize(1);
        Element cloud = clouds.get(0);
        List<Element> partners = partners(cloud);
        assertThat(partners).extracting(PartnerFixturesTest::id)
            .containsExactly("ACME-0001", "ACME-0002", "ACME-0003");
        assertThat(field(partners.get(0), "Name")).isEqualTo("ACME Corp");

        assertThat(entries(partners.get(0))).as("two FTP entries for two workflows")
            .extracting(e -> e.attribute("protocol").orElseThrow() + "->"
                + e.attribute("referencableTWFId").orElseThrow())
            .containsExactly("FTP->1", "FTP->2");
        Element sftp = entries(partners.get(1)).get(0);
        assertThat(sftp.attribute("protocol")).hasValue("FTP");
        assertThat(leaf(sftp, "FTPProtocol").text()).isEqualTo("SFTP");
        Element as4 = entries(partners.get(1)).get(1);
        assertThat(as4.attribute("protocol")).hasValue("AS4");
        assertThat(leaf(as4, PartnerFixtures.UNKNOWN_SETTING).text()).isNotEmpty();
        assertThat(entries(partners.get(2))).extracting(e -> e.attribute("protocol").orElseThrow())
            .containsExactly("WebService", "WebService");

        Set<String> ids = settings(cloud, "ReferencableTWFs").elements().stream()
            .map(t -> t.attribute("id").orElseThrow()).collect(Collectors.toSet());
        List<String> unresolved = partners.stream().flatMap(p -> entries(p).stream())
            .map(e -> e.attribute("referencableTWFId").orElseThrow())
            .filter(id -> !ids.contains(id)).toList();
        assertThat(unresolved).containsExactly(PartnerFixtures.UNRESOLVED_TWF_ID);
    }

    @Test
    void theDetailSetHoldsEverySyntheticSecretWhereTheMaskingRulesCatchIt() {
        Element root = root(PartnerFixtures.workflowXml(PartnerFixtures.detail()));
        Element cloud = clouds(root).get(0);
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        settings(cloud, "PartnerFields").elements().forEach(f -> fieldTypes.put(
            f.attribute("name").orElseThrow(), f.attribute("fieldType").orElseThrow()));
        List<Element> partners = partners(cloud);

        Element aes = leaf(entries(partners.get(0)).get(0), "Password");
        assertThat(aes.text()).isEqualTo(PartnerFixtures.AES_PASSWORD);
        assertThat(aes.attribute("encrypted")).hasValue("true");
        Element legacy = leaf(entries(partners.get(1)).get(0), "Password");
        assertThat(legacy.text()).isEqualTo(PartnerFixtures.LEGACY_PASSWORD);
        assertThat(legacy.attribute("encrypted")).isEmpty();
        Element wsSecurity = leaf(entries(partners.get(2)).get(0), "WSSecurityPassword");
        assertThat(wsSecurity.text()).isEqualTo(PartnerFixtures.WS_SECURITY_PASSWORD);
        assertThat(wsSecurity.attribute("type")).isEmpty();
        Element secretField = property(property(partners.get(0), "Fields"),
            PartnerFixtures.SECRET_FIELD);
        assertThat(secretField.text()).isEqualTo(PartnerFixtures.SECRET_FIELD_VALUE);
        assertThat(fieldTypes.get(PartnerFixtures.SECRET_FIELD)).isNotEqualTo("String");
        Element keyField = property(property(partners.get(1), "Fields"),
            PartnerFixtures.KEY_MATERIAL_FIELD);
        assertThat(keyField.text()).isEqualTo(PartnerFixtures.KEY_MATERIAL);
        assertThat(fieldTypes.get(PartnerFixtures.KEY_MATERIAL_FIELD)).isEqualTo("String");

        assertThat(List.of(aes, legacy, wsSecurity)).allSatisfy(
            setting -> assertThat(PartnerSecretRules.masked(setting)).isTrue());
        assertThat(PartnerSecretRules.maskedField(secretField,
            fieldTypes.get(PartnerFixtures.SECRET_FIELD))).isTrue();
        assertThat(PartnerSecretRules.maskedField(keyField,
            fieldTypes.get(PartnerFixtures.KEY_MATERIAL_FIELD))).isTrue();
        assertThat(PartnerFixtures.SECRETS).containsExactlyInAnyOrder(
            PartnerFixtures.AES_PASSWORD, PartnerFixtures.OTHER_AES_PASSWORD,
            PartnerFixtures.LEGACY_PASSWORD, PartnerFixtures.WS_SECURITY_PASSWORD,
            PartnerFixtures.SECRET_FIELD_VALUE, PartnerFixtures.KEY_MATERIAL);
    }

    @Test
    void theListSetsHold120And1000PartnersWithDistinctIds() {
        for (int count : List.of(120, 1000)) {
            List<Element> partners = partners(
                clouds(root(PartnerFixtures.workflowXml(PartnerFixtures.list(count)))).get(0));

            assertThat(partners).hasSize(count);
            assertThat(partners.stream().map(PartnerFixturesTest::id).distinct()).hasSize(count);
            assertThat(id(partners.get(count - 1))).isEqualTo("ACME-%04d".formatted(count));
        }
        List<Element> partners = partners(
            clouds(root(PartnerFixtures.workflowXml(PartnerFixtures.list(120)))).get(0));
        assertThat(partners.stream().filter(p -> entries(p).get(0).attribute("protocol")
            .orElseThrow().equals("FTP"))).as("odd numbers are FTP").hasSize(60);
        assertThat(partners.stream().filter(p -> entries(p).get(0)
            .attribute("referencableTWFId").orElseThrow().equals("1"))).hasSize(40);
    }

    @Test
    void theTwoCloudsOfOneDiagramShareAPartnerId() {
        Element root = root(PartnerFixtures.workflowXml(PartnerFixtures.twoClouds()));
        List<Element> clouds = clouds(root);

        assertThat(clouds).hasSize(2);
        assertThat(clouds).extracting(c -> c.child("ModuleName").orElseThrow().text())
            .doesNotHaveDuplicates();
        assertThat(clouds).allSatisfy(c -> assertThat(partners(c))
            .extracting(PartnerFixturesTest::id).contains(PartnerFixtures.SHARED_ID));
        assertThat(root.child("Workflows").orElseThrow().elements().get(0).elements())
            .filteredOn(e -> e.localName().equals("Workflow")).as("one system diagram").hasSize(1);
    }

    @Test
    void theStagesDifferExactlyAsDocumented() {
        Map<String, byte[]> stages = PartnerFixtures.stages();
        assertThat(stages.keySet())
            .containsExactly("test/node1", "test/node2", "prod/node1", "prod/node2");
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        stages.forEach((server, xml) -> values.put(server, values(root(xml))));

        Map<String, List<String>> differing = new TreeMap<>();
        Set<String> keys = new TreeSet<>();
        values.values().forEach(v -> keys.addAll(v.keySet()));
        for (String key : keys) {
            List<String> perServer = values.values().stream().map(v -> v.get(key)).toList();
            if (new HashSet<>(perServer).size() > 1) {
                differing.put(key, perServer);
            }
        }

        assertThat(differing).hasEntrySatisfying("ACME-0001/ServerName", v -> assertThat(v)
            .containsExactly("ftp.test.example.test", "ftp.test.example.test",
                "ftp.prod.example.test", "ftp.prod.example.test"));
        assertThat(differing).hasEntrySatisfying("ACME-0001/Login",
            v -> assertThat(v).containsExactly("login-a", "login-b", "login-a", "login-a"));
        assertThat(differing).hasEntrySatisfying("ACME-0002/Password", v -> assertThat(v)
            .containsExactly(PartnerFixtures.AES_PASSWORD, PartnerFixtures.AES_PASSWORD,
                PartnerFixtures.AES_PASSWORD, PartnerFixtures.OTHER_AES_PASSWORD));
        assertThat(differing.keySet().stream().filter(k -> !k.startsWith("ACME-0003/")))
            .as("no other difference").hasSize(3);
        assertThat(values).allSatisfy((server, v) -> assertThat(v.containsKey("ACME-0003/Id"))
            .as(server).isEqualTo(server.startsWith("test/")));
    }

    /** {@code <path>[attribute names]} of every element, Map/List properties named by path. */
    private static void shapes(Element element, String parent, Set<String> shapes) {
        String type = element.attribute("type").orElse("");
        String step = element.localName().equals("Property")
            && (type.equals("Map") || type.equals("List"))
            ? "Property:" + element.attribute("name").orElseThrow() : element.localName();
        String path = parent + "/" + step;
        shapes.add(path + element.attributes().stream().map(XmlTree.Attribute::localName)
            .sorted().toList());
        element.elements().forEach(child -> shapes(child, path, shapes));
    }

    /** {@code <Id>/<leaf name>} → value of every partner field and entry leaf of the document. */
    private static Map<String, String> values(Element root) {
        Map<String, String> values = new TreeMap<>();
        for (Element cloud : clouds(root)) {
            for (Element partner : partners(cloud)) {
                String id = id(partner);
                FixtureScan.elements(partner, e -> {
                    if (!e.hasElements() && e.attribute("type").filter("Map"::equals).isEmpty()) {
                        assertThat(values.put(id + "/" + e.attribute("name").orElseThrow(),
                            e.text())).as("one entry per partner").isNull();
                    }
                });
            }
        }
        return values;
    }

    private static List<Element> clouds(Element root) {
        List<Element> clouds = new ArrayList<>();
        FixtureScan.elements(root, e -> {
            if (e.localName().equals("WorkflowModule")
                && e.attribute("moduleType").filter("sdCommunicationCloud"::equals).isPresent()) {
                clouds.add(e);
            }
        });
        return clouds;
    }

    private static List<Element> partners(Element cloud) {
        return settings(cloud, "Partners").elements();
    }

    /** The property {@code name} of the cloud's {@code PartnerManagementSettings}. */
    private static Element settings(Element cloud, String name) {
        return property(property(cloud.child("Properties").orElseThrow(),
            "PartnerManagementSettings"), name);
    }

    private static List<Element> entries(Element partner) {
        return property(partner, "ReferencedTWFs").elements();
    }

    private static String id(Element partner) {
        return field(partner, "Id");
    }

    private static String field(Element partner, String name) {
        return property(property(partner, "Fields"), name).text();
    }

    /** The leaf named {@code name} in the protocol settings or additional parameters. */
    private static Element leaf(Element entry, String name) {
        return entry.elements().stream().flatMap(group -> group.elements().stream())
            .filter(e -> e.attribute("name").filter(name::equals).isPresent()).findFirst()
            .orElseThrow(() -> new AssertionError("no setting " + name));
    }

    private static Element property(Element parent, String name) {
        return parent.elements().stream()
            .filter(e -> e.attribute("name").filter(name::equals).isPresent()).findFirst()
            .orElseThrow(() -> new AssertionError("no property " + name));
    }

    private static Element root(byte[] xml) {
        return XmlTree.parse(xml).root();
    }

    private static Element recordedWorkflowXml(String zipResource) {
        try (InputStream in = PartnerFixturesTest.class.getResourceAsStream(zipResource)) {
            Objects.requireNonNull(in, zipResource);
            return root(ArtifactFixtures.entries(in.readAllBytes()).get("workflow/workflow.xml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
