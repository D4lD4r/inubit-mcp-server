package de.dadecker.inubit.mcp.adapter.rest;

import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig.Tls;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.Arrays;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Builds the {@link SSLContext} of one server (research R-5, R-5a): trust from
 * {@code tls.trustStore} (PKCS12, password-less allowed) or the JVM default trust store, wrapped
 * in a {@link PinningTrustManager}. Each call returns a new context, so TLS settings never leak
 * between servers.
 */
public final class SslContexts {

    private SslContexts() {
    }

    /**
     * @param variables          the node's credential variables, named in errors
     * @param trustStorePassword the resolved {@code <PREFIX>_…_TRUSTSTORE_PASSWORD}, if any
     * @throws ToolErrorException {@code TLS_ERROR} if the trust store cannot be used
     */
    public static SSLContext forServer(CredentialVariables variables, Tls tls,
        Optional<Secret> trustStorePassword) {
        KeyStore trustStore = tls.trustStore()
            .map(path -> loadTrustStore(path, trustStorePassword, variables))
            .orElse(null);
        X509ExtendedTrustManager trustManager = new PinningTrustManager(trustManager(trustStore),
            tls.pinnedCertificateSha256(), tls.disableHostnameVerification());
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustManager}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS is not available in this JVM", e);
        }
    }

    /**
     * Loads a PKCS12 trust store; without a password only unencrypted entries are readable.
     * Errors name the node's trust-store password variables under the profile's effective prefix
     * (002 review U2, research D-6).
     */
    static KeyStore loadTrustStore(Path path, Optional<Secret> password,
        CredentialVariables nodeVariables) {
        String variables = nodeVariables.either(CredentialVariables.TRUSTSTORE_PASSWORD);
        char[] chars = password.map(secret -> secret.reveal().toCharArray()).orElse(null);
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, chars);
            if (store.size() == 0) {
                throw tlsError("The trust store " + path + " contains no certificates"
                    + (password.isEmpty() ? " that are readable without a password" : ""),
                    "The trust store is empty, or its certificates are encrypted and neither "
                        + variables + " is set",
                    "Import the INUBIT server certificate into a password-less PKCS12 trust"
                        + " store, or set " + variables + " (not allowed together with a CLI)");
            }
            return store;
        } catch (NoSuchFileException e) {
            throw tlsError("The trust store " + path + " does not exist",
                "tls.trustStore points to a missing file", "Fix tls.trustStore in the config");
        } catch (IOException | GeneralSecurityException e) {
            // IOException also covers a wrong password; the message is not forwarded
            throw tlsError("The trust store " + path + " cannot be read ("
                    + e.getClass().getSimpleName() + ")",
                "The file is not a PKCS12 trust store, or the trust-store password is wrong",
                "Check tls.trustStore and " + variables);
        } finally {
            if (chars != null) {
                Arrays.fill(chars, '\0');
            }
        }
    }

    /** The PKIX trust manager of {@code trustStore}, or of the JVM default when {@code null}. */
    static X509ExtendedTrustManager trustManager(KeyStore trustStore) {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
            factory.init(trustStore);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509ExtendedTrustManager extended) {
                    return extended;
                }
            }
            throw new IllegalStateException("No X509ExtendedTrustManager available");
        } catch (KeyStoreException e) {
            throw tlsError("The trust store cannot be used (" + e.getClass().getSimpleName() + ")",
                "The trust store has no usable certificates", "Check tls.trustStore");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PKIX is not available in this JVM", e);
        }
    }

    private static ToolErrorException tlsError(String message, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.TLS_ERROR, message, likelyCause,
            nextStep));
    }
}
