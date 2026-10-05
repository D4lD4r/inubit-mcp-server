package de.dadecker.inubit.mcp.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A validated {@code find_processes} query for one server (contracts/mcp-tools.md §3,
 * research R-8).
 *
 * @param states           requested states; empty = all states
 * @param hangingThreshold resolved from the request, the server config or {@code PT60M}
 * @param since            lower bound of {@code since} (inclusive)
 * @param until            upper bound of {@code since} (inclusive)
 * @param now              the reference time of {@code timeInState} and {@code hanging}
 */
public record ProcessQuery(
    Set<ProcessState> states,
    boolean hangingOnly,
    Duration hangingThreshold,
    Optional<String> workflow,
    Optional<String> tag,
    Optional<Instant> since,
    Optional<Instant> until,
    int offset,
    int limit,
    Instant now) {

    public ProcessQuery {
        states = states.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(states));
        Objects.requireNonNull(hangingThreshold, "hangingThreshold");
        workflow = workflow == null ? Optional.empty() : workflow;
        tag = tag == null ? Optional.empty() : tag;
        since = since == null ? Optional.empty() : since;
        until = until == null ? Optional.empty() : until;
        Objects.requireNonNull(now, "now");
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset >= 0 and limit >= 1 required");
        }
    }

    /**
     * The raw INUBIT {@code status} values to query, one request each (state table order), or
     * empty for "no status filter". {@code hangingOnly} keeps only the raw values of the
     * {@link ProcessState#NON_FINAL} states, intersected with {@code states}.
     */
    public Optional<List<String>> rawStates() {
        if (states.isEmpty() && !hangingOnly) {
            return Optional.empty();
        }
        Set<ProcessState> selected = states.isEmpty() ? EnumSet.allOf(ProcessState.class)
            : EnumSet.copyOf(states);
        if (hangingOnly) {
            selected.retainAll(ProcessState.NON_FINAL);
        }
        return Optional.of(selected.stream()
            .flatMap(state -> state.rawValues().stream())
            .toList());
    }

    /** True if no row can match, e.g. {@code states=[ERROR]} with {@code hangingOnly}. */
    public boolean matchesNothing() {
        return rawStates().map(List::isEmpty).orElse(false)
            || (since.isPresent() && effectiveUntil().isPresent()
                && since.get().isAfter(effectiveUntil().get()));
    }

    /**
     * The inclusive upper bound of {@code since}: {@code until}, lowered for {@code hangingOnly}
     * to the last instant before {@code now - threshold} (only longer waits are hanging).
     */
    public Optional<Instant> effectiveUntil() {
        if (!hangingOnly) {
            return until;
        }
        Instant hangingBefore = now.minus(hangingThreshold).minusMillis(1);
        return Optional.of(until.filter(value -> value.isBefore(hangingBefore))
            .orElse(hangingBefore));
    }
}
