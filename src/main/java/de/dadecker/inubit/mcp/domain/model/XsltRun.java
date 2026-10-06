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
 * @param findings     what the run found: the static errors, the reason it is not testable,
 *                     the stand-ins used
 */
public record XsltRun(String stylesheet, String input, Optional<String> output, Outcome outcome,
    List<String> standInsUsed, List<CheckFinding> findings) {

    /**
     * {@link #NOT_TESTABLE}: the stylesheet needs something only INUBIT has; never reported as
     * passed (FR-031).
     */
    public enum Outcome {
        OK,
        ERROR,
        NOT_TESTABLE
    }

    /** A run without findings. */
    public XsltRun(String stylesheet, String input, Optional<String> output, Outcome outcome,
        List<String> standInsUsed) {
        this(stylesheet, input, output, outcome, standInsUsed, List.of());
    }

    public XsltRun {
        Objects.requireNonNull(stylesheet, "stylesheet");
        Objects.requireNonNull(input, "input");
        output = output == null ? Optional.empty() : output;
        Objects.requireNonNull(outcome, "outcome");
        standInsUsed = List.copyOf(Objects.requireNonNull(standInsUsed, "standInsUsed"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (output.isPresent() != (outcome == Outcome.OK)) {
            throw new IllegalArgumentException("an output exists exactly for outcome OK");
        }
    }
}
