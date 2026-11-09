package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowGroupXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.SecretRedactor.Kind;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T008 (feature 018 FR-008, research D-3): the masking rules of Partner Management values, at
 * least as strict as the workspace export's redaction.
 */
class PartnerSecretRulesTest {

    private static final List<String> SYSTEM_DIAGRAMS = List.of(
        "/fixtures/v8_1/rest/model_export_systemdiagram.zip",
        "/fixtures/v8_1/cli/export_systemdiagram.zip");

    private static final String PEM_KEY = """
        -----BEGIN PRIVATE KEY-----
        MIIBfixture
        -----END PRIVATE KEY-----""";

    @Test
    void everySecretPropertyOfTheWorkspaceExportIsMaskedHere() {
        List<Element> secrets = new ArrayList<>();
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        ArchiveReader reader = new ArchiveReader();
        for (String fixture : ArtifactFixtures.EXPORTS) {
            ExportArchive archive = reader.read(ArtifactFixtures.bytes(fixture));
            for (WorkflowGroupXml group : archive.workflowGroups()) {
                for (WorkflowXml workflow : group.workflows()) {
                    secrets.addAll(secretProperties(true, workflow.element(), kinds));
                }
            }
            for (ModuleXml module : archive.moduleFiles().values()) {
                secrets.addAll(secretProperties(false, module.element(), kinds));
            }
        }
        for (String systemDiagram : SYSTEM_DIAGRAMS) {
            secrets.addAll(secretProperties(true, workflowXml(systemDiagram), kinds));
        }
        secrets.addAll(secretProperties(false, syntheticSecretForms(), kinds));

        assertThat(kinds).as("every property kind of SecretPaths is exercised").containsAll(
            EnumSet.of(Kind.PASSWORD, Kind.ENCRYPTED, Kind.KEYSTORE, Kind.UNTYPED_SECRET,
                Kind.SAVED_TEST_MESSAGE, Kind.PRIVATE_KEY_CERTIFICATE, Kind.KEY_MATERIAL));
        assertThat(secrets).allSatisfy(property -> assertThat(PartnerSecretRules.masked(property))
            .as(property.attribute("name").orElse("")).isTrue());
    }

    @Test
    void aClearTextLegacyPasswordIsMasked() {
        assertThat(PartnerSecretRules.masked(property(
            "<Property name=\"Password\" type=\"Password\">synthetic-secret-0001</Property>")))
            .isTrue();
        assertThat(PartnerSecretRules.masked(property(
            "<Property name=\"Password\">synthetic-secret-0001</Property>"))).as("without type")
            .isTrue();
    }

    @Test
    void anEncryptedValueIsMaskedWhateverItsType() {
        assertThat(PartnerSecretRules.masked(property(
            "<Property name=\"Login\" encrypted=\"true\">AES-c3ludGhldGlj</Property>"))).isTrue();
    }

    @Test
    void aWsSecurityPasswordWithoutTypeIsMasked() {
        assertThat(PartnerSecretRules.masked(property(
            "<Property name=\"WSSecurityPassword\">synthetic-secret-0002</Property>"))).isTrue();
    }

    @Test
    void anSslKeyStoreReferenceIsMaskedByItsName() {
        Element reference = property("<Property name=\"SSLKeyStore\" "
            + "type=\"HTTPConnectorTrustStore\">truststore-0001</Property>");
        Element empty = property(
            "<Property name=\"SSLKeyStore\" type=\"HTTPConnectorTrustStore\" />");

        assertThat(SecretPaths.kind("SSLKeyStore", "HTTPConnectorTrustStore", false,
            "truststore-0001")).as("the workspace export keeps it").isNull();
        assertThat(PartnerSecretRules.masked(reference)).isTrue();
        assertThat(PartnerSecretRules.masked(empty)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FtpPassword", "PASSWD", "loginPwd", "ClientSecret", "AuthToken",
        "TrustKeyStoreRef", "PrivateKeyAlias", "KeyPassphrase", "CredentialRef", "ApiKey",
        "AccessKeyId"})
    void aSecretNameIsMaskedEvenWithAHarmlessValue(String name) {
        assertThat(PartnerSecretRules.masked(property(
            "<Property name=\"" + name + "\">harmless-0001</Property>"))).isTrue();
    }

    @Test
    void keyMaterialInAPlainFieldIsMasked() {
        String jks = Base64.getMimeEncoder().encodeToString(
            ArtifactFixtures.bytes("keystores/fixture-truststore.jks"));

        assertThat(PartnerSecretRules.maskedField(property(
            "<Property name=\"Name\">" + PEM_KEY + "</Property>"), "String")).isTrue();
        assertThat(PartnerSecretRules.maskedField(property(
            "<Property name=\"Name\">" + jks + "</Property>"), "String")).isTrue();
        assertThat(PartnerSecretRules.masked(property(
            "<Property name=\"ServerName\">" + PEM_KEY + "</Property>"))).as("a setting")
            .isTrue();
    }

    @Test
    void aPartnerFieldWithAnUnknownOrUndeclaredTypeIsMasked() {
        Element id = property("<Property name=\"Id\">ACME-0001</Property>");

        assertThat(PartnerSecretRules.PLAIN_FIELD_TYPES).containsExactly("String");
        assertThat(PartnerSecretRules.maskedField(id, "String")).isFalse();
        assertThat(PartnerSecretRules.maskedField(id, "Integer")).isTrue();
        assertThat(PartnerSecretRules.maskedField(id, "string")).as("case-sensitive").isTrue();
        assertThat(PartnerSecretRules.maskedField(id, "")).as("not declared").isTrue();
    }

    @Test
    void theRecordedPartnerManagementsMaskOnlyTheirSecrets() {
        Set<String> masked = new TreeSet<>();
        Set<String> shown = new TreeSet<>();
        for (String systemDiagram : SYSTEM_DIAGRAMS) {
            FixtureScan.elements(workflowXml(systemDiagram), settings -> {
                String group = settings.attribute("name").orElse("");
                if (group.equals("ProtocolSettings")
                    || group.equals("AdditionalParameterSettings")) {
                    FixtureScan.elements(settings, e -> {
                        if (e != settings && !e.hasElements()) {
                            (PartnerSecretRules.masked(e) ? masked : shown)
                                .add(e.attribute("name").orElse(""));
                        }
                    });
                }
            });
        }

        assertThat(masked).containsExactly("Password", "SSLKeyStore", "WSSecurityPassword");
        assertThat(shown).contains("Auth", "Login", "ServerName", "WSSecurityUsername")
            .doesNotContainAnyElementsOf(masked);
    }

    /**
     * The original leaf {@code Property} elements below {@code root} for which the workspace
     * export's walk calls its replacer, with their kinds added to {@code kinds}. Values inside
     * {@code xslt.sourceVariables} are secret by position, not by property kind; a Partner
     * Management has no such map, so they are left out (FR-008: "every property kind both see").
     */
    private static List<Element> secretProperties(boolean workflow, Element root,
        Set<Kind> kinds) {
        Map<String, String> originals = new HashMap<>();
        Map<String, Kind> kindOf = new HashMap<>();
        SecretPaths paths = new SecretPaths((artifact, path, kind, value) -> {
            if (kind == Kind.SOURCE_VARIABLE) {
                return value;
            }
            String marker = "partner-secret-rules-marker-" + originals.size();
            originals.put(marker, value);
            kindOf.put(marker, kind);
            return marker;
        });
        Element walked = workflow ? paths.workflow("Workflow-0001", root)
            : paths.module("Module-0001", root);
        List<Element> found = new ArrayList<>();
        FixtureScan.elements(walked, e -> {
            if (e.localName().equals("Property") && originals.containsKey(e.text())) {
                found.add(e.withText(originals.get(e.text())));
                kinds.add(kindOf.get(e.text()));
            }
        });
        return found;
    }

    /** One module document with every property secret form of the workspace export. */
    private static Element syntheticSecretForms() {
        Base64.Encoder base64 = Base64.getEncoder();
        String p12 = base64.encodeToString(ArtifactFixtures.bytes("keystores/fixture-client.p12"));
        String jks = Base64.getMimeEncoder().encodeToString(
            ArtifactFixtures.bytes("keystores/fixture-truststore.jks"));
        String jksHeader = base64.encodeToString(new byte[] {(byte) 0xfe, (byte) 0xed,
            (byte) 0xfe, (byte) 0xed, 0, 0, 0, 2});
        return property("""
            <Properties version="4.1">
            <Property name="Password" type="Password">synthetic-secret-0001</Property>
            <Property name="var.user" type="MaskedString" encrypted="true">AES-x</Property>
            <Property name="ks" type="KeyStore">%s</Property>
            <Property name="SSLKeyStoreRemoteConnector">synthetic-secret-0002</Property>
            <Property name="smime.keystore.alias.password">synthetic-secret-0003</Property>
            <Property name="xslt.source">synthetic-message-0001</Property>
            <Property name="Mime.Sign.X509" type="X509">%s</Property>
            <Property name="smime.certificate.data">%s</Property>
            <Property name="a" type="InternalDocument" documentName="data">%s</Property>
            <Property name="c" type="InternalDocument" documentName="c.pfx">Zml4</Property>
            <Property name="d">%s</Property>
            <Property name="partnerBlob">%s</Property>
            </Properties>
            """.formatted(p12, jksHeader, PEM_KEY, p12, PEM_KEY, jks));
    }

    private static Element workflowXml(String zipResource) {
        try (InputStream in = PartnerSecretRulesTest.class.getResourceAsStream(zipResource)) {
            assertThat(in).as(zipResource).isNotNull();
            byte[] xml = ArtifactFixtures.entries(in.readAllBytes()).get("workflow/workflow.xml");
            return XmlTree.parse(xml).root();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Element property(String xml) {
        return XmlTree.parse(xml.getBytes(StandardCharsets.UTF_8)).root();
    }
}
