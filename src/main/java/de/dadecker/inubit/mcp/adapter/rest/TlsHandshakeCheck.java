package de.dadecker.inubit.mcp.adapter.rest;

import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * The server's own certificate check of an HTTPS StartCLI URL before StartCLI runs (feature 011
 * FR-012, research D-7): a TLS handshake with host and port of the URL, then the connection is
 * closed; no byte of HTTP is sent.
 *
 * <p>The context is the REST client's ({@link SslContexts}, {@link PinningTrustManager}): the
 * chain against the node's trust store, the pin if set, and the host name, which is checked
 * through the endpoint identification {@code HTTPS} unless {@code disableHostnameVerification} is
 * set together with a pin. The host name goes out as SNI. The node's {@code timeout} bounds the
 * connect and the handshake.
 */
public final class TlsHandshakeCheck {

    private static final int HTTPS_PORT = 443;
    /** An IPv4 literal, or an IPv6 literal as {@link URI#getHost()} returns it (in brackets). */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9.]+|\\[.*]");

    private TlsHandshakeCheck() {
    }

    /**
     * The trust store is read without a password: a node with StartCLI must not have one (the
     * existing configuration rule), so a protected trust store fails closed here.
     *
     * @param url the node's effective StartCLI URL ({@code https://}); only host and port are used
     * @throws ToolErrorException {@code TLS_ERROR} if the certificate is rejected (pin mismatch,
     *     untrusted chain, host name) or the TLS settings are unusable, {@code UNREACHABLE} if no
     *     connection can be opened, {@code TIMEOUT} if the connect or the handshake takes longer
     *     than the node's {@code timeout}; always with the node id
     */
    public static void verify(EffectiveNodeConfig node, URI url) {
        String host = url.getHost();
        int port = url.getPort() < 0 ? HTTPS_PORT : url.getPort();
        SSLContext context = InubitHttpClient.sslContext(node, Optional.empty());
        int timeout = Math.clamp(node.timeout().toMillis(), 1, Integer.MAX_VALUE);
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), timeout);
            plain.setSoTimeout(timeout);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                .createSocket(plain, host, port, true)) {
                SSLParameters parameters = socket.getSSLParameters();
                // the JDK sends SNI by itself only for names with a dot; never for an IP literal
                if (!IP_LITERAL.matcher(host).matches()) {
                    parameters.setServerNames(List.of(new SNIHostName(host)));
                }
                if (!node.tls().disableHostnameVerification()) {
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                }
                socket.setSSLParameters(parameters);
                socket.startHandshake();
            }
        } catch (IOException e) {
            throw new ToolErrorException(error(node, host, host + ":" + port, e));
        }
    }

    private static ToolError error(EffectiveNodeConfig node, String host, String target,
        IOException e) {
        String request = "the TLS handshake with " + target;
        String cause = InubitHttpClient.rootCause(e).getClass().getSimpleName();
        ToolError error;
        if (InubitHttpClient.hasCause(e, SocketTimeoutException.class)) {
            error = ToolError.of(ErrorCode.TIMEOUT, "INUBIT did not answer " + request
                    + " within " + Durations.human(node.timeout()),
                InubitHttpClient.TIMEOUT_CAUSE, InubitHttpClient.TIMEOUT_NEXT_STEP);
        } else if (InubitHttpClient.hasCause(e, SSLException.class)
            || InubitHttpClient.hasCause(e, CertificateException.class)) {
            error = ToolError.of(ErrorCode.TLS_ERROR, "The TLS connection to " + node.id()
                    + " failed for " + request + " (" + cause + ")",
                tlsCause(e, host, !node.tls().disableHostnameVerification()),
                InubitHttpClient.tlsNextStep(node.id()));
        } else {
            error = ToolError.of(ErrorCode.UNREACHABLE, "Cannot connect to " + node.id()
                    + " for " + request + " (" + cause + ")",
                InubitHttpClient.isUnreachable(e) ? InubitHttpClient.UNREACHABLE_CAUSE
                    : InubitHttpClient.INTERRUPTED_CAUSE,
                "Check the StartCLI URL (cli.url, otherwise baseUrl), DNS and the VPN, then"
                    + " retry");
        }
        return error.withNode(node.id());
    }

    /** Names the rejection of the certificate, falling back to the REST client's text. */
    private static String tlsCause(IOException e, String host, boolean hostnameChecked) {
        CertificateException rejection = null;
        for (Throwable t = e; t != null && rejection == null; t = t.getCause()) {
            if (t instanceof CertificateException certificate) {
                rejection = certificate;
            }
        }
        if (rejection instanceof PinningTrustManager.PinMismatchException) {
            return PinningTrustManager.PinMismatchException.MESSAGE;
        }
        if (InubitHttpClient.hasCause(e, CertPathBuilderException.class)
            || InubitHttpClient.hasCause(e, CertPathValidatorException.class)) {
            return "The server certificate is not trusted: neither it nor an issuer is in"
                + " tls.trustStore (without tls.trustStore: the JVM default trust store)";
        }
        // the JDK's host name check throws the plain type; its subclasses mean other rejections
        if (hostnameChecked && rejection != null
            && rejection.getClass() == CertificateException.class) {
            return "The server certificate is not issued for the host name " + host;
        }
        return InubitHttpClient.TLS_CAUSE;
    }
}
