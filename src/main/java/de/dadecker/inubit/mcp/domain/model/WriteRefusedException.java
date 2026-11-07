package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A write the write lock did not hand off (feature 017, FR-018, research D-24): the node's known
 * version is no longer of the permit's major.minor, or is stale or unknown. Carries the permit
 * and the version found, so that a multi-step write can name both (contracts "Every write tool").
 * Nothing was sent.
 */
public final class WriteRefusedException extends ToolErrorException {

    private static final long serialVersionUID = 1L;

    private final transient WritePermit permit;
    private final transient Optional<InubitVersion> current;

    private WriteRefusedException(ToolError error, WritePermit permit,
        Optional<InubitVersion> current) {
        super(error);
        this.permit = permit;
        this.current = current;
    }

    /**
     * The refusal of {@code permit}'s write ({@code UNSUPPORTED_VERSION}).
     *
     * @param current the version the node runs now; empty if it is stale or unknown
     */
    public static WriteRefusedException of(WritePermit permit, Optional<InubitVersion> current) {
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(current, "current");
        NodeId node = permit.node();
        String admitted = permit.admitted().raw();
        String message = current.map(version -> "INUBIT on " + node + " changed from " + admitted
                + " to " + version.raw() + " during this call; the write was admitted for INUBIT "
                + permit.admittedLine().text() + "; nothing was sent")
            .orElse("INUBIT on " + node + " may run another version than " + admitted
                + " since this call was admitted; nothing was sent");
        return new WriteRefusedException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION, message,
            "A parallel call found that " + node + " runs another INUBIT version",
            "Call the tool again; it is judged on the version INUBIT runs now").withNode(node),
            permit, current);
    }

    /** The permit of the refused write. */
    public WritePermit permit() {
        return permit;
    }

    /** The version the node runs now; empty if it is stale or unknown. */
    public Optional<InubitVersion> current() {
        return current;
    }

    /**
     * What happened to INUBIT on the node, e.g. {@code changed from 8.1.17 to 9.0.7 during this
     * call}.
     */
    public String change() {
        return current.map(version -> "changed from " + permit.admitted().raw() + " to "
            + version.raw() + " during this call").orElse("may run another version than "
                + permit.admitted().raw() + " since this call was admitted");
    }
}
