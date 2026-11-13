package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.UserFacts.UserGroups;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T031 (feature 022, US2): the answer of {@code user <login>}, against the recorded cases
 * {@code admin-users-user*} and {@code admin-users-session} (T028: three rows {@code User name},
 * {@code User group}, {@code Super group}, labels padded to 13 characters, values padded with
 * trailing spaces).
 */
class UserGroupsParserTest {

    private static final UserGroups OPS = new UserGroups("OPS", "headoffice");

    @Test
    void theRecordedScriptAnswersGiveGroupAndSuperGroup() {
        assertThat(UserGroupsParser.parse("jdoe", AdminCliFixtures.answer("admin-users-user")))
            .isEqualTo(OPS);
        assertThat(UserGroupsParser.parse("batch-user",
            AdminCliFixtures.answer("admin-users-user-hyphen"))).isEqualTo(OPS);
        assertThat(UserGroupsParser.parse("qa_robot",
            AdminCliFixtures.answer("admin-users-user-underscore"))).isEqualTo(OPS);
    }

    @Test
    void theRecordedSessionBlocksGiveTheSameGroups() {
        List<List<String>> blocks = AdminCliFixtures.sessionBlocks("admin-users-session", 9);

        assertThat(UserGroupsParser.parse("jdoe", blocks.get(0))).isEqualTo(OPS);
        assertThat(UserGroupsParser.parse("batch-user", blocks.get(2))).isEqualTo(OPS);
        assertThat(UserGroupsParser.parse("qa_robot", blocks.get(4))).isEqualTo(OPS);
    }

    @Test
    void groupNamesWithSpacesAreKeptWhole() {
        assertThat(UserGroupsParser.parse("jdoe", List.of("User name    jdoe          ",
            "User group   Ops Team      ", "Super group  Head Office   ")))
            .isEqualTo(new UserGroups("Ops Team", "Head Office"));
    }

    @Test
    void theBlockOfAnotherUserIsNotTaken() {
        assertUnknown(() -> UserGroupsParser.parse("jdoe",
            AdminCliFixtures.answer("admin-users-user-hyphen")));
        assertUnknown(() -> UserGroupsParser.parse("jdo",
            AdminCliFixtures.answer("admin-users-user")));
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        List<String> recorded = AdminCliFixtures.answer("admin-users-user");

        assertUnknown(() -> UserGroupsParser.parse("jdoe", List.of()));
        assertUnknown(() -> UserGroupsParser.parse("jdoe", List.of("Command not found.")));
        // a row missing, an extra row, an unknown label, another order
        assertUnknown(() -> UserGroupsParser.parse("jdoe", recorded.subList(0, 2)));
        assertUnknown(() -> UserGroupsParser.parse("jdoe", List.of(recorded.get(0),
            recorded.get(1), recorded.get(2), "Add. groups  QA         ")));
        assertUnknown(() -> UserGroupsParser.parse("jdoe", List.of(recorded.get(0),
            "Main group   OPS        ", recorded.get(2))));
        assertUnknown(() -> UserGroupsParser.parse("jdoe", List.of(recorded.get(0),
            recorded.get(2), recorded.get(1))));
        // a row without a value
        assertUnknown(() -> UserGroupsParser.parse("jdoe", List.of(recorded.get(0),
            recorded.get(1), "Super group  ")));
    }

    @Test
    void theExcerptShowsTheRecordedRowsButNoValueOfAnotherRow() {
        String excerpt = assertUnknown(() -> UserGroupsParser.parse("jdoe", List.of(
            "User name    jdoe                        ",
            "User group   OPS                         ",
            "Super group  headoffice                  ",
            "E-mail       petra.planted@planted.example.test",
            "Full name    Planted, Petra              ",
            "petra.planted@planted.example.test"))).excerpt().orElseThrow();

        assertThat(excerpt).contains("User name    jdoe", "User group   OPS",
            "Super group  headoffice", "E-mail  " + UnknownOutput.HIDDEN,
            "Full name  " + UnknownOutput.HIDDEN)
            .doesNotContain("Planted", "Petra", "planted.example.test");
    }

    @Test
    void anUnknownLineWithoutAPaddedLabelIsHiddenWhole() {
        assertThat(assertUnknown(() -> UserGroupsParser.parse("jdoe",
            List.of("User jdoe does not exist."))).excerpt()).hasValue(UnknownOutput.HIDDEN);
    }
}
