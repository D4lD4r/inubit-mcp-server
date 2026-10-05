package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * 002-T015: the profile in the MCP {@code initialize} result (FR-003, research D-5 and spike
 * T-S1): {@code serverInfo.name} stays neutral, {@code serverInfo.title} names the profile, and
 * the server {@code instructions} name the profile, its description and the terminology.
 */
@Timeout(60)
class ServerIdentityTest {

    private static final Terminology CUSTOM =
        new Terminology("Umgebung", "Umgebungen", "Knoten", "Knoten");

    private static JsonNode initialize(ProfileInfo profile) {
        try (McpTestClient client = McpTestClient.start(profile, List.of())) {
            return client.initialize();
        }
    }

    @Test
    void theServerInfoKeepsItsNameAndCarriesTheProfileInItsTitle() {
        JsonNode serverInfo = initialize(new ProfileInfo("acme", Optional.of("ACME test"),
            CUSTOM, "INUBIT_ACME")).path("serverInfo");

        assertThat(serverInfo.path("name").asString()).isEqualTo("inubit-mcp-server");
        assertThat(serverInfo.path("title").asString()).isEqualTo("INUBIT MCP – acme");
        assertThat(serverInfo.path("version").asString()).isEqualTo("0.0.0-test");
    }

    @Test
    void theInstructionsNameTheProfileItsDescriptionAndTheTerminology() {
        String instructions = initialize(new ProfileInfo("acme", Optional.of("ACME test"),
            CUSTOM, "INUBIT_ACME")).path("instructions").asString();

        assertThat(instructions).contains("profile acme (ACME test)",
            "The targets are Umgebungen and their Knoten.", "Knoten ids have the form",
            "`group`", "`node`", "list_nodes", "`<Umgebung>/<Knoten>`",
            "write tools (if offered)");
        assertThat(instructions.replaceAll("`[^`]*`", "").replace("MCP server", ""))
            .as("level words outside code spans")
            .doesNotContainPattern("(?i)\\b(stages?|servers?|groups?|nodes?)\\b");
        assertThat(instructions).doesNotContain("{", "INUBIT_ACME");
    }

    @Test
    void withoutDescriptionTheInstructionsNameTheProfileOnly() {
        String instructions = initialize(new ProfileInfo("globex", Optional.empty(),
            Terminology.DEFAULT, "INUBIT_GLOBEX")).path("instructions").asString();

        assertThat(instructions).contains("profile globex.",
            "The targets are groups and their nodes.", "Node ids have the form `<group>/<node>`")
            .doesNotContain("()", "globex (");
    }

    @Test
    void theDescriptionIsInsertedAsGivenAndNeverRenderedAsATemplate() {
        String instructions = initialize(new ProfileInfo("acme", Optional.of("{stage} and {node}"),
            CUSTOM, "INUBIT_ACME")).path("instructions").asString();

        assertThat(instructions).startsWith("INUBIT systems of profile acme ({stage} and {node}).");
    }

    @Test
    void theTitleNamesEachProfile() {
        assertThat(initialize(new ProfileInfo("globex", Optional.empty(), Terminology.DEFAULT,
            "INUBIT_GLOBEX")).path("serverInfo").path("title").asString())
            .isEqualTo("INUBIT MCP – globex");
    }
}
