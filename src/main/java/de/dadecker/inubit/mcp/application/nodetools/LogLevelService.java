package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.application.WriteChallengeRegistry;
import de.dadecker.inubit.mcp.application.WriteGuard;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.node.LogLevelResult;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Stub (T049 red phase). */
public final class LogLevelService {

    public LogLevelService(WriteGuard guard, Function<NodeId, WritePolicy> policies,
        Predicate<NodeId> optedIn, GatewayFactory gateways, WriteChallengeRegistry challenges,
        LeaseStore store, NodeLogLevelLocks locks, ProcessLiveness liveness, LogLevelAudit audit,
        Function<NodeId, Duration> cliTimeouts, Clock clock, LongSupplier nanoTime) {
    }

    public LogLevelResult set(SetLevelRequest request) {
        throw new UnsupportedOperationException("T049");
    }
}
