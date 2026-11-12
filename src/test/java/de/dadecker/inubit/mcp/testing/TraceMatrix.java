package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Feature 021 (T031): a capability matrix that admits the trace download on 8.1, as the production
 * matrix does since the 8.1 trace recording (T033); offline tests use it so that they do not
 * depend on the production declaration.
 */
public final class TraceMatrix {

    private TraceMatrix() {
    }

    /**
     * 8.1 supports every capability except the web service log; every other version, 6.1
     * included, and unknown versions support none.
     */
    public static CapabilityMatrix admittingEightOne() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        states.put(Capability.WEBSERVICE_LOG_READ, SupportState.UNSUPPORTED);
        return new CapabilityMatrix(List.of(
            new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), states)), Map.of());
    }
}
