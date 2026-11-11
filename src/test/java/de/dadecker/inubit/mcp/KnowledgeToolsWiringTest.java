package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeAnnouncement;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.UpdateNotice;
import de.dadecker.inubit.mcp.testing.FakeCredentialStore;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * 019-T023: the production wiring of the local knowledge (research D-7, D-8, D-15):
 * {@code read_knowledge} always offered, the profile's credentials as the masking source, and the
 * knowledge paragraph in the instructions of the server the wiring builds; 019-T024: the
 * start-time wait for the credentials and its log line; 019-T032: {@code record_knowledge} only
 * with {@code recording} on, right after {@code read_knowledge}, audited in the profile's log.
 */
@Timeout(60)
class KnowledgeToolsWiringTest {

    private static final String PASSWORD = "wiring-knowledge-pw";

    @TempDir
    Path temp;

    private ProfileConfig config(boolean recording) {
        String yaml = """
            profile:
              name: acme
            workspace: %s
            auditDirectory: %s
            knowledge:
              directory: %s
              recording: %s
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
            """.formatted(temp.resolve("workspace"), temp.resolve("audit"),
                temp.resolve("knowledge"), recording);
        return new ConfigLoader(Map.of(), temp, false).parse(yaml, temp.resolve("config.yaml"))
            .config();
    }

    private static FakeCredentialStore store() {
        return TestSecrets.store(Map.of("INUBIT_ACME_DEV_USERNAME", "user",
            "INUBIT_ACME_DEV_PASSWORD", PASSWORD));
    }

    private Wiring wiring(ProfileConfig config, FakeCredentialStore store,
        SecretScrubber scrubber) {
        ProcessLauncher failing = spec -> {
            throw new IOException("StartCLI must not be launched in this test");
        };
        return new Wiring(config, store, scrubber, ClockProvider.system(), path -> false, false,
            failing, Map.of(), () -> {
                throw new AssertionError("no node reads from the Parameter Store");
            }, TestWiring.TEST_CONFIG_LOCATION);
    }

    private void note(String name, String text) throws IOException {
        Path file = temp.resolve("knowledge").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void theNotesAreListedInTheInstructionsAndReadMaskedThroughTheTool() throws IOException {
        note("partners/acme.md", "---\ntitle: ACME\ntopics: [sap]\n---\nlogin with " + PASSWORD);
        SecretScrubber scrubber = new SecretScrubber();
        FakeCredentialStore store = store();
        try (Wiring wiring = wiring(config(true), store, scrubber);
             McpTestClient client = McpTestClient.start(
                 wiring.serverFactory("0.0.0-test", wiring.toolHandlers()))) {
            String instructions = client.initialize().path("instructions").asString();
            JsonNode page = client.callTool("read_knowledge", Map.of("name", "partners/acme"))
                .path("structuredContent");

            assertThat(instructions).contains("\n\nLocal knowledge: this profile has",
                "To record a finding", "Notes (newest first):",
                "\n- \"partners/acme\" — \"ACME\" [topics: \"sap\"]");
            assertThat(store.reads()).isNotEmpty();
            assertThat(page.path("content").asString()).isEqualTo("login with ***");
            assertThat(client.rawOutput()).doesNotContain(PASSWORD);
        }
    }

    @Test
    void withRecordingOffTheParagraphIsReadOnlyAndOnlyReadingIsOffered() throws IOException {
        note("a.md", "x");
        SecretScrubber scrubber = new SecretScrubber();
        try (Wiring wiring = wiring(config(false), store(), scrubber);
             McpTestClient client = McpTestClient.start(
                 wiring.serverFactory("0.0.0-test", wiring.toolHandlers()))) {
            String instructions = client.initialize().path("instructions").asString();

            assertThat(instructions).contains("The local knowledge is read-only for this profile")
                .doesNotContain("To record a finding");
            assertThat(wiring.toolHandlers().stream().map(ToolHandler::name))
                .contains("read_knowledge").doesNotContain("record_knowledge");
        }
    }

    @Test
    void withRecordingOnTheNoteIsWrittenAndAuditedInTheProfilesLog() throws IOException {
        SecretScrubber scrubber = new SecretScrubber();
        try (Wiring wiring = wiring(config(true), store(), scrubber);
             McpTestClient client = McpTestClient.start(
                 wiring.serverFactory("0.0.0-test", wiring.toolHandlers()))) {
            client.initialize();
            List<String> names = wiring.toolHandlers().stream().map(ToolHandler::name).toList();
            JsonNode result = client.callTool("record_knowledge", Map.of("name", "partners/acme",
                "mode", "create", "title", "ACME", "content", "Uses EDIFACT."));

            assertThat(names.subList(0, 4)).containsExactly("list_nodes", "get_health",
                "read_knowledge", "record_knowledge");
            assertThat(result.path("isError").asBoolean()).isFalse();
            String auditId = result.path("structuredContent").path("auditId").asString();
            assertThat(Files.readString(temp.resolve("knowledge/partners/acme.md")))
                .contains("source: 'assistant (mcp-test-client)'").endsWith("Uses EDIFACT.");
            List<String> audit = new ArrayList<>();
            try (var files = Files.list(temp.resolve("audit"))) {
                for (Path file : files.toList()) {
                    audit.addAll(Files.readAllLines(file));
                }
            }
            assertThat(audit).hasSize(2).allSatisfy(line -> assertThat(line).contains(auditId,
                "\"record_knowledge\"", "\"acme\"", "mcp-test-client/1.0"));
            assertThat(audit.get(1)).contains("EXECUTED");
        }
    }

    @Test
    void withoutNotesNoCredentialIsReadAtStart() {
        FakeCredentialStore store = store();
        SecretScrubber scrubber = new SecretScrubber();
        try (Wiring wiring = wiring(config(true), store, scrubber);
             McpTestClient client = McpTestClient.start(
                 wiring.serverFactory("0.0.0-test", wiring.toolHandlers()))) {
            String instructions = client.initialize().path("instructions").asString();

            assertThat(instructions).contains("The local knowledge of this profile is empty.");
            assertThat(store.reads()).isEmpty();
        }
    }

    @Test
    void credentialsNotReadInTimeWithholdTheListAndLogTheNodesAtInfo() throws Exception {
        // 019-T024 (D-7, configuration-delta.md): the bound runs out, the reads continue
        note("partners/acme.md", "---\ntitle: " + PASSWORD + "\n---\nlogin with " + PASSWORD);
        FakeCredentialStore store = store().delay("INUBIT_ACME_DEV_PASSWORD",
            Duration.ofSeconds(30));
        SecretScrubber scrubber = new SecretScrubber();
        try (Wiring wiring = wiring(config(true), store, scrubber)) {
            List<String> log = new ArrayList<>();
            KnowledgeAnnouncement.State state = launcherLog(log,
                () -> Launcher.knowledgeAtStart(wiring, Duration.ofMillis(200)));
            try (McpTestClient client = McpTestClient.start(wiring.serverFactory("0.0.0-test",
                wiring.toolHandlers(), UpdateNotice.NONE, state))) {
                String instructions = client.initialize().path("instructions").asString();

                assertThat(state).isInstanceOf(KnowledgeAnnouncement.NotReady.class);
                assertThat(log).containsExactly("Knowledge note list withheld from the"
                    + " instructions: credentials of "
                    + String.join(", ", ((KnowledgeAnnouncement.NotReady) state).nodes())
                    + " not read yet");
                assertThat(log.getFirst()).contains("dev/node1").doesNotContain(PASSWORD,
                    "partners/acme");
                assertThat(instructions).contains("1 note").doesNotContain("partners/acme",
                    "Notes (newest first):");
                assertThat(client.rawOutput()).doesNotContain(PASSWORD);
            }
        }
    }

    @Test
    void credentialsReadInTimeListTheNotesAndLogNothing() throws Exception {
        note("a.md", "x");
        try (Wiring wiring = wiring(config(true), store(), new SecretScrubber())) {
            List<String> log = new ArrayList<>();
            KnowledgeAnnouncement.State state = launcherLog(log,
                () -> Launcher.knowledgeAtStart(wiring, Duration.ofSeconds(5)));

            assertThat(state).isInstanceOf(KnowledgeAnnouncement.Listed.class);
            assertThat(log).isEmpty();
        }
    }

    /** Runs {@code action}, collecting the {@link Launcher} log's messages into {@code log}. */
    private static <T> T launcherLog(List<String> log, Supplier<T> action) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(Launcher.class);
        Level level = logger.getLevel();
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        try {
            return action.get();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(level);
            appender.list.stream().map(ILoggingEvent::getFormattedMessage).forEach(log::add);
        }
    }
}
