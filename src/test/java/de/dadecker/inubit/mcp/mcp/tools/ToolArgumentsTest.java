package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Review D2: integer arguments are never silently wrapped into the int range. */
class ToolArgumentsTest {

    @Test
    void integersOutsideTheIntRangeAreInvalidInput() {
        for (Object value : List.of(4_294_967_296L, -4_294_967_296L,
            BigInteger.TWO.pow(70), 2.5)) {
            ToolArguments arguments = new ToolArguments(Map.of("offset", value));

            assertThatThrownBy(() -> arguments.integer("offset", 0))
                .as(String.valueOf(value))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(e.error().message()).contains("offset");
                });
        }
    }

    @Test
    void integersInRangeAndDefaultsAreRead() {
        ToolArguments arguments = new ToolArguments(Map.of("offset", 9_999L, "limit", 5));

        assertThat(arguments.integer("offset", 0)).isEqualTo(9_999);
        assertThat(arguments.integer("limit", 50)).isEqualTo(5);
        assertThat(arguments.integer("missing", 50)).isEqualTo(50);
        assertThat(arguments.optionalInteger("missing")).isEmpty();
    }
}
