package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.NoteFile;
import de.dadecker.inubit.mcp.domain.model.NoteName;
import java.util.Objects;
import java.util.Optional;

/**
 * A note as read in one call (feature 019, research D-4, D-9): built per walk, never cached
 * (FR-009).
 *
 * @param file     the listed file, with the size and modification time it had when it was read
 * @param state    whether the text could be read
 * @param text     the whole text, exactly for {@link State#OK}
 * @param metadata its metadata block, {@link NoteMetadata#NONE} unless {@link State#OK}
 * @param problem  why it was not read: the bound or the cause, never a name or content
 */
public record Note(NoteFile file, State state, Optional<String> text, NoteMetadata metadata,
    Optional<String> problem) {

    /** Whether the text of a note could be read. */
    public enum State { OK, TOO_LARGE, UNREADABLE }

    public Note {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(problem, "problem");
        if (text.isPresent() != (state == State.OK) || problem.isPresent() == (state == State.OK)) {
            throw new IllegalArgumentException("a text exactly for OK, a problem otherwise");
        }
    }

    static Note ok(NoteFile file, String text) {
        return new Note(file, State.OK, Optional.of(text), NoteMetadata.parse(text),
            Optional.empty());
    }

    static Note notRead(NoteFile file, State state, String problem) {
        return new Note(file, state, Optional.empty(), NoteMetadata.NONE, Optional.of(problem));
    }

    public NoteName name() {
        return file.name();
    }

    /** The text after the metadata block; empty unless {@link State#OK}. */
    public String body() {
        return text.map(t -> t.substring(metadata.bodyStart())).orElse("");
    }
}
