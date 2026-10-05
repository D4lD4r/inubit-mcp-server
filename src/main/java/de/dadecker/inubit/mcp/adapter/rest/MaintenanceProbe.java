package de.dadecker.inubit.mcp.adapter.rest;

import java.util.Optional;

/**
 * Tells {@link InubitHttpClient} whether a server is in maintenance mode when it answers HTTP 503
 * (research R-5). Supplied by the version-specific adapter layer, so that the client itself stays
 * version-neutral.
 */
@FunctionalInterface
public interface MaintenanceProbe {

    /** A probe that never knows: every 503 becomes {@code UNREACHABLE}. */
    MaintenanceProbe NONE = Optional::empty;

    /**
     * @return {@code true} if the server reports maintenance mode, {@code false} if it reports
     *     normal operation, empty if that cannot be determined
     */
    Optional<Boolean> maintenance();
}
