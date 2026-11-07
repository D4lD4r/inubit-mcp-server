package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One distinct StartCLI client of {@code --check-config} (feature 017, T025, research D-17): its
 * directory, the Java major of its effective JDK and its {@code startcli -v} result. Holds no
 * URL and no credentials.
 *
 * @param javaMajor the Java major of the client's JDK; empty when unknown ({@link JdkRelease})
 * @param version   the probe's result; empty when the client was not probed or the probe
 *     failed (a failure is among the warnings)
 */
public record CheckedClient(Path home, OptionalInt javaMajor, Optional<ClientVersion> version) {

    public CheckedClient {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(javaMajor, "javaMajor");
        Objects.requireNonNull(version, "version");
    }

    /** {@code client <home> (Java <n>): CLI <version> (<from>–<to>)}. */
    public String line() {
        String java = javaMajor.isPresent() ? String.valueOf(javaMajor.getAsInt()) : "unknown";
        String cli = version.map(client -> client.version().raw() + client.supportedFrom()
                .map(from -> " (" + from.raw() + "–" + client.supportedTo().orElseThrow().raw()
                    + ")").orElse(""))
            .orElse("version unknown");
        return "client " + home + " (Java " + java + "): CLI " + cli;
    }
}
