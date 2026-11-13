package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserRecordsTest {

    private static final String EMAIL = "jane.roe@mail.example.test";
    private static final String PHONE = "+49 30 5550123";

    private static UserSummary summary() {
        return new UserSummary("jroe", "Operators", List.of("Auditors"), List.of("Monitor"),
            Optional.of("locked"));
    }

    @Test
    void aUserSummaryHasNoPersonalFieldByConstruction() {
        List<String> components = Arrays.stream(UserSummary.class.getRecordComponents())
            .map(RecordComponent::getName).toList();

        assertThat(components)
            .containsExactly("login", "group", "additionalGroups", "roles", "accountState");
    }

    @Test
    void aUserSummaryKeepsItsListsAsGiven() {
        List<String> roles = new ArrayList<>(List.of("Monitor"));
        UserSummary user = new UserSummary("jroe", "Operators", List.of(), roles, null);
        roles.add("System Administrator");

        assertThat(user.roles()).containsExactly("Monitor");
        assertThat(user.accountState()).isEmpty();
        assertThatNullPointerException().isThrownBy(
            () -> new UserSummary(null, "Operators", List.of(), List.of(), Optional.empty()));
        assertThatNullPointerException().isThrownBy(
            () -> new UserSummary("jroe", null, List.of(), List.of(), Optional.empty()));
    }

    @Test
    void aUserDetailAddsTheEmailAndTheOtherPersonalFields() {
        UserDetail detail = new UserDetail(summary(), Optional.of(EMAIL),
            List.of(UserDetail.PersonalField.shown("phone", PHONE),
                UserDetail.PersonalField.masked("passwordHash")));

        assertThat(detail.summary()).isEqualTo(summary());
        assertThat(detail.email()).contains(EMAIL);
        assertThat(detail.personal()).extracting(UserDetail.PersonalField::name)
            .containsExactly("phone", "passwordHash");
        assertThat(detail.personal()).extracting(UserDetail.PersonalField::masked)
            .containsExactly(false, true);
        assertThat(detail.personal().get(1).value()).isEmpty();
    }

    @Test
    void aUserDetailWithoutEmailHasAnEmptyEmail() {
        UserDetail detail = new UserDetail(summary(), null, List.of());

        assertThat(detail.email()).isEmpty();
        assertThatNullPointerException()
            .isThrownBy(() -> new UserDetail(null, Optional.empty(), List.of()));
    }

    @Test
    void theStringFormOfAUserDetailHasNoPersonalValue() {
        UserDetail.PersonalField phone = UserDetail.PersonalField.shown("phone", PHONE);
        UserDetail detail = new UserDetail(summary(), Optional.of(EMAIL), List.of(phone));

        assertThat(detail.toString()).contains("jroe").doesNotContain(EMAIL)
            .doesNotContain(PHONE);
        assertThat(phone.toString()).contains("phone").doesNotContain(PHONE);
    }

    @Test
    void aLoginEntryMayHaveNoLoginTime() {
        assertThat(new LoginEntry("jroe", null).since()).isEmpty();
        assertThat(new LoginEntry("jroe", Optional.of("2026-11-13 08:15:00")).since())
            .contains("2026-11-13 08:15:00");
        assertThatNullPointerException().isThrownBy(() -> new LoginEntry(null, Optional.empty()));
    }

    @Test
    void aMessageSummaryNeedsEveryField() {
        MessageSummary message = new MessageSummary("17", "admin", "2026-11-13 08:15", "Hello");

        assertThat(message.subject()).isEqualTo("Hello");
        assertThatNullPointerException()
            .isThrownBy(() -> new MessageSummary("17", "admin", "2026-11-13 08:15", null));
    }

    @Test
    void theStringFormOfAMessageSummaryHasNoSubject() {
        MessageSummary message = new MessageSummary("17", "admin", "2026-11-13 08:15",
            "Call " + PHONE);

        assertThat(message.toString()).contains("17").doesNotContain(PHONE);
    }

    @Test
    void aShortMessageTextIsKeptWhole() {
        MessageText text = MessageText.of("17", "Hello");

        assertThat(text.text()).isEqualTo("Hello");
        assertThat(text.truncated()).isFalse();
    }

    @Test
    void aMessageTextOfExactlyTheBoundIsNotTruncated() {
        String body = "x".repeat(ItemBounds.MAX_ITEM_CHARS);

        MessageText text = MessageText.of("17", body);

        assertThat(text.text()).isEqualTo(body);
        assertThat(text.truncated()).isFalse();
    }

    @Test
    void aLongerMessageTextIsCutWithTheMarker() {
        MessageText text = MessageText.of("17", "x".repeat(ItemBounds.MAX_ITEM_CHARS + 1));

        assertThat(text.text()).hasSize(ItemBounds.MAX_ITEM_CHARS)
            .endsWith(ItemBounds.TRUNCATION_MARKER);
        assertThat(text.truncated()).isTrue();
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        int end = ItemBounds.MAX_ITEM_CHARS - ItemBounds.TRUNCATION_MARKER.length();
        String body = "x".repeat(end - 1) + "😀"
            + "x".repeat(ItemBounds.TRUNCATION_MARKER.length());
        assertThat(body).hasSizeGreaterThan(ItemBounds.MAX_ITEM_CHARS);

        MessageText text = MessageText.of("17", body);

        assertThat(text.text()).isEqualTo("x".repeat(end - 1) + ItemBounds.TRUNCATION_MARKER);
        assertThat(text.truncated()).isTrue();
    }

    @Test
    void aMessageTextLongerThanTheBoundIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(
            () -> new MessageText("17", "x".repeat(ItemBounds.MAX_ITEM_CHARS + 1), true));
    }

    @Test
    void theStringFormOfAMessageTextHasNoText() {
        MessageText text = MessageText.of("17", "Call " + PHONE);

        assertThat(text.toString()).contains("17").doesNotContain(PHONE);
    }
}
