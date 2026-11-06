package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;

/**
 * {@link ProcessContainment} on macOS and Linux (feature 013, research R-7): no job is needed,
 * StartCLI's processes are ended as today, so the state is always {@link Bound}.
 */
public final class NoJob implements ProcessContainment {

    @Override
    public State state() {
        return new Bound();
    }
}
