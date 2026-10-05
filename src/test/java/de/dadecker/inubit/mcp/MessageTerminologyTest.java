package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.CredentialResolution;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.LoadedConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 002-T018: human-readable messages use the profile's terminology (FR-007, SC-006). A profile
 * with "Umgebung"/"Knoten" runs through the error paths of the production wiring (unknown target,
 * a group id for a write action, missing credentials, the production lock, disabled write
 * access) and through the configuration check; the messages name the configured levels and
 * never the previous ("stage", "server") or default ("group", "node") level names.
 */
@Timeout(30)
class MessageTerminologyTest {

    /** Level words; "MCP server"/"MCP client" are the product, removed before the check. */
    private static final Pattern LEVEL_WORD =
        Pattern.compile("(?i)\\b(stages?|servers?|groups?|nodes?)\\b");

    @TempDir
    Path cliHome;

    private final Map<String, String> env = new HashMap<>();
    private Wiring wiring;

    @BeforeEach
    void startCliScript() throws IOException {
        Files.createDirectories(cliHome.resolve("bin"));
        Files.writeString(cliHome.resolve("bin").resolve("startcli.sh"), "#!/bin/sh\n");
        env.put("INUBIT_ACME_TEST_USERNAME", "user");
        env.put("INUBIT_ACME_TEST_PASSWORD", "message-test-pw");
        env.put("INUBIT_ACME_PROD_USERNAME", "user");
        env.put("INUBIT_ACME_PROD_PASSWORD", "message-test-pw");
    }

    @AfterEach
    void close() {
        if (wiring != null) {
            wiring.close();
        }
    }

    private LoadedConfig load() {
        String yaml = """
            profile:
              name: acme
            terminology:
              group: { singular: Umgebung, plural: Umgebungen }
              node: { singular: Knoten, plural: Knoten }
            auditDirectory: %s
            defaults:
              inventory:
                owner: INTEGRATION
            groups:
              - name: test
                write:
                  enabled: true
                nodes:
                  - name: node1
                    baseUrl: https://127.0.0.1:9
                  - name: node2
                    baseUrl: https://127.0.0.1:9
                    write:
                      enabled: false
              - name: dev
                write:
                  enabled: true
                nodes:
                  - name: node1
                    baseUrl: https://127.0.0.1:9
                    cli:
                      home: %s
                      javaHome: /opt/jdk17
              - name: prod
                production: true
                write:
                  enabled: true
                nodes:
                  - name: node1
                    baseUrl: https://127.0.0.1:9
            """.formatted(cliHome.resolve("audit"), cliHome);
        return new ConfigLoader(Map.of(), Path.of("/home/test"), false)
            .parse(yaml, Path.of("/home/test/acme.yaml"));
    }

    private ToolHandler handler(String name) {
        if (wiring == null) {
            LoadedConfig loaded = load();
            SecretScrubber scrubber = new SecretScrubber();
            CredentialResolution credentials = new CredentialResolver(env, scrubber,
                loaded.config().credentialPrefix())
                .resolve(loaded.config().nodeIds());
            wiring = new Wiring(loaded.config(), credentials, scrubber, ClockProvider.system(),
                path -> true, false);
        }
        return wiring.toolHandlers().stream().filter(h -> h.name().equals(name)).findFirst()
            .orElseThrow();
    }

    private ToolError error(String tool, Map<String, Object> arguments) {
        return catchThrowableOfType(ToolErrorException.class,
            () -> handler(tool).handle(arguments)).error();
    }

    /**
     * Machine names (FR-008) that are not display text: YAML key paths such as
     * {@code groups[0].nodes[1]} and the {@code write.confirmation} value {@code SERVER}.
     */
    private static final Pattern MACHINE_NAME =
        Pattern.compile("\\b(groups|nodes)\\[\\d+\\]|\\bSERVER\\b");

    private static void assertOnlyCustomTerms(String... texts) {
        for (String text : texts) {
            String rest = MACHINE_NAME.matcher(text).replaceAll("")
                .replace("MCP server", "").replace("MCP client", "");
            assertThat(rest).as("level words in %s", text)
                .doesNotContainPattern(LEVEL_WORD.pattern());
        }
    }

    private static void assertOnlyCustomTerms(ToolError error) {
        assertOnlyCustomTerms(error.message(), error.likelyCause(), error.nextStep());
    }

    @Test
    void anUnknownTargetNamesTheConfiguredLevels() {
        ToolError error = error("get_health", Map.of("target", "nope"));

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(error.message()).contains("Configured Umgebungen and Knoten: test,");
        assertThat(error.likelyCause()).contains("Umgebung or Knoten");
        assertOnlyCustomTerms(error);
    }

    @Test
    void anInvalidTargetNamesTheConfiguredLevels() {
        ToolError error = error("find_processes", Map.of("target", "a/b/c"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("the id of one Umgebung",
            "of one Knoten (<Umgebung>/<Knoten>");
        assertThat(error.likelyCause()).contains("<Umgebung> or <Umgebung>/<Knoten>");
        assertOnlyCustomTerms(error);
    }

    @Test
    void aGroupIdForAWriteActionNamesTheConfiguredLevels() {
        ToolError error = error("restart_process", Map.of("node", "test", "processId", "1"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("'test' is the id of one Umgebung; this action"
            + " needs the id of exactly one Knoten (<Umgebung>/<Knoten>)");
        assertThat(error.likelyCause()).contains("exactly one Knoten", "of one Umgebung");
        assertOnlyCustomTerms(error);
    }

    @Test
    void theProductionLockNamesTheConfiguredLevels() {
        ToolError error = error("kill_process", Map.of("node", "prod/node1", "processId", "1"));

        assertThat(error.code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(error.message()).contains("production Umgebung prod");
        assertThat(error.likelyCause()).contains("Umgebung", "Knoten");
        assertThat(error.nextStep()).contains("restart the MCP client");
        assertOnlyCustomTerms(error);
    }

    @Test
    void disabledWriteAccessNamesTheConfiguredLevels() {
        ToolError error = error("restart_process", Map.of("node", "test/node2",
            "processId", "1"));

        assertThat(error.code()).isEqualTo(ErrorCode.WRITE_DISABLED);
        assertThat(error.likelyCause()).contains("Knoten is read-only");
        assertThat(error.nextStep()).contains("its Umgebung or Knoten",
            "restart the MCP client");
        assertOnlyCustomTerms(error);
    }

    @Test
    void missingCredentialsNameNoLevelAndAskToRestartTheMcpClient() {
        env.remove("INUBIT_ACME_DEV_USERNAME");
        env.remove("INUBIT_ACME_DEV_PASSWORD");

        ToolError error = error("restart_process", Map.of("node", "dev/node1",
            "processId", "1"));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).contains("dev/node1");
        assertThat(error.nextStep()).contains("restart the MCP client")
            .doesNotContain("Claude Code");
        assertOnlyCustomTerms(error);
    }

    @Test
    void theConfigurationCheckNamesTheConfiguredLevels() throws IOException {
        env.remove("INUBIT_ACME_PROD_USERNAME");
        env.put("INUBIT_ACME_QA_PASSWORD", "stray-value");
        Path file = cliHome.resolve("acme.yaml");
        Files.writeString(file, """
            profile:
              name: acme
              description: ACME test
            terminology:
              group: { singular: Umgebung, plural: Umgebungen }
              node: { singular: Knoten, plural: Knoten }
            defaults:
              cliHome: /opt/client
              cliJavaHome: /opt/jdk17
            groups:
              - name: prod
                production: true
                cli:
                  url: https://cli.example.test:8443/ibis/rest/cli
                nodes:
                  - name: node1
                    baseUrl: https://node1.example.test:8443
                    write:
                      confirmation: CLIENT
                  - name: node1
                    baseUrl: https://node1.example.test:8443
                  - name: Bad_Name
                    baseUrl: https://node1.example.test:8443
              - name: a-b
                nodes:
                  - name: c
                    baseUrl: https://c.example.test:8443
              - name: a
                nodes:
                  - name: b-c
                    baseUrl: https://bc.example.test:8443
              - name: Empty_Group
                nodes: []
            """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Launcher.Context context = new Launcher.Context(env, Path.of("/home/test"), false,
            InputStream.nullInputStream(), new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8), new SecretScrubber(),
            path -> true);

        int exit = new Launcher(context).run("--config", file.toString(), "--check-config");

        String summary = out.toString(StandardCharsets.UTF_8);
        assertThat(exit).as(summary + err).isEqualTo(Launcher.EXIT_ERROR);
        assertThat(summary).contains("Profile: acme (ACME test)",
            "Terminology: Umgebung/Umgebungen, Knoten/Knoten",
            "Invalid Umgebung name 'Empty_Group'",
            "Umgebung 'Empty_Group' must have at least one Knoten",
            "Duplicate Knoten name 'node1' in Umgebung 'prod'",
            "Invalid Knoten name 'Bad_Name' in Umgebung 'prod'",
            "not allowed in production Umgebungen",
            "would be used by Knoten a-b/c and Knoten a/b-c; rename one of the Umgebungen or"
                + " Knoten",
            "INUBIT_ACME_QA_PASSWORD matches no configured Umgebung or Knoten",
            "Umgebung 'prod': cli.url is set for 3 Knoten",
            "No inventory.owner is set (in defaults, for any Umgebung or for any Knoten)");
        assertThat(summary).doesNotContain("umgebung", "knoten");
        assertOnlyCustomTerms(summary.replace(file.toString(), ""));
    }
}
