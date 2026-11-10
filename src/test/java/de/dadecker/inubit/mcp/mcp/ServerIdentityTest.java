package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.UpdateCache;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeAnnouncement;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import de.dadecker.inubit.mcp.domain.model.ServerUpdate;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * 002-T015: the profile in the MCP {@code initialize} result (FR-003, research D-5 and spike
 * T-S1): {@code serverInfo.name} stays neutral, {@code serverInfo.title} names the profile, and
 * the server {@code instructions} name the profile, its description and the terminology.
 * 019-T023 (contracts/mcp-tools-delta.md, research D-8): the knowledge paragraph follows the
 * profile paragraph, is not rendered as a template, and precedes the update notice of 016.
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

    private static McpServerFactory factory(ProfileInfo profile, UpdateNotice notice,
        String knowledge) {
        return new McpServerFactory("0.0.0-test", profile, List.of(),
            SchemaResources.forClasspath(), new ResultMapper(new SecretScrubber()), notice,
            knowledge);
    }

    private static UpdateNotice newerRelease() {
        ServerUpdate update = ServerUpdate.of(SemVer.parse("0.8.0").orElseThrow(),
            SemVer.parse("0.9.0").orElseThrow(), Instant.parse("2026-11-06T12:00:00Z"));
        return new UpdateNotice(() -> Optional.of(update), UpdateCache.NONE, Runnable::run);
    }

    @Test
    void theKnowledgeParagraphFollowsTheProfileUnrenderedAndPrecedesTheUpdateNotice() {
        ProfileInfo profile = new ProfileInfo("acme", Optional.empty(), CUSTOM, "INUBIT_ACME");
        String knowledge = "Local knowledge: …\nNotes (newest first):\n- \"{node}\" — \"x\"";
        String profileOnly = factory(profile, UpdateNotice.NONE, "").instructions();
        UpdateNotice notice = newerRelease();
        String sentence = notice.instructionsSentence().orElseThrow();

        String instructions = factory(profile, notice, knowledge).instructions();

        assertThat(instructions)
            .isEqualTo(profileOnly + "\n\n" + knowledge + "\n\n" + sentence);
        assertThat(factory(profile, UpdateNotice.NONE, knowledge).instructions())
            .isEqualTo(profileOnly + "\n\n" + knowledge);
        assertThat(factory(profile, notice, "").instructions())
            .as("016 unchanged without a paragraph").isEqualTo(profileOnly + " " + sentence);
    }

    @Test
    void theKnowledgeParagraphUsesNoLevelWordOfTheDefaultTerminology() {
        ProfileInfo profile = new ProfileInfo("acme", Optional.empty(), CUSTOM, "INUBIT_ACME");
        Iterator<KnowledgeAnnouncement.Entry> none = List.<KnowledgeAnnouncement.Entry>of()
            .iterator();
        for (KnowledgeAnnouncement.State state : List.of(new KnowledgeAnnouncement.Empty(),
            new KnowledgeAnnouncement.Unusable("not a directory"),
            new KnowledgeAnnouncement.NotReady(2, List.of("dev/node1 (store: waiting)")),
            new KnowledgeAnnouncement.Listed(0, none))) {
            for (boolean recording : new boolean[] {true, false}) {
                String instructions = factory(profile, UpdateNotice.NONE,
                    KnowledgeAnnouncement.paragraph(recording, state)).instructions();

                assertThat(instructions.replaceAll("`[^`]*`", "").replace("MCP server", ""))
                    .as("%s, recording %s", state, recording)
                    .doesNotContainPattern("(?i)\\b(stages?|servers?|groups?|nodes?)\\b");
            }
        }
    }
}
