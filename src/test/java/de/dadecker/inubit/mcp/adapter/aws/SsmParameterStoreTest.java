package de.dadecker.inubit.mcp.adapter.aws;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.Fetched;
import de.dadecker.inubit.mcp.config.ParameterType;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.infra.Secret;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T009 (feature 010, research R-1, R-2, R-11): the AWS adapter against a local WireMock stub of
 * the SSM JSON 1.1 protocol ({@code POST /}, {@code X-Amz-Target: AmazonSSM.GetParameters}).
 * Fully offline: every store gets an endpoint override pointing at the stub (or at a closed local
 * port), the AWS config and credentials files are empty temporary files set through the SDK's
 * system properties (never the developer's own files), and instance metadata is disabled.
 * Credentials and values are synthetic.
 */
@Timeout(60)
class SsmParameterStoreTest {

    private static final String TARGET = "AmazonSSM.GetParameters";
    private static final String JSON_11 = "application/x-amz-json-1.1";
    private static final StoreLocation LOCATION = new StoreLocation("eu-central-1",
        Optional.empty());
    private static final AwsCredentialsProvider TEST_CREDENTIALS = StaticCredentialsProvider
        .create(AwsBasicCredentials.create("test-access-key-1", "test-secret-key-1"));
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> AWS_PROPERTIES = List.of("aws.configFile",
        "aws.sharedCredentialsFile", "aws.disableEc2Metadata");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static WireMockServer wireMock;

    @TempDir
    Path temp;
    private final Map<String, String> savedProperties = new HashMap<>();

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void isolateFromTheMachine() throws IOException {
        wireMock.resetAll();
        for (String property : AWS_PROPERTIES) {
            savedProperties.put(property, System.getProperty(property));
        }
        System.setProperty("aws.disableEc2Metadata", "true");
        awsFiles("", "");
    }

    @AfterEach
    void restoreSystemProperties() {
        savedProperties.forEach((property, value) -> {
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        });
    }

    // --- values -------------------------------------------------------------------------------

    @Test
    void readsValuesAndTypesWithDecryptionInOneSignedRequest() {
        stub(getParameters().willReturn(ok("""
            {"Parameters":[
              {"Name":"/app/user","Type":"String","Value":"test-user-1","Version":1},
              {"Name":"/app/password","Type":"SecureString","Value":"test-password-1","Version":3},
              {"Name":"/app/list","Type":"StringList","Value":"a,b","Version":1}],
             "InvalidParameters":[]}""")));

        Fetched fetched = store().get(LOCATION, List.of("/app/user", "/app/password",
            "/app/list"), TIMEOUT);

        assertThat(perName(fetched)).containsOnly(
            Map.entry("/app/user", value("test-user-1", ParameterType.STRING)),
            Map.entry("/app/password", value("test-password-1", ParameterType.SECURE_STRING)),
            Map.entry("/app/list", value("a,b", ParameterType.STRING_LIST)));
        List<LoggedRequest> requests = requests();
        assertThat(requests).hasSize(1);
        JsonNode body = JSON.readTree(requests.get(0).getBodyAsString());
        assertThat(body.get("WithDecryption").asBoolean()).isTrue();
        assertThat(names(body)).containsExactly("/app/user", "/app/password", "/app/list");
        assertThat(requests.get(0).getHeader("Authorization"))
            .contains("test-access-key-1/").contains("/eu-central-1/ssm/aws4_request");
    }

    @Test
    void invalidAndMissingNamesAreNotFound() {
        stub(getParameters().willReturn(ok("""
            {"Parameters":[{"Name":"/app/user","Type":"String","Value":"test-user-1"}],
             "InvalidParameters":["/app/password"]}""")));

        Map<String, Fetched.Result> results = perName(store().get(LOCATION,
            List.of("/app/user", "/app/password", "/app/trust"), TIMEOUT));

        assertThat(results.get("/app/user")).isEqualTo(value("test-user-1", ParameterType.STRING));
        assertThat(kind(results.get("/app/password"))).isEqualTo(FailureKind.NOT_FOUND);
        assertThat(kind(results.get("/app/trust"))).isEqualTo(FailureKind.NOT_FOUND);
        assertThat(results).hasSize(3);
    }

    @Test
    void anUnsupportedTypeIsAnInvalidValue() {
        stub(getParameters().willReturn(ok("""
            {"Parameters":[{"Name":"/app/user","Type":"FutureType","Value":"test-user-1"}],
             "InvalidParameters":[]}""")));

        Map<String, Fetched.Result> results = perName(store().get(LOCATION, List.of("/app/user"),
            TIMEOUT));

        assertThat(kind(results.get("/app/user"))).isEqualTo(FailureKind.INVALID_VALUE);
    }

    @Test
    void twelveNamesAreReadInBatchesOfTenAndTwo() {
        List<String> all = IntStream.rangeClosed(1, 12)
            .mapToObj(i -> "/app/p%02d".formatted(i)).toList();
        stub(getParameters().withRequestBody(containing("\"/app/p01\""))
            .willReturn(ok(response(all.subList(0, 10)))));
        stub(getParameters().withRequestBody(containing("\"/app/p11\""))
            .willReturn(ok(response(all.subList(10, 12)))));

        Map<String, Fetched.Result> results = perName(store().get(LOCATION, all, TIMEOUT));

        assertThat(results).hasSize(12);
        all.forEach(name -> assertThat(results.get(name))
            .isEqualTo(value("value-of-" + name, ParameterType.SECURE_STRING)));
        List<List<String>> batches = requests().stream()
            .map(request -> names(JSON.readTree(request.getBodyAsString()))).toList();
        assertThat(batches).containsExactlyInAnyOrder(all.subList(0, 10), all.subList(10, 12));
    }

    @Test
    void noRequestWhenNoNameIsAsked() {
        Fetched fetched = store().get(LOCATION, List.of(), TIMEOUT);

        assertThat(perName(fetched)).isEmpty();
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    // --- store errors ------------------------------------------------------------------------

    @Test
    void accessDeniedFailsEveryNameOfTheCallAndKeepsTheMessageOnlyInTheLogDetail() {
        String message = "User test-role is not authorized to perform: ssm:GetParameters";
        stub(getParameters().willReturn(error(400, "AccessDeniedException", message)));

        Map<String, Fetched.Result> results = perName(store().get(LOCATION,
            List.of("/app/user", "/app/password"), TIMEOUT));

        assertThat(results).hasSize(2).allSatisfy((name, result) -> {
            assertThat(kind(result)).isEqualTo(FailureKind.ACCESS_DENIED);
            assertThat(((Fetched.Failure) result).logDetail()).contains(message);
            assertThat(result.toString()).doesNotContain("test-role");
        });
    }

    @Test
    void aKmsKeyDenialIsAccessDenied() {
        stub(getParameters().willReturn(error(400, "InvalidKeyId", "The key is disabled")));

        Map<String, Fetched.Result> results = perName(store().get(LOCATION,
            List.of("/app/password"), TIMEOUT));

        assertThat(kind(results.get("/app/password"))).isEqualTo(FailureKind.ACCESS_DENIED);
    }

    @Test
    void anExpiredSessionTokenMeansNoCredentials() {
        stub(getParameters().willReturn(error(400, "ExpiredTokenException",
            "The security token included in the request is expired")));

        Fetched.Failure failure = callFailure(store().get(LOCATION, List.of("/app/user"),
            TIMEOUT));

        assertThat(failure.kind()).isEqualTo(FailureKind.NO_CREDENTIALS);
        assertThat(failure.logDetail()).contains("expired");
    }

    @Test
    void throttlingIsUnreachable() {
        stub(getParameters().willReturn(error(400, "ThrottlingException", "Rate exceeded")));

        Fetched.Failure failure = callFailure(store().get(LOCATION, List.of("/app/user"),
            Duration.ofSeconds(30)));

        assertThat(failure.kind()).isEqualTo(FailureKind.UNREACHABLE);
        assertThat(failure.logDetail()).contains("Rate exceeded");
    }

    @Test
    void aResponseSlowerThanTheTimeoutIsUnreachable() {
        stub(getParameters().willReturn(ok(response(List.of("/app/user")))
            .withFixedDelay(5_000)));

        long start = System.nanoTime();
        Fetched.Failure failure = callFailure(store().get(LOCATION, List.of("/app/user"),
            Duration.ofMillis(500)));

        assertThat(failure.kind()).isEqualTo(FailureKind.UNREACHABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void aConnectionFailureIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SsmParameterStore store = new SsmParameterStore(
            URI.create("http://localhost:" + closedPort), TEST_CREDENTIALS);

        Fetched.Failure failure = callFailure(store.get(LOCATION, List.of("/app/user"),
            TIMEOUT));

        assertThat(failure.kind()).isEqualTo(FailureKind.UNREACHABLE);
        assertThat(failure.logDetail()).isNotBlank();
    }

    // --- credentials -------------------------------------------------------------------------

    @Test
    void anUnknownAwsProfileFailsTheCallWithoutARequest() throws IOException {
        awsFiles("""
            [profile other]
            region = eu-central-1
            """, """
            [other]
            aws_access_key_id = test-access-key-2
            aws_secret_access_key = test-secret-key-2
            """);
        SsmParameterStore store = new SsmParameterStore(endpoint(), null);

        Fetched.Failure failure = callFailure(store.get(
            new StoreLocation("eu-central-1", Optional.of("missing")), List.of("/app/user"),
            TIMEOUT));

        assertThat(failure.kind()).isEqualTo(FailureKind.UNKNOWN_PROFILE);
        assertThat(failure.logDetail()).contains("missing");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aNamedProfileSignsWithItsOwnCredentials() throws IOException {
        awsFiles("", """
            [test-profile]
            aws_access_key_id = test-access-key-3
            aws_secret_access_key = test-secret-key-3
            """);
        stub(getParameters().willReturn(ok(response(List.of("/app/user")))));
        SsmParameterStore store = new SsmParameterStore(endpoint(), null);

        Fetched fetched = store.get(new StoreLocation("eu-central-1",
            Optional.of("test-profile")), List.of("/app/user"), TIMEOUT);

        assertThat(perName(fetched)).containsOnlyKeys("/app/user");
        assertThat(requests().get(0).getHeader("Authorization")).contains("test-access-key-3/");
    }

    /** The SDK reads the AWS config while building the client; a failed build is not kept. */
    @Test
    void aMalformedAwsConfigMeansNoCredentialsWithoutARequestUntilItIsFixed() throws IOException {
        awsFiles("[profile broken\nregion = eu-central-1\n", "");
        stub(getParameters().willReturn(ok(response(List.of("/app/user")))));
        SsmParameterStore store = store();

        Fetched.Failure failure = callFailure(store.get(LOCATION, List.of("/app/user"),
            TIMEOUT));

        assertThat(failure.kind()).isEqualTo(FailureKind.NO_CREDENTIALS);
        assertThat(failure.logDetail()).isNotBlank();
        wireMock.verify(0, anyRequestedFor(anyUrl()));

        awsFiles("", "");
        assertThat(perName(store.get(LOCATION, List.of("/app/user"), TIMEOUT)))
            .containsOnlyKeys("/app/user");
    }

    @Test
    void failingProfileCredentialsMeanNoCredentialsWithoutARequest() throws IOException {
        awsFiles("", """
            [incomplete]
            aws_access_key_id = test-access-key-4
            """);
        SsmParameterStore store = new SsmParameterStore(endpoint(), null);

        Fetched.Failure failure = callFailure(store.get(
            new StoreLocation("eu-central-1", Optional.of("incomplete")), List.of("/app/user"),
            TIMEOUT));

        assertThat(failure.kind()).isEqualTo(FailureKind.NO_CREDENTIALS);
        assertThat(failure.logDetail()).isNotBlank();
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * The SSO provider reads its token cache below the user's home directory, which a test must
     * not touch; a provider failing the way the SSO provider does for an expired token stands in.
     */
    @Test
    void anExpiredSsoTokenMeansNoCredentialsWithoutARequest() {
        AwsCredentialsProvider expiredSso = () -> {
            throw SdkClientException.create("The SSO session associated with this profile has"
                + " expired or is otherwise invalid. To refresh this SSO session run aws sso"
                + " login with the corresponding profile.");
        };
        SsmParameterStore store = new SsmParameterStore(endpoint(), expiredSso);

        Fetched.Failure failure = callFailure(store.get(LOCATION, List.of("/app/user"),
            TIMEOUT));

        assertThat(failure.kind()).isEqualTo(FailureKind.NO_CREDENTIALS);
        assertThat(failure.logDetail()).contains("SSO session");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    // --- helpers -----------------------------------------------------------------------------

    private static SsmParameterStore store() {
        return new SsmParameterStore(endpoint(), TEST_CREDENTIALS);
    }

    private static URI endpoint() {
        return URI.create("http://localhost:" + wireMock.port());
    }

    /** Points the SDK at temporary AWS config and credentials files with the given content. */
    private void awsFiles(String config, String credentials) throws IOException {
        Path configFile = Files.writeString(temp.resolve("config"), config);
        Path credentialsFile = Files.writeString(temp.resolve("credentials"), credentials);
        System.setProperty("aws.configFile", configFile.toString());
        System.setProperty("aws.sharedCredentialsFile", credentialsFile.toString());
    }

    private static MappingBuilder getParameters() {
        return post(urlEqualTo("/")).withHeader("X-Amz-Target", equalTo(TARGET));
    }

    private static void stub(MappingBuilder mapping) {
        wireMock.stubFor(mapping);
    }

    private static ResponseDefinitionBuilder ok(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", JSON_11).withBody(body);
    }

    private static ResponseDefinitionBuilder error(int status, String type,
        String message) {
        return aResponse().withStatus(status).withHeader("Content-Type", JSON_11)
            .withBody("{\"__type\":\"" + type + "\",\"message\":\"" + message + "\"}");
    }

    /** A response with {@code value-of-<name>} as the SecureString value of every name. */
    private static String response(List<String> names) {
        List<String> parameters = new ArrayList<>();
        for (String name : names) {
            parameters.add("{\"Name\":\"" + name + "\",\"Type\":\"SecureString\",\"Value\":"
                + "\"value-of-" + name + "\",\"Version\":1}");
        }
        return "{\"Parameters\":[" + String.join(",", parameters) + "],\"InvalidParameters\":[]}";
    }

    private static List<LoggedRequest> requests() {
        return wireMock.findAll(postRequestedFor(urlEqualTo("/")));
    }

    private static List<String> names(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.get("Names").forEach(name -> names.add(name.asString()));
        return names;
    }

    private static Fetched.Value value(String value, ParameterType type) {
        return new Fetched.Value(Secret.of(value), type);
    }

    private static Map<String, Fetched.Result> perName(Fetched fetched) {
        assertThat(fetched).isInstanceOf(Fetched.PerName.class);
        return ((Fetched.PerName) fetched).results();
    }

    private static Fetched.Failure callFailure(Fetched fetched) {
        assertThat(fetched).isInstanceOf(Fetched.CallFailed.class);
        return ((Fetched.CallFailed) fetched).failure();
    }

    private static FailureKind kind(Fetched.Result result) {
        assertThat(result).isInstanceOf(Fetched.Failure.class);
        return ((Fetched.Failure) result).kind();
    }
}
