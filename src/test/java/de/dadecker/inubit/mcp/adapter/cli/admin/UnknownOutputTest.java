package de.dadecker.inubit.mcp.adapter.cli.admin;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T018 (feature 022, spec edge case "a command prints a text the parser does not know"): the
 * error of every settings parser for an unknown answer, with a masked excerpt of at most 500
 * characters.
 */
class UnknownOutputTest {

    private static final String PLANTED = "synthetic-secret-0181";

    @Test
    void theErrorNamesTheCommandAndTheExpectedForm() {
        ToolError error = UnknownOutput.of("date", "the assumed form X",
            List.of("Unknown command: date")).error();

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("date");
        assertThat(error.likelyCause()).contains("the assumed form X");
        assertThat(error.excerpt()).contains("Unknown command: date");
    }

    @Test
    void registeredSecretsAndSecretNamedValuesAreMaskedInTheExcerpt() {
        SecretScrubber.global().register(PLANTED);

        String excerpt = UnknownOutput.of("date", "X", List.of("token " + PLANTED,
            "password=synthetic-secret-0182")).error().excerpt().orElseThrow();

        assertThat(excerpt).doesNotContain(PLANTED, "synthetic-secret-0182")
            .contains(SecretScrubber.MASK);
    }

    @Test
    void theExcerptIsAtMost500Characters() {
        List<String> block = List.of("x".repeat(400), "y".repeat(400));

        assertThat(UnknownOutput.of("date", "X", block).error().excerpt().orElseThrow())
            .hasSize(ToolError.MAX_EXCERPT_LENGTH);
    }
}
