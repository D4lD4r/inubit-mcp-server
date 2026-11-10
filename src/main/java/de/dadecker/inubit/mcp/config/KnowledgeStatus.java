package de.dadecker.inubit.mcp.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The knowledge directory as {@code --check-config} shows it (feature 019, FR-003, research
 * D-3): path, state, recording and the number of notes; never a note name, title or content.
 *
 * @param path      {@link KnowledgeConfig#directory()}
 * @param state     whether it exists and can be used
 * @param problem   why it cannot be used, exactly for {@link State#PROBLEM}
 * @param recording {@link KnowledgeConfig#recording()}
 * @param notes     the notes {@code read_knowledge} would list, {@code 0} unless {@link State#OK}
 */
public record KnowledgeStatus(Path path, State state, Optional<String> problem,
    boolean recording, int notes) {

    /** The state of the knowledge directory. */
    public enum State { OK, ABSENT, PROBLEM }

    public KnowledgeStatus {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(problem, "problem");
        if (problem.isPresent() != (state == State.PROBLEM)) {
            throw new IllegalArgumentException("A problem is given exactly for PROBLEM");
        }
        if (notes < 0) {
            throw new IllegalArgumentException("notes must not be negative");
        }
    }

    /**
     * The state of {@code knowledge} on disk, without walking it: {@code not usable: see the
     * errors} for a relative path (the validator reports it), absent, {@code not a directory},
     * {@code not readable} or ok with {@code notes}.
     */
    public static KnowledgeStatus probe(KnowledgeConfig knowledge, int notes) {
        Path path = knowledge.directory();
        boolean recording = knowledge.recording();
        Optional<String> problem = !path.isAbsolute() ? Optional.of("not usable: see the errors")
            : !Files.exists(path) ? Optional.empty()
            : !Files.isDirectory(path) ? Optional.of("not a directory")
            : !Files.isReadable(path) ? Optional.of("not readable") : Optional.empty();
        if (problem.isPresent()) {
            return new KnowledgeStatus(path, State.PROBLEM, problem, recording, 0);
        }
        return Files.exists(path) ? new KnowledgeStatus(path, State.OK, problem, recording, notes)
            : new KnowledgeStatus(path, State.ABSENT, problem, recording, 0);
    }

    /** {@code Knowledge: <path> (<ok|absent|problem>), recording on|off, <n> notes}. */
    public String line() {
        String shown = switch (state) {
            case OK -> "ok";
            case ABSENT -> "absent";
            case PROBLEM -> problem.orElseThrow();
        };
        return "Knowledge: " + path + " (" + shown + "), recording " + (recording ? "on" : "off")
            + ", " + notes + " notes";
    }
}
