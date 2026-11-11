package de.dadecker.inubit.mcp.adapter.cli.queue;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.Secret;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * The one-time trust store of one queue-entry helper call (feature 020, FR-014, research D-7):
 * a PKCS12 file whose only entry is the leaf certificate the server itself verified in its TLS
 * handshake, protected by a random one-time password (32 bytes, Base64). The file is created
 * private (owner-only on POSIX, this account and {@code SYSTEM} on Windows) in the call's private
 * directory and deleted with it; the password travels to the helper on stdin only and appears in
 * no text.
 */
public final class HelperTrustStore {

    /** The file name in the call's directory, {@code <tmp>/trust.p12}. */
    public static final String FILE_NAME = "trust.p12";
    private static final int PASSWORD_BYTES = 32;
    private static final String ALIAS = "server";
    private static final SecureRandom RANDOM = new SecureRandom();

    private HelperTrustStore() {
    }

    /**
     * Writes the new private file {@code file} with the leaf of {@code chain} as its only
     * trusted entry.
     *
     * @param chain the peer chain the server's handshake accepted, leaf first
     * @return the file and its one-time password
     * @throws IllegalArgumentException if {@code chain} is empty
     * @throws ToolErrorException {@code INTERNAL} with {@code node} if the file cannot be written
     *     (it is removed again; an existing file is left as it is)
     */
    public static HelperRequest.TrustStore write(NodeId node, Path file,
        List<X509Certificate> chain, PrivateFileSystem files) {
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("The verified peer chain is empty");
        }
        char[] password = newPassword();
        boolean created = false;
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry(ALIAS, chain.get(0));
            files.createFile(file);
            created = true;
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                store.store(out, password);
            }
            return new HelperRequest.TrustStore(file, Secret.of(new String(password)));
        } catch (IOException | GeneralSecurityException | UnsupportedOperationException e) {
            if (created) {
                deleteQuietly(file);
            }
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The trust store for the queue-entry helper could not be written on " + node
                    + " (" + e.getClass().getSimpleName() + "); the queue-entry helper was not"
                    + " started",
                e instanceof FileAlreadyExistsException
                    ? "A file of that name already exists in the call's private directory"
                    : "The temporary directory is not writable, or the access to the file cannot"
                        + " be restricted to the MCP server's account",
                "Check the temporary directory (java.io.tmpdir) of the MCP server and call the"
                    + " tool again").withNode(node));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** 32 random bytes in Base64; the bytes are zeroed. */
    private static char[] newPassword() {
        byte[] random = new byte[PASSWORD_BYTES];
        RANDOM.nextBytes(random);
        byte[] encoded = Base64.getEncoder().encode(random);
        Arrays.fill(random, (byte) 0);
        char[] password = new char[encoded.length];
        for (int i = 0; i < encoded.length; i++) {
            password[i] = (char) encoded[i];
        }
        Arrays.fill(encoded, (byte) 0);
        return password;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // the call's directory is deleted with everything in it
        }
    }
}
