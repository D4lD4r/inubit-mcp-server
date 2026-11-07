package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.config.CliChoice.AddressKind;
import de.dadecker.inubit.mcp.config.CliChoice.HomeSource;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig.Cli;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** {@link EffectiveNodeConfig#cliFor} (feature 017, research D-3). */
class EffectiveNodeConfigCliForTest {

    private static final String SERVLET = EffectiveNodeConfig.DEFAULT_CLI_PATH;

    private static final String PROFILE = """
        profile:
          name: acme
        defaults:
          cliHome: /opt/default/client
          cliJavaHome: /opt/jdk-default
          cliClients:
            "8.1": { home: /opt/c81, javaHome: /opt/jdk-17 }
            "9.0": { home: /opt/c90, javaHome: /opt/jdk-21 }
            "9.1": { home: /opt/c91 }
        groups:
          - name: auto
            nodes:
              - name: plain
                baseUrl: https://inubit-auto.example.test:8443
              - name: own
                baseUrl: https://inubit-own.example.test:8443
                cli:
                  home: /opt/node-client
              - name: jdk
                baseUrl: https://inubit-jdk.example.test:8443
                cli:
                  javaHome: /opt/jdk-node
              - name: url
                baseUrl: https://inubit-url.example.test:8443
                cli:
                  url: https://inubit-url.example.test:9443/custom/cli
              - name: ctx
                baseUrl: https://inubit-ctx.example.test:8443/inubit/
          - name: grp
            versionLine: V9_0
            cli:
              home: /opt/group-client
              javaHome: /opt/jdk-group
            nodes:
              - name: g1
                baseUrl: https://inubit-g1.example.test:8443
              - name: g2
                baseUrl: https://inubit-g2.example.test:8443
                cli:
                  javaHome: /opt/jdk-node
          - name: x
            versionLine: V9_X
            nodes:
              - name: x1
                baseUrl: https://inubit-x1.example.test:8443
          - name: pins
            nodes:
              - name: v90
                versionLine: V9_0
                baseUrl: https://inubit-v90.example.test:8443
              - name: p907
                versionLine: "9.0.7-HF1"
                baseUrl: https://inubit-p907.example.test:8443
              - name: v81
                versionLine: V8_1
                baseUrl: https://inubit-v81.example.test:8443
        """;

    private static final String WITHOUT_CLIENTS = """
        profile:
          name: acme
        defaults:
          cliHome: /opt/default/client
          cliJavaHome: /opt/jdk-default
        groups:
          - name: g
            nodes:
              - name: auto
                baseUrl: https://inubit-a.example.test:8443
              - name: v61
                versionLine: V6_1
                baseUrl: https://inubit-b.example.test:8443
              - name: v81
                versionLine: V8_1
                baseUrl: https://inubit-c.example.test:8443
              - name: p817
                versionLine: "8.1.17"
                baseUrl: https://inubit-d.example.test:8443
                cli:
                  url: https://inubit-d.example.test:9443/x
          - name: bare
            nodes:
              - name: none
                baseUrl: https://inubit-e.example.test:8443
        """;

    @TempDir
    static Path home;

    private static EffectiveNodeConfig server(String profile, String id) {
        return new ConfigLoader(Map.of(), home, false).parse(profile, home.resolve("acme.yaml"))
            .config().effectiveNodes().stream()
            .filter(server -> server.id().equals(NodeId.parse(id)))
            .findFirst().orElseThrow();
    }

    private static EffectiveNodeConfig server(String id) {
        return server(PROFILE, id);
    }

    private static Optional<InubitVersion> version(String text) {
        return text == null ? Optional.empty() : InubitVersion.parse(text);
    }

    private static Optional<Path> path(String text) {
        return Optional.ofNullable(text).map(Path::of);
    }

    static Stream<Arguments> table() {
        return Stream.of(
            // node cli.home wins over the entry; JDK without the entry's javaHome
            Arguments.of("auto/own", "9.0.7", "/opt/node-client", "/opt/jdk-default",
                HomeSource.NODE, null, AddressKind.BASE_URL, "https://inubit-own.example.test:8443",
                9),
            // group cli.home wins; JDK group → node
            Arguments.of("grp/g1", "9.0.7", "/opt/group-client", "/opt/jdk-group",
                HomeSource.GROUP, null, AddressKind.BASE_URL, "https://inubit-g1.example.test:8443",
                9),
            Arguments.of("grp/g2", "9.0.7", "/opt/group-client", "/opt/jdk-node",
                HomeSource.GROUP, null, AddressKind.BASE_URL, "https://inubit-g2.example.test:8443",
                9),
            Arguments.of("grp/g1", "8.1.17", "/opt/group-client", "/opt/jdk-group",
                HomeSource.GROUP, null, AddressKind.SERVLET_PATH,
                "https://inubit-g1.example.test:8443" + SERVLET, 8),
            // version entry with its JDK
            Arguments.of("auto/plain", "9.0.7", "/opt/c90", "/opt/jdk-21",
                HomeSource.VERSION_ENTRY, "9.0", AddressKind.BASE_URL,
                "https://inubit-auto.example.test:8443", 9),
            Arguments.of("auto/plain", "9.0", "/opt/c90", "/opt/jdk-21",
                HomeSource.VERSION_ENTRY, "9.0", AddressKind.BASE_URL,
                "https://inubit-auto.example.test:8443", 9),
            Arguments.of("auto/plain", "8.1.17", "/opt/c81", "/opt/jdk-17",
                HomeSource.VERSION_ENTRY, "8.1", AddressKind.SERVLET_PATH,
                "https://inubit-auto.example.test:8443" + SERVLET, 8),
            // entry without javaHome: defaults.cliJavaHome
            Arguments.of("auto/plain", "9.1.0", "/opt/c91", "/opt/jdk-default",
                HomeSource.VERSION_ENTRY, "9.1", AddressKind.BASE_URL,
                "https://inubit-auto.example.test:8443", 9),
            // node cli.javaHome wins over the entry's javaHome
            Arguments.of("auto/jdk", "9.0.7", "/opt/c90", "/opt/jdk-node",
                HomeSource.VERSION_ENTRY, "9.0", AddressKind.BASE_URL,
                "https://inubit-jdk.example.test:8443", 9),
            // no entry for the version: the version-less client
            Arguments.of("auto/plain", "9.2.0", "/opt/default/client", "/opt/jdk-default",
                HomeSource.DEFAULTS, null, AddressKind.BASE_URL,
                "https://inubit-auto.example.test:8443", 9),
            Arguments.of("auto/plain", "7.0.1", "/opt/default/client", "/opt/jdk-default",
                HomeSource.DEFAULTS, null, AddressKind.SERVLET_PATH,
                "https://inubit-auto.example.test:8443" + SERVLET, 7),
            // explicit cli.url wins; the entry is still chosen
            Arguments.of("auto/url", "9.0.7", "/opt/c90", "/opt/jdk-21",
                HomeSource.VERSION_ENTRY, "9.0", AddressKind.CONFIGURED,
                "https://inubit-url.example.test:9443/custom/cli", 9),
            Arguments.of("auto/url", null, "/opt/default/client", "/opt/jdk-default",
                HomeSource.DEFAULTS, null, AddressKind.CONFIGURED,
                "https://inubit-url.example.test:9443/custom/cli", null),
            // V9_X never chooses an entry, also after an observed 9.0
            Arguments.of("x/x1", "9.0.7", "/opt/default/client", "/opt/jdk-default",
                HomeSource.DEFAULTS, null, AddressKind.BASE_URL,
                "https://inubit-x1.example.test:8443", 9),
            Arguments.of("x/x1", null, "/opt/default/client", "/opt/jdk-default",
                HomeSource.DEFAULTS, null, AddressKind.BASE_URL,
                "https://inubit-x1.example.test:8443", 9),
            // unknown version: the 0.8.0 values
            Arguments.of("auto/plain", null, "/opt/default/client", "/opt/jdk-default",
                HomeSource.DEFAULTS, null, AddressKind.SERVLET_PATH,
                "https://inubit-auto.example.test:8443" + SERVLET, null),
            // a line pin gives the major for an unknown version
            Arguments.of("pins/v90", null, "/opt/default/client", "/opt/jdk-default",
                HomeSource.DEFAULTS, null, AddressKind.BASE_URL,
                "https://inubit-v90.example.test:8443", 9),
            // base URL with a context path (stored without trailing slash)
            Arguments.of("auto/ctx", "9.0.7", "/opt/c90", "/opt/jdk-21",
                HomeSource.VERSION_ENTRY, "9.0", AddressKind.BASE_URL,
                "https://inubit-ctx.example.test:8443/inubit", 9),
            Arguments.of("auto/ctx", "8.1.17", "/opt/c81", "/opt/jdk-17",
                HomeSource.VERSION_ENTRY, "8.1", AddressKind.SERVLET_PATH,
                "https://inubit-ctx.example.test:8443/inubit" + SERVLET, 8));
    }

    @ParameterizedTest(name = "{0} @ {1}")
    @MethodSource("table")
    void choosesPerTheD3Table(String node, String versionText, String clientHome,
        String javaHome, HomeSource source, String entry, AddressKind address, String url,
        Integer major) {
        CliChoice choice = server(node).cliFor(version(versionText));

        assertThat(choice.cli()).isEqualTo(new Cli(URI.create(url), path(clientHome),
            path(javaHome)));
        assertThat(choice.source()).isEqualTo(source);
        assertThat(choice.entry()).isEqualTo(Optional.ofNullable(entry)
            .flatMap(MajorMinor::parse));
        assertThat(choice.address()).isEqualTo(address);
        assertThat(choice.major()).isEqualTo(major == null ? OptionalInt.empty()
            : OptionalInt.of(major));
        assertThat(choice.versionKnown()).isEqualTo(versionText != null);
    }

    @Test
    void withoutAnyClientTheSourceIsNone() {
        CliChoice choice = server("""
            profile:
              name: acme
            groups:
              - name: g
                nodes:
                  - name: n
                    baseUrl: https://inubit.example.test:8443
            """, "g/n").cliFor(version("9.0.7"));

        assertThat(choice.source()).isEqualTo(HomeSource.NONE);
        assertThat(choice.cli().home()).isEmpty();
        assertThat(choice.cli().javaHome()).isEmpty();
        assertThat(choice.cli().url()).isEqualTo(URI.create("https://inubit.example.test:8443"));
    }

    @Test
    void cliIsTheChoiceForThePinnedVersion() {
        assertThat(server("pins/v90").cli()).isEqualTo(new Cli(
            URI.create("https://inubit-v90.example.test:8443"), path("/opt/c90"),
            path("/opt/jdk-21")));
        assertThat(server("pins/p907").cli()).isEqualTo(new Cli(
            URI.create("https://inubit-p907.example.test:8443"), path("/opt/c90"),
            path("/opt/jdk-21")));
        assertThat(server("pins/v81").cli()).isEqualTo(new Cli(
            URI.create("https://inubit-v81.example.test:8443" + SERVLET), path("/opt/c81"),
            path("/opt/jdk-17")));
        assertThat(server("auto/plain").cli())
            .isEqualTo(server("auto/plain").cliFor(Optional.empty()).cli());
        assertThat(server("x/x1").cli()).isEqualTo(new Cli(
            URI.create("https://inubit-x1.example.test:8443"), path("/opt/default/client"),
            path("/opt/jdk-default")));
    }

    @Test
    void withoutCliClientsCliHasThe080Values() {
        for (String id : new String[] {"g/auto", "g/v61", "g/v81"}) {
            EffectiveNodeConfig server = server(WITHOUT_CLIENTS, id);
            assertThat(server.cli()).isEqualTo(new Cli(
                URI.create(server.baseUrl() + SERVLET), path("/opt/default/client"),
                path("/opt/jdk-default")));
        }
        assertThat(server(WITHOUT_CLIENTS, "g/p817").cli()).isEqualTo(new Cli(
            URI.create("https://inubit-d.example.test:9443/x"), path("/opt/default/client"),
            path("/opt/jdk-default")));
        assertThat(server(WITHOUT_CLIENTS, "bare/none").cli().url())
            .isEqualTo(URI.create("https://inubit-e.example.test:8443" + SERVLET));
    }

    @Test
    void withoutCliClientsAnObserved90OnlyChangesTheAddress() {
        CliChoice choice = server(WITHOUT_CLIENTS, "g/auto").cliFor(version("9.0.7"));

        assertThat(choice.source()).isEqualTo(HomeSource.DEFAULTS);
        assertThat(choice.cli().home()).isEqualTo(path("/opt/default/client"));
        assertThat(choice.address()).isEqualTo(AddressKind.BASE_URL);
    }

    @Test
    void withCliKeepsTheSourcesSoSelectingIsIdempotent() {
        EffectiveNodeConfig server = server("auto/plain");
        CliChoice choice = server.cliFor(version("9.0.7"));

        EffectiveNodeConfig selected = server.withCli(choice.cli());

        assertThat(selected.cli()).isEqualTo(choice.cli());
        assertThat(selected.cliSources()).isEqualTo(server.cliSources());
        assertThat(selected.cliFor(version("9.0.7"))).isEqualTo(choice);
        assertThat(selected.cliFor(Optional.empty())).isEqualTo(server.cliFor(Optional.empty()));
        assertThat(selected.withCli(selected.cliFor(version("9.0.7")).cli())).isEqualTo(selected);
        assertThat(selected.withCli(server.cli())).isEqualTo(server);
    }

    @Test
    void withTimeoutKeepsTheSources() {
        EffectiveNodeConfig server = server("auto/plain");

        assertThat(server.withTimeout(Duration.ofSeconds(1)).cliSources())
            .isEqualTo(server.cliSources());
    }

    @Test
    void aConfigBuiltWithoutSourcesTreatsItsCliAsNodeSettings() {
        EffectiveNodeConfig loaded = server(WITHOUT_CLIENTS, "g/auto");
        EffectiveNodeConfig built = new EffectiveNodeConfig(loaded.id(), loaded.production(),
            loaded.baseUrl(), loaded.allowInsecureHttp(), loaded.versionLine(), loaded.write(),
            loaded.tls(), loaded.cli(), loaded.inventory(), loaded.timeout(), loaded.cliTimeout(),
            loaded.cliExportTimeout(), loaded.hangingThreshold(), loaded.confirmationTtl(),
            loaded.credentialPrefix(), loaded.development(), loaded.secretPlan(),
            loaded.archivesProductionOptIn());

        assertThat(built.cli()).isEqualTo(loaded.cli());
        assertThat(built.cliFor(Optional.empty())).isEqualTo(new CliChoice(loaded.cli(),
            HomeSource.NODE, Optional.empty(), AddressKind.SERVLET_PATH, OptionalInt.empty(),
            false));
        assertThat(built.cliFor(version("9.0.7")).address()).isEqualTo(AddressKind.BASE_URL);
        assertThat(built.cliFor(version("9.0.7")).cli().home()).isEqualTo(loaded.cli().home());
    }

    @Test
    void anEntryIsNamedExactlyForTheVersionEntrySource() {
        Cli cli = server("auto/plain").cli();

        assertThatThrownBy(() -> new CliChoice(cli, HomeSource.DEFAULTS,
            Optional.of(new MajorMinor(9, 0)), AddressKind.BASE_URL, OptionalInt.of(9), true))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CliChoice(cli, HomeSource.VERSION_ENTRY, Optional.empty(),
            AddressKind.BASE_URL, OptionalInt.of(9), true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theChoiceHoldsNoCredentials() {
        assertThat(server("auto/plain").cliFor(version("9.0.7")).toString())
            .doesNotContain("password", "user@");
    }
}
