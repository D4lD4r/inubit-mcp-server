package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.ArrayList;
import java.util.List;

/**
 * The answer of {@code library --list --type driver|plugin} (US3, action {@code libraries};
 * research D-21).
 *
 * <p>FORM as recorded on 8.1.17 (T039; fixtures {@code admin-model-library-driver},
 * {@code admin-model-library-plugin} and the two {@code library} blocks of
 * {@code admin-model-session}): with no library uploaded, script mode prints the preamble and one
 * empty line, a session block is one {@code \r} line. No listed library is recorded, so the row
 * format is unknown (L-21): until a recording shows it, each line that is not blank is read as one
 * file name, and the listing says {@code rowFormatVerified = false}. A line over
 * {@value #MAX_LINE_CHARS} characters or with a control character is not taken for a file name.
 */
public final class LibraryListParser {

    static final int MAX_LINE_CHARS = 255;
    private static final String EXPECTED = "one file name per line (at most " + MAX_LINE_CHARS
        + " characters, no control character); only the empty answer is recorded";

    private LibraryListParser() {
    }

    /**
     * @param type  {@code driver} or {@code plugin}, the {@code --type} of the command
     * @param lines the answer after the client's preamble (script mode) or the session block
     * @return one entry per line that is not blank (blank and {@code \r}-only lines are skipped),
     *     in the answer's order, with {@code rowFormatVerified = false}
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} if a line is longer than
     *     {@value #MAX_LINE_CHARS} characters or holds a control character
     */
    public static LibraryEntry.Listing parse(String type, List<String> lines) {
        List<LibraryEntry> entries = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (line.length() > MAX_LINE_CHARS || line.chars().anyMatch(Character::isISOControl)) {
                throw UnknownOutput.of("library --list --type " + type, EXPECTED, lines);
            }
            entries.add(new LibraryEntry(line, type));
        }
        return new LibraryEntry.Listing(entries, false);
    }
}
