package de.dadecker.inubit.mcp.adapter.cli.v74;

import de.dadecker.inubit.mcp.adapter.cli.v61.CliLineProfile;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;

/**
 * The INUBIT 7.4 profile of the 6.1 StartCLI adapters (feature 025, spec FR-024, FR-026,
 * FR-027).
 */
public final class V74Profile {

    /**
     * "INUBIT 7.4" in every text, {@link UnavailablePart#NOT_AVAILABLE_ON_7_4} for readiness,
     * threads and blocking queue, and {@code uname} as the client's version: 6.1 behaviour
     * assumed without a recording (FR-027, a known limitation: no server version and no pin
     * contradiction from 7.4).
     */
    public static final CliLineProfile PROFILE =
        new CliLineProfile("7.4", UnavailablePart.NOT_AVAILABLE_ON_7_4, false);

    private V74Profile() {
    }
}
