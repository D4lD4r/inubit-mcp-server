package de.dadecker.inubit.mcp.adapter.rest;

import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Server-certificate check of one INUBIT server (research R-5a).
 *
 * <ol>
 *   <li>The chain is validated against the server's trust store by the PKIX {@code delegate},
 *       always.
 *   <li>Endpoint identification (hostname verification) is part of that check unless
 *       {@code disableHostnameVerification} is set; this is the <em>only</em> step that can be
 *       skipped, and only together with a pin.
 *   <li>If a pin is configured, the SHA-256 fingerprint of the leaf certificate must equal it.
 * </ol>
 *
 * <p>Note: with {@code disableHostnameVerification} the chain is validated through the
 * two-argument {@code checkServerTrusted}, which has no access to the TLS session. PKIX validation
 * and the JDK's {@code jdk.certpath.disabledAlgorithms} still apply, but the session-specific
 * algorithm constraints (the signature algorithms negotiated in the handshake) are not checked
 * for the chain. The leaf pin compensates for the accepted certificate; without the flag the
 * session-aware variants are used.
 *
 * <p>The decision is per {@code SSLContext}, i.e. per server. The JVM-wide switch
 * {@code jdk.internal.httpclient.disableHostnameVerification} is never used. Because this class
 * extends {@link X509ExtendedTrustManager}, JSSE uses it as is and adds no hostname check of its
 * own.
 */
public final class PinningTrustManager extends X509ExtendedTrustManager {

    private static final Pattern PIN = Pattern.compile("^(?:[0-9A-Fa-f]{2}:?){31}[0-9A-Fa-f]{2}$");

    private final X509ExtendedTrustManager delegate;
    private final Optional<byte[]> pin;
    private final boolean disableHostnameVerification;

    /**
     * @param delegate the PKIX trust manager of the server's trust store
     * @param pinnedSha256 the expected SHA-256 fingerprint of the leaf, 64 hex digits, optionally
     *     colon-separated
     * @param disableHostnameVerification skip endpoint identification; requires a pin
     * @throws IllegalArgumentException if hostname verification is disabled without a pin, or the
     *     pin is malformed
     */
    public PinningTrustManager(X509ExtendedTrustManager delegate, Optional<String> pinnedSha256,
        boolean disableHostnameVerification) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.pin = pinnedSha256.map(PinningTrustManager::parsePin);
        if (disableHostnameVerification && pin.isEmpty()) {
            throw new IllegalArgumentException("disableHostnameVerification requires"
                + " tls.pinnedCertificateSha256");
        }
        this.disableHostnameVerification = disableHostnameVerification;
    }

    /** Parses 64 hex digits, optionally separated by colons, into the 32 fingerprint bytes. */
    static byte[] parsePin(String pin) {
        String value = pin == null ? "" : pin.strip();
        if (!PIN.matcher(value).matches()) {
            throw new IllegalArgumentException("tls.pinnedCertificateSha256 must be 64 hex digits,"
                + " optionally colon-separated");
        }
        return HexFormat.of().parseHex(value.replace(":", ""));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
        if (disableHostnameVerification) {
            delegate.checkServerTrusted(chain, authType);
        } else {
            delegate.checkServerTrusted(chain, authType, socket);
        }
        checkPin(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
        if (disableHostnameVerification) {
            delegate.checkServerTrusted(chain, authType);
        } else {
            delegate.checkServerTrusted(chain, authType, engine);
        }
        checkPin(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        checkPin(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
        throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    private void checkPin(X509Certificate[] chain) throws CertificateException {
        if (pin.isEmpty()) {
            return;
        }
        if (chain == null || chain.length == 0) {
            throw new CertificateException("No server certificate to compare with the pin");
        }
        if (!MessageDigest.isEqual(pin.get(), sha256(chain[0]))) {
            throw new CertificateException("The server certificate does not match the pinned"
                + " SHA-256 fingerprint (tls.pinnedCertificateSha256)");
        }
    }

    private static byte[] sha256(X509Certificate certificate) throws CertificateException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException("SHA-256 is not available", e);
        }
    }
}
