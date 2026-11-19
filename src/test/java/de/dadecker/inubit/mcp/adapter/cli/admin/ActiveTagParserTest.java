package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.block;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T063 (feature 022, US4, research D-18): the active tag of a user from the answer of
 * {@code user <name>}. The three rows are the recorded form ({@code admin-users-user}; T055
 * recorded them as the whole block for a user without an active tag,
 * {@code admin-tags-user-no-active-tag-session}); RECONSTRUCTED: the row {@code Active tag <tag>}
 * of {@code admin-tags-user-active-tag-reconstructed} is SYNTHETIC until the live check T055 (L-4)
 * records the answer for a user with an active tag.
 */
class ActiveTagParserTest {

    private static final String USER = "User name    jdoe       ";
    private static final String GROUP = "User group   OPS        ";
    private static final String SUPER = "Super group  headoffice ";

    @Test
    void theReconstructedRowGivesTheActiveTag() {
        assertThat(ActiveTagParser.parse("jdoe",
            block("admin-tags-user-active-tag-reconstructed"))).contains("REL-1");
        assertThat(ActiveTagParser.parse("jdoe",
            List.of(USER, GROUP, SUPER, "Active tag   Release 2026-11 ")))
            .contains("Release 2026-11");
    }

    @Test
    void theRecordedAnswerWithoutTheRowNamesNoActiveTag() {
        assertThat(ActiveTagParser.parse("jdoe", AdminCliFixtures.answer("admin-users-user")))
            .isEmpty();
        // T055 (L-4): the recorded session block of a user without an active tag
        assertThat(ActiveTagParser.parse("jdoe",
            AdminCliFixtures.sessionBlocks("admin-tags-user-no-active-tag-session", 1).getFirst()))
            .isEmpty();
        assertThat(ActiveTagParser.parse("jdoe", List.of(USER, GROUP, SUPER,
            "Additional user group  QA"))).isEmpty();
        assertThat(ActiveTagParser.parse("jdoe", List.of(USER, GROUP, SUPER,
            "Additional user group  QA", "Active tag   REL-1"))).contains("REL-1");
    }

    @Test
    void theBlockOfAnotherUserIsNotTaken() {
        assertUnknown(() -> ActiveTagParser.parse("jdo",
            block("admin-tags-user-active-tag-reconstructed")));
        assertUnknown(() -> ActiveTagParser.parse("batch-user",
            AdminCliFixtures.answer("admin-users-user")));
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        assertUnknown(() -> ActiveTagParser.parse("jdoe", List.of()));
        assertUnknown(() -> ActiveTagParser.parse("jdoe", List.of("EXECUTION ERROR")));
        assertUnknown(() -> ActiveTagParser.parse("jdoe", List.of(USER, GROUP, SUPER,
            "Active tag")));
        assertUnknown(() -> ActiveTagParser.parse("jdoe", List.of(USER, GROUP, SUPER,
            "Active tag   REL-1", "Active tag   REL-2")));
        assertUnknown(() -> ActiveTagParser.parse("jdoe", List.of(USER, GROUP, SUPER,
            "Active tags  REL-1")));
    }

    /** Leak check: an unknown row shows only its label, never a value that might be personal. */
    @Test
    void theExcerptShowsTheRecordedRowsAndOnlyTheLabelOfAnUnknownRow() {
        String excerpt = assertUnknown(() -> ActiveTagParser.parse("jdoe", List.of(USER, GROUP,
            SUPER, "Mail address  " + TestSecrets.FREE_TEXT_SECRETS))).toString();

        assertThat(excerpt).contains("User name    jdoe", "Mail address  <hidden>");
        TestSecrets.assertNoLeak(excerpt);
    }
}
