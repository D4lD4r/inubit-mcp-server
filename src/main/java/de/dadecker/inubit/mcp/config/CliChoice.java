package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.config.EffectiveNodeConfig.Cli;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The StartCLI client and address chosen for one version of a node (feature 017, research D-3,
 * data-model.md → CliChoice). Holds no credentials.
 *
 * @param source  where the client directory came from
 * @param entry   the {@code defaults.cliClients} key; present exactly for
 *     {@link HomeSource#VERSION_ENTRY}
 * @param address how the address was chosen
 * @param major   the INUBIT major the choice was made for: the version's, else the line pin's
 * @param versionKnown whether a version was given
 */
public record CliChoice(Cli cli, HomeSource source, Optional<MajorMinor> entry,
    AddressKind address, OptionalInt major, boolean versionKnown) {

    /** Where the client directory came from. */
    public enum HomeSource { NODE, GROUP, VERSION_ENTRY, DEFAULTS, NONE }

    /** How the StartCLI address was chosen. */
    public enum AddressKind {
        /** {@code <baseUrl>/ibis/servlet/IBISSoapServlet}: major below 9 or unknown. */
        SERVLET_PATH,
        /** {@code <baseUrl>}: major 9 and later. */
        BASE_URL,
        /** An explicit {@code cli.url}. */
        CONFIGURED
    }

    public CliChoice {
        Objects.requireNonNull(cli, "cli");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(major, "major");
        if (entry.isPresent() != (source == HomeSource.VERSION_ENTRY)) {
            throw new IllegalArgumentException("A cliClients entry is named exactly for the"
                + " source VERSION_ENTRY, not for " + source);
        }
    }
}
