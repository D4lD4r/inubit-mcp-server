package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Feature 021 (T043): a capability matrix that admits the web service log download on 8.1, as
 * the production matrix does since the 8.1 web service recording (T046); offline tests use it so
 * that they do not depend on the production declaration.
 */
public final class WebServiceMatrix {

    private WebServiceMatrix() {
    }

    /**
     * 8.1 supports every capability except the trace log, so that only the web service log can
     * make a node download a server log; every other version, 6.1 included, and unknown versions
     * support none.
     */
    public static CapabilityMatrix admittingEightOneWithoutTrace() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        states.put(Capability.TRACE_LOG_READ, SupportState.UNSUPPORTED);
        return new CapabilityMatrix(List.of(
            new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1), states)), Map.of());
    }
}
