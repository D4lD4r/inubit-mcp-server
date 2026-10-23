package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.infra.Secret;
import java.util.Map;
import java.util.Objects;

/**
 * The answer of {@link ParameterStore#get}: a result per name, or a failure of the whole call
 * (feature 010, data-model.md → ParameterStore port and Fetched). {@code toString} never reveals a
 * value or the raw store message.
 */
public sealed interface Fetched {

    /** The outcome for one name. */
    sealed interface Result permits Value, Failure {
    }

    /** The stored value and its type. */
    record Value(Secret value, ParameterType type) implements Result {

        public Value {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * A failed read.
     *
     * @param logDetail the raw store message; only for the WARN log after scrubbing, never for a
     *     tool result (it may contain account ids and role ARNs)
     */
    record Failure(FailureKind kind, String logDetail) implements Result {

        public Failure {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(logDetail, "logDetail");
        }

        @Override
        public String toString() {
            return "Failure[kind=" + kind + "]";
        }
    }

    /** The call reached the store; {@code results} holds one entry per requested name. */
    record PerName(Map<String, Result> results) implements Fetched {

        public PerName {
            results = Map.copyOf(results);
        }
    }

    /** The whole call failed (e.g. {@code NO_CREDENTIALS}, {@code UNKNOWN_PROFILE}). */
    record CallFailed(Failure failure) implements Fetched {

        public CallFailed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
