package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One local stylesheet run (data-model.md → Checks, research D-11). Paths are
 * workspace-relative.
 *
 * @param output       the transformation output below {@code .tests/}; present exactly when
 *                     {@code outcome} is {@link Outcome#OK}
 * @param standInsUsed the INUBIT extension functions served by local stand-ins, e.g.
 *                     {@code Misc.guid}
 */
public record XsltRun(String stylesheet, String input, Optional<String> output, Outcome outcome,
    List<String> standInsUsed) {

    /**
     * {@link #NOT_TESTABLE}: the stylesheet needs something only INUBIT has; never reported as
     * passed (FR-031).
     */
    public enum Outcome {
        OK,
        ERROR,
        NOT_TESTABLE
    }

    public XsltRun {
        Objects.requireNonNull(stylesheet, "stylesheet");
        Objects.requireNonNull(input, "input");
        output = output == null ? Optional.empty() : output;
        Objects.requireNonNull(outcome, "outcome");
        standInsUsed = List.copyOf(Objects.requireNonNull(standInsUsed, "standInsUsed"));
        if (output.isPresent() != (outcome == Outcome.OK)) {
            throw new IllegalArgumentException("an output exists exactly for outcome OK");
        }
    }
}
