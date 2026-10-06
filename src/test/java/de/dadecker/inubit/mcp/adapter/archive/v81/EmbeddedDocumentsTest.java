package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Document;
import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Encoding;
import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Extracted;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * T015 (research D-4): embedded documents become files of their own, the property text a
 * {@code @file:} reference; re-embedding restores the property exactly.
 */
class EmbeddedDocumentsTest {

    private static Map<String, ModuleXml> modules(String fixture) {
        return new ArchiveReader().read(ArtifactFixtures.bytes(fixture)).moduleFiles();
    }

    private static String value(Element properties, String name) {
        return properties.elements().stream()
            .filter(p -> p.attribute("name").orElse("").equals(name))
            .findFirst().orElseThrow().text();
    }

    private static Optional<String> attribute(Element properties, String name, String attr) {
        return properties.elements().stream()
            .filter(p -> p.attribute("name").orElse("").equals(name))
            .findFirst().orElseThrow().attribute(attr);
    }

    private static Element properties(String xml) {
        return XmlTree.parse(xml.getBytes(StandardCharsets.UTF_8)).root();
    }

    @Test
    void stylesheetsAndWsdlsBecomeFilesReferencedFromTheProperty() {
        Map<String, ModuleXml> modules = modules("grp-b.zip");

        Extracted xslt = EmbeddedDocuments.extract(modules.get("Module-0020").element());
        assertThat(xslt.documents()).extracting(Document::fileName, Document::encoding)
            .containsExactly(tuple("xslt.stylesheet.xsl",
                Encoding.ESCAPED_XML));
        assertThat(value(xslt.properties(), "xslt.stylesheet"))
            .isEqualTo("@file:xslt.stylesheet.xsl");
        assertThat(new String(xslt.documents().get(0).content(), StandardCharsets.UTF_8))
            .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xsl:stylesheet")
            .doesNotContain("&lt;xsl");

        Extracted wsdl = EmbeddedDocuments.extract(modules.get("Module-0026").element());
        assertThat(wsdl.documents()).extracting(Document::fileName)
            .containsExactlyInAnyOrder("ValidWsdlData.wsdl", "WsdlData.wsdl");
        assertThat(value(wsdl.properties(), "WsdlData")).isEqualTo("@file:WsdlData.wsdl");
    }

    @Test
    void internalDocumentsAreDecodedByContent() {
        Extracted json = EmbeddedDocuments.extract(modules("grp-b.zip").get("Module-0018")
            .element());

        assertThat(json.documents()).singleElement().satisfies(document -> {
            assertThat(document.fileName()).isEqualTo("JSONStaticSchema.bin");
            assertThat(document.encoding()).isEqualTo(Encoding.GZIP_BASE64);
            assertThat(new String(document.content(), StandardCharsets.UTF_8))
                .startsWith("{\r\n\t\"$schema\"");
        });
        assertThat(value(json.properties(), "JSONStaticSchema"))
            .isEqualTo("@file:JSONStaticSchema.bin");
    }

    @Test
    void theExtensionOfAnInternalDocumentFollowsItsNameOrContentType() throws IOException {
        String gz = gzipBase64("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
        Extracted extracted = EmbeddedDocuments.extract(properties("""
            <Properties version="4.1">
            <Property name="logEvent.xsd" type="InternalDocument" documentName="logEvent.xsd" \
            documentContentType="application/octet-stream" documentSize="1">%s</Property>
            <Property name="sample" type="InternalDocument" documentName="sample" \
            documentContentType="text/xml" documentSize="1">%s</Property>
            <Property name="plain" type="InternalDocument" documentName="data" \
            documentContentType="application/octet-stream" documentSize="3">%s</Property>
            <Property name="config" type="XmlDocument">&lt;config/></Property>
            <Property name="empty" type="XmlDocument"></Property>
            <Property name="redacted" type="InternalDocument">${secret:redacted}</Property>
            <Property name="xslt.sourceVariables" type="Map">
            <Property name="var.input" type="XmlDocument">&lt;nested/></Property>
            </Property>
            </Properties>
            """.formatted(gz, gz, Base64.getEncoder().encodeToString("abc".getBytes(
                StandardCharsets.UTF_8)))));

        assertThat(extracted.documents()).extracting(Document::fileName, Document::encoding)
            .containsExactly(
                tuple("logEvent.xsd.xsd", Encoding.GZIP_BASE64),
                tuple("sample.xml", Encoding.GZIP_BASE64),
                tuple("plain.bin", Encoding.BASE64),
                tuple("config.xml", Encoding.ESCAPED_XML));
        assertThat(value(extracted.properties(), "empty")).isEmpty();
        assertThat(value(extracted.properties(), "redacted")).isEqualTo("${secret:redacted}");
    }

    @Test
    void reEmbeddingRestoresEveryFixtureModuleExactly() {
        int documents = 0;
        for (String fixture : ArtifactFixtures.EXPORTS) {
            for (ModuleXml module : modules(fixture).values()) {
                Extracted extracted = EmbeddedDocuments.extract(module.element());
                documents += extracted.documents().size();

                assertThat(EmbeddedDocuments.embed(extracted.properties(),
                    extracted.documents())).as("%s %s", fixture, module.name())
                    .isEqualTo(module.element());
            }
        }
        assertThat(documents).isGreaterThanOrEqualTo(10);
    }

    @Test
    void reEmbeddingAnEditedDocumentRecomputesSizeAndChecksum() throws Exception {
        Extracted extracted = EmbeddedDocuments.extract(modules("grp-b.zip").get("Module-0018")
            .element());
        byte[] edited = "{\"type\": \"object\"}".getBytes(StandardCharsets.UTF_8);
        List<Document> documents = new ArrayList<>();
        for (Document document : extracted.documents()) {
            documents.add(new Document(document.property(), document.fileName(),
                document.encoding(), edited));
        }

        Element embedded = EmbeddedDocuments.embed(extracted.properties(), documents);

        assertThat(attribute(embedded, "JSONStaticSchema", "documentSize"))
            .contains(String.valueOf(edited.length));
        assertThat(value(embedded, "JSONStaticSchemaMD5")).isEqualTo(HexFormat.of()
            .formatHex(MessageDigest.getInstance("MD5").digest(edited)));
        assertThat(EmbeddedDocuments.extract(embedded).documents().get(0).content())
            .isEqualTo(edited);
    }

    private static String gzipBase64(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
