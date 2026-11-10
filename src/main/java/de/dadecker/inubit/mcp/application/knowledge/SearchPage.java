package de.dadecker.inubit.mcp.application.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * One page of a listing or search (research D-9).
 *
 * @param total      the number of matching notes
 * @param nextOffset present when more hits follow
 * @param warnings   directory-level warnings, at most 20 lines and a summary line
 */
public record SearchPage(List<Hit> hits, int offset, int limit, int total, OptionalInt nextOffset,
    List<String> warnings) {

    public SearchPage {
        hits = List.copyOf(hits);
        Objects.requireNonNull(nextOffset, "nextOffset");
        warnings = List.copyOf(warnings);
    }
}
