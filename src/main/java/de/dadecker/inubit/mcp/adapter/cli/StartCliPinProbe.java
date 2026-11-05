package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;

/**
 * Checks the certificate at a server's StartCLI address against the server's own pin before
 * StartCLI is started (feature 014 FR-005a, research R-15). StartCLI has no pin option of its own.
 */
public interface StartCliPinProbe {

    /**
     * Returns normally if the StartCLI address of {@code server} presents a certificate that the
     * server's trust store, pin and host-name rule accept, or if the address is {@code http}.
     *
     * @throws ToolErrorException {@code TLS_ERROR}, {@code TIMEOUT} or {@code UNREACHABLE} with
     *     the server id; StartCLI must then not be started
     */
    void check(EffectiveNodeConfig server);
}
