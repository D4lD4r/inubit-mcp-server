package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * T012 (feature 008, research D-7): the synthesized metadata of a new repository file, with the
 * content type from the table that probe P-1 recorded.
 */
class RepositoryMetadataTest {

    private static final String TYPES = "/Root/jdoe/xsd/P8/types/";

    private static Element parsed(String xml) {
        return XmlTree.parse(xml.getBytes(StandardCharsets.UTF_8)).root();
    }

    /** File name → contentType that INUBIT stored for the probe's new files (P-1, T002). */
    private static Map<String, String> storedTypes() {
        byte[] zip;
        try (InputStream in = RepositoryMetadataTest.class.getResourceAsStream(
            "/fixtures/v8_1/cli/export_repository_types.zip")) {
            zip = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> types = new TreeMap<>();
        RepositoryArchive.read(zip).forEach(file -> {
            Element property = XmlTree.parse(file.metadata()).root();
            types.put(property.attribute("name").orElseThrow(),
                property.attribute("contentType").orElseThrow());
        });
        return types;
    }

    @Test
    void theContentTypeOfEachSupportedExtensionIsTheOneInubitStored() {
        Map<String, String> stored = storedTypes();

        assertThat(stored).containsOnlyKeys("probe.xsd", "probe.xsl", "probe.xslt",
            "probe.wsdl", "probe.xml");
        stored.forEach((name, type) -> assertThat(parsed(RepositoryMetadata.forNewFile(
            TYPES + name)).attribute("contentType")).as(name).contains(type));
    }

    @Test
    void theTableIsTheOneOfResearchD7() {
        assertThat(contentType("/Root/jdoe/a.xsd")).isEqualTo("application/x-xsd+xml");
        assertThat(contentType("/Root/jdoe/a.xsl")).isEqualTo("application/xslt+xml");
        assertThat(contentType("/Root/jdoe/a.xslt")).isEqualTo("application/xslt+xml");
        assertThat(contentType("/Root/jdoe/a.wsdl")).isEqualTo("application/wsdl+xml");
        assertThat(contentType("/Root/jdoe/a.xml")).isEqualTo("application/xml");
        assertThat(contentType("/Root/jdoe/A.XSD")).isEqualTo("application/x-xsd+xml");
    }

    private static String contentType(String path) {
        return parsed(RepositoryMetadata.forNewFile(path)).attribute("contentType").orElseThrow();
    }

    @Test
    void aNewFileGetsTheProbedPropertyWithAnEmptyDescriptionAndNoAnonymousRead() {
        String path = "/Root/jdoe/xsd/orders/order v2.xsd";

        Element property = parsed(RepositoryMetadata.forNewFile(path));

        assertThat(property.localName()).isEqualTo("Property");
        assertThat(property.attribute("name")).contains("order v2.xsd");
        assertThat(property.attribute("type")).contains("RepositoryFile");
        assertThat(property.attribute("path")).contains(path);
        assertThat(property.attribute("contentType")).contains("application/x-xsd+xml");
        // INUBIT's own import default applies (research D-7), and nothing else is synthesized
        assertThat(property.attributes()).hasSize(4);
        assertThat(property.attribute("anonymousRead")).isEmpty();
        assertThat(property.elements()).singleElement().satisfies(description -> {
            assertThat(description.localName()).isEqualTo("Description");
            assertThat(description.text()).isEmpty();
        });
    }

    @Test
    void anotherExtensionIsInvalidInputNamingTheSupportedOnes() {
        for (String path : new String[] {"/Root/jdoe/data/a.json", "/Root/jdoe/data/README",
            "/Root/jdoe/data/a.xsd.bak"}) {
            assertThatThrownBy(() -> RepositoryMetadata.forNewFile(path))
                .as(path)
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.error().message()).contains(path, ".xsd", ".xsl", ".xslt",
                        ".wsdl", ".xml");
                });
        }
    }
}
