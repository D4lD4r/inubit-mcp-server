package de.dadecker.inubit.mcp.adapter.xslt;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Check;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T033 (research D-12, FR-035): well-formedness and XSD validation of workspace documents, with
 * secure processing and resolution inside the workspace only.
 */
class XmlValidationTest {

    @TempDir
    Path root;
    @TempDir
    Path outside;

    private XsdValidator validator;

    @BeforeEach
    void setUp() throws IOException {
        for (String name : List.of("schema.xsd", "valid.xml", "invalid.xml",
            "not-well-formed.xml")) {
            Files.createDirectories(root.resolve("docs"));
            Files.write(root.resolve("docs").resolve(name), ArtifactFixtures.bytes("xslt/"
                + name));
        }
        validator = new XsdValidator(root);
    }

    private List<CheckFinding> validate(String xml, String xsd) {
        return validator.validate(Path.of(xml), Optional.ofNullable(xsd).map(Path::of));
    }

    @Test
    void aDocumentThatIsNotWellFormedNamesItsLine() {
        assertThat(validate("docs/not-well-formed.xml", null)).singleElement()
            .satisfies(finding -> {
                assertThat(finding.code()).isEqualTo("XML_NOT_WELL_FORMED");
                assertThat(finding.check()).isEqualTo(Check.XML);
                assertThat(finding.severity()).isEqualTo(Severity.ERROR);
                assertThat(finding.path()).isEqualTo("docs/not-well-formed.xml");
                assertThat(finding.location()).hasValueSatisfying(l -> assertThat(l)
                    .startsWith("4:"));
            });
        assertThat(validate("docs/not-well-formed.xml", "docs/schema.xsd")).singleElement()
            .satisfies(f -> assertThat(f.code()).isEqualTo("XML_NOT_WELL_FORMED"));
    }

    @Test
    void eachViolationOfTheSchemaIsOneFindingWithItsPosition() {
        List<CheckFinding> findings = validate("docs/invalid.xml", "docs/schema.xsd");

        assertThat(findings).extracting(CheckFinding::code)
            .containsExactly("XSD_INVALID", "XSD_INVALID");
        assertThat(findings).allMatch(f -> f.check() == Check.XSD
            && f.severity() == Severity.ERROR && f.path().equals("docs/invalid.xml"));
        assertThat(findings).extracting(f -> f.location().orElseThrow().split(":")[0])
            .containsExactly("5", "6");
    }

    @Test
    void aValidDocumentHasNoFinding() {
        assertThat(validate("docs/valid.xml", "docs/schema.xsd")).isEmpty();
        assertThat(validate("docs/valid.xml", null)).isEmpty();
    }

    @Test
    void nothingOutsideTheWorkspaceIsRead() throws IOException {
        Files.writeString(outside.resolve("other.xsd"), "<xs:schema"
            + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
        Files.writeString(root.resolve("docs/import.xsd"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:include schemaLocation="%s"/>
              <xs:element name="order" type="xs:anyType"/>
            </xs:schema>
            """.formatted(outside.resolve("other.xsd").toUri()));
        Files.writeString(root.resolve("docs/entity.xml"), """
            <?xml version="1.0"?>
            <!DOCTYPE order [<!ENTITY secret SYSTEM "%s">]>
            <order>&secret;</order>
            """.formatted(outside.resolve("other.xsd").toUri()));

        assertThat(validate("docs/valid.xml", "docs/import.xsd")).singleElement()
            .satisfies(finding -> {
                assertThat(finding.code()).isEqualTo("XSD_INVALID");
                assertThat(finding.path()).isEqualTo("docs/import.xsd");
            });
        assertThat(validate("docs/entity.xml", null)).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("XML_NOT_WELL_FORMED");
            assertThat(finding.message()).doesNotContain("xs:schema");
        });
    }
}
