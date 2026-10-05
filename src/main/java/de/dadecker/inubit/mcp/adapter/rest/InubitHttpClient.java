package de.dadecker.inubit.mcp.adapter.rest;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

/**
 * The REST client of one INUBIT server (research R-5): its own {@link HttpClient} with the
 * server's {@code SSLContext} ({@link SslContexts}), HTTP/1.1, no redirects.
 *
 * <ul>
 *   <li>Every request carries the {@link #ACCEPT} header and, if credentials are configured, an
 *       {@code Authorization: Basic} header that is built from the {@link Secret} for each
 *       request.
 *   <li>The request timeout is the server's {@code timeout}.
 *   <li>Failures become {@link ToolErrorException}s with the server id: 401 → {@code AUTH_FAILED}
 *       (the body is not read), 403 → {@code FORBIDDEN}, 404 → {@code NOT_FOUND}, 503 →
 *       {@code MAINTENANCE_MODE} if the {@link MaintenanceProbe} reports maintenance, otherwise
 *       {@code UNREACHABLE} (the server answered but is not serving), a body above
 *       {@link #MAX_BODY_BYTES} → {@code UNEXPECTED_RESPONSE}, any other
 *       non-2xx status → {@code UNEXPECTED_RESPONSE} with a scrubbed excerpt; timeouts →
 *       {@code TIMEOUT}, refused connections and unknown hosts → {@code UNREACHABLE}, TLS
 *       failures → {@code TLS_ERROR}. Statuses passed as {@code acceptedStatuses} are returned
 *       instead (e.g. 503 of {@code /ready}).
 *   <li>No message or excerpt contains a credential: the text is scrubbed, and exception
 *       messages are never forwarded (only their class names).
 * </ul>
 */
public final class InubitHttpClient implements AutoCloseable {

    /** The {@code Accept} header of every request (research R-5; required from 9.0 on). */
    public static final String ACCEPT = "application/json, application/xml;q=0.9, */*;q=0.8";

    /** Upper bound of a response body; larger responses are {@code UNEXPECTED_RESPONSE}. */
    public static final long MAX_BODY_BYTES = 64L << 20;
    /** Raw prefix of an error body that is turned into an excerpt. */
    static final int EXCERPT_WINDOW = 4096;
    /** Scrubbed beyond the window, so that no secret crossing the cut survives in part. */
    private static final int EXCERPT_OVERLAP = 512;
    static final String UNAVAILABLE_CAUSE =
        "INUBIT answered 503: starting up, overloaded, or behind a proxy that is not serving";
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Username (not secret, FR-025) and password of the INUBIT account. */
    public record Credentials(String username, Secret password) {
        public Credentials {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }
    }

    private final NodeId server;
    private final URI baseUrl;
    private final Duration timeout;
    private final Optional<Credentials> credentials;
    private final SecretScrubber scrubber;
    private final MaintenanceProbe maintenanceProbe;
    private final long maxBodyBytes;
    private final CredentialGuard guard;
    private final HttpClient client;

    /**
     * @param trustStorePassword the resolved trust-store password, if the trust store needs one
     * @param maintenanceProbe asked once per HTTP 503 whether the server is in maintenance mode
     * @throws ToolErrorException {@code TLS_ERROR} (with the server id) if the trust store or the
     *     pin cannot be used
     */
    public InubitHttpClient(EffectiveNodeConfig server, Optional<Credentials> credentials,
        Optional<Secret> trustStorePassword, MaintenanceProbe maintenanceProbe,
        SecretScrubber scrubber) {
        this(server, credentials, trustStorePassword, maintenanceProbe, scrubber,
            new CredentialGuard(server.credentialVariables(), Clock.systemUTC()));
    }

    /** As above, with the server's shared {@link CredentialGuard} (Phase 3 review M2). */
    public InubitHttpClient(EffectiveNodeConfig server, Optional<Credentials> credentials,
        Optional<Secret> trustStorePassword, MaintenanceProbe maintenanceProbe,
        SecretScrubber scrubber, CredentialGuard guard) {
        this(server, credentials, trustStorePassword, maintenanceProbe, scrubber, guard,
            MAX_BODY_BYTES);
    }

    InubitHttpClient(EffectiveNodeConfig server, Optional<Credentials> credentials,
        Optional<Secret> trustStorePassword, MaintenanceProbe maintenanceProbe,
        SecretScrubber scrubber, long maxBodyBytes) {
        this(server, credentials, trustStorePassword, maintenanceProbe, scrubber,
            new CredentialGuard(server.credentialVariables(), Clock.systemUTC()), maxBodyBytes);
    }

    private InubitHttpClient(EffectiveNodeConfig server, Optional<Credentials> credentials,
        Optional<Secret> trustStorePassword, MaintenanceProbe maintenanceProbe,
        SecretScrubber scrubber, CredentialGuard guard, long maxBodyBytes) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.server = server.id();
        this.baseUrl = server.baseUrl();
        this.timeout = server.timeout();
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.maintenanceProbe = Objects.requireNonNull(maintenanceProbe, "maintenanceProbe");
        this.maxBodyBytes = maxBodyBytes;
        credentials.ifPresent(c -> {
            scrubber.register(c.password());
            scrubber.registerBasicAuth(c.username(), c.password());
        });
        this.client = HttpClient.newBuilder()
            .sslContext(sslContext(server, trustStorePassword))
            .connectTimeout(server.timeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    /** A client with the resolved credentials of {@code server}, scrubbing globally. */
    public static InubitHttpClient create(EffectiveNodeConfig server,
        NodeCredentials credentials, MaintenanceProbe maintenanceProbe) {
        return create(server, credentials, maintenanceProbe, SecretScrubber.global());
    }

    /** A client with the resolved credentials of {@code server}. */
    public static InubitHttpClient create(EffectiveNodeConfig server,
        NodeCredentials credentials, MaintenanceProbe maintenanceProbe,
        SecretScrubber scrubber) {
        return create(server, credentials, maintenanceProbe, scrubber,
            new CredentialGuard(server.credentialVariables(), Clock.systemUTC()));
    }

    /** A client with the resolved credentials and the shared credential guard of a server. */
    public static InubitHttpClient create(EffectiveNodeConfig server,
        NodeCredentials credentials, MaintenanceProbe maintenanceProbe,
        SecretScrubber scrubber, CredentialGuard guard) {
        Optional<Credentials> account = credentials.username().isPresent()
            && credentials.password().isPresent()
            ? Optional.of(new Credentials(credentials.username().get().value(),
                credentials.password().get().value()))
            : Optional.empty();
        return new InubitHttpClient(server, account,
            credentials.trustStorePassword().map(SourcedValue::value), maintenanceProbe,
            scrubber, guard);
    }

    private static SSLContext sslContext(EffectiveNodeConfig server,
        Optional<Secret> trustStorePassword) {
        try {
            return SslContexts.forServer(server.credentialVariables(), server.tls(),
                trustStorePassword);
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(server.id()));
        } catch (IllegalArgumentException e) {
            // malformed pin or a pin-less disableHostnameVerification; the message has no value
            throw new ToolErrorException(ToolError.of(ErrorCode.TLS_ERROR,
                "The TLS settings of " + server.id() + " are invalid: " + e.getMessage(),
                "tls.pinnedCertificateSha256 is malformed or missing",
                "Fix the tls block of " + server.id() + " (run --check-config)")
                .withNode(server.id()));
        }
    }

    /** Percent-encodes one path segment (spaces as {@code %20}, {@code /} as {@code %2F}). */
    public static String encodePathSegment(String segment) {
        return encode(segment);
    }

    /**
     * {@code GET <baseUrl><path>?<query>}.
     *
     * @param path absolute path below the base URL, already encoded (see
     *     {@link #encodePathSegment})
     * @param query parameters in iteration order; keys and values are encoded here
     * @param acceptedStatuses non-2xx statuses that are returned instead of mapped to an error
     */
    public RestResponse get(String path, Map<String, String> query, int... acceptedStatuses) {
        return send("GET", path, HttpRequest.newBuilder(uri(path, query)).GET(), true,
            acceptedStatuses);
    }

    /** As {@link #get}, but never with the {@code Authorization} header (public endpoints). */
    public RestResponse getWithoutCredentials(String path, Map<String, String> query,
        int... acceptedStatuses) {
        return send("GET", path, HttpRequest.newBuilder(uri(path, query)).GET(), false,
            acceptedStatuses);
    }

    /** {@code POST} with a text body of the given content type; see {@link #get}. */
    public RestResponse post(String path, Map<String, String> query, String body,
        String contentType, int... acceptedStatuses) {
        return send("POST", path, HttpRequest.newBuilder(uri(path, query))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), true,
            acceptedStatuses);
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public String toString() {
        return "InubitHttpClient[" + server + "]";
    }

    private RestResponse send(String method, String path, HttpRequest.Builder builder,
        boolean authenticate, int[] acceptedStatuses) {
        builder.timeout(timeout).header("Accept", ACCEPT);
        if (!authenticate || credentials.isEmpty()) {
            return evaluate(method, path, exchange(method, path, builder), acceptedStatuses);
        }
        // at most one failed login per server and pause (Phase 3 review M2)
        try (CredentialGuard.Permit permit = guard.acquire()) {
            builder.header("Authorization", basicAuth(credentials.get()));
            HttpResponse<byte[]> response = exchange(method, path, builder);
            int status = response.statusCode();
            if (status == 401) {
                permit.rejected();
            } else if ((status >= 200 && status < 300) || status == 403) {
                permit.accepted();
            }
            return evaluate(method, path, response, acceptedStatuses);
        }
    }

    /** The guard of this server's authenticated calls (shared with its CLI calls). */
    public CredentialGuard credentialGuard() {
        return guard;
    }

    private HttpResponse<byte[]> exchange(String method, String path,
        HttpRequest.Builder builder) {
        try {
            return client.send(builder.build(), info -> new LimitedBody(maxBodyBytes));
        } catch (IOException e) {
            if (hasCause(e, ResponseTooLargeException.class)) {
                throw new ToolErrorException(error(ErrorCode.UNEXPECTED_RESPONSE,
                    "The INUBIT response to " + method + " " + path + " is too large (more than "
                        + (maxBodyBytes >> 20) + " MB)",
                    "The request selects far more data than expected",
                    "Narrow the request (filters, paging)"));
            }
            throw new ToolErrorException(transportError(method, path, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolErrorException(error(ErrorCode.TIMEOUT,
                method + " " + path + " was cancelled before INUBIT answered",
                "The overall deadline of the request expired",
                "Retry, or raise the timeout in the configuration"));
        }
    }

    private RestResponse evaluate(String method, String path, HttpResponse<byte[]> response,
        int[] acceptedStatuses) {
        int status = response.statusCode();
        RestResponse result = new RestResponse(status,
            response.headers().firstValue("Content-Type"), response.body());
        if ((status >= 200 && status < 300)
            || Arrays.stream(acceptedStatuses).anyMatch(accepted -> accepted == status)) {
            return result;
        }
        throw new ToolErrorException(statusError(method, path, result));
    }

    private static String basicAuth(Credentials credentials) {
        String pair = credentials.username() + ":" + credentials.password().reveal();
        return "Basic " + Base64.getEncoder()
            .encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private URI uri(String path, Map<String, String> query) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/'");
        }
        StringBuilder text = new StringBuilder(baseUrl.toString()).append(path);
        if (!query.isEmpty()) {
            StringJoiner parameters = new StringJoiner("&", "?", "");
            query.forEach((key, value) -> parameters.add(encode(key) + "=" + encode(value)));
            text.append(parameters);
        }
        return URI.create(text.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private ToolError statusError(String method, String path, RestResponse response) {
        String request = method + " " + path;
        int status = response.status();
        return switch (status) {
            case 401 -> error(ErrorCode.AUTH_FAILED,
                "INUBIT rejected the credentials (HTTP 401) for " + request,
                "Wrong username or password, or the account is locked",
                guard.fixCredentials());
            case 403 -> error(ErrorCode.FORBIDDEN,
                "The INUBIT account is not allowed to call " + request + " (HTTP 403)",
                "The account lacks the INUBIT permission (or license) for this function",
                "Ask the INUBIT administrators for the permission, or use another account");
            case 404 -> error(ErrorCode.NOT_FOUND,
                "INUBIT has no resource for " + request + " (HTTP 404)",
                "The name or id does not exist on this INUBIT server, or the endpoint is missing in"
                    + " this INUBIT version",
                "Check the name or id, e.g. with the list tools");
            case 503 -> inMaintenance()
                ? error(ErrorCode.MAINTENANCE_MODE,
                    "INUBIT is in maintenance mode (HTTP 503 for " + request + ")",
                    "An administrator switched the INUBIT server to maintenance mode",
                    "Retry after the maintenance window")
                : error(ErrorCode.UNREACHABLE,
                    "INUBIT is not serving (HTTP 503 for " + request + ")",
                    UNAVAILABLE_CAUSE, "run get_health")
                    .withExcerpt(excerpt(response));
            default -> error(ErrorCode.UNEXPECTED_RESPONSE,
                "Unexpected HTTP " + status + " from INUBIT for " + request,
                "The request was not accepted, or the INUBIT server failed internally",
                "See the excerpt; check the INUBIT system log for the time of the call")
                .withExcerpt(excerpt(response));
        };
    }

    private boolean inMaintenance() {
        try {
            return maintenanceProbe.maintenance().orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ToolError transportError(String method, String path, IOException e) {
        String request = method + " " + path;
        if (hasCause(e, SSLException.class) || hasCause(e, CertificateException.class)) {
            return error(ErrorCode.TLS_ERROR,
                "The TLS connection to " + server + " failed for " + request + " ("
                    + rootCause(e).getClass().getSimpleName() + ")",
                "The INUBIT server certificate is not in the trust store, does not match the pinned"
                    + " fingerprint, or the hostname does not match",
                "Check tls.trustStore, tls.pinnedCertificateSha256 and"
                    + " tls.disableHostnameVerification for " + server);
        }
        if (hasCause(e, HttpTimeoutException.class)) {
            return error(ErrorCode.TIMEOUT,
                "INUBIT did not answer " + request + " within " + Durations.human(timeout),
                "The INUBIT server is slow, overloaded or not reachable through the network",
                "Retry, check get_health, or raise the timeout in the configuration");
        }
        boolean unreachable = hasCause(e, ConnectException.class)
            || hasCause(e, UnresolvedAddressException.class)
            || hasCause(e, UnknownHostException.class)
            || hasCause(e, NoRouteToHostException.class);
        return error(ErrorCode.UNREACHABLE,
            "Cannot connect to " + server + " for " + request + " ("
                + rootCause(e).getClass().getSimpleName() + ")",
            unreachable
                ? "The INUBIT server is down, the host name cannot be resolved, or the network/VPN is"
                    + " not connected"
                : "The connection was interrupted",
            "Check baseUrl, DNS and the VPN, then retry");
    }

    private ToolError error(ErrorCode code, String message, String likelyCause, String nextStep) {
        return ToolError.of(code, scrubber.scrub(message), scrubber.scrub(likelyCause),
            scrubber.scrub(nextStep)).withNode(server);
    }

    /**
     * The first {@value #EXCERPT_WINDOW} chars of the body, HTML tags removed and whitespace
     * collapsed, scrubbed. The window is scrubbed together with an overlap before it is cut, so a
     * secret crossing the cut is masked as a whole; ToolError cuts the result to 500 chars.
     */
    private String excerpt(RestResponse response) {
        String raw = response.bodyText();
        String window = scrubber.scrub(
            raw.substring(0, Math.min(raw.length(), EXCERPT_WINDOW + EXCERPT_OVERLAP)));
        String text = window.substring(0, Math.min(window.length(), EXCERPT_WINDOW));
        boolean html = response.contentType()
            .map(type -> type.toLowerCase(Locale.ROOT).contains("html"))
            .orElse(text.stripLeading().startsWith("<html"));
        if (html) {
            text = HTML_TAG.matcher(text).replaceAll(" ");
        }
        text = WHITESPACE.matcher(text).replaceAll(" ").strip();
        return scrubber.scrub(text);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    /** Signals that a response body exceeded the limit. */
    private static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        ResponseTooLargeException() {
            super("response body too large");
        }
    }

    /** Collects the body up to {@code max} bytes and fails (cancelling the stream) beyond. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final long max;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        LimitedBody(long max) {
            this.max = max;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription newSubscription) {
            this.subscription = newSubscription;
            newSubscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (bytes.size() + (long) item.remaining() > max) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
