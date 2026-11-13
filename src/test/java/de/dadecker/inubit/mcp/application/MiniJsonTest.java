package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 024-T045: the integers and {@code null} the lease store needs, and what stays refused. */
class MiniJsonTest {

    @Test
    void wholeNumbersAndNullAreRead() {
        Map<String, Object> object = MiniJson.parse(
            "{\"a\": 0, \"b\": -7, \"c\": 9223372036854775807, \"d\": null, \"e\": [1, null]}");

        assertThat(object).containsEntry("a", 0L).containsEntry("b", -7L)
            .containsEntry("c", Long.MAX_VALUE).containsEntry("d", null)
            .containsEntry("e", Arrays.asList(1L, null));
        assertThat(object).containsOnlyKeys("a", "b", "c", "d", "e");
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "-", "-01", "nul", "nulls", "9223372036854775808", "1.5",
        "1e3", "1E3", "-0.0", "true", "false", "+1", ""})
    void otherValuesAreRefused(String value) {
        assertThatIllegalArgumentException().isThrownBy(
            () -> MiniJson.parse("{\"a\": " + value + "}"));
    }

    @Test
    void stringsArraysAndObjectsAreReadAsBefore() {
        assertThat(MiniJson.parse("{\"a\": \"x\\n\", \"b\": [\"y\"], \"c\": {}}"))
            .containsEntry("a", "x\n").containsEntry("b", List.of("y"))
            .containsEntry("c", Map.of());
    }
}
