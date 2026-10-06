package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticSecret;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.SecretRedactor.Kind;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T013 (research D-7, FR-022 – FR-025): every secret form is replaced in memory by a placeholder
 * that names the property path and nothing derived from the value.
 */
class SecretRedactorTest {

    private final ArchiveReader reader = new ArchiveReader();
    private final SecretRedactor redactor = new SecretRedactor();

    static List<String> exports() {
        return ArtifactFixtures.EXPORTS;
    }

    private RedactedArchive redact(String fixture) {
        return redactor.redact(reader.read(ArtifactFixtures.bytes(fixture)));
    }

    /** Everything of the model that a writer could write, serialized. */
    static String serialized(ExportArchive archive) {
        StringBuilder all = new StringBuilder();
        archive.properties().forEach((k, v) -> all.append(k).append('=').append(v).append('\n'));
        archive.workflowGroups().forEach(group -> group.workflows().forEach(workflow ->
            all.append(text(workflow.element()))));
        archive.moduleIndex().forEach(entry -> all.append(text(entry.element())));
        archive.moduleFiles().values().forEach(module -> all.append(text(module.element())));
        archive.repository().values().forEach(file -> {
            all.append(new String(file.content(), StandardCharsets.UTF_8));
            file.metadata().ifPresent(meta -> all.append(text(meta)));
        });
        return all.toString();
    }

    private static String text(Element element) {
        return new String(XmlNormalizer.normalize(element), StandardCharsets.UTF_8);
    }

    private static ModuleXml module(RedactedArchive archive, String name) {
        return archive.archive().moduleFiles().get(name);
    }

    private static String property(Element properties, String name) {
        List<String> values = new ArrayList<>();
        FixtureScan.elements(properties, e -> {
            if (e.localName().equals("Property") && e.attribute("name").orElse("").equals(name)
                && !e.hasElements()) {
                values.add(e.text());
            }
        });
        assertThat(values).as(name).hasSize(1);
        return values.get(0);
    }

    @ParameterizedTest
    @MethodSource("exports")
    void noSyntheticSecretValueSurvivesTheRedaction(String fixture) {
        String before = serialized(reader.read(ArtifactFixtures.bytes(fixture)));
        String after = serialized(redact(fixture).archive());
        List<String> leaked = new ArrayList<>();
        List<String> present = new ArrayList<>();
        for (SyntheticSecret secret : ArtifactFixtures.syntheticSecrets()) {
            if (secret.fixture().equals(fixture) && before.contains(secret.value())) {
                present.add(secret.kind());
            }
            if (after.contains(secret.value())) {
                leaked.add(secret.kind() + " " + secret.fixture() + " " + secret.location());
            }
        }
        assertThat(leaked).isEmpty();
        assertThat(present).as("the listed secrets of the fixture are in the model before")
            .hasSize((int) ArtifactFixtures.syntheticSecrets().stream()
                .filter(secret -> secret.fixture().equals(fixture)).count());
    }

    @Test
    void placeholdersNameThePropertyPath() {
        RedactedArchive b = redact("grp-b.zip");

        Element as2 = module(b, "Module-0024").element();
        assertThat(property(as2, "Mime.Decrypt.Password"))
            .isEqualTo("${secret:Mime.Decrypt.Password}");
        assertThat(property(as2, "Password")).isEqualTo("${secret:Password}");
        assertThat(property(as2, "Mime.Decrypt.Keystore"))
            .isEqualTo("${secret:Mime.Decrypt.Keystore}");
        Element ws = module(b, "Module-0028").element();
        assertThat(property(ws, "SSLKeyStoreRemoteConnector"))
            .isEqualTo("${secret:SSLKeyStoreRemoteConnector}");
        assertThat(property(ws, "SSLKeyStorePasswordRemoteConnector"))
            .isEqualTo("${secret:SSLKeyStorePasswordRemoteConnector}");
        Element xslt = module(b, "Module-0020").element();
        assertThat(property(xslt, "var.userPassword"))
            .isEqualTo("${secret:xslt.sourceVariables/var.userPassword}");
        assertThat(property(xslt, "ISCurrentTime"))
            .isEqualTo("${secret:xslt.sourceVariables/ISCurrentTime}");
        assertThat(property(xslt, "xslt.source")).isEqualTo("${secret:xslt.source}");
        assertThat(property(xslt, "xslt.target")).isEqualTo("${secret:xslt.target}");
        Element smime = module(redact("module-smime.zip"), "Module-0029").element();
        assertThat(property(smime, "smime.keystore.data"))
            .isEqualTo("${secret:smime.keystore.data}");
        assertThat(property(smime, "smime.keystore.alias.password"))
            .isEqualTo("${secret:smime.keystore.alias.password}");
        assertThat(property(smime, "smime.keystore.alias")).as("not a secret")
            .isEqualTo("partner");
    }

    @Test
    void workflowLiteralsAndVariableDefaultsAreReplaced() {
        WorkflowXml workflow = redact("grp-b.zip").archive().workflowGroups().get(0).workflows()
            .stream().filter(w -> w.name().equals("Workflow-0006")).findFirst().orElseThrow();
        String text = text(workflow.element());

        assertThat(text).contains(
            "<literal isPassword=\"true\">${secret:WorkflowModule(23963799)/Assignments/"
                + "var.fixturePassword}</literal>",
            "<DefaultValue>${secret:Variables/var.fixturePassword}</DefaultValue>");
    }

    @Test
    void theReportHoldsCountsPerKindOnly() {
        RedactionReport report = redact("grp-b.zip").report();

        assertThat(report.counts()).containsEntry(Kind.PASSWORD, 11)
            .containsEntry(Kind.KEYSTORE, 2).containsEntry(Kind.UNTYPED_SECRET, 4)
            .containsEntry(Kind.PASSWORD_LITERAL, 1).containsEntry(Kind.PASSWORD_DEFAULT, 1)
            .containsEntry(Kind.SAVED_TEST_MESSAGE, 2);
        assertThat(report.counts().get(Kind.SOURCE_VARIABLE)).isGreaterThanOrEqualTo(42);
        assertThat(report.total()).isEqualTo(report.counts().values().stream()
            .mapToInt(Integer::intValue).sum());
        // e.g. WS-Security.Client.KeyStore.Alias: suspicious name, kept, counted only
        assertThat(report.suspicious()).isPositive();
        assertThat(report.toString()).doesNotContain("partner", "Alias");
    }

    @Test
    void emptyValuesStayEmptyAndPlainCertificatesStay() {
        String cert = certificate();
        RedactedArchive archive = redactModule("""
            <?xml version='1.0' encoding='UTF-8'?>
            <Properties version="4.1">
            \t<Property name="Password" type="Password" encrypted="true"></Property>
            \t<Property name="Login.Password" type="Password"/>
            \t<Property name="Mime.Verify.X509" type="X509">%s</Property>
            \t<Property name="Mime.Sign.X509" type="X509">%s</Property>
            \t<Property name="smime.certificate.data">-----BEGIN PRIVATE KEY-----
            MIIBfixture
            -----END PRIVATE KEY-----</Property>
            \t<Property name="var.userPassword" type="MaskedString" encrypted="true">\
            AES-x</Property>
            \t<Property name="xslt.sourceVariables" type="Map">
            \t\t<Property name="empty"></Property>
            \t</Property>
            </Properties>
            """.formatted(cert, Base64.getEncoder().encodeToString(new byte[] {(byte) 0xfe,
            (byte) 0xed, (byte) 0xfe, (byte) 0xed, 0, 0, 0, 2})));
        Element module = module(archive, "Module-0023").element();

        assertThat(property(module, "Password")).isEmpty();
        assertThat(property(module, "Login.Password")).isEmpty();
        assertThat(property(module, "Mime.Verify.X509")).as("certificate without key")
            .isEqualTo(cert);
        assertThat(property(module, "Mime.Sign.X509")).as("a keystore in a certificate property")
            .isEqualTo("${secret:Mime.Sign.X509}");
        assertThat(property(module, "smime.certificate.data"))
            .isEqualTo("${secret:smime.certificate.data}");
        assertThat(property(module, "var.userPassword")).isEqualTo("${secret:var.userPassword}");
        assertThat(property(module, "empty")).isEmpty();
        assertThat(archive.report().counts()).containsEntry(Kind.PRIVATE_KEY_CERTIFICATE, 2)
            .containsEntry(Kind.ENCRYPTED, 1).doesNotContainKey(Kind.SOURCE_VARIABLE);
    }

    @Test
    void thePlaceholderDoesNotDependOnTheValue() {
        String template = """
            <?xml version='1.0' encoding='UTF-8'?>
            <Properties version="4.1">
            \t<Property name="Password" type="Password" encrypted="true">%s</Property>
            </Properties>
            """;
        Element first = module(redactModule(template.formatted("U1lOMDAwMDAx")), "Module-0023")
            .element();
        Element second = module(redactModule(template.formatted("AES-other")), "Module-0023")
            .element();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void keyMaterialOutsideKeyStorePropertiesIsReplaced() throws IOException {
        // review I3 (FR-023): a JKS InternalDocument and a PKCS#12 repository file in grp-b
        RedactedArchive archive = redact("grp-b.zip");

        assertThat(property(module(archive, "Module-0018").element(), "partnerTrustStore"))
            .isEqualTo("${secret:partnerTrustStore}");
        assertThat(property(module(archive, "Module-0018").element(), "JSONStaticSchema"))
            .as("an ordinary InternalDocument stays").startsWith("H4sI");
        assertThat(archive.withheldRepositoryFiles())
            .containsExactly("Root/OWNERS/keys/fixture-client.p12");
        assertThat(archive.archive().repository().get("Root/OWNERS/keys/fixture-client.p12")
            .content()).isEmpty();
        assertThat(archive.archive().repository().get("Root/OWNERS/xsd/msg.xsd").content())
            .isNotEmpty();
        assertThat(archive.report().counts()).containsEntry(Kind.KEY_MATERIAL, 1)
            .containsEntry(Kind.REPOSITORY_KEY_MATERIAL, 1);
        String all = serialized(archive.archive());
        for (ArtifactFixtures.SyntheticKeystore keystore : ArtifactFixtures.syntheticKeystores()) {
            assertThat(all).doesNotContain(Base64.getEncoder().encodeToString(keystore.bytes()));
        }
    }

    @Test
    void keyMaterialIsRecognizedByContentOrName() throws IOException {
        byte[] p12 = ArtifactFixtures.bytes("keystores/fixture-client.p12");
        RedactedArchive archive = redactModule("""
            <?xml version='1.0' encoding='UTF-8'?>
            <Properties version="4.1">
            \t<Property name="a" type="InternalDocument" documentName="data">%s</Property>
            \t<Property name="b" type="InternalDocument" documentName="data">%s</Property>
            \t<Property name="c" type="InternalDocument" documentName="client.pfx">%s</Property>
            \t<Property name="d">-----BEGIN EC PRIVATE KEY-----
            MHcfixture
            -----END EC PRIVATE KEY-----</Property>
            \t<Property name="e" type="InternalDocument" documentName="data">%s</Property>
            \t<Property name="f" type="InternalDocument" documentName="notes.txt">%s</Property>
            </Properties>
            """.formatted(gzipBase64(p12),
            Base64.getEncoder().encodeToString(new byte[] {(byte) 0xce, (byte) 0xce,
                (byte) 0xce, (byte) 0xce, 0, 0, 0, 2}),
            Base64.getEncoder().encodeToString("fixture".getBytes(StandardCharsets.UTF_8)),
            gzipBase64("{\"fixture\": true}".getBytes(StandardCharsets.UTF_8)),
            Base64.getEncoder().encodeToString("fixture notes".getBytes(StandardCharsets.UTF_8))));
        Element module = module(archive, "Module-0023").element();

        assertThat(property(module, "a")).as("PKCS#12 by content").isEqualTo("${secret:a}");
        assertThat(property(module, "b")).as("JCEKS by content").isEqualTo("${secret:b}");
        assertThat(property(module, "c")).as("by name").isEqualTo("${secret:c}");
        assertThat(property(module, "d")).as("PEM private key").isEqualTo("${secret:d}");
        assertThat(property(module, "e")).as("JSON").doesNotContain("${secret:");
        assertThat(property(module, "f")).as("text").doesNotContain("${secret:");
        assertThat(archive.report().counts()).containsEntry(Kind.KEY_MATERIAL, 4);
    }

    @Test
    void base64KeyMaterialUnderAnUnknownNameIsReplaced() {
        // stage-2 minor 2: untyped properties are decoded too; BER indefinite-length PKCS#12
        byte[] jks = ArtifactFixtures.bytes("keystores/fixture-truststore.jks");
        byte[] ber = new byte[80];
        System.arraycopy(new byte[] {0x30, (byte) 0x80, 0x02, 0x01, 0x03, 0x30, (byte) 0x80,
            0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x07,
            0x01}, 0, ber, 0, 18);
        String text = Base64.getEncoder().encodeToString(
            "fixture text that is long enough to be decoded as base64 too".repeat(2)
                .getBytes(StandardCharsets.UTF_8));
        RedactedArchive archive = redactModule("""
            <?xml version='1.0' encoding='UTF-8'?>
            <Properties version="4.1">
            \t<Property name="partnerBlob">%s</Property>
            \t<Property name="otherBlob">%s</Property>
            \t<Property name="plainBlob">%s</Property>
            </Properties>
            """.formatted(Base64.getMimeEncoder().encodeToString(jks),
            Base64.getEncoder().encodeToString(ber), text));
        Element module = module(archive, "Module-0023").element();

        assertThat(KeyMaterial.isKeyMaterial(ber)).as("BER indefinite length").isTrue();
        assertThat(property(module, "partnerBlob")).isEqualTo("${secret:partnerBlob}");
        assertThat(property(module, "otherBlob")).isEqualTo("${secret:otherBlob}");
        assertThat(property(module, "plainBlob")).isEqualTo(text);
    }

    @Test
    void aVeryLongUntypedValueIsCheckedWithoutOverflow() {
        // structure corpus: a regex with a group loop overflowed the stack on a long value
        String value = Base64.getEncoder().encodeToString(new byte[300_000]);

        org.assertj.core.api.Assertions.assertThatCode(() -> redactModule("""
            <?xml version='1.0' encoding='UTF-8'?>
            <Properties version="4.1">
            \t<Property name="largeBlob">%s</Property>
            </Properties>
            """.formatted(value))).doesNotThrowAnyException();
    }

    private static String gzipBase64(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes)) {
            gzip.write(content);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    @Test
    void thePasswordTypeIsResolvedByNamespaceNotByPrefix() {
        // review M3: is:password means {http://inubit.com/variables/types}password
        String renamed = workflowDefault(workflow -> workflow
            .replace("xmlns:is=\"http://inubit.com/variables/types\"",
                "xmlns:vt=\"http://inubit.com/variables/types\"")
            .replace("type=\"is:password\"", "type=\"vt:password\""));
        String foreign = workflowDefault(workflow -> workflow
            .replace("xmlns:is=\"http://inubit.com/variables/types\"",
                "xmlns:is=\"urn:example:fixture:other\""));

        String undeclared = workflowDefault(workflow -> workflow
            .replace(" xmlns:is=\"http://inubit.com/variables/types\"", ""));

        assertThat(renamed).isEqualTo("${secret:Variables/var.fixturePassword}");
        assertThat(foreign).as("another namespace").isEqualTo("synthetic-default-0001");
        assertThat(undeclared).as("an undeclared prefix fails closed")
            .isEqualTo("${secret:Variables/var.fixturePassword}");
    }

    /** The DefaultValue of var.fixturePassword after redacting grp-b with a changed workflow. */
    private String workflowDefault(java.util.function.UnaryOperator<String> change) {
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries("grp-b.zip"));
        String workflow = new String(entries.get("workflow/workflow.xml"), StandardCharsets.UTF_8);
        String changed = change.apply(workflow);
        assertThat(changed).isNotEqualTo(workflow);
        entries.put("workflow/workflow.xml", changed.getBytes(StandardCharsets.UTF_8));
        RedactedArchive archive = redactor.redact(reader.read(ArtifactFixtures.zip(entries)));
        List<String> defaults = new ArrayList<>();
        archive.archive().workflowGroups().forEach(group -> group.workflows().forEach(w ->
            FixtureScan.elements(w.element(), e -> {
                if (e.localName().equals("Variable") && e.attribute("name")
                    .filter("var.fixturePassword"::equals).isPresent()) {
                    e.child("DefaultValue").ifPresent(d -> defaults.add(d.text()));
                }
            })));
        assertThat(defaults).hasSize(1);
        return defaults.get(0);
    }

    /** One of the synthetic certificates of the fixtures (no private key). */
    private static String certificate() {
        List<String> certificates = new ArrayList<>();
        FixtureScan.elements(redactorInput("grp-b.zip").moduleFiles().get("Module-0024")
            .element(), e -> {
                if (e.attribute("type").filter("X509"::equals).isPresent()) {
                    certificates.add(e.text().strip());
                }
            });
        return certificates.get(0);
    }

    private static ExportArchive redactorInput(String fixture) {
        return new ArchiveReader().read(ArtifactFixtures.bytes(fixture));
    }

    /** {@code module-one.zip} with {@code moduleXml} as its module file. */
    private RedactedArchive redactModule(String moduleXml) {
        Map<String, byte[]> entries = new LinkedHashMap<>(
            ArtifactFixtures.entries("module-one.zip"));
        entries.put("module/module-0023.xml", moduleXml.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return redactor.redact(reader.read(bytes.toByteArray()));
    }
}
