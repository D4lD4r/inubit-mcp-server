package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ServerUpdate;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** The last known update status of this process (feature 016); thread-safe. */
public final class UpdateStatus {

    private final AtomicReference<ServerUpdate> current = new AtomicReference<>();

    /** The last recorded status; empty until a check (or the cache) produced one. */
    public Optional<ServerUpdate> current() {
        return Optional.ofNullable(current.get());
    }

    public void record(ServerUpdate update) {
        current.set(Objects.requireNonNull(update, "update"));
    }
}
