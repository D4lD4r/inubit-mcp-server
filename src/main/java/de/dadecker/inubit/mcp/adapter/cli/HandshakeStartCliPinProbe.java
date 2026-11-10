package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;

/**
 * Opens one TLS handshake to the host and port of the server's {@code cli.url} through
 * {@link TlsPeer}: the same {@code SSLContext} as the REST client (trust store, the server's own
 * pin) and the same host-name rule; no HTTP request and no credential is sent (research R-15). An
 * {@code http} address is not probed.
 */
public final class HandshakeStartCliPinProbe implements StartCliPinProbe {

    private final TlsPeer peer = new TlsPeer("StartCLI");

    @Override
    public void check(EffectiveNodeConfig server) {
        if ("http".equalsIgnoreCase(server.cli().url().getScheme())) {
            return;
        }
        peer.handshake(server, server.timeout());
    }
}
