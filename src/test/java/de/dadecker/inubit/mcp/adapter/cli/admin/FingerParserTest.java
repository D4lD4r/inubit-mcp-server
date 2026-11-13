package de.dadecker.inubit.mcp.adapter.cli.admin;

import static de.dadecker.inubit.mcp.adapter.cli.admin.AdminCliFixtures.assertUnknown;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.admin.FingerParser.Finger;
import de.dadecker.inubit.mcp.domain.model.UserDetail.PersonalField;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T031 (feature 022, US2, FR-011): the answer of {@code finger <login>}, against the recorded
 * cases {@code admin-users-finger*} and {@code admin-users-session} (T028: {@code Login: } with the
 * login padded to 15 characters and {@code Name: <last>, <first>} only for a user with both names,
 * then {@code Email: } and one {@code Role: } line; no account state).
 */
class FingerParserTest {

    private static final String PLANTED_NAME = "Planted, Petra";
    private static final String PLANTED_EMAIL = "petra.planted@planted.example.test";

    private static List<String> answer(String loginLine, String... rest) {
        return Stream.concat(Stream.of(loginLine), Arrays.stream(rest)).toList();
    }

    @Test
    void theRecordedAnswerGivesRoleEmailAndTheNameAsPersonalField() {
        Finger finger = FingerParser.parse("jdoe", AdminCliFixtures.answer("admin-users-finger"));

        assertThat(finger.login()).isEqualTo("jdoe");
        assertThat(finger.roles()).containsExactly("Platform Administrator");
        assertThat(finger.email()).contains("john.doe@example.test");
        assertThat(finger.personal()).containsExactly(PersonalField.shown("Name", "Doe, John"));
    }

    @Test
    void aUserWithoutNamesHasNoPersonalField() {
        Finger finger = FingerParser.parse("qa_robot",
            AdminCliFixtures.answer("admin-users-finger-no-name"));

        assertThat(finger.roles()).containsExactly("XYZ_operator");
        assertThat(finger.email()).contains("qa.robot+qa_inubit@corp.example.test");
        assertThat(finger.personal()).isEmpty();
    }

    @Test
    void theRecordedSessionBlocksGiveTheSameFields() {
        List<List<String>> blocks = AdminCliFixtures.sessionBlocks("admin-users-session", 9);

        assertThat(FingerParser.parse("jdoe", blocks.get(1)))
            .isEqualTo(FingerParser.parse("jdoe", AdminCliFixtures.answer("admin-users-finger")));
        assertThat(FingerParser.parse("batch-user", blocks.get(3))).isEqualTo(
            FingerParser.parse("batch-user", AdminCliFixtures.answer("admin-users-finger-hyphen")));
        assertThat(FingerParser.parse("qa_robot", blocks.get(5))).isEqualTo(
            FingerParser.parse("qa_robot", AdminCliFixtures.answer("admin-users-finger-no-name")));
        assertThat(FingerParser.parse("batch-user", blocks.get(3)).personal())
            .containsExactly(PersonalField.shown("Name", "Service, Batch"));
    }

    @Test
    void severalRoleLinesGiveSeveralRolesInTheirOrder() {
        // reconstructed: T028 recorded one Role line per user
        Finger finger = FingerParser.parse("jdoe", answer("Login: jdoe", "Email: a@example.test",
            "Role: Platform Administrator", "Role: XYZ_operator"));

        assertThat(finger.roles()).containsExactly("Platform Administrator", "XYZ_operator");
    }

    @Test
    void aLoginOfFifteenOrMoreCharactersMayTouchTheNameLabel() {
        // reconstructed: the login is padded to 15 characters, so a longer one leaves no space
        Finger finger = FingerParser.parse("integration-user", answer(
            "Login: integration-userName: User, Integration", "Email: i@example.test",
            "Role: XYZ_operator"));

        assertThat(finger.personal())
            .containsExactly(PersonalField.shown("Name", "User, Integration"));
    }

    @Test
    void anEmptyEmailIsAbsent() {
        // reconstructed: T028 recorded an e-mail for every user
        assertThat(FingerParser.parse("jdoe", answer("Login: jdoe", "Email: ",
            "Role: XYZ_operator")).email()).isEmpty();
    }

    @Test
    void aNameTheSecretRulesMaskIsMasked() {
        String keyMaterial = "-----BEGIN PRIVATE KEY----- AAAA -----END PRIVATE KEY-----";

        assertThat(FingerParser.parse("jdoe", answer("Login: jdoe           Name: " + keyMaterial,
            "Email: a@example.test", "Role: XYZ_operator")).personal())
            .containsExactly(PersonalField.masked("Name"));
    }

    @Test
    void theBlockOfAnotherUserIsNotTaken() {
        assertUnknown(() -> FingerParser.parse("jdoe",
            AdminCliFixtures.answer("admin-users-finger-hyphen")));
        assertUnknown(() -> FingerParser.parse("jdo",
            AdminCliFixtures.answer("admin-users-finger")));
    }

    @Test
    void anUnknownAnswerIsUnexpectedAndNeverGuessed() {
        String login = "Login: jdoe           Name: " + PLANTED_NAME;
        String email = "Email: " + PLANTED_EMAIL;
        String role = "Role: XYZ_operator";

        assertUnknown(() -> FingerParser.parse("jdoe", List.of()));
        assertUnknown(() -> FingerParser.parse("jdoe", List.of("Command not found.")));
        // a line missing, an extra line, an unknown label, another order
        assertUnknown(() -> FingerParser.parse("jdoe", answer(login, role)));
        assertUnknown(() -> FingerParser.parse("jdoe", answer(login, email)));
        assertUnknown(() -> FingerParser.parse("jdoe", answer(login, email, email, role)));
        assertUnknown(() -> FingerParser.parse("jdoe", answer(login, email, role,
            "Phone: +49 000 0000")));
        assertUnknown(() -> FingerParser.parse("jdoe", answer(login, email, role,
            "Account locked")));
        assertUnknown(() -> FingerParser.parse("jdoe", answer(login, role, email)));
        assertUnknown(() -> FingerParser.parse("jdoe", answer(email, login, role)));
        // another text after the login, an empty name or role
        assertUnknown(() -> FingerParser.parse("jdoe", answer(
            "Login: jdoe           Office: Room 1", email, role)));
        assertUnknown(() -> FingerParser.parse("jdoe", answer("Login: jdoe   Name: ", email,
            role)));
        assertUnknown(() -> FingerParser.parse("jdoe", answer(login, email, "Role: ")));
    }

    @Test
    void theExcerptOfAnUnknownAnswerShowsTheLabelsButNoPersonalValue() {
        String excerpt = assertUnknown(() -> FingerParser.parse("jdoe", List.of(
            "Login: jdoe           Name: " + PLANTED_NAME, "Email: " + PLANTED_EMAIL,
            "Phone: +49 000 0000", "Role: XYZ_operator"))).excerpt().orElseThrow();

        assertThat(excerpt).contains("Login:", "Email:", "Phone:", "Role:")
            .doesNotContain("Planted", "Petra", "planted.example.test", "+49");
    }

    @Test
    void theStringFormNamesNoPersonalValue() {
        Finger finger = FingerParser.parse("jdoe", List.of(
            "Login: jdoe           Name: " + PLANTED_NAME, "Email: " + PLANTED_EMAIL,
            "Role: XYZ_operator"));

        assertThat(finger.toString()).contains("jdoe")
            .doesNotContain("Planted", "Petra", "planted.example.test");
        assertThat(finger.email()).isEqualTo(Optional.of(PLANTED_EMAIL));
    }
}
