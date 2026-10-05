package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class PageTest {

    @Test
    void holdsAllFields() {
        Page<String> page = new Page<>(List.of("a", "b"), 10, 2, OptionalLong.of(20), false, false,
            OptionalInt.of(12));

        assertThat(page.items()).containsExactly("a", "b");
        assertThat(page.offset()).isEqualTo(10);
        assertThat(page.limit()).isEqualTo(2);
        assertThat(page.total()).hasValue(20);
        assertThat(page.totalIsLowerBound()).isFalse();
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextOffset()).hasValue(12);
    }

    @Test
    void itemsAreAnImmutableCopy() {
        List<String> source = new ArrayList<>(List.of("a"));
        Page<String> page = new Page<>(source, 0, 5, OptionalLong.of(1), false, false,
            OptionalInt.empty());

        source.add("b");

        assertThat(page.items()).containsExactly("a");
        assertThatThrownBy(() -> page.items().add("c"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unknownTotalRequiresTheLowerBoundFlag() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Page<>(List.of(), 0, 5,
            OptionalLong.empty(), false, false, OptionalInt.empty()));

        Page<String> page = new Page<>(List.of(), 0, 5, OptionalLong.empty(), true, false,
            OptionalInt.empty());
        assertThat(page.total()).isEmpty();
    }

    @Test
    void rejectsInvalidBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Page<>(List.of(), -1, 5,
            OptionalLong.of(0), false, false, OptionalInt.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Page<>(List.of(), 0, 0,
            OptionalLong.of(0), false, false, OptionalInt.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Page<>(List.of("a", "b"), 0, 1,
            OptionalLong.of(2), false, false, OptionalInt.empty()));
    }

    @Test
    void asTruncatedReturnsAFlaggedCopy() {
        Page<String> page = new Page<>(List.of("a"), 0, 5, OptionalLong.of(1), false, false,
            OptionalInt.empty());

        assertThat(page.asTruncated().truncated()).isTrue();
        assertThat(page.truncated()).isFalse();
    }
}
