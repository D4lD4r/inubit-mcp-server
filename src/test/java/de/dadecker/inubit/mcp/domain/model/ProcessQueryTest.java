package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Feature 022 T040a (research D-21): the optional {@code userColumn} of a query is 1 … 5. */
class ProcessQueryTest {

    private static ProcessQuery query(Optional<Integer> userColumn) {
        return new ProcessQuery(Set.of(), false, Duration.ofMinutes(60), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), 0, 50,
            Instant.ofEpochMilli(1_790_861_400_000L), userColumn);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void aUserColumnFromOneToFiveIsAccepted(int column) {
        assertThat(query(Optional.of(column)).userColumn()).contains(column);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1})
    void aUserColumnOutsideOneToFiveIsRefused(int column) {
        assertThatThrownBy(() -> query(Optional.of(column)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("userColumn");
    }

    @Test
    void withoutAUserColumnTheQueryHasNone() {
        assertThat(query(Optional.empty()).userColumn()).isEmpty();
        assertThat(query(null).userColumn()).isEmpty();
    }
}
