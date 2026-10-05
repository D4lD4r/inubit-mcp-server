package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolErrorTest {

    @Test
    void errorCodesAreExactlyTheDocumentedCatalogue() {
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name)).containsExactly(
            "TARGET_UNKNOWN", "UNREACHABLE", "TIMEOUT", "TLS_ERROR", "AUTH_FAILED",
            "FORBIDDEN", "MAINTENANCE_MODE", "NOT_FOUND", "INVALID_INPUT", "CLI_UNAVAILABLE",
            "UNEXPECTED_RESPONSE", "WRITE_DISABLED", "PRODUCTION_PROTECTED",
            "CONFIRMATION_REQUIRED", "CONFIRMATION_INVALID", "PRECONDITION_FAILED",
            "UNSUPPORTED_VERSION", "NOT_CONFIGURED", "INTERNAL");
    }

    @Test
    void ofCreatesAnErrorWithoutServerAndExcerpt() {
        ToolError error = ToolError.of(ErrorCode.TIMEOUT, "what", "cause", "next");

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).isEqualTo("what");
        assertThat(error.likelyCause()).isEqualTo("cause");
        assertThat(error.nextStep()).isEqualTo("next");
        assertThat(error.node()).isEmpty();
        assertThat(error.excerpt()).isEmpty();
    }

    @Test
    void withNodeAndWithExcerptReturnCopies() {
        ToolError base = ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, "m", "c", "n");
        NodeId server = NodeId.parse("dev/node1");

        ToolError enriched = base.withNode(server).withExcerpt("<html>");

        assertThat(enriched.node()).contains(server);
        assertThat(enriched.excerpt()).contains("<html>");
        assertThat(base.node()).isEmpty();
    }

    @Test
    void excerptIsTruncatedTo500Characters() {
        String longText = "x".repeat(600);

        ToolError error = ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, "m", "c", "n")
            .withExcerpt(longText);

        assertThat(ToolError.MAX_EXCERPT_LENGTH).isEqualTo(500);
        assertThat(error.excerpt()).hasValueSatisfying(e -> assertThat(e).hasSize(500));
    }

    @Test
    void excerptOfExactly500CharactersIsKept() {
        String text = "y".repeat(500);

        ToolError error = new ToolError(ErrorCode.INTERNAL, "m", "c", "n", Optional.empty(),
            Optional.of(text));

        assertThat(error.excerpt()).contains(text);
    }

    @Test
    void excerptTruncationDoesNotSplitASurrogatePair() {
        String text = "a".repeat(499) + "😀" + "tail";

        ToolError error = ToolError.of(ErrorCode.INTERNAL, "m", "c", "n").withExcerpt(text);

        assertThat(error.excerpt()).hasValueSatisfying(e -> {
            assertThat(e).hasSizeLessThanOrEqualTo(500);
            assertThat(Character.isHighSurrogate(e.charAt(e.length() - 1))).isFalse();
        });
    }

    @Test
    void nullOptionalsAreNormalisedToEmpty() {
        ToolError error = new ToolError(ErrorCode.INTERNAL, "m", "c", "n", null, null);

        assertThat(error.node()).isEmpty();
        assertThat(error.excerpt()).isEmpty();
    }

    @Test
    void requiredFieldsMustNotBeNull() {
        assertThatNullPointerException().isThrownBy(() -> ToolError.of(null, "m", "c", "n"));
        assertThatNullPointerException()
            .isThrownBy(() -> ToolError.of(ErrorCode.INTERNAL, null, "c", "n"));
        assertThatNullPointerException()
            .isThrownBy(() -> ToolError.of(ErrorCode.INTERNAL, "m", null, "n"));
        assertThatNullPointerException()
            .isThrownBy(() -> ToolError.of(ErrorCode.INTERNAL, "m", "c", null));
    }

    @Test
    void toolErrorExceptionCarriesTheErrorAndUsesItsMessage() {
        ToolError error = ToolError.of(ErrorCode.NOT_FOUND, "Process 4711 not found", "c", "n");

        ToolErrorException exception = new ToolErrorException(error);

        assertThat(exception.error()).isSameAs(error);
        assertThat(exception).hasMessage("NOT_FOUND: Process 4711 not found");
    }
}
