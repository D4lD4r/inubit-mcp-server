package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Feature 025 T044 (FR-030 (b)): the warnings an error carries to its node's entry. */
class ToolErrorExceptionTest {

    private static final ToolError ERROR = ToolError.of(ErrorCode.UNREACHABLE, "m", "c", "n");

    @Test
    void anErrorHasNoWarningsByDefault() {
        assertThat(new ToolErrorException(ERROR).warnings()).isEmpty();
        assertThat(new ContactedException(ERROR).warnings()).isEmpty();
    }

    @Test
    void withWarningsKeepsTheErrorAndTheDetails() {
        ToolErrorException plain = new ToolErrorException(ERROR, "details")
            .withWarnings(List.of("not verified"));

        assertThat(plain.getClass()).isEqualTo(ToolErrorException.class);
        assertThat(plain.error()).isEqualTo(ERROR);
        assertThat(plain.details()).contains("details");
        assertThat(plain.warnings()).containsExactly("not verified");
    }

    @Test
    void aContactedExceptionWithWarningsStaysOne() {
        ToolErrorException contacted = new ContactedException(ERROR)
            .withWarnings(List.of("not verified"));

        assertThat(contacted).isInstanceOf(ContactedException.class);
        assertThat(contacted.error()).isEqualTo(ERROR);
        assertThat(contacted.warnings()).containsExactly("not verified");
    }

    @Test
    void aContactedExceptionKeepsTheDetailsAlsoWithWarnings() {
        ToolErrorException contacted = new ContactedException(ERROR, "details");
        ToolErrorException withWarnings = contacted.withWarnings(List.of("not verified"));

        assertThat(contacted.details()).contains("details");
        assertThat(withWarnings).isInstanceOf(ContactedException.class);
        assertThat(withWarnings.details()).contains("details");
        assertThat(withWarnings.warnings()).containsExactly("not verified");
    }
}
