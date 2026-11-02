package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.infra.Secret;
import java.time.Duration;
import java.util.Objects;

/**
 * Reads one entry of the operating system's credential store (feature 011, research R-2,
 * data-model → CredentialStore port). An entry is identified by {@link #SERVICE} and an account
 * name (the derived entry name); the port references no OS type, the implementations live in
 * {@code adapter/os}.
 */
public interface CredentialStore {

    /** The service of every entry (not configurable). */
    String SERVICE = "inubit-mcp";

    /** Which store this is, for messages. */
    StoreKind kind();

    /**
     * Reads the entry {@code account}. Store failures are returned, never thrown.
     *
     * @param timeout upper bound for the read, including any access dialog
     */
    Lookup read(String account, Duration timeout);

    /** The outcome of one read; {@code toString} never reveals a value or the raw store text. */
    sealed interface Lookup {

        /** The entry exists; its value, possibly empty (the caller treats empty as not found). */
        record Found(Secret value) implements Lookup {

            public Found {
                Objects.requireNonNull(value, "value");
            }
        }

        /** No entry with this account. */
        record NotFound() implements Lookup {
        }

        /**
         * The read failed.
         *
         * @param detail store text for the scrubbed WARN log only; never tool output that may carry
         *     the value (research R-3, R-5)
         */
        record Failed(FailureKind kind, String detail) implements Lookup {

            public Failed {
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(detail, "detail");
            }

            @Override
            public String toString() {
                return "Failed[kind=" + kind + "]";
            }
        }
    }
}
