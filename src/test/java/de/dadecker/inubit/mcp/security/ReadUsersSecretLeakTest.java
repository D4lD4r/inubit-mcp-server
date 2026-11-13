package de.dadecker.inubit.mcp.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliUserRunner;
import de.dadecker.inubit.mcp.adapter.cli.admin.FingerParser;
import de.dadecker.inubit.mcp.adapter.cli.admin.UserGroupsParser;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81UserAdapter;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.UserReadService;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.tools.ReadUsersTool;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * T036 (feature 022, US2, SC-005, FR-010, FR-011, Constitution II): {@code read_users} over MCP on
 * the real {@link UserReadService}, the real {@link V81UserAdapter} against WireMock and the real
 * {@link CliUserRunner} on a scripted StartCLI session and replayed {@code --who} runs.
 *
 * <ul>
 *   <li>Personal values (FR-011): the e-mails and names of the recorded REST list
 *       {@code admin-users.xml} (T028) and the e-mails and names planted in every {@code finger}
 *       block, in a {@code finger} and a {@code user} block no parser knows, and in a session that
 *       fails as a whole (exit code 1 without {@code EXECUTION ERROR}, output that does not split,
 *       cut output), appear in no {@code users} or {@code logins} result, no error text, no
 *       parser excerpt and no log line (captured appender, TRACE). After a failed session the
 *       {@code user} view shows no e-mail either.
 *   <li>Secrets (SC-005): the node's password ({@link #PASSWORD}, registered with the global
 *       scrubber as the production wiring does) echoed after the password prompt and printed as a
 *       group, and the secret-named value {@code password=}{@link #CLI_SECRET} in unknown blocks
 *       and failed runs, appear nowhere; a PEM private key printed as a user's name is masked in
 *       the {@code user} view by the secret rules (FR-010).
 * </ul>
 *
 * <p>The capability matrix supports every capability and the coverage list is a fixture whose US2
 * rows are reachable on 8.1, so every view reaches the adapters.
 */
@Timeout(60)
class ReadUsersSecretLeakTest {

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final String USERS_PATH = "/ibis/rest/user/users";
    private static final String USER = "jdoe";
    private static final String PROMPT = "CLI|jdoe@localhost> ";

    /** The node's configured password (INUBIT login over REST and StartCLI). */
    private static final String PASSWORD = "users-leak-password-0361";
    /** A secret value StartCLI prints under a secret name. */
    private static final String CLI_SECRET = "synthetic-secret-0361";
    /** The body of {@link #KEY}; it must never be returned. */
    private static final String KEY_BODY = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEA0361";
    /** A PEM private key on one line, printed by {@code finger} as a user's name. */
    private static final String KEY = "-----BEGIN PRIVATE KEY-----" + KEY_BODY
        + "-----END PRIVATE KEY-----";
    private static final List<String> SECRETS = List.of(PASSWORD, CLI_SECRET, KEY_BODY);

    /** The domain of the e-mails planted in the StartCLI answers. */
    private static final String PLANTED_DOMAIN = "@planted.example.test";
    /** The start of the names planted in the {@code finger} answers. */
    private static final String PLANTED_NAME = "Planted-Last";
    /** Any e-mail address. */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+"
        + "(?:\\.[A-Za-z0-9-]+)+");

    /** The personal attribute values of the recorded REST list. */
    private static final List<String> REST_PERSONAL = restPersonal();
    /** The first page of the REST list, sorted by login. */
    private static final List<String> FIRST_PAGE = restLogins().stream().sorted().limit(25)
        .toList();
    /** Its {@code finger} block is unknown and holds an e-mail and the secret-named value. */
    private static final String BROKEN_FINGER = "User03";
    /** Its {@code user} block is unknown and holds an e-mail and the password. */
    private static final String BROKEN_USER = "User05";
    /** StartCLI prints the password as its group. */
    private static final String PASSWORD_GROUP = "User07";
    /** {@code finger} prints {@link #KEY} as its name. */
    private static final String KEY_NAME = "User01";

    /** The US2 rows of interface-coverage.tsv, reachable on 8.1. */
    private static final String COVERAGE = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        REST\tGET /user/users\tREAD\tB\tUS2\treachable:read_users/users\tplanned\t-
        CLI\tuser\tREAD\tB\tUS2\treachable:read_users/users\tplanned\t-
        CLI\tfinger\tREAD\tB\tUS2\treachable:read_users/users\tplanned\t-
        CLI\t-who\tREAD\tB\tUS2\treachable:read_users/logins\tplanned\t-
        CLI\t-nwho\tREAD\tB\tUS2\tplanned\tplanned\t-
        """;

    private static WireMockServer wireMock;

    @TempDir
    Path cliHome;

    private final SecretScrubber scrubber = SecretScrubber.global();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<InubitHttpClient> clients = new ArrayList<>();
    private Level previousLevel;
    private McpTestClient client;
    /** The scripted StartCLI of the next session or client run. */
    private FakeProcessLauncher launcher;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        wireMock.resetAll();
        FakeStartCli.install(cliHome);
        // as the production wiring registers each node's password with the global scrubber
        scrubber.register(PASSWORD);
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);

        NodeVersions versions = new NodeVersions(Clock.systemUTC(),
            Map.of(DEV1, VersionSetting.V8_1)::get, node -> Duration.ofMinutes(10));
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE, new ClientVersion(
            InubitVersion.parse("8.1.12").orElseThrow(), InubitVersion.parse("8.1.0"),
            InubitVersion.parse("8.1.99"))));
        VersionGate gate = new VersionGate(versions, new FakeVersionDetection(versions),
            everythingSupported(), (node, version, timeout) -> {
                throw new AssertionError("no client probe expected for " + node);
            }, TestClientSelection.of(DEV1::equals), node -> Duration.ZERO,
            Terminology.DEFAULT, false, InterfaceCoverage.load(new StringReader(COVERAGE)));
        client = McpTestClient.start(List.of(new ReadUsersTool(new UserReadService(
            new TargetResolver(List.of(DEV1)), gate, node -> adapter()))), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        clients.forEach(InubitHttpClient::close);
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
        logs.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
    }

    @Test
    void theFixtureCarriesThePlantedPersonalValues() {
        assertThat(REST_PERSONAL).contains("user01@corp.example.test",
            "user09@partner.example.test", "First01", "Last01");
        assertThat(FIRST_PAGE).hasSize(25).contains(BROKEN_FINGER, BROKEN_USER, PASSWORD_GROUP,
            KEY_NAME);
    }

    /**
     * Every page of the listing, with e-mails and names in the REST list and in every
     * {@code finger} block, an unknown {@code finger} and {@code user} block and the password as
     * a group: no personal value, no secret, no excerpt.
     */
    @Test
    void theListingShowsNoPersonalValueAndNoSecret() {
        serveUserList();
        launcher = session(ReadUsersSecretLeakTest::plantedAnswer, 0);
        List<JsonNode> results = new ArrayList<>();
        for (int offset : List.of(0, 25, 50)) {
            results.add(call(Map.of("target", "dev/node1", "view", "users", "offset", offset)));
        }
        List<String> errors = new ArrayList<>();
        for (ToolError error : List.of(
            errorOf(() -> FingerParser.parse(BROKEN_FINGER, brokenFinger(BROKEN_FINGER))),
            errorOf(() -> UserGroupsParser.parse(BROKEN_USER, brokenUser(BROKEN_USER))))) {
            assertThat(error.excerpt()).as("the parser's excerpt").isPresent();
            errors.add(reported(error) + "\n" + error.excerpt().orElseThrow());
        }

        assertThat(results).allSatisfy(result ->
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse());
        Map<String, JsonNode> users = users(results.get(0));
        assertThat(users.keySet()).containsExactlyElementsOf(FIRST_PAGE);
        assertThat(reasons(users.get(BROKEN_FINGER))).containsOnlyKeys(UnavailablePart.ROLES)
            .allSatisfy((part, reason) -> assertThat(reason).startsWith("UNEXPECTED_RESPONSE: "));
        assertThat(reasons(users.get(BROKEN_USER))).containsOnlyKeys(UnavailablePart.GROUPS)
            .allSatisfy((part, reason) -> assertThat(reason).startsWith("UNEXPECTED_RESPONSE: "));
        assertThat(users.get(PASSWORD_GROUP).path("group").asString())
            .isEqualTo(SecretScrubber.MASK);
        assertThat(users.get("User02").path("roles").toString()).contains("Operator");
        assertThat(launcher.launchCount()).as("one session per page").isEqualTo(3);
        assertThat(logText()).as("log lines were captured").isNotBlank();
        assertNoPersonalValue(results, errors);
        assertNoSecret(results, errors);
    }

    /**
     * The {@code user} view shows the e-mail and name of the one user (FR-011), but a private key
     * printed as the name is masked by the secret rules (FR-010) and the password printed as a
     * group is scrubbed.
     */
    @Test
    void theUserViewMasksAKeyAndThePassword() {
        serveUserList();
        launcher = session(ReadUsersSecretLeakTest::plantedAnswer, 0);

        JsonNode keyUser = call(Map.of("target", "dev/node1", "view", "user", "login",
            KEY_NAME));
        JsonNode passwordUser = call(Map.of("target", "dev/node1", "view", "user", "login",
            PASSWORD_GROUP));

        JsonNode detail = keyUser.path("structuredContent").path("user");
        assertThat(keyUser.path("isError").asBoolean()).as(keyUser.toString()).isFalse();
        assertThat(detail.path("email").asString()).isEqualTo(email(KEY_NAME));
        assertThat(detail.path("personal").toString())
            .isEqualTo("[{\"name\":\"Name\",\"masked\":true}]");
        assertThat(passwordUser.path("structuredContent").path("user").path("group").asString())
            .isEqualTo(SecretScrubber.MASK);
        assertNoSecret(List.of(keyUser, passwordUser), List.of());
    }

    /**
     * A session that fails as a whole with the page's e-mails and names in its output: exit code
     * 1 without {@code EXECUTION ERROR}, output that does not split (with stderr), and cut output.
     * Every listed user keeps its login with groups and roles unavailable, the {@code user} view
     * shows no e-mail, and neither the reasons nor the runner's error carry a personal value or
     * a secret.
     */
    @Test
    void aFailedSessionShowsNoPersonalValue() {
        serveUserList();
        String blocks = FIRST_PAGE.stream().flatMap(login -> Stream.concat(
                plantedAnswer("user '" + login + "'").stream(),
                plantedAnswer("finger '" + login + "'").stream()))
            .collect(Collectors.joining("\n"));
        String filler = "x".repeat(1_000);
        List<Supplier<FakeProcessLauncher>> failures = List.of(
            () -> session(ReadUsersSecretLeakTest::plantedAnswer, 1),
            () -> FakeProcessLauncher.of("Password: " + PASSWORD + "\n" + blocks
                    + "\npassword=" + CLI_SECRET + "\n",
                "java.lang.IllegalStateException: " + email("User01") + " password="
                    + CLI_SECRET + " " + PASSWORD + "\n", 1),
            () -> session(command -> {
                List<String> lines = new ArrayList<>(plantedAnswer(command));
                if (command.equals("finger 'User02'")) {
                    for (int i = 0; i <= CliRunner.MAX_OUTPUT_BYTES / filler.length(); i++) {
                        lines.add(filler);
                    }
                }
                return lines;
            }, 0));
        List<JsonNode> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Supplier<FakeProcessLauncher> failure : failures) {
            launcher = failure.get();
            JsonNode listing = call(Map.of("target", "dev/node1", "view", "users"));
            JsonNode user = call(Map.of("target", "dev/node1", "view", "user", "login",
                "User02"));
            ToolError error = errorOf(() -> runner().read(FIRST_PAGE));

            assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            Map<String, JsonNode> users = users(listing);
            assertThat(users.keySet()).containsExactlyElementsOf(FIRST_PAGE);
            assertThat(users.values()).allSatisfy(entry -> assertThat(reasons(entry))
                .containsOnlyKeys(UnavailablePart.GROUPS, UnavailablePart.ROLES)
                .allSatisfy((part, reason) -> assertThat(reason)
                    .isEqualTo(error.code() + ": " + error.message())));
            assertThat(reasons(user.path("structuredContent").path("user")))
                .containsOnlyKeys(UnavailablePart.GROUPS, UnavailablePart.ROLES,
                    UnavailablePart.EMAIL, UnavailablePart.PERSONAL);
            results.add(listing);
            results.add(user);
            errors.add(reported(error) + "\n" + error.excerpt().orElse(""));
        }

        assertNoPersonalValue(results, errors);
        assertNoSecret(results, errors);
    }

    /**
     * HTTP 500 (as HTML and as XML) and a list with a user without an id, each with the REST
     * list's personal values and the password in the body: the error results of both views carry
     * neither, and no excerpt of the body (T036 found the 500 body's e-mails in the excerpt of
     * {@code InubitHttpClient}; {@code V81UserAdapter} drops it).
     */
    @Test
    void restErrorsShowNoPersonalValueAndNoSecret() {
        String body = RestFixtures.text("admin-users.xml");
        List<JsonNode> results = new ArrayList<>();

        for (ResponseDefinitionBuilder answer : List.of(
            aResponse().withStatus(500).withHeader("Content-Type", "text/html")
                .withBody(body + " " + PASSWORD),
            aResponse().withStatus(500).withHeader("Content-Type", "application/xml")
                .withBody(body + " " + PASSWORD),
            aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                .withBody(body.replace("id=\"User01\" ", "").replace("</UserList>",
                    "<!-- " + PASSWORD + " --></UserList>")))) {
            stub(answer);
            results.add(call(Map.of("target", "dev/node1", "view", "users")));
            results.add(call(Map.of("target", "dev/node1", "view", "user", "login", "User02")));
        }

        assertThat(results).allSatisfy(result -> {
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
            assertThat(texts(result)).as("no excerpt of the body").doesNotContain("\"excerpt\"");
        });
        assertThat(wireMock.findAllUnmatchedRequests()).isEmpty();
        assertNoPersonalValue(results, List.of());
        assertNoSecret(results, List.of());
    }

    /**
     * {@code logins} on the recorded {@code --who} table, then an unknown table and a failed run
     * with the secrets in their output: neither the result, the error results with their excerpts
     * nor the runner's errors carry a secret or an e-mail (T036 found {@code password=<value>} in
     * the classifier's excerpt of the failed run; {@code CliUserRunner} masks it).
     */
    @Test
    void theLoginsShowNoSecret() {
        launcher = FakeProcessLauncher.replaying("admin-users-who");
        JsonNode read = call(Map.of("target", "dev/node1", "view", "logins"));
        assertThat(read.path("isError").asBoolean()).as(read.toString()).isFalse();
        assertThat(read.path("structuredContent").path("page").path("items").toString())
            .contains("\"jdoe\"");

        List<JsonNode> results = new ArrayList<>(List.of(read));
        List<String> errors = new ArrayList<>();
        String planted = "password=" + CLI_SECRET + " for " + PASSWORD;
        for (FakeProcessLauncher failure : List.of(
            FakeProcessLauncher.of("Password: " + PASSWORD + "\nSYSTEM-ID  " + planted + "\n",
                "", 0),
            FakeProcessLauncher.of("Password: " + PASSWORD + "\nERROR " + planted + "\n",
                "java.lang.IllegalStateException: " + planted + "\n", 1))) {
            launcher = failure;
            JsonNode result = call(Map.of("target", "dev/node1", "view", "logins"));
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
            results.add(result);
            ToolError error = errorOf(() -> runner().logins());
            errors.add(reported(error) + "\n" + error.excerpt().orElse(""));
        }

        assertThat(texts(results.get(1))).as("the parser's excerpt is shown")
            .contains("\"excerpt\"");
        assertNoPersonalValue(results, errors);
        assertNoSecret(results, errors);
    }

    /**
     * SC-005's audit channel: read_users is a read, and neither the tool nor its service takes an
     * {@link AuditPort}, so it writes no audit record that could carry a secret.
     */
    @Test
    void theReadWritesNoAuditRecord() {
        for (Class<?> type : List.of(ReadUsersTool.class, UserReadService.class)) {
            for (Constructor<?> constructor : type.getConstructors()) {
                assertThat(constructor.getParameterTypes()).as(constructor.toString())
                    .doesNotContain(AuditPort.class);
            }
        }
    }

    // --- the planted answers -------------------------------------------------------------------

    private static String email(String login) {
        return login.toLowerCase() + ".finger" + PLANTED_DOMAIN;
    }

    /**
     * The answer of {@code user '<login>'} or {@code finger '<login>'} in the recorded form, with
     * a planted e-mail and name in every {@code finger} block, and the planted cases of
     * {@link #BROKEN_FINGER}, {@link #BROKEN_USER}, {@link #PASSWORD_GROUP} and {@link #KEY_NAME}.
     */
    private static List<String> plantedAnswer(String command) {
        String login = command.substring(command.indexOf('\'') + 1, command.length() - 1);
        if (command.startsWith("user ")) {
            if (login.equals(BROKEN_USER)) {
                return brokenUser(login);
            }
            return List.of(row("User name", login),
                row("User group", login.equals(PASSWORD_GROUP) ? PASSWORD : "OPS"),
                row("Super group", "headoffice"));
        }
        if (login.equals(BROKEN_FINGER)) {
            return brokenFinger(login);
        }
        String name = login.equals(KEY_NAME) ? KEY : PLANTED_NAME + "-" + login + ", Planted";
        return List.of(String.format("Login: %-15sName: %s", login, name),
            "Email: " + email(login), "Role: Operator");
    }

    /** A {@code finger} block with a line no parser knows, holding an e-mail and a secret. */
    private static List<String> brokenFinger(String login) {
        return List.of(String.format("Login: %-15sName: %s-%s, Planted", login, PLANTED_NAME,
                login), "Email: " + email(login), "Mobile: " + email(login) + " password="
                + CLI_SECRET, "Role: Operator");
    }

    /** A {@code user} block with a row no parser knows, holding an e-mail, and the password. */
    private static List<String> brokenUser(String login) {
        return List.of(row("User name", login), row("User group", PASSWORD),
            row("E-mail", email(login)), row("Super group", "headoffice"));
    }

    /** A row of {@code user}: label padded to 13 characters, value padded with a space. */
    private static String row(String label, String value) {
        return String.format("%-13s%s ", label, value);
    }

    /**
     * A session that answers what was sent on stdin: the password prompt with the echoed
     * password, then per command line the prompt with the echoed command and {@code answers}'
     * block in CRLF lines, then the echoed exit.
     */
    private static FakeProcessLauncher session(Function<String, List<String>> answers,
        int exitCode) {
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> {
            List<String> lines = new String(stdin, StandardCharsets.UTF_8).lines().toList();
            StringBuilder out = new StringBuilder("Password: " + PASSWORD + "\n");
            for (String command : lines.subList(1, lines.size())) {
                out.append(PROMPT).append(command).append('\n');
                if (!command.equals("exit")) {
                    answers.apply(command).forEach(line -> out.append(line).append("\r\n"));
                }
            }
            return new Answer(out.toString(), exitCode);
        });
    }

    private static List<String> restPersonal() {
        Matcher matcher = Pattern.compile("(?:email|firstName|lastName)=\"([^\"]+)\"")
            .matcher(RestFixtures.text("admin-users.xml"));
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return List.copyOf(values);
    }

    private static List<String> restLogins() {
        Matcher matcher = Pattern.compile("<User id=\"([^\"]+)\"")
            .matcher(RestFixtures.text("admin-users.xml"));
        List<String> logins = new ArrayList<>();
        while (matcher.find()) {
            logins.add(matcher.group(1));
        }
        return logins;
    }

    // --- support -------------------------------------------------------------------------------

    private JsonNode call(Map<String, Object> arguments) {
        return client.callTool("read_users", arguments);
    }

    /** The users of a {@code users} result by login, in the result's order. */
    private static Map<String, JsonNode> users(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        Map<String, JsonNode> users = new LinkedHashMap<>();
        result.path("structuredContent").path("page").path("items")
            .forEach(user -> users.put(user.path("login").asString(), user));
        return users;
    }

    /** The {@code unavailable} reasons of a user by part. */
    private static Map<String, String> reasons(JsonNode user) {
        Map<String, String> reasons = new LinkedHashMap<>();
        user.path("unavailable").forEach(part ->
            reasons.put(part.path("part").asString(), part.path("reason").asString()));
        return reasons;
    }

    private void serveUserList() {
        stub(RestFixtures.response("admin-users", "xml"));
    }

    private static void stub(ResponseDefinitionBuilder answer) {
        wireMock.stubFor(get(urlPathEqualTo(USERS_PATH)).willReturn(answer));
    }

    private static ToolError errorOf(Supplier<?> call) {
        try {
            call.get();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    /**
     * What {@code UnavailablePart.of} and a tool error hand on from {@code error}: code and
     * message, cause and next step.
     */
    private static String reported(ToolError error) {
        return String.join("\n", error.code() + ": " + error.message(), error.likelyCause(),
            error.nextStep());
    }

    private EffectiveNodeConfig server() {
        return TestNodeConfig.node().id(DEV1.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(10))
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17")).build();
    }

    /** The production composition of {@code V81PortSet}: REST client and user runner. */
    private V81UserAdapter adapter() {
        InubitHttpClient http = new InubitHttpClient(server(),
            Optional.of(new InubitHttpClient.Credentials(USER,
                scrubber.register(PASSWORD))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        synchronized (clients) {
            clients.add(http);
        }
        CliUserRunner users = runner();
        return new V81UserAdapter(DEV1, http, users::read, users::logins,
            users::connectedSystems);
    }

    private CliUserRunner runner() {
        NodeCredentials credentials = new NodeCredentials(DEV1,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliUserRunner(server(), credentials,
            new CredentialGuard(storeLabels("INUBIT", DEV1),
                new MutableClock(Instant.parse("2026-11-13T08:00:00Z"))),
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(scrubber, storeLabels("INUBIT", DEV1), false));
    }

    private static CapabilityMatrix everythingSupported() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(), states);
    }

    // --- the checks ----------------------------------------------------------------------------

    /**
     * No e-mail address, no personal value of the REST list and no planted name in the rendered
     * {@code results} (JSON and decoded text), in the {@code errors} texts or in a captured log
     * line (FR-011).
     */
    private void assertNoPersonalValue(List<JsonNode> results, List<String> errors) {
        String text = rendered(results) + "\n" + String.join("\n", errors) + "\n" + logText();
        assertThat(EMAIL.matcher(text).results().map(match -> match.group()).toList())
            .as("e-mail addresses").isEmpty();
        assertThat(text).doesNotContain(PLANTED_DOMAIN, PLANTED_NAME);
        for (String value : REST_PERSONAL) {
            assertThat(text).as("personal value " + value).doesNotContain(value);
        }
    }

    /**
     * No secret in the rendered {@code results} (JSON and decoded text), in the {@code errors}
     * texts or in a captured log line (SC-005).
     */
    private void assertNoSecret(List<JsonNode> results, List<String> errors) {
        String result = rendered(results);
        String error = String.join("\n", errors);
        String log = logText();
        for (String secret : SECRETS) {
            assertThat(result).as("result: " + secret).doesNotContain(secret);
            assertThat(error).as("error: " + secret).doesNotContain(secret);
            assertThat(log).as("log: " + secret).doesNotContain(secret);
        }
    }

    /** The results as JSON and with every text value and property name decoded. */
    private static String rendered(List<JsonNode> results) {
        return results.stream().map(result -> result.toString() + "\n" + texts(result))
            .collect(Collectors.joining("\n"));
    }

    /** Every text value and property name of {@code node}, decoded. */
    private static String texts(JsonNode node) {
        StringBuilder text = new StringBuilder();
        collect(node, text);
        return text.toString();
    }

    private static void collect(JsonNode node, StringBuilder text) {
        if (node.isString()) {
            text.append(node.asString()).append('\n');
        }
        node.forEach(inner -> collect(inner, text));
        node.propertyNames().forEach(name -> text.append(name).append('\n'));
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return text.toString();
    }
}
