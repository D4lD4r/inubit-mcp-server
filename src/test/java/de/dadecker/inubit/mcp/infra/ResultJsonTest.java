package de.dadecker.inubit.mcp.infra;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.management.Difference;
import de.dadecker.inubit.mcp.domain.model.management.ElementEntry;
import de.dadecker.inubit.mcp.domain.model.management.LinkedModule;
import de.dadecker.inubit.mcp.domain.model.management.ManagementSection;
import de.dadecker.inubit.mcp.domain.model.management.ManagementSection.Status;
import de.dadecker.inubit.mcp.domain.model.management.ManagingElement;
import de.dadecker.inubit.mcp.domain.model.management.Reason;
import de.dadecker.inubit.mcp.domain.model.management.SystemDiagramManagement;
import de.dadecker.inubit.mcp.domain.model.management.Unreadable;
import de.dadecker.inubit.mcp.domain.model.management.Unreadable.Kind;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * T006 (feature 026, research D-10): the JSON of the management check, compared with the examples
 * of contracts/management-section.md §1 and §2.
 */
class ResultJsonTest {

    private static final Optional<Instant> AT = Optional.of(Instant.parse("2026-11-28T08:00:00Z"));
    private static final DiagramRef SD = new DiagramRef("SD-HTTP", "GRP-CONNECT");
    private static final String NEXT_STEP =
        "check the system diagram with get_inventory_item, then retry with refresh: true";

    @Test
    void aManagedSectionIsTheExampleOfSection1() {
        ManagingElement element = new ManagingElement(SD, "Central-Backend",
            Optional.of("HTTP Connector"),
            List.of(Property.shown("ServerName", "String", "https://central.example.test", false),
                Property.shown("Login", "String", "", false),
                Property.masked("Password", "Password")),
            List.of(new Difference("ServerName", "https://central.example.test",
                "http://localhost:8000/dummy")),
            Optional.empty());
        ManagementSection section = new ManagementSection(Status.MANAGED, true,
            Optional.of("OWNERS"), AT, 3, 0, Optional.empty(), List.of(element), List.of(),
            Optional.empty(), false);

        assertThat(tree(section)).isEqualTo(json("""
            {
              "status": "managed",
              "complete": true,
              "owner": "OWNERS",
              "collectedAt": "2026-11-28T08:00:00Z",
              "systemDiagramsChecked": 3,
              "systemDiagramsNotRead": 0,
              "elements": [
                {"systemDiagram": {"name": "SD-HTTP", "diagramGroup": "GRP-CONNECT"},
                 "element": "Central-Backend",
                 "connectorType": "HTTP Connector",
                 "settings": [
                   {"name": "ServerName", "type": "String", "value": "https://central.example.test"},
                   {"name": "Login", "type": "String", "value": ""},
                   {"name": "Password", "type": "Password", "masked": true}],
                 "differing": [
                   {"name": "ServerName", "central": "https://central.example.test",
                    "module": "http://localhost:8000/dummy"}]}],
              "warnings": [],
              "truncated": false
            }
            """));
    }

    @Test
    void aManagingElementWithoutDifferencesHasNoDifferingButKeepsEmptySettings() {
        ManagingElement element = new ManagingElement(SD, "Central-Backend", Optional.empty(),
            List.of(), List.of(), Optional.empty());

        assertThat(tree(element)).isEqualTo(json("""
            {"systemDiagram": {"name": "SD-HTTP", "diagramGroup": "GRP-CONNECT"},
             "element": "Central-Backend", "settings": []}
            """));
    }

    @Test
    void aNotManagedSectionHasNoElements() {
        ManagementSection section = new ManagementSection(Status.NOT_MANAGED, true,
            Optional.of("OWNERS"), AT, 3, 0, Optional.empty(), List.of(), List.of(),
            Optional.empty(), false);

        assertThat(tree(section)).isEqualTo(json("""
            {"status": "not_managed", "complete": true, "owner": "OWNERS",
             "collectedAt": "2026-11-28T08:00:00Z", "systemDiagramsChecked": 3,
             "systemDiagramsNotRead": 0, "warnings": [], "truncated": false}
            """));
    }

    @Test
    void anUnknownSectionHasNoElementsAndNamesAnUnreadableElementWithItsNodeId() {
        Unreadable notRead = new Unreadable(Kind.ELEMENT, Optional.of("Second-Backend"),
            Optional.of(SD), Optional.of("202"), ErrorCode.UNEXPECTED_RESPONSE, "bad response");
        ManagementSection section = new ManagementSection(Status.UNKNOWN, false,
            Optional.of("OWNERS"), AT, 2, 1, Optional.of(notRead), List.of(),
            List.of("incomplete: further management cannot be ruled out"),
            Optional.of(new Reason(ErrorCode.UNEXPECTED_RESPONSE, "bad response", NEXT_STEP)),
            false);

        assertThat(tree(section)).isEqualTo(json("""
            {"status": "unknown", "complete": false, "owner": "OWNERS",
             "collectedAt": "2026-11-28T08:00:00Z", "systemDiagramsChecked": 2,
             "systemDiagramsNotRead": 1,
             "firstNotRead": {"kind": "element", "name": "Second-Backend",
               "systemDiagram": {"name": "SD-HTTP", "diagramGroup": "GRP-CONNECT"},
               "nodeId": "202", "code": "UNEXPECTED_RESPONSE", "message": "bad response"},
             "warnings": ["incomplete: further management cannot be ruled out"],
             "reason": {"code": "UNEXPECTED_RESPONSE", "message": "bad response",
               "nextStep": "%s"},
             "truncated": false}
            """.formatted(NEXT_STEP)));
    }

    @Test
    void theOtherUnreadableKindsAreDiagramListAndSystemDiagram() {
        Unreadable list = new Unreadable(Kind.DIAGRAM_LIST, Optional.empty(), Optional.empty(),
            Optional.empty(), ErrorCode.UNREACHABLE, "connection refused");
        Unreadable diagram = new Unreadable(Kind.SYSTEM_DIAGRAM, Optional.of("SD-FTP"),
            Optional.empty(), Optional.empty(), ErrorCode.TIMEOUT, "read timed out");

        assertThat(tree(list)).isEqualTo(json("""
            {"kind": "diagramList", "code": "UNREACHABLE", "message": "connection refused"}
            """));
        assertThat(tree(diagram)).isEqualTo(json("""
            {"kind": "systemDiagram", "name": "SD-FTP", "code": "TIMEOUT",
             "message": "read timed out"}
            """));
    }

    @Test
    void aSystemDiagramManagementIsTheExampleOfSection2() {
        ElementEntry readable = new ElementEntry("Central-Backend", "201",
            Optional.of("HTTP Connector"),
            Optional.of(List.of(
                Property.shown("ServerName", "String", "https://central.example.test", false))),
            Optional.of(List.of(new LinkedModule("Demo-HTTP-Out-A", Optional.of(true)),
                new LinkedModule("Demo-HTTP-Out-B", Optional.of(true)))),
            Optional.empty());
        ElementEntry failed = new ElementEntry("Second-Backend", "202", Optional.empty(),
            Optional.empty(), Optional.empty(),
            Optional.of(new Reason(ErrorCode.UNEXPECTED_RESPONSE, "…", NEXT_STEP)));
        SystemDiagramManagement management = new SystemDiagramManagement(
            List.of(readable, failed), Optional.empty(), Optional.empty(), AT, List.of(), false);

        assertThat(tree(management)).isEqualTo(json("""
            {
              "collectedAt": "2026-11-28T08:00:00Z",
              "elements": [
                {"element": "Central-Backend", "nodeId": "201", "connectorType": "HTTP Connector",
                 "settings": [{"name": "ServerName", "type": "String", "value": "https://central.example.test"}],
                 "linked": [{"name": "Demo-HTTP-Out-A", "found": true},
                            {"name": "Demo-HTTP-Out-B", "found": true}]},
                {"element": "Second-Backend", "nodeId": "202",
                 "error": {"code": "UNEXPECTED_RESPONSE", "message": "…",
                           "nextStep": "check the system diagram with get_inventory_item, then retry with refresh: true"}}],
              "warnings": [],
              "truncated": false
            }
            """));
    }

    @Test
    void aReadableElementWithoutLinkedModulesHasEmptyLinked() {
        ElementEntry entry = new ElementEntry("Cloud-Backend", "203", Optional.empty(),
            Optional.of(List.of()), Optional.of(List.of()), Optional.empty());

        assertThat(tree(entry)).isEqualTo(json("""
            {"element": "Cloud-Backend", "nodeId": "203", "settings": [], "linked": []}
            """));
    }

    @Test
    void aModuleDetailPropertyIsWrittenLikeReadModelDetails() throws Exception {
        List<Property> properties = List.of(
            Property.shown("ServerName", "String", "https://central.example.test", false),
            Property.shown("Body", "String", "cut", true),
            Property.masked("Password", "Password"),
            Property.entries("ResourceSettings", "Map", List.of(
                Property.shown("Login", "String", "user", false),
                Property.masked("Secret", "Password"))));

        for (Property property : properties) {
            assertThat(tree(property)).isEqualTo(tree(readModelDetailsProperty(property)));
        }
        assertThat(tree(properties.get(1))).isEqualTo(json("""
            {"name": "Body", "type": "String", "value": "cut", "truncated": true}
            """));
    }

    /**
     * {@code ReadModelDetailsTool.Property.of(property)}; the record is package-private in
     * {@code mcp.tools}, hence the reflection.
     */
    private static Object readModelDetailsProperty(Property property) throws Exception {
        Method of = Class.forName("de.dadecker.inubit.mcp.mcp.tools.ReadModelDetailsTool$Property")
            .getDeclaredMethod("of", Property.class);
        of.setAccessible(true);
        return of.invoke(null, property);
    }

    private static JsonNode tree(Object value) {
        return ResultJson.mapper().readTree(ResultJson.mapper().writeValueAsString(value));
    }

    private static JsonNode json(String text) {
        return ResultJson.mapper().readTree(text);
    }
}
