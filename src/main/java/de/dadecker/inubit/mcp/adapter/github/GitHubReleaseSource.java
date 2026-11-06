package de.dadecker.inubit.mcp.adapter.github;

import de.dadecker.inubit.mcp.application.ReleaseSource;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import java.io.ByteArrayOutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The latest release of this project from the public GitHub REST API (feature 016, research R-1,
 * R-2, R-5, R-9). One anonymous GET to a fixed URL; the request carries no configuration data,
 * only the {@code User-Agent} {@code inubit-mcp-server/<version>} (FR-014). It honours the JVM's
 * standard proxy settings (FR-016) and keeps the JDK's TLS verification. Only {@code tag_name}
 * ({@code v} + SemVer of a release) is used from the answer.
 */
public final class GitHubReleaseSource implements ReleaseSource {

    /** The "latest release" endpoint of the project. */
    public static final URI LATEST =
        URI.create("https://api.github.com/repos/D4lD4r/inubit-mcp-server/releases/latest");

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BODY = 1 << 20;

    private final URI uri;
    private final String userAgent;
    private final Duration requestTimeout;
    private final int maxBody;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    /** The production source for the running {@code version}. */
    public GitHubReleaseSource(String version) {
        this(LATEST, version, CONNECT_TIMEOUT, REQUEST_TIMEOUT, MAX_BODY);
    }

    GitHubReleaseSource(URI uri, String version, Duration connectTimeout, Duration requestTimeout,
        int maxBody) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.userAgent = "inubit-mcp-server/" + Objects.requireNonNull(version, "version");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.maxBody = maxBody;
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL);
        // without an explicit selector the JDK client uses no proxy at all
        Optional.ofNullable(ProxySelector.getDefault()).ifPresent(builder::proxy);
        this.client = builder.build();
    }

    @Override
    public LatestRelease latest() throws ReleaseCheckException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .GET()
            .build();
        CompletableFuture<HttpResponse<byte[]>> pending =
            client.sendAsync(request, info -> new LimitedBody(maxBody));
        HttpResponse<byte[]> response;
        try {
            response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new ReleaseCheckException("no answer within " + requestTimeout.toSeconds() + " s");
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ReleaseCheckException("interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ReleaseCheckException(cause.getClass().getSimpleName());
        }
        if (response.statusCode() != 200) {
            throw new ReleaseCheckException("HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    private LatestRelease parse(byte[] body) throws ReleaseCheckException {
        JsonNode tree;
        try {
            tree = json.readTree(body);
        } catch (JacksonException e) {
            throw new ReleaseCheckException("unreadable answer");
        }
        if (tree == null || !tree.isObject()) {
            throw new ReleaseCheckException("unreadable answer");
        }
        if (tree.path("draft").asBoolean(false) || tree.path("prerelease").asBoolean(false)) {
            throw new ReleaseCheckException("latest release is a draft or pre-release");
        }
        JsonNode tag = tree.get("tag_name");
        if (tag == null || !tag.isString() || !tag.asString().startsWith("v")) {
            throw new ReleaseCheckException("no release tag of the form v<version>");
        }
        SemVer version = SemVer.parse(tag.asString().substring(1))
            .filter(v -> v.preRelease().isEmpty())
            .orElseThrow(() -> new ReleaseCheckException("no release tag of the form v<version>"));
        return new LatestRelease(version);
    }

    /** Collects the body up to {@code limit} bytes; a longer body fails the request. */
    private static final class LimitedBody implements BodySubscriber<byte[]> {

        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new BodyTooLargeException());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
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

    private static final class BodyTooLargeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("answer too large", null, false, false);
        }
    }
}
