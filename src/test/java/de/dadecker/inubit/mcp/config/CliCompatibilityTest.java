package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.dadecker.inubit.mcp.config.CliChoice.AddressKind;
import de.dadecker.inubit.mcp.config.CliChoice.HomeSource;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig.Cli;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.testing.Platform;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T033 (feature 017, US3, FR-009, FR-011, SC-002, SC-003): every 0.8.0 configuration example
 * keeps its StartCLI values. The profile examples of {@code docs/setup.md} and {@code README.md}
 * and the config test resources are loaded; for every 8.1, 6.1 and {@code AUTO} node the
 * effective {@code cli()} (home, JDK, address) equals the value of the 0.8.0 rule, computed here
 * from the parsed settings: {@code cli.url} node → group → {@code <baseUrl>} plus the servlet
 * path; {@code cli.home} and {@code cli.javaHome} node → group → {@code defaults}.
 */
class CliCompatibilityTest {

    private static final Path HOME = Platform.absolutePath("/home/test");
    private static final Path SOURCE = HOME.resolve("acme.yaml");
    private static final Pattern YAML_BLOCK =
        Pattern.compile("```ya?ml\n(.*?)```", Pattern.DOTALL);
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("(?m)^([a-zA-Z][\\w-]*):");
    private static final InubitVersion V8_1_17 = version("8.1.17");
    private static final InubitVersion V6_1_9 = version("6.1.9");
    private static final InubitVersion V9_0_7 = version("9.0.7");

    /** The config test resources a 0.8.0 build refused as well (each is a refusal fixture). */
    private static final Set<String> REFUSAL_FIXTURES = Set.of("deploy-on-node.yaml",
        "deploy-unknown-exclude-key.yaml", "deploy-unknown-mode.yaml", "duplicate-key.yaml",
        "malformed.yaml", "migration-before.yaml", "nested-x-key.yaml", "undefined-alias.yaml",
        "unknown-server-key.yaml", "unknown-top-level-key.yaml");

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    private static ConfigLoader loader() {
        return new ConfigLoader(Map.of(), HOME, false);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The profile examples of {@code doc}: every YAML block that is a document of its own (not a
     * node-level fragment shown indented), dedented and completed to a profile — a missing
     * {@code profile} section and, for a settings fragment, a minimal group are added.
     */
    private static Map<String, String> examples(Path doc) {
        Map<String, String> examples = new LinkedHashMap<>();
        Matcher blocks = YAML_BLOCK.matcher(read(doc));
        int index = 0;
        while (blocks.find()) {
            String block = blocks.group(1);
            String label = doc + " block " + index++;
            String text = block.stripIndent();
            boolean indented = !block.equals(text);
            Set<String> keys = TOP_LEVEL_KEY.matcher(text).results().map(m -> m.group(1))
                .collect(Collectors.toSet());
            if (indented && !keys.contains("profile")) {
                continue; // a node-level fragment such as the tls settings of one node
            }
            if (!keys.contains("profile")) {
                text = "profile:\n  name: acme\n" + text;
            }
            if (!keys.contains("groups")) {
                text = text + "\ngroups:\n  - name: dev\n    nodes:\n      - name: node1\n"
                    + "        baseUrl: https://inubit-dev-1.example.test:8443\n";
            }
            examples.put(label, text);
        }
        return examples;
    }

    private static List<Path> resources() {
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/config"))) {
            return files.filter(file -> file.toString().endsWith(".yaml")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every 0.8.0 configuration example that loads, by label. */
    private static Map<String, ProfileConfig> loadedExamples() {
        Map<String, ProfileConfig> loaded = new LinkedHashMap<>();
        for (Path doc : List.of(Path.of("docs/setup.md"), Path.of("README.md"))) {
            examples(doc).forEach((label, text) ->
                loaded.put(label, loader().parse(text, SOURCE).config()));
        }
        for (Path resource : resources()) {
            if (!REFUSAL_FIXTURES.contains(resource.getFileName().toString())) {
                loaded.put(resource.toString(), loader().loadFile(resource).config());
            }
        }
        return loaded;
    }

    /** The StartCLI settings of the 0.8.0 rule ({@code EffectiveNodeConfig.resolve} of 0.8.0). */
    private static Cli rule080(Defaults defaults, GroupConfig group, NodeConfig node) {
        URI baseUrl = Urls.withoutTrailingSlash(node.baseUrl().orElseThrow());
        return new Cli(
            node.cli().url().or(group.cli()::url)
                .orElseGet(() -> URI.create(baseUrl + EffectiveNodeConfig.DEFAULT_CLI_PATH)),
            node.cli().home().or(group.cli()::home).or(defaults::cliHome),
            node.cli().javaHome().or(group.cli()::javaHome).or(defaults::cliJavaHome));
    }

    private static VersionSetting line(GroupConfig group, NodeConfig node) {
        return node.versionLine().or(group::versionLine).orElse(Defaults.BUILTIN_VERSION_LINE);
    }

    private static Optional<NodeId> id(GroupConfig group, NodeConfig node) {
        try {
            return Optional.of(NodeId.of(group.name(), node.name()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Test
    void everyProfileExampleOfTheDocsLoads() {
        Map<String, String> examples = new LinkedHashMap<>();
        examples.putAll(examples(Path.of("docs/setup.md")));
        examples.putAll(examples(Path.of("README.md")));

        assertThat(examples).hasSizeGreaterThanOrEqualTo(15)
            .containsKey("README.md block 0");
        examples.forEach((label, text) -> assertThat(catchThrowable(
            () -> loader().parse(text, SOURCE))).as(label).isNull());
    }

    @Test
    void theConfigTestResourcesLoadExceptTheRefusalFixtures() {
        Set<String> refused = new TreeSet<>();
        for (Path resource : resources()) {
            if (catchThrowable(() -> loader().loadFile(resource)) instanceof ConfigException) {
                refused.add(resource.getFileName().toString());
            }
        }

        assertThat(refused).containsExactlyInAnyOrderElementsOf(REFUSAL_FIXTURES);
    }

    @Test
    void every81And61AndAutoNodeKeepsItsStartCliValuesOf080() {
        List<String> checked = new ArrayList<>();
        loadedExamples().forEach((label, config) -> {
            Map<NodeId, EffectiveNodeConfig> nodes = config.resolvableNodes().stream()
                .collect(Collectors.toMap(EffectiveNodeConfig::id, Function.identity()));
            for (GroupConfig group : config.groups()) {
                for (NodeConfig node : group.nodes()) {
                    Optional<EffectiveNodeConfig> effective = id(group, node).map(nodes::get);
                    VersionSetting line = line(group, node);
                    Optional<InubitVersion> runs = switch (line) {
                        case VersionSetting.Auto auto -> Optional.of(V8_1_17);
                        case VersionSetting.Line l when l.major() == 8 -> Optional.of(V8_1_17);
                        case VersionSetting.Line l when l.major() == 6 -> Optional.of(V6_1_9);
                        case VersionSetting.Pinned p when p.version().major() < 9 ->
                            Optional.of(p.version());
                        default -> Optional.empty();
                    };
                    if (effective.isEmpty() || runs.isEmpty()) {
                        continue;
                    }
                    String what = label + " " + effective.get().id() + " (" + line.text() + ")";
                    Cli golden = rule080(config.defaults(), group, node);
                    assertThat(effective.get().cli()).as(what).isEqualTo(golden);
                    assertThat(effective.get().cliFor(runs).cli()).as(what + " on " + runs.get())
                        .isEqualTo(golden);
                    assertThat(effective.get().choosesCliByVersion()).as(what).isFalse();
                    checked.add(what);
                }
            }
        });

        assertThat(checked).as("nodes compared with the 0.8.0 rule").hasSizeGreaterThanOrEqualTo(25)
            .anyMatch(what -> what.contains("(V6_1)")).anyMatch(what -> what.contains("(AUTO)"))
            .anyMatch(what -> what.contains("(8.1.17)"));
    }

    @Test
    void theV9xNodeOfTheDocsLoadsWithTheVersionLessClientAtTheBaseUrl() {
        ProfileConfig config = loader().parse(examples(Path.of("docs/setup.md")).values().stream()
            .filter(text -> text.contains("versionLine: V9_X")).findFirst().orElseThrow(), SOURCE)
            .config();
        EffectiveNodeConfig next = config.resolvableNodes().stream()
            .filter(node -> node.id().equals(NodeId.of("next", "node1"))).findFirst()
            .orElseThrow();

        assertThat(next.versionLine()).isEqualTo(VersionSetting.V9_X);
        assertThat(next.cli().url())
            .isEqualTo(URI.create("https://inubit-next-1.example.test:8443"));
        // FR-011: no cliClients entry is chosen for a line without minor, also once 9.0.7 is read
        CliChoice detected = next.cliFor(Optional.of(V9_0_7));
        assertThat(detected.entry()).isEmpty();
        assertThat(detected.address()).isEqualTo(AddressKind.BASE_URL);
    }

    // --- 9.0 with cliClients (US3 scenario 2, SC-002) ------------------------------------------

    private static final String CLIENTS = """
        profile:
          name: acme
        defaults:
          cliHome: /opt/inubit/client
          cliJavaHome: /opt/jdk-17
          cliClients:
            "8.1": { home: /opt/inubit-8.1/client, javaHome: /opt/jdk-17 }
            "9.0": { home: /opt/inubit-9.0/client, javaHome: /opt/jdk-21 }
        groups:
          - name: int
            versionLine: V8_1
            nodes:
              - name: node1
                baseUrl: https://inubit-int-1.example.test:8443
          - name: dev
            nodes:
              - name: node1
                baseUrl: https://inubit-dev-1.example.test:8443
              - name: node2
                baseUrl: https://inubit-dev-2.example.test:8443
                cli:
                  url: https://cli-dev-2.example.test:9443/custom
              - name: node3
                baseUrl: https://inubit-dev-3.example.test:8443
                cli:
                  url: https://inubit-dev-3.example.test:8443/ibis/servlet/IBISSoapServlet
        """;

    private static EffectiveNodeConfig node(String yaml, String group, String node) {
        return loader().parse(yaml, SOURCE).config().effectiveNodes().stream()
            .filter(each -> each.id().equals(NodeId.of(group, node))).findFirst().orElseThrow();
    }

    @Test
    void anExplicitCliUrlIsKeptOnANodeDetectedAs90() {
        for (String name : List.of("node2", "node3")) {
            EffectiveNodeConfig server = node(CLIENTS, "dev", name);
            CliChoice choice = server.cliFor(Optional.of(V9_0_7));

            assertThat(choice.address()).as(name).isEqualTo(AddressKind.CONFIGURED);
            assertThat(choice.cli().url()).as(name).isEqualTo(server.cli().url());
            assertThat(choice.cli().home()).as(name)
                .contains(Path.of("/opt/inubit-9.0/client"));
        }
        assertThat(node(CLIENTS, "dev", "node2").cli().url())
            .isEqualTo(URI.create("https://cli-dev-2.example.test:9443/custom"));
    }

    @Test
    void switchingAStageFrom81To90ChangesOnlyItsVersionLine() {
        String nine = CLIENTS.replace("    versionLine: V8_1\n", "    versionLine: V9_0\n");
        assertThat(nine).isNotEqualTo(CLIENTS);

        CliChoice before = node(CLIENTS, "int", "node1").cliForSetting();
        CliChoice after = node(nine, "int", "node1").cliForSetting();

        assertThat(before.cli()).isEqualTo(new Cli(URI.create("https://inubit-int-1.example.test"
            + ":8443/ibis/servlet/IBISSoapServlet"), Optional.of(Path.of("/opt/inubit-8.1/client")),
            Optional.of(Path.of("/opt/jdk-17"))));
        assertThat(before.entry()).contains(new MajorMinor(8, 1));
        assertThat(after.cli()).isEqualTo(new Cli(URI.create("https://inubit-int-1.example.test"
            + ":8443"), Optional.of(Path.of("/opt/inubit-9.0/client")),
            Optional.of(Path.of("/opt/jdk-21"))));
        assertThat(after.entry()).contains(new MajorMinor(9, 0));
        assertThat(after.source()).isEqualTo(HomeSource.VERSION_ENTRY);
        assertThat(after.address()).isEqualTo(AddressKind.BASE_URL);
    }

    @Test
    void anAutoStageNeedsNoChangeTheDetectedVersionChoosesTheClient() {
        EffectiveNodeConfig auto = node(CLIENTS, "dev", "node1");

        assertThat(auto.versionLine()).isEqualTo(VersionSetting.AUTO);
        assertThat(auto.cliFor(Optional.of(V8_1_17)).cli()).isEqualTo(new Cli(
            URI.create("https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet"),
            Optional.of(Path.of("/opt/inubit-8.1/client")), Optional.of(Path.of("/opt/jdk-17"))));
        assertThat(auto.cliFor(Optional.of(V9_0_7)).cli()).isEqualTo(new Cli(
            URI.create("https://inubit-dev-1.example.test:8443"),
            Optional.of(Path.of("/opt/inubit-9.0/client")), Optional.of(Path.of("/opt/jdk-21"))));
        // before the first detection: the version-less client of defaults, as in 0.8.0
        assertThat(auto.cli()).isEqualTo(new Cli(
            URI.create("https://inubit-dev-1.example.test:8443/ibis/servlet/IBISSoapServlet"),
            Optional.of(Path.of("/opt/inubit/client")), Optional.of(Path.of("/opt/jdk-17"))));
    }
}
