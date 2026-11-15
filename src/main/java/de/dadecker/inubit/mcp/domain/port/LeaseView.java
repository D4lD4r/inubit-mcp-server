package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.node.Lease;
import java.util.List;
import java.util.Objects;

/**
 * Read-only view of the temporary log level leases of the profile (feature 024, research D-12,
 * D-23), for {@code get_health} and the configuration summary. Reading never creates a file.
 */
public interface LeaseView {

    /** The leases as stored now, or why they cannot be read. */
    Snapshot leases();

    /** A read of the lease store. */
    sealed interface Snapshot {

        /** The stored leases; empty if nothing was stored yet. */
        record Leases(List<Lease> leases) implements Snapshot {
            public Leases {
                leases = List.copyOf(leases);
            }
        }

        /**
         * The store exists but cannot be read (damaged, edited by hand, a link); it is never
         * overwritten.
         *
         * @param reason a short phrase without lease content, e.g. "lease 2: invalid auditId"
         */
        record Unreadable(String reason) implements Snapshot {
            public Unreadable {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }
}
