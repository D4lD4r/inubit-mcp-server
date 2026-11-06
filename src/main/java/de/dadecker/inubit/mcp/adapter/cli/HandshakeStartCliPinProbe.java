package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.rest.SslContexts;
import de.dadecker.inubit.mcp.adapter.rest.TransportErrorTexts;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertificateException;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Opens one TLS handshake to the host and port of the server's {@code cli.url} with the same
 * {@code SSLContext} as the REST client ({@link SslContexts}: trust store, the server's own pin)
 * and the same host-name rule; no HTTP request and no credential is sent (research R-15). An
 * {@code http} address is not probed. A node with a CLI never has a trust-store password, so the
 * context is built without one.
 */
public final class HandshakeStartCliPinProbe implements StartCliPinProbe {

    private static final int DEFAULT_HTTPS_PORT = 443;

    @Override
    public void check(EffectiveNodeConfig server) {
        URI url = server.cli().url();
        if ("http".equalsIgnoreCase(url.getScheme())) {
            return;
        }
        String host = url.getHost();
        int port = url.getPort() == -1 ? DEFAULT_HTTPS_PORT : url.getPort();
        SSLContext context =
            SslContexts.forServer(server.secretLabels(), server.tls(), Optional.empty());
        int timeoutMillis = (int) Math.clamp(server.timeout().toMillis(), 1, Integer.MAX_VALUE);
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), timeoutMillis);
            plain.setSoTimeout(timeoutMillis);
            // the host passed here becomes the SNI name and the peer host of the session
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                .createSocket(plain, host, port, true)) {
                if (!server.tls().disableHostnameVerification()) {
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    socket.setSSLParameters(parameters);
                }
                socket.startHandshake();
            }
        } catch (IOException | UnresolvedAddressException e) {
            throw new ToolErrorException(error(server, host + ":" + port, e));
        }
    }

    private static ToolError error(EffectiveNodeConfig server, String address, Exception e) {
        String notStarted = "; StartCLI was not started";
        if (hasCause(e, SocketTimeoutException.class)) {
            return ToolError.of(ErrorCode.TIMEOUT,
                "The StartCLI address " + address + " of " + server.id()
                    + " did not complete the TLS handshake within "
                    + Durations.human(server.timeout()) + notStarted,
                TransportErrorTexts.TIMEOUT_LIKELY_CAUSE, TransportErrorTexts.TIMEOUT_NEXT_STEP)
                .withNode(server.id());
        }
        String cause = " (" + rootCause(e).getClass().getSimpleName() + ")";
        if (hasCause(e, SSLException.class) || hasCause(e, CertificateException.class)) {
            return ToolError.of(ErrorCode.TLS_ERROR,
                "The TLS connection to " + server.id() + " failed for the StartCLI address "
                    + address + cause + notStarted,
                TransportErrorTexts.TLS_ERROR_LIKELY_CAUSE,
                TransportErrorTexts.tlsErrorNextStep(server.id()))
                .withNode(server.id());
        }
        boolean unreachable = hasCause(e, ConnectException.class)
            || hasCause(e, UnresolvedAddressException.class)
            || hasCause(e, UnknownHostException.class)
            || hasCause(e, NoRouteToHostException.class);
        return ToolError.of(ErrorCode.UNREACHABLE,
            "Cannot connect to " + server.id() + " for the StartCLI address " + address + cause
                + notStarted,
            unreachable
                ? TransportErrorTexts.UNREACHABLE_LIKELY_CAUSE
                : TransportErrorTexts.INTERRUPTED_LIKELY_CAUSE,
            "Check cli.url (" + server.cli().url() + "), DNS and the VPN, then retry")
            .withNode(server.id());
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }
}
