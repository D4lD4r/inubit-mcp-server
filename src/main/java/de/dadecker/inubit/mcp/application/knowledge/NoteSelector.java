package de.dadecker.inubit.mcp.application.knowledge;

import java.util.Objects;

/** Which note to read or write: by its exact name or by the {@code ref} of a hit (research D-9). */
public sealed interface NoteSelector {

    /** The note whose name equals {@code name} after NFC. */
    record ByName(String name) implements NoteSelector {

        public ByName {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("a note name is never empty");
            }
        }
    }

    /** The note a hit of the current server process referred to ({@link NoteRefs}). */
    record ByRef(String ref) implements NoteSelector {

        public ByRef {
            Objects.requireNonNull(ref, "ref");
        }
    }
}
