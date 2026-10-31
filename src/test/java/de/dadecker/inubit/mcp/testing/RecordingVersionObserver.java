package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.VersionObserver;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every report in order, e.g. {@code detected dev/node1 8.1.17},
 * {@code detectionFailed dev/node1 AUTH_FAILED} (with {@code endpointMissing} after an HTTP 404),
 * {@code stale dev/node1}; knows no version.
 */
public final class RecordingVersionObserver implements VersionObserver {

    private final List<String> events = new CopyOnWriteArrayList<>();

    public List<String> events() {
        return List.copyOf(events);
    }

    @Override
    public void detected(NodeId node, InubitVersion version) {
        events.add("detected " + node + " " + version.raw());
    }

    @Override
    public void detectionFailed(NodeId node, ErrorCode code, boolean endpointMissing) {
        events.add("detectionFailed " + node + " " + code
            + (endpointMissing ? " endpointMissing" : ""));
    }

    @Override
    public void observed(NodeId node, InubitVersion version) {
        events.add("observed " + node + " " + version.raw());
    }

    @Override
    public void stale(NodeId node) {
        events.add("stale " + node);
    }

    @Override
    public Optional<InubitVersion> effectiveVersion(NodeId node) {
        return Optional.empty();
    }
}
