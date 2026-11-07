package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * The write purpose a call was admitted for (feature 017, FR-018, research D-24, data-model.md →
 * WritePermit): the node, the capability of kind {@code WRITE} and the INUBIT version the
 * version gate judged the call on. Handed out by the write guards only; every state-changing
 * port method takes it, and the write lock hands the write off only while the node's known
 * version has {@link #admittedLine()}. Carries no credentials.
 *
 * @param capability any capability of kind {@code WRITE}: {@code DEVELOPMENT_WRITE},
 *     {@code DEPLOYMENT}, {@code E2E_TEST} or {@code PROCESS_CONTROL}
 * @param admitted   the version the call was admitted on
 */
public record WritePermit(NodeId node, Capability capability, InubitVersion admitted) {

    public WritePermit {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(admitted, "admitted");
        if (capability.kind() != Capability.Kind.WRITE) {
            throw new IllegalArgumentException("A write permit needs a write capability, not "
                + capability);
        }
    }

    /** The major.minor the write may be handed off on. */
    public MajorMinor admittedLine() {
        return MajorMinor.of(admitted);
    }

    /**
     * This permit, if it is for {@code target}; the check of every write port before anything
     * is sent.
     *
     * @throws IllegalArgumentException if it is for another node (a programming error)
     */
    public WritePermit requireFor(NodeId target) {
        if (!node.equals(target)) {
            throw new IllegalArgumentException("The write permit " + this + " is not for "
                + target);
        }
        return this;
    }

    @Override
    public String toString() {
        return "WritePermit[" + node + ", " + capability + ", INUBIT " + admitted.raw() + "]";
    }
}
