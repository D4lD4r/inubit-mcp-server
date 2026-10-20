package de.dadecker.inubit.mcp.config;

import java.time.Duration;
import java.util.List;

/**
 * Reads named parameters from a secret store (feature 010, research R-2). The port references no
 * SDK type; the AWS implementation lives in {@code adapter/aws}.
 */
public interface ParameterStore {

    /**
     * Reads {@code names} at {@code location}. Store failures are returned, not thrown: either a
     * {@link Fetched.CallFailed}, or a {@link Fetched.PerName} with an entry for every requested
     * name.
     *
     * @param timeout upper bound for the store call
     */
    Fetched get(StoreLocation location, List<String> names, Duration timeout);
}
