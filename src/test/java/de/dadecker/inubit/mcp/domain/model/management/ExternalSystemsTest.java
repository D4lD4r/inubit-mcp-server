package de.dadecker.inubit.mcp.domain.model.management;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * T005: an {@code sdExtSystem} module detail becomes an {@link ExternalSystem}
 * (data-model.md → ExternalSystem).
 */
class ExternalSystemsTest {

    private static final DiagramRef SD = new DiagramRef("SD-HTTP", "GRP-CONNECT");
    private static final ModuleRef ELEMENT = new ModuleRef("Central-Backend", "sdExtSystem", "201");
    private static final Property TYPE = Property.shown("ResourceType", "String",
        "HTTP Connector", false);
    private static final Property SERVER = Property.shown("ServerName", "String",
        "https://central.example.test", false);
    private static final Property PASSWORD = Property.masked("Password", "Password");

    private static ModuleDetail detail(Property... properties) {
        long masked = List.of(properties).stream().mapToLong(Property::maskedCount).sum();
        return new ModuleDetail(SD.name(), ELEMENT.nodeId(), ELEMENT.name(), "sdExtSystem",
            Optional.empty(), List.of(), List.of(properties), (int) masked);
    }

    private static Property linked(Property... modules) {
        return Property.entries("ResourcesLinked", "List", List.of(modules));
    }

    private static Property module(String name) {
        return Property.shown("Module", "String", name, false);
    }

    @Test
    void theThreePropertiesBecomeConnectorTypeSettingsAndLinkedNames() {
        ExternalSystems.Result result = ExternalSystems.from(SD, ELEMENT, detail(TYPE,
            Property.entries("ResourceSettings", "Map", List.of(SERVER, PASSWORD)),
            linked(module("Demo-HTTP-Out-A"), module("Demo-HTTP-Out-B"))));

        assertThat(result.system()).isEqualTo(new ExternalSystem(SD, "Central-Backend", "201",
            Optional.of("HTTP Connector"), List.of(SERVER, PASSWORD),
            List.of("Demo-HTTP-Out-A", "Demo-HTTP-Out-B")));
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void absentPropertiesGiveEmptyValues() {
        ExternalSystems.Result result = ExternalSystems.from(SD, ELEMENT, detail());

        assertThat(result.system().connectorType()).isEmpty();
        assertThat(result.system().settings()).isEmpty();
        assertThat(result.system().linkedNames()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void emptyContainersGiveEmptyLists() {
        ExternalSystems.Result result = ExternalSystems.from(SD, ELEMENT, detail(
            Property.shown("ResourceSettings", "Map", "", false),
            Property.shown("ResourcesLinked", "List", "", false)));

        assertThat(result.system().settings()).isEmpty();
        assertThat(result.system().linkedNames()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void aMaskedResourceTypeGivesNoConnectorType() {
        ExternalSystems.Result result = ExternalSystems.from(SD, ELEMENT,
            detail(Property.masked("ResourceType", "String")));

        assertThat(result.system().connectorType()).isEmpty();
    }

    @Test
    void aMaskedSettingsContainerStaysOneMaskedSetting() {
        Property container = Property.masked("ResourceSettings", "Map");

        ExternalSystems.Result result = ExternalSystems.from(SD, ELEMENT, detail(container));

        assertThat(result.system().settings()).containsExactly(container);
    }

    @Test
    void theNamesAreMatchedExactlyAndOtherPropertiesAreIgnored() {
        ExternalSystems.Result result = ExternalSystems.from(SD, ELEMENT, detail(
            Property.shown("resourcetype", "String", "FTP Connector", false),
            Property.entries("resourceSettings", "Map", List.of(SERVER)),
            Property.entries("ResourcesLinkedOld", "List", List.of(module("Old-Module"))),
            Property.shown("Description", "String", "central backend", false),
            TYPE));

        assertThat(result.system().connectorType()).contains("HTTP Connector");
        assertThat(result.system().settings()).isEmpty();
        assertThat(result.system().linkedNames()).isEmpty();
    }

    @Test
    void aMaskedOrNonStringModuleEntryIsSkippedWithAWarning() {
        ExternalSystems.Result result = ExternalSystems.from(SD, ELEMENT, detail(linked(
            module("Demo-HTTP-Out-A"),
            Property.masked("Module", "String"),
            Property.shown("Module", "Integer", "42", false),
            Property.entries("Module", "Map", List.of(module("Nested"))),
            module("Demo-HTTP-Out-B"))));

        assertThat(result.system().linkedNames())
            .containsExactly("Demo-HTTP-Out-A", "Demo-HTTP-Out-B");
        assertThat(result.warnings()).hasSize(3).allSatisfy(warning -> assertThat(warning)
            .contains("SD-HTTP", "Central-Backend", "201", "ResourcesLinked")
            .doesNotContain("42", "Nested"));
    }

    @Test
    void aMaskedLinkedContainerGivesNoNamesAndAWarning() {
        ExternalSystems.Result result = ExternalSystems.from(SD, ELEMENT,
            detail(Property.masked("ResourcesLinked", "List")));

        assertThat(result.system().linkedNames()).isEmpty();
        assertThat(result.warnings()).singleElement().asString()
            .contains("SD-HTTP", "Central-Backend", "201", "ResourcesLinked");
    }
}
