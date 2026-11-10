package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.SecretSpan;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The credentials configured for the profile, as far as they are needed to mask them in the local
 * knowledge (feature 019, FR-010 (a), research D-7, option A: fail closed).
 *
 * <p>No value leaves this port; {@link #occurrences} returns positions only.
 */
public interface ConfiguredSecrets {

    /**
     * Starts or joins the reads of every node's credentials (all nodes in parallel) and waits until
     * all of them are settled (held, or nothing configured) or {@code bound} is over. A profile
     * without nodes is {@link Readiness.Ready} at once. Never throws for a failed read: a failure
     * is a {@link Readiness.NotReady}.
     */
    Readiness prepare(Duration bound);

    /**
     * The spans of every configured credential known so far in {@code text}, in every spelling the
     * server's scrubbing recognizes (plain, JSON, XML, URL-encoded, Base64).
     */
    List<SecretSpan> occurrences(String text);

    /** Whether every configured credential is known. */
    sealed interface Readiness {

        /** Every credential is held, or nothing is configured. */
        record Ready() implements Readiness {
        }

        /** Some reads are not settled; {@code pending} is never empty. */
        record NotReady(List<Pending> pending) implements Readiness {

            public NotReady {
                pending = List.copyOf(pending);
                if (pending.isEmpty()) {
                    throw new IllegalArgumentException("a not ready state names its pending reads");
                }
            }
        }
    }

    /**
     * A read that is not settled.
     *
     * @param node  the node name
     * @param store the kind of store, e.g. {@code credential store}, {@code AWS Parameter Store}
     * @param state {@code waiting} or the failure kind in lower case; never a value
     */
    record Pending(String node, String store, String state) {

        public Pending {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(state, "state");
        }
    }
}
