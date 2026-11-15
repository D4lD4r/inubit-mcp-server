package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.application.nodetools.ProcessLiveness;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * {@link ProcessLiveness} through {@link ProcessHandle} (research D-13). It lives in
 * {@code adapter.cli} because only this package inspects operating-system processes.
 */
public final class SystemProcessLiveness implements ProcessLiveness {

    private static final Duration START_TOLERANCE = Duration.ofSeconds(1);

    @Override
    public LeaseOwner self() {
        ProcessHandle current = ProcessHandle.current();
        return new LeaseOwner(current.pid(),
            current.info().startInstant().map(start -> start.truncatedTo(ChronoUnit.MILLIS)));
    }

    @Override
    public boolean alive(LeaseOwner owner) {
        Optional<ProcessHandle> handle = ProcessHandle.of(owner.pid());
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        Optional<Instant> live = handle.get().info().startInstant();
        if (owner.startedAt().isEmpty() || live.isEmpty()) {
            return true;
        }
        return Duration.between(owner.startedAt().get(), live.get()).abs()
            .compareTo(START_TOLERANCE) <= 0;
    }
}
