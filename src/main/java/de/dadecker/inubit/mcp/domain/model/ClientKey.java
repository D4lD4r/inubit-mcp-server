package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * The identity of one StartCLI client of a node: the chosen client directory and the configured
 * JDK (feature 017, research D-5). A client check belongs to the key it checked. No URL and no
 * credentials: the same client at another address is the same client.
 *
 * @param home     the client directory; {@code ""} for none
 * @param javaHome the configured JDK; {@code ""} for none (the server's {@code JAVA_HOME})
 */
public record ClientKey(String home, String javaHome) {

    /** No client directory and no configured JDK. */
    public static final ClientKey NONE = new ClientKey("", "");

    public ClientKey {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(javaHome, "javaHome");
    }

    /** Whether a client directory was chosen. */
    public boolean hasHome() {
        return !home.isEmpty();
    }
}
