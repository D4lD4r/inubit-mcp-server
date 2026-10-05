package de.dadecker.inubit.mcp.adapter.rest;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/** A minimal TLS endpoint on 127.0.0.1 that completes handshakes with a given key store. */
final class TlsTestServer implements AutoCloseable {

    private final SSLServerSocket socket;
    private final Thread acceptor;

    private TlsTestServer(SSLServerSocket socket) {
        this.socket = socket;
        this.acceptor = Thread.ofVirtual().start(this::serve);
    }

    static TlsTestServer start(Path keyStore) throws IOException, GeneralSecurityException {
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

    int port() {
        return socket.getLocalPort();
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

    @Override
    public void close() throws IOException, InterruptedException {
        socket.close();
        acceptor.join(2000);
    }
}
