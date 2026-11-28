package de.dadecker.inubit.mcp.adapter.cli.v61;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.cli.v74.V74Profile;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Feature 025 T032 (spec FR-024, FR-028): the {@code tag} refusal of the StartCLI query rules
 * names the profile's release; {@link V61QueryRules#INSTANCE} keeps the 6.1 texts.
 */
class V61QueryRulesTest {

    private static ProcessQuery withTag() {
        return new ProcessQuery(Set.of(), false, Duration.ofMinutes(60), Optional.empty(),
            Optional.of("release-1"), Optional.empty(), Optional.empty(), 0, 50,
            Instant.parse("2026-11-01T07:00:00Z"), Optional.empty());
    }

    private static ToolError refusal(V61QueryRules rules) {
        ToolErrorException exception =
            catchThrowableOfType(ToolErrorException.class, () -> rules.validate(withTag()));
        assertThat(exception).as("expected a ToolErrorException").isNotNull();
        return exception.error();
    }

    @Test
    void theSixOneRulesRefuseTagNamingSixOne() {
        ToolError error = refusal(V61QueryRules.INSTANCE);

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo(
            "find_processes: INUBIT 6.1 does not support the input tag; nothing was sent");
        assertThat(error.likelyCause())
            .isEqualTo("The process listing of StartCLI 6.1 has no tag column");
        assertThat(error.node()).as("DiagnosisService adds the node").isEmpty();
    }

    @Test
    void theSevenFourRulesRefuseTagNamingSevenFour() {
        ToolError error = refusal(new V61QueryRules(V74Profile.PROFILE));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo(
            "find_processes: INUBIT 7.4 does not support the input tag; nothing was sent");
        assertThat(error.likelyCause())
            .isEqualTo("The process listing of StartCLI 7.4 has no tag column");
        assertThat(error.nextStep()).isEqualTo("Omit tag for this node, or filter by workflow");
        assertThat(error.node()).isEmpty();
    }
}
