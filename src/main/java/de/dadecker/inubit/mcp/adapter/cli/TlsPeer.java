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
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * One TLS handshake to the host and port of the server's {@code https} {@code cli.url} with the
 * same {@code SSLContext} as the REST client ({@link SslContexts}: trust store, the server's own
 * pin) and the same host-name rule; no HTTP request and no credential is sent (feature 014 research
 * R-15, feature 020 research D-7). A node with a CLI never has a trust-store password, so the
 * context is built without one. Errors name the program that is therefore not started.
 */
public final class TlsPeer {

    private static final int DEFAULT_HTTPS_PORT = 443;

    private final String program;

    /** @param program the program not started on an error, e.g. {@code StartCLI} */
    public TlsPeer(String program) {
        this.program = program;
    }

    /**
     * @return the verified peer chain, leaf first
     * @throws ToolErrorException {@code TLS_ERROR}, {@code UNREACHABLE} or {@code TIMEOUT} if no
     *     handshake within {@code timeout} is accepted
     */
    public List<X509Certificate> handshake(EffectiveNodeConfig server, Duration timeout) {
        URI url = server.cli().url();
        String host = url.getHost();
        int port = url.getPort() == -1 ? DEFAULT_HTTPS_PORT : url.getPort();
        SSLContext context =
            SslContexts.forServer(server.secretLabels(), server.tls(), Optional.empty());
        int timeoutMillis = (int) Math.clamp(timeout.toMillis(), 1, Integer.MAX_VALUE);
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
                Certificate[] chain = socket.getSession().getPeerCertificates();
                return Arrays.stream(chain).map(X509Certificate.class::cast).toList();
            }
        } catch (IOException | UnresolvedAddressException e) {
            throw new ToolErrorException(error(server, host + ":" + port, timeout, e));
        }
    }

    private ToolError error(EffectiveNodeConfig server, String address, Duration timeout,
        Exception e) {
        String notStarted = "; " + program + " was not started";
        if (hasCause(e, SocketTimeoutException.class)) {
            return ToolError.of(ErrorCode.TIMEOUT,
                "The StartCLI address " + address + " of " + server.id()
                    + " did not complete the TLS handshake within "
                    + Durations.human(timeout) + notStarted,
                TransportErrorTexts.TIMEOUT_LIKELY_CAUSE, TransportErrorTexts.TIMEOUT_NEXT_STEP)
                .withNode(server.id());
        }
        String cause = " (" + TransportErrorTexts.causeName(e) + ")";
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
}
