package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** {@code defaults.cliClients} (feature 017, research D-1, contracts/configuration-delta.md). */
class CliClientsConfigTest {

    private static final String HEAD = """
        profile:
          name: acme
        """;

    private static final String GROUPS = """
        groups:
          - name: dev
            nodes:
              - name: node1
                baseUrl: https://inubit.example.test:8443
        """;

    /** A value that must never show up in an error message. */
    private static final String SECRET_DIR = "/opt/do-not-echo-s3cr3t";

    @TempDir
    Path home;

    private Path file() {
        return home.resolve("acme.yaml");
    }

    private ProfileConfig load(String defaults) {
        return new ConfigLoader(Map.of(), home, false)
            .parse(HEAD + defaults + GROUPS, file()).config();
    }

    private ConfigException loadError(String yaml) {
        try {
            new ConfigLoader(Map.of(), home, false).parse(yaml, file());
        } catch (ConfigException e) {
            return e;
        }
        throw new AssertionError("expected a load error");
    }

    @Test
    void quotedAndUnquotedKeysLoad() {
        ProfileConfig config = load("""
            defaults:
              cliClients:
                "8.1":
                  home: /opt/inubit-8.1/client
                  javaHome: /opt/jdk-17
                9.0:
                  home: /opt/inubit-9.0/client
            """);

        assertThat(config.defaults().cliClients()).containsOnly(
            Map.entry(new MajorMinor(8, 1),
                new CliClient(Path.of("/opt/inubit-8.1/client"),
                    Optional.of(Path.of("/opt/jdk-17")))),
            Map.entry(new MajorMinor(9, 0),
                new CliClient(Path.of("/opt/inubit-9.0/client"), Optional.empty())));
    }

    @Test
    void unquotedFlowEntriesLoad() {
        ProfileConfig config = load("""
            defaults:
              cliClients:
                8.1: { home: /opt/inubit-8.1/client, javaHome: /opt/jdk-17 }
                "9.0": { home: /opt/inubit-9.0/client, javaHome: /opt/jdk-21 }
            """);

        assertThat(config.defaults().cliClients()).containsOnlyKeys(new MajorMinor(8, 1),
            new MajorMinor(9, 0));
    }

    @Test
    void entryPathsUseTheCliHomeBinding() {
        ProfileConfig config = load("""
            defaults:
              cliHome: ~/inubit-8.1/client
              cliClients:
                "9.0":
                  home: ~/inubit-9.0/client
                  javaHome: ~/jdk-21
            """);

        CliClient client = config.defaults().cliClients().get(new MajorMinor(9, 0));
        assertThat(client.home()).isEqualTo(home.resolve("inubit-9.0/client"));
        assertThat(client.javaHome()).contains(home.resolve("jdk-21"));
        assertThat(config.defaults().cliHome()).contains(home.resolve("inubit-8.1/client"));
    }

    @Test
    void absentCliClientsIsEmpty() {
        assertThat(load("defaults:\n  cliHome: /opt/client\n").defaults().cliClients()).isEmpty();
        assertThat(load("").defaults().cliClients()).isEmpty();
        assertThat(Defaults.EMPTY.cliClients()).isEmpty();
    }

    @Test
    void anUndeclaredVersionKeyLoads() {
        ProfileConfig config = load("""
            defaults:
              cliClients:
                "9.1":
                  home: /opt/inubit-9.1/client
            """);

        assertThat(config.defaults().cliClients()).containsOnlyKeys(new MajorMinor(9, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"9.00\"", "9.00", "9", "\"9\"", "9.x", "V9_0", "\"09.0\"",
        "\" 9.0\""})
    void invalidKeysAreRejectedWithTheirPath(String key) {
        String text = key.replace("\"", "");
        ConfigException error = loadError(HEAD + """
            defaults:
              cliClients:
                %s:
                  home: %s
            """.formatted(key, SECRET_DIR) + GROUPS);

        assertThat(error).hasMessage("Invalid cliClients key at defaults.cliClients." + text
            + " in " + file() + ": expected major.minor, e.g. \"9.0\"");
        assertThat(error.getMessage()).doesNotContain(SECRET_DIR);
    }

    @Test
    void aDuplicateKeyIsRejected() {
        ConfigException error = loadError(HEAD + """
            defaults:
              cliClients:
                "9.0":
                  home: %s
                9.0:
                  home: /opt/inubit-9.0/client
            """.formatted(SECRET_DIR) + GROUPS);

        assertThat(error).hasMessage("Duplicate cliClients key \"9.0\" at defaults.cliClients in "
            + file());
        assertThat(error.getMessage()).doesNotContain(SECRET_DIR, "inubit-9.0");
    }

    @Test
    void duplicateKeysElsewhereKeepTheirMessage() {
        ConfigException error = loadError(HEAD + """
            defaults:
              cliHome: /opt/a
              cliHome: /opt/b
            """ + GROUPS);

        assertThat(error.getMessage()).startsWith("Invalid YAML in " + file()
            + ": Duplicate key 'cliHome' at line ");
    }

    @Test
    void anEntryWithoutHomeIsRejected() {
        ConfigException error = loadError(HEAD + """
            defaults:
              cliClients:
                "9.0":
                  javaHome: %s
            """.formatted(SECRET_DIR) + GROUPS);

        assertThat(error).hasMessage("Invalid value at defaults.cliClients.9.0 in " + file()
            + ": expected a mapping with home (the client directory) and optional javaHome");
        assertThat(error.getMessage()).doesNotContain(SECRET_DIR);
    }

    @Test
    void anEntryThatIsNoMappingIsRejectedWithoutItsValue() {
        ConfigException error = loadError(HEAD + """
            defaults:
              cliClients:
                "9.0": %s
            """.formatted(SECRET_DIR) + GROUPS);

        assertThat(error.getMessage()).startsWith("Invalid value at defaults.cliClients.9.0 in ")
            .doesNotContain(SECRET_DIR);
    }

    @Test
    void cliClientsThatIsNoMappingIsRejectedWithoutItsValue() {
        ConfigException error = loadError(HEAD + """
            defaults:
              cliClients:
                - %s
            """.formatted(SECRET_DIR) + GROUPS);

        assertThat(error.getMessage()).startsWith("Invalid value at defaults.cliClients in ")
            .doesNotContain(SECRET_DIR);
    }

    @Test
    void anUnknownEntryFieldIsRejected() {
        ConfigException error = loadError(HEAD + """
            defaults:
              cliClients:
                "9.0":
                  home: /opt/inubit-9.0/client
                  javaHom: %s
            """.formatted(SECRET_DIR) + GROUPS);

        assertThat(error).hasMessage("Unknown key 'javaHom' at defaults.cliClients.9.0 in "
            + file());
    }

    @Test
    void cliClientsInAGroupIsRejected() {
        ConfigException error = loadError(HEAD + """
            groups:
              - name: dev
                cliClients:
                  "9.0":
                    home: %s
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
            """.formatted(SECRET_DIR));

        assertThat(error).hasMessage("Unknown key 'cliClients' at groups[0] in " + file());
    }

    @Test
    void cliClientsInANodeIsRejected() {
        ConfigException error = loadError(HEAD + """
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    cliClients:
                      "9.0":
                        home: %s
            """.formatted(SECRET_DIR));

        assertThat(error).hasMessage("Unknown key 'cliClients' at groups[0].nodes[0] in "
            + file());
    }

    @Test
    void cliClientsInACliBlockIsRejected() {
        ConfigException error = loadError(HEAD + """
            groups:
              - name: dev
                cli:
                  cliClients:
                    "9.0":
                      home: %s
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
            """.formatted(SECRET_DIR));

        assertThat(error).hasMessage("Unknown key 'cliClients' at groups[0].cli in " + file());
    }

    /** Without the tree reader in front (which rejects duplicates first) the binding does too. */
    @Test
    void theDeserializerItselfRejectsADuplicateKey() {
        YAMLMapper mapper = YAMLMapper.builder().build();

        assertThatThrownBy(() -> mapper.readValue("""
            clients:
              "9.0": { home: %s }
              9.0: { home: /opt/b }
            """.formatted(SECRET_DIR), Holder.class))
            .isInstanceOf(DatabindException.class)
            .hasMessageContaining("Duplicate cliClients key \"9.0\"")
            .hasMessageNotContaining(SECRET_DIR);
    }

    record Holder(@JsonDeserialize(using = CliClientsDeserializer.class)
        Map<MajorMinor, CliClient> clients) {
    }
}
