package de.dadecker.inubit.mcp.adapter.rest;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.StandardConstants;

/**
 * A minimal TLS endpoint on 127.0.0.1 that completes handshakes with a given key store. It counts
 * the accepted TCP connections and records the SNI host names of the client hellos, so a test can
 * see a bare handshake that sends no HTTP request.
 */
public final class TlsTestServer implements AutoCloseable {

    private final SSLServerSocket socket;
    private final Thread acceptor;
    private final AtomicInteger connections = new AtomicInteger();
    private final List<String> serverNames = new CopyOnWriteArrayList<>();

    private TlsTestServer(SSLServerSocket socket) {
        this.socket = socket;
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setSNIMatchers(List.of(new RecordingMatcher()));
        socket.setSSLParameters(parameters);
        this.acceptor = Thread.ofVirtual().start(this::serve);
    }

    public static TlsTestServer start(Path keyStore) throws IOException,
        GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStore)) {
            store.load(in, TestCertificates.PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, TestCertificates.PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory()
            .createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        return new TlsTestServer(socket);
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** The TCP connections accepted so far, whether or not their handshake succeeded. */
    public int connections() {
        return connections.get();
    }

    /**
     * The SNI host names sent so far, one per client hello that carried one. A matcher records
     * them while the server reads the client hello, i.e. before the client's handshake completes.
     */
    public List<String> requestedServerNames() {
        return List.copyOf(serverNames);
    }

    /** Connects as a client to {@code host} and completes a handshake with HTTPS identification. */
    void handshake(SSLContext client, String host) throws IOException {
        try (SSLSocket connection = (SSLSocket) client.getSocketFactory()
            .createSocket(host, port())) {
            SSLParameters parameters = connection.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            connection.setSSLParameters(parameters);
            connection.startHandshake();
        }
    }

    private void serve() {
        while (!socket.isClosed()) {
            try {
                SSLSocket connection = (SSLSocket) socket.accept();
                connections.incrementAndGet();
                Thread.ofVirtual().start(() -> {
                    try (connection) {
                        connection.startHandshake();
                        respond(connection);
                    } catch (IOException e) {
                        // the client rejected the certificate or closed the connection
                    }
                });
            } catch (IOException e) {
                return;
            }
        }
    }

    /** Answers one HTTP request (if the client sends one) with {@code 204 No Content}. */
    private static void respond(SSLSocket connection) throws IOException {
        InputStream in = connection.getInputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        int b;
        while (matched < end.length && (b = in.read()) >= 0) {
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        if (matched == end.length) {
            connection.getOutputStream().write(("HTTP/1.1 204 No Content\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            connection.getOutputStream().flush();
        }
    }

    /** Records each SNI host name and accepts it. */
    private final class RecordingMatcher extends SNIMatcher {

        RecordingMatcher() {
            super(StandardConstants.SNI_HOST_NAME);
        }

        @Override
        public boolean matches(SNIServerName serverName) {
            serverNames.add(new SNIHostName(serverName.getEncoded()).getAsciiName());
            return true;
        }
    }

    @Override
    public void close() throws IOException, InterruptedException {
        socket.close();
        acceptor.join(2000);
    }
}
