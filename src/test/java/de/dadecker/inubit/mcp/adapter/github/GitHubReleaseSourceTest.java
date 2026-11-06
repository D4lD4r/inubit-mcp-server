package de.dadecker.inubit.mcp.adapter.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.dadecker.inubit.mcp.application.ReleaseSource.ReleaseCheckException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubReleaseSourceTest {

    private static final String PATH = "/repos/D4lD4r/inubit-mcp-server/releases/latest";

    private WireMockServer github;
    private GitHubReleaseSource source;

    @BeforeEach
    void start() {
        github = new WireMockServer(wireMockConfig().dynamicPort());
        github.start();
        source = source(1 << 20);
    }

    @AfterEach
    void stop() {
        github.stop();
    }

    @Test
    void readsTheVersionFromTheReleaseTag() throws ReleaseCheckException {
        answer(200, "{\"tag_name\":\"v0.8.0\",\"draft\":false,\"prerelease\":false,"
            + "\"name\":\"ignored\",\"assets\":[]}");
        assertThat(source.latest().version()).hasToString("0.8.0");
    }

    @Test
    void sendsOnlyTheFixedRequestWithoutConfigurationData() throws ReleaseCheckException {
        answer(200, "{\"tag_name\":\"v0.8.0\"}");
        source.latest();
        List<LoggedRequest> requests = github.findAll(getRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(1);
        LoggedRequest request = requests.get(0);
        assertThat(request.getUrl()).isEqualTo(PATH);
        assertThat(request.getBody()).isEmpty();
        assertThat(request.getHeader("User-Agent")).isEqualTo("inubit-mcp-server/0.7.1");
        assertThat(request.getHeader("Accept")).isEqualTo("application/vnd.github+json");
        assertThat(request.getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28");
        assertThat(request.getHeaders().keys()).noneMatch(name -> name.equalsIgnoreCase("Cookie")
            || name.equalsIgnoreCase("Authorization"));
        for (HttpHeader header : request.getHeaders().all()) {
            assertThat(header.values()).noneMatch(value -> value.contains("acme")
                || value.contains("example.test"));
        }
    }

    @Test
    void anHttpErrorOrRateLimitFails() {
        answer(404, "{\"message\":\"Not Found\"}");
        assertThatExceptionOfType(ReleaseCheckException.class).isThrownBy(source::latest)
            .withMessage("HTTP 404");
        answer(403, "{\"message\":\"API rate limit exceeded\"}");
        assertThatExceptionOfType(ReleaseCheckException.class).isThrownBy(source::latest)
            .withMessage("HTTP 403");
    }

    @Test
    void anUnreadableAnswerFails() {
        for (String body : List.of("not json", "[]", "{}", "{\"tag_name\":7}",
            "{\"tag_name\":\"0.8.0\"}", "{\"tag_name\":\"vnext\"}", "{\"tag_name\":\"v0.8.0-rc.1\"}",
            "{\"tag_name\":\"v0.8.0\",\"draft\":true}",
            "{\"tag_name\":\"v0.8.0\",\"prerelease\":true}")) {
            answer(200, body);
            assertThatExceptionOfType(ReleaseCheckException.class).as(body)
                .isThrownBy(source::latest);
        }
    }

    @Test
    void anOversizedAnswerFails() {
        answer(200, "{\"tag_name\":\"v0.8.0\",\"body\":\"" + "x".repeat(2048) + "\"}");
        assertThatExceptionOfType(ReleaseCheckException.class)
            .isThrownBy(() -> source(1024).latest());
    }

    @Test
    void aSlowAnswerTimesOut() {
        github.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withBody("{\"tag_name\":\"v0.8.0\"}").withFixedDelay(3000)));
        long start = System.nanoTime();
        assertThatExceptionOfType(ReleaseCheckException.class).isThrownBy(source::latest)
            .withMessageContaining("no answer");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void anUnreachableSourceFails() {
        github.stop();
        assertThatExceptionOfType(ReleaseCheckException.class).isThrownBy(source::latest);
    }

    @Test
    void theProductionSourceTargetsTheProjectsLatestRelease() {
        assertThat(GitHubReleaseSource.LATEST).hasToString("https://api.github.com" + PATH);
    }

    private GitHubReleaseSource source(int maxBody) {
        return new GitHubReleaseSource(URI.create(github.baseUrl() + PATH), "0.7.1",
            Duration.ofMillis(500), Duration.ofMillis(500), maxBody);
    }

    private void answer(int status, String body) {
        github.resetAll();
        github.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
            .withHeader("Content-Type", "application/json").withBody(body)));
    }
}
