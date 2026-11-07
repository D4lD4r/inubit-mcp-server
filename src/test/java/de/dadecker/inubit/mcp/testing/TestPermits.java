package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import java.util.OptionalInt;

/**
 * Write permits for adapter tests (feature 017, research D-24): the adapters under test run with
 * the unchecked lock of their legacy constructors, so only the node of the permit matters.
 */
public final class TestPermits {

    /** INUBIT 8.1.17, the version of the 0.8.0 fixtures. */
    public static final InubitVersion V8_1_17 = new InubitVersion(8, 1, OptionalInt.of(17),
        "8.1.17");

    private TestPermits() {
    }

    /** A permit for {@code node} with {@code capability}, admitted on 8.1.17. */
    public static WritePermit of(NodeId node, Capability capability) {
        return new WritePermit(node, capability, V8_1_17);
    }

    /** A permit for {@code node} with {@code capability}, admitted on 8.1.17. */
    public static WritePermit of(String node, Capability capability) {
        return of(NodeId.parse(node), capability);
    }
}
