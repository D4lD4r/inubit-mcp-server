package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.List;

/** The error of a parser in this package for a block it does not know. */
final class UnknownOutput {

    private UnknownOutput() {
    }

    /**
     * {@code UNEXPECTED_RESPONSE} with the block as excerpt: registered secrets and secret-named
     * values masked (as {@code HelperFailures} does), cut by {@link ToolError} to
     * {@value ToolError#MAX_EXCERPT_LENGTH} characters.
     *
     * @param command  the StartCLI command that answered
     * @param expected the form the parser expects, for the likely cause
     */
    static ToolErrorException of(String command, String expected, List<String> block) {
        String excerpt = SecretTextMasker.mask(
            SecretScrubber.global().scrub(String.join("\n", block)));
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "StartCLI answered " + command + " in an unknown format",
            "StartCLI's output differs from " + expected,
            "See the excerpt; check the INUBIT client version (cliHome) and the INUBIT system log")
            .withExcerpt(excerpt));
    }
}
