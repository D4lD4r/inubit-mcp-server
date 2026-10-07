package de.dadecker.inubit.mcp.adapter.soap;

import de.dadecker.inubit.mcp.adapter.rest.SslContexts;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.E2ePort;
import de.dadecker.inubit.mcp.infra.Secret;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

/**
 * The SOAP end-to-end client of one node (feature 004, research D-17, D-25 H8): the JDK
 * {@link HttpClient} with the node's TLS settings ({@link SslContexts#forServer}, the builder
 * the REST client uses: trust store, pinned certificate, hostname rule), HTTP/1.1, redirects
 * never followed.
 *
 * <ul>
 *   <li>The endpoint is the configured {@code e2e.soap.baseUrl} plus a relative path; a path
 *       with a scheme or host, {@code ..} or {@code .} segments (also percent-encoded), a query,
 *       a fragment or a backslash is {@code INVALID_INPUT} before anything is sent, and the
 *       resulting URL must stay below the base address.
 *   <li>Headers: {@code Content-Type: text/xml; charset=utf-8}, {@code SOAPAction} (quoted,
 *       empty if none), {@code X-Inubit-Mcp-Test-Id}; {@code Authorization: Basic} only from the
 *       configuration ({@code <PREFIX>_<GROUP>[_<NODE>]_E2E_USERNAME/_PASSWORD}). The envelope
 *       bytes are sent unchanged.
 *   <li>Any status is an answer (a SOAP fault is an answer, a redirect is not followed); the
 *       body is kept up to {@link E2ePort#MAX_BODY_BYTES}. A timeout is an exchange marked
 *       timed out; TLS failures are {@code TLS_ERROR}, other transport failures
 *       {@code UNREACHABLE} — messages name the node and the path, never credentials.
 * </ul>
 */
public final class SoapE2eClient implements E2ePort, AutoCloseable {

    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_.~!$&'()*+,;=:@%-]+$");

    /** Optional HTTP basic authentication of the SOAP endpoints (from configuration only). */
    public record BasicAuth(String username, Secret password) {
        public BasicAuth {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "BasicAuth[" + username + "]";
        }
    }

    private final NodeId node;
    private final URI baseUrl;
    private final Optional<BasicAuth> auth;
    private final HttpClient client;

    /**
     * @param server             the node (TLS settings, credential variables, id)
     * @param baseUrl            its {@code e2e.soap.baseUrl}
     * @param trustStorePassword the resolved trust-store password of the node, if any
     * @param auth               the configured e2e basic authentication, if any
     * @throws ToolErrorException {@code TLS_ERROR} if the trust store cannot be used
     */
    public SoapE2eClient(EffectiveNodeConfig server, URI baseUrl,
        Optional<Secret> trustStorePassword, Optional<BasicAuth> auth) {
        this.node = server.id();
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.auth = Objects.requireNonNull(auth, "auth");
        SSLContext ssl;
        try {
            ssl = SslContexts.forServer(server.credentialVariables(), server.tls(),
                trustStorePassword);
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(node));
        }
        this.client = HttpClient.newBuilder()
            .sslContext(ssl)
            .connectTimeout(server.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    @Override
    public Exchange post(Message message) {
        URI endpoint = endpoint(message.path());
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
            .timeout(message.timeout())
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("SOAPAction", "\"" + message.soapAction().orElse("") + "\"")
            .header("X-Inubit-Mcp-Test-Id", message.testId())
            .POST(HttpRequest.BodyPublishers.ofByteArray(message.envelope()));
        auth.ifPresent(basic -> request.header("Authorization", "Basic " + Base64.getEncoder()
            .encodeToString((basic.username() + ":" + basic.password().reveal())
                .getBytes(StandardCharsets.UTF_8))));
        long start = System.nanoTime();
        try {
            HttpResponse<InputStream> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                boolean truncated = false;
                for (int n = body.read(buffer); n >= 0; n = body.read(buffer)) {
                    int room = MAX_BODY_BYTES - out.size();
                    if (n > room) {
                        out.write(buffer, 0, room);
                        truncated = true;
                        break;
                    }
                    out.write(buffer, 0, n);
                }
                return new Exchange(endpoint.toString(), Optional.of(response.statusCode()),
                    out.toByteArray(), truncated, elapsed(start), false);
            }
        } catch (HttpTimeoutException e) {
            return new Exchange(endpoint.toString(), Optional.empty(), new byte[0], false,
                elapsed(start), true);
        } catch (IOException e) {
            if (hasCause(e, HttpTimeoutException.class)) {
                return new Exchange(endpoint.toString(), Optional.empty(), new byte[0], false,
                    elapsed(start), true);
            }
            throw transportError(message.path(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
                "The SOAP test on " + node + " was interrupted", "The MCP server is stopping",
                "Retry").withNode(node));
        }
    }

    /** The base address plus {@code path}, refused if it leaves the base (research D-25 H8). */
    URI endpoint(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        boolean valid = !trimmed.isEmpty() && !trimmed.startsWith("/");
        if (valid) {
            for (String segment : trimmed.split("/", -1)) {
                String decoded = segment.toLowerCase(Locale.ROOT).replace("%2e", ".");
                if (!SEGMENT.matcher(segment).matches() || decoded.equals(".")
                    || decoded.equals("..") || segment.toLowerCase(Locale.ROOT)
                        .contains("%2f") || segment.toLowerCase(Locale.ROOT).contains("%5c")) {
                    valid = false;
                    break;
                }
            }
        }
        URI endpoint = null;
        if (valid) {
            String base = baseUrl.toString();
            endpoint = URI.create((base.endsWith("/") ? base : base + "/") + trimmed)
                .normalize();
            String basePath = baseUrl.getPath() == null ? "" : baseUrl.getPath();
            valid = Objects.equals(endpoint.getScheme(), baseUrl.getScheme())
                && Objects.equals(endpoint.getHost(), baseUrl.getHost())
                && endpoint.getPort() == baseUrl.getPort() && endpoint.getRawQuery() == null
                && endpoint.getRawFragment() == null && endpoint.getPath().startsWith(
                    basePath.endsWith("/") ? basePath : basePath + "/");
        }
        if (!valid) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The endpoint path is not a plain path below the SOAP base address of " + node
                    + "; nothing was sent",
                "The path must be relative (no scheme or host), without '..', '.', query,"
                    + " fragment or backslash",
                "Give the path of the endpoint below e2e.soap.baseUrl, e.g."
                    + " /ibis/ws/Service-01").withNode(node));
        }
        return endpoint;
    }

    private ToolErrorException transportError(String path, IOException e) {
        if (hasCause(e, SSLException.class) || hasCause(e, CertificateException.class)) {
            return new ToolErrorException(ToolError.of(ErrorCode.TLS_ERROR,
                "The TLS connection for the SOAP test on " + node + " failed (" + path + ", "
                    + rootCause(e).getClass().getSimpleName() + ")",
                "The SOAP endpoint's certificate is not in the trust store, does not match the"
                    + " pinned fingerprint, or the hostname does not match",
                "Check tls.trustStore, tls.pinnedCertificateSha256 and e2e.soap.baseUrl for "
                    + node).withNode(node));
        }
        return new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
            "Cannot connect to the SOAP endpoint of " + node + " (" + path + ", "
                + rootCause(e).getClass().getSimpleName() + ")",
            hasCause(e, ConnectException.class) ? "The endpoint is down or the address is wrong"
                : "The connection was interrupted",
            "Check e2e.soap.baseUrl, DNS and the VPN, then retry").withNode(node));
    }

    private static Duration elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public String toString() {
        return "SoapE2eClient[" + node + ", " + baseUrl + "]";
    }
}
