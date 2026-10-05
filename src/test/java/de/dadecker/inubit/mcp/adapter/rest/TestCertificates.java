package de.dadecker.inubit.mcp.adapter.rest;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Self-signed, test-only TLS material, generated once per test JVM with the JDK's
 * {@code keytool} into {@code target/test-tls/} (never committed, regenerated on every run).
 *
 * <ul>
 *   <li>{@code localhost.p12}: key + certificate {@code CN=localhost}, SAN {@code localhost} and
 *       {@code 127.0.0.1} (hostname verification succeeds).
 *   <li>{@code selfsigned.p12}: {@code CN=selfsigned.example.test}, no SAN, {@code CA:TRUE},
 *       like the certificate of research R-5a (hostname verification cannot succeed).
 *   <li>{@code rogue.p12}: {@code CN=localhost} with SAN, but not in any trust store.
 *   <li>{@code truststore.p12}: password-less PKCS12 with the localhost and selfsigned
 *       certificates.
 *   <li>{@code protected-truststore.p12}: the same certificates, protected by {@link #PASSWORD}.
 * </ul>
 */
public final class TestCertificates {

    /** Test-only password of the generated key stores; never a real credential. */
    public static final String PASSWORD = "test-only-pw";

    private static final TestCertificates INSTANCE = generate();

    private final Path directory;
    private final X509Certificate localhost;
    private final X509Certificate selfsigned;
    private final X509Certificate rogue;

    private TestCertificates(Path directory, X509Certificate localhost, X509Certificate selfsigned,
        X509Certificate rogue) {
        this.directory = directory;
        this.localhost = localhost;
        this.selfsigned = selfsigned;
        this.rogue = rogue;
    }

    public static TestCertificates get() {
        return INSTANCE;
    }

    public Path localhostKeyStore() {
        return directory.resolve("localhost.p12");
    }

    public Path selfsignedKeyStore() {
        return directory.resolve("selfsigned.p12");
    }

    public Path rogueKeyStore() {
        return directory.resolve("rogue.p12");
    }

    public Path trustStore() {
        return directory.resolve("truststore.p12");
    }

    public Path protectedTrustStore() {
        return directory.resolve("protected-truststore.p12");
    }

    public X509Certificate localhostCertificate() {
        return localhost;
    }

    public X509Certificate selfsignedCertificate() {
        return selfsigned;
    }

    public X509Certificate rogueCertificate() {
        return rogue;
    }

    /** SHA-256 fingerprint as {@code AA:BB:…} (the config format). */
    public static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A started WireMock server on 127.0.0.1, HTTPS only, presenting {@code keyStore}. */
    public static WireMockServer httpsWireMock(Path keyStore) {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options()
            .httpDisabled(true)
            .dynamicHttpsPort()
            .bindAddress("127.0.0.1")
            .keystorePath(keyStore.toString())
            .keystorePassword(PASSWORD)
            .keyManagerPassword(PASSWORD)
            .keystoreType("PKCS12"));
        server.start();
        return server;
    }

    private static TestCertificates generate() {
        try {
            Path directory = Path.of(System.getProperty("basedir", "."), "target", "test-tls");
            deleteRecursively(directory);
            Files.createDirectories(directory);
            keytool(directory, "localhost.p12", "CN=localhost",
                "san=dns:localhost,ip:127.0.0.1");
            keytool(directory, "selfsigned.p12", "CN=selfsigned.example.test", "bc:c");
            keytool(directory, "rogue.p12", "CN=localhost", "san=dns:localhost,ip:127.0.0.1");
            X509Certificate localhost = certificate(directory.resolve("localhost.p12"));
            X509Certificate selfsigned = certificate(directory.resolve("selfsigned.p12"));
            X509Certificate rogue = certificate(directory.resolve("rogue.p12"));
            writeTrustStore(directory.resolve("truststore.p12"), null, localhost, selfsigned);
            writeTrustStore(directory.resolve("protected-truststore.p12"),
                PASSWORD.toCharArray(), localhost, selfsigned);
            return new TestCertificates(directory, localhost, selfsigned, rogue);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate test certificates", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while generating test certificates", e);
        }
    }

    private static void keytool(Path directory, String file, String dname, String extension)
        throws IOException, InterruptedException {
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? "keytool.exe" : "keytool");
        List<String> command = new ArrayList<>(List.of(keytool.toString(), "-genkeypair",
            "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
            "-sigalg", "SHA256withECDSA", "-dname", dname, "-ext", extension,
            "-validity", "3650", "-storetype", "PKCS12",
            "-keystore", directory.resolve(file).toString(),
            "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("keytool failed for " + file + ": " + output);
        }
    }

    private static X509Certificate certificate(Path keyStore)
        throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStore)) {
            store.load(in, PASSWORD.toCharArray());
        }
        return (X509Certificate) store.getCertificate("server");
    }

    private static void writeTrustStore(Path file, char[] password,
        X509Certificate... certificates) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        for (int i = 0; i < certificates.length; i++) {
            store.setCertificateEntry("cert" + i, certificates[i]);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, password);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
