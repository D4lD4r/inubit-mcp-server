package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One library file as {@code library --list --type driver|plugin} lists it (data-model.md →
 * LibraryEntry, US3, research D-21).
 *
 * @param file the file name, one line of the answer
 * @param type {@code driver} or {@code plugin}
 */
public record LibraryEntry(String file, String type) {

    private static final Set<String> TYPES = Set.of("driver", "plugin");

    public LibraryEntry {
        Objects.requireNonNull(file, "file");
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("a library type is driver or plugin: " + type);
        }
    }

    /**
     * The libraries of one answer.
     *
     * @param entries           the files in the answer's order
     * @param rowFormatVerified false while only the empty answer is recorded and each line is
     *                          read as one file name (L-21)
     */
    public record Listing(List<LibraryEntry> entries, boolean rowFormatVerified) {

        public Listing {
            entries = List.copyOf(entries);
        }
    }
}
