package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The error of a parser in this package for a block it does not know. */
final class UnknownOutput {

    /**
     * What {@link #labelsOnly} and {@link #paddedLabelsOnly} show in place of a value or of a line
     * without a label.
     */
    static final String HIDDEN = "<hidden>";

    /** A leading label: letters and spaces up to the first colon. */
    private static final Pattern LABEL = Pattern.compile("([A-Za-z][A-Za-z ]{0,29}):.*");
    /**
     * A leading padded label: words of letters, dots and hyphens, separated by single spaces,
     * then two or more spaces before the value.
     */
    private static final Pattern PADDED_LABEL = Pattern.compile(
        "([A-Za-z][A-Za-z.-]{0,29}(?: [A-Za-z.-]{1,29}){0,3}) {2,}\\S.*");

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
        return error(command, expected, masked(String.join("\n", block)));
    }

    /**
     * As {@link #of}, for a table of padded labels whose known rows hold no personal value but
     * whose other rows might (FR-011): a line that matches {@code known} (after stripping) is
     * shown, masked as in {@link #of}; of any other line only its padded label is kept
     * ({@code E-mail  <hidden>}), a line without one becomes {@value #HIDDEN}. The label is the
     * leading words before two or more spaces, so a line whose first column is itself a value (a
     * name) would keep it; this suits label/value tables such as {@code user}, not other tables.
     */
    static ToolErrorException paddedLabelsOnly(String command, String expected,
        List<String> block, Pattern known) {
        return error(command, expected, String.join("\n", block.stream().map(line -> {
            if (known.matcher(line.strip()).matches()) {
                return masked(line);
            }
            Matcher matcher = PADDED_LABEL.matcher(line.strip());
            return matcher.matches() ? matcher.group(1) + "  " + HIDDEN : HIDDEN;
        }).toList()));
    }

    private static String masked(String text) {
        return SecretTextMasker.mask(SecretScrubber.global().scrub(text));
    }

    /**
     * As {@link #of}, for a block that holds personal values (FR-011): the excerpt keeps of each
     * line only its leading label ({@code Email: <hidden>}), a line without one becomes
     * {@value #HIDDEN}.
     */
    static ToolErrorException labelsOnly(String command, String expected, List<String> block) {
        return error(command, expected, String.join("\n", block.stream().map(line -> {
            Matcher matcher = LABEL.matcher(line.strip());
            return matcher.matches() ? matcher.group(1) + ": " + HIDDEN : HIDDEN;
        }).toList()));
    }

    private static ToolErrorException error(String command, String expected, String excerpt) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "StartCLI answered " + command + " in an unknown format",
            "StartCLI's output differs from " + expected,
            "See the excerpt; check the INUBIT client version (cliHome) and the INUBIT system log")
            .withExcerpt(excerpt));
    }
}
