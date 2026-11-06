package de.dadecker.inubit.mcp.adapter.rest;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.security.cert.CertificateException;

/**
 * Likely-cause and next-step texts of transport failures, shared by the REST client and the
 * StartCLI pin probe (feature 014 research R-15), so both report the same texts for one failure.
 */
public final class TransportErrorTexts {

    public static final String TLS_ERROR_LIKELY_CAUSE =
        "The INUBIT server certificate is not in the trust store, does not match the pinned"
            + " fingerprint, or the hostname does not match";
    public static final String TIMEOUT_LIKELY_CAUSE =
        "The INUBIT server is slow, overloaded or not reachable through the network";
    public static final String TIMEOUT_NEXT_STEP =
        "Retry, check get_health, or raise the timeout in the configuration";
    public static final String UNREACHABLE_LIKELY_CAUSE =
        "The INUBIT server is down, the host name cannot be resolved, or the network/VPN is"
            + " not connected";
    public static final String INTERRUPTED_LIKELY_CAUSE = "The connection was interrupted";

    private TransportErrorTexts() {
    }

    /**
     * The simple class name of the innermost cause, as a message names it. A pin mismatch keeps
     * the name {@code CertificateException} it had before the pin got its own exception type.
     */
    public static String causeName(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof PinningTrustManager.PinMismatchException) {
            return CertificateException.class.getSimpleName();
        }
        return root.getClass().getSimpleName();
    }

    public static String tlsErrorNextStep(NodeId server) {
        return "Check tls.trustStore, tls.pinnedCertificateSha256 and"
            + " tls.disableHostnameVerification for " + server;
    }
}
