package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.PatchDepth;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feature 025 T038 (spec FR-030, FR-036): the test matrices of the flip, written before it
 * landed in production (T035), whose 7.4 and 6.1 entries they repeat. Each declares monitoring and process diagnosis {@code UNVERIFIED} through StartCLI on
 * one CLI-only version and every capability {@code SUPPORTED} on 8.1; unknown versions support
 * nothing.
 */
public final class TestMatrices {

    private static final Map<Capability, SupportState> UNVERIFIED = Map.of(
        Capability.MONITORING, SupportState.UNVERIFIED,
        Capability.PROCESS_DIAGNOSIS, SupportState.UNVERIFIED);

    private static final Map<Capability, Set<Channel>> CLI = Map.of(
        Capability.MONITORING, Set.of(Channel.CLI),
        Capability.PROCESS_DIAGNOSIS, Set.of(Channel.CLI));

    private TestMatrices() {
    }

    /**
     * 7.4 as in production since the flip (T035): the two capabilities {@code UNVERIFIED} and
     * without a note, every other one with "not yet verified", and {@code ownClientOnly} (T033,
     * spec FR-029).
     */
    public static CapabilityMatrix unverified74() {
        Map<Capability, String> notes = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            if (!UNVERIFIED.containsKey(capability)) {
                notes.put(capability, "not yet verified");
            }
        }
        return withV81(new CapabilityMatrix.Entry(new VersionRange(7, 4, 7, 4), UNVERIFIED, CLI,
            true, notes, PatchDepth.FOUR, true));
    }

    /** 6.1 as in production since the flip (T035): the two capabilities {@code UNVERIFIED}. */
    public static CapabilityMatrix unverified61() {
        return withV81(new CapabilityMatrix.Entry(new VersionRange(6, 1, 6, 1), UNVERIFIED, CLI,
            true));
    }

    private static CapabilityMatrix withV81(CapabilityMatrix.Entry cliOnly) {
        Map<Capability, SupportState> all = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            all.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(cliOnly,
            new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), all)), Map.of());
    }
}
