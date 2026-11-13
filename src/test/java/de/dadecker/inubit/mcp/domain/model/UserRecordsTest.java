package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserRecordsTest {

    private static final String PHONE = "+49 30 5550123";

    @Test
    void aPersonalFieldIsShownOrMasked() {
        PersonalField phone = PersonalField.shown("phone", PHONE);
        PersonalField hash = PersonalField.masked("passwordHash");

        assertThat(phone.value()).contains(PHONE);
        assertThat(phone.masked()).isFalse();
        assertThat(hash.value()).isEmpty();
        assertThat(hash.masked()).isTrue();
        assertThatNullPointerException()
            .isThrownBy(() -> new PersonalField(null, Optional.empty()));
    }

    @Test
    void theStringFormOfAPersonalFieldHasNoValue() {
        PersonalField phone = PersonalField.shown("phone", PHONE);

        assertThat(phone.toString()).contains("phone").doesNotContain(PHONE);
        assertThat(PersonalField.masked("passwordHash").toString()).contains("masked");
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
