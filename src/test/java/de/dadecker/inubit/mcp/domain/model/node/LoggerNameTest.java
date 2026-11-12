package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Feature 024 (T006, FR-041, research D-11): a logger whose level is raised for a while. */
class LoggerNameTest {

    @ParameterizedTest
    @ValueSource(strings = {"com.inubit.ibis.acme.Orders", "com.inubit.ibis.acme",
        "com.inubit.ibis._x", "com.inubit.ibis.a1.B_2.c3"})
    void acceptsNamesBelowComInubitIbis(String name) {
        assertThat(new LoggerName(name).value()).isEqualTo(name);
        assertThat(LoggerName.isValid(name)).isTrue();
    }

    @Test
    void acceptsAName200CharactersLong() {
        String name = "com.inubit.ibis." + "a".repeat(200 - "com.inubit.ibis.".length());

        assertThat(new LoggerName(name).value()).hasSize(LoggerName.MAX_CHARS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.inubit.ibis", "com.inubit.ibisx.a", "org.acme.A",
        "com.inubit.ibis.a$B", "com.inubit.ibis..a", "com.inubit.ibis.acme'", "com.inubit.ibis.a b",
        "com.inubit.ibis.\"a\"", "com.inubit.ibis.", "com.inubit.ibis.1a", "Com.inubit.ibis.a",
        "com.inubit.ibis.a.", "com.inubit.ibis.ä", "com.inubit.ibis.a\n", ""})
    void refusesOtherNames(String name) {
        assertThat(LoggerName.isValid(name)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new LoggerName(name))
            .withMessageContaining("com.inubit.ibis");
    }

    @Test
    void refusesANameOver200Characters() {
        String name = "com.inubit.ibis." + "a".repeat(201 - "com.inubit.ibis.".length());

        assertThat(LoggerName.isValid(name)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new LoggerName(name));
    }

    @Test
    void refusesNull() {
        assertThat(LoggerName.isValid(null)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new LoggerName(null));
    }
}
