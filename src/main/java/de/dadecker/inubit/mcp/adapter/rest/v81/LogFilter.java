package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One {@code <filtering>} block of an INUBIT {@code <logRequest>} (research R-9, S-5). Several
 * blocks are combined with AND by INUBIT. Dates are epoch milliseconds.
 */
sealed interface LogFilter {

    /** The INUBIT field the filter applies to. */
    String field();

    /** {@code EQUAL}: workflow, status, success, workflowId, globalPId, tag. */
    record Equal(String field, String value) implements LogFilter {
        public Equal {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(value, "value");
        }
    }

    /** {@code LIKE}: case-sensitive, {@code %} and {@code _} are SQL wildcards (passed as is). */
    record Like(String field, String pattern) implements LogFilter {
        public Like {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    /** {@code BETWEEN min AND max} (inclusive), epoch milliseconds. */
    record Between(String field, long min, long max) implements LogFilter {
        public Between {
            Objects.requireNonNull(field, "field");
        }
    }

    /** {@code GREATER} (exclusive), epoch milliseconds. */
    record Greater(String field, long value) implements LogFilter {
        public Greater {
            Objects.requireNonNull(field, "field");
        }
    }

    /** {@code LESSER} (exclusive), epoch milliseconds. */
    record Lesser(String field, long value) implements LogFilter {
        public Lesser {
            Objects.requireNonNull(field, "field");
        }
    }

    /**
     * The time filter for the inclusive range {@code [since, until]}: {@code BETWEEN} with both
     * bounds, otherwise {@code GREATER since - 1 ms} or {@code LESSER until + 1 ms}; empty
     * without bounds.
     *
     * @throws ToolErrorException {@code INVALID_INPUT} if a bound is outside the range of epoch
     *     milliseconds
     */
    static Optional<LogFilter> timeRange(String field, Optional<Instant> since,
        Optional<Instant> until) {
        try {
            if (since.isPresent() && until.isPresent()) {
                return Optional.of(new Between(field, since.get().toEpochMilli(),
                    until.get().toEpochMilli()));
            }
            if (since.isPresent()) {
                return Optional.of(new Greater(field,
                    Math.subtractExact(since.get().toEpochMilli(), 1)));
            }
            return until.map(value -> new Lesser(field,
                Math.addExact(value.toEpochMilli(), 1)));
        } catch (ArithmeticException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "since/until is outside the range of epoch milliseconds",
                "INUBIT compares times as epoch milliseconds",
                "Use a timestamp between the years 1970 ± 290 million, e.g."
                    + " 2026-10-01T08:00:00Z, or a duration such as PT24H"));
        }
    }
}
