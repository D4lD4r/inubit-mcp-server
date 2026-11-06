package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T014 (feature 013, SC-002, US1 AS5): every character of SC-002 in every value passed to
 * StartCLI outside its command, through the real interpreter and the substitute StartCLI. A
 * refused value starts no process; any other value reaches StartCLI exactly, the interpreter
 * received exactly the line the server built, so no second program ran.
 *
 * <p>Some characters cannot be part of the value's type on Windows at all (a path cannot hold
 * {@code "} or {@code *}, a URI no space); the configuration cannot hold such a value, so the
 * test asserts exactly that instead.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(60)
class WindowsCommandLineInjectionTest {

    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-CLI-pw";
    private static final String URL =
        "https://inubit.example.test:8443/ibis/servlet/IBISSoapServlet";

    /** One SC-002 case: inserted into a value as {@code fragment} (at the end if trailing). */
    enum Sc002 {
        QUOTE("\"", true), PERCENT("%", true), BANG("!", true), CARET("^", true),
        AMPERSAND("&", true), PIPE("|", true), LESS("<", true), GREATER(">", true),
        TAB("\t", true), LINE_BREAK("\n", true), NON_ASCII("\u00e4", true),
        SPACE(" ", false), APOSTROPHE("'", false), OPEN("(", false), CLOSE(")", false),
        SEMICOLON(";", false), COMMA(",", false), EQUALS("=", false), BACKSLASH("\\", false),
        TRAILING_BACKSLASH("\\", false), STAR("*", false), QUESTION("?", false);

        final String fragment;
        final boolean refused;

        Sc002(String fragment, boolean refused) {
            this.fragment = fragment;
            this.refused = refused;
        }

        /** {@code prefix}, the character, {@code suffix}; a trailing {@code \} ends the value. */
        String in(String prefix, String suffix) {
            return this == TRAILING_BACKSLASH ? prefix + suffix + fragment
                : prefix + fragment + suffix;
        }
    }

    private static final Set<Sc002> NOT_IN_A_PATH = Set.of(Sc002.QUOTE, Sc002.PIPE, Sc002.LESS,
        Sc002.GREATER, Sc002.TAB, Sc002.LINE_BREAK, Sc002.STAR, Sc002.QUESTION,
        Sc002.TRAILING_BACKSLASH);

    private static final Set<Sc002> NOT_IN_A_URI = Set.of(Sc002.QUOTE, Sc002.CARET, Sc002.PIPE,
        Sc002.LESS, Sc002.GREATER, Sc002.TAB, Sc002.LINE_BREAK, Sc002.SPACE, Sc002.BACKSLASH,
        Sc002.TRAILING_BACKSLASH);

    /** The values FR-002 names that are passed outside the StartCLI command. */
    enum Setting {
        USERNAME("the username", ErrorCode.AUTH_FAILED, Set.of()),
        TRUST_STORE("tls.trustStore", ErrorCode.CLI_UNAVAILABLE, NOT_IN_A_PATH),
        CLI_URL("cli.url", ErrorCode.CLI_UNAVAILABLE, NOT_IN_A_URI),
        CLI_HOME("cli.home", ErrorCode.CLI_UNAVAILABLE, NOT_IN_A_PATH),
        CLI_JAVA_HOME("cli.javaHome", ErrorCode.CLI_UNAVAILABLE, NOT_IN_A_PATH),
        SERVER_JAVA_HOME("JAVA_HOME", ErrorCode.CLI_UNAVAILABLE, Set.of());

        final String named;
        final ErrorCode refusal;
        final Set<Sc002> unrepresentable;

        Setting(String named, ErrorCode refusal, Set<Sc002> unrepresentable) {
            this.named = named;
            this.refusal = refusal;
            this.unrepresentable = unrepresentable;
        }

        boolean refuses(Sc002 character) {
            return character.refused || character == Sc002.SEMICOLON
                && (this == CLI_JAVA_HOME || this == SERVER_JAVA_HOME);
        }
    }

    @TempDir
    Path directory;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T08:00:00Z"));

    static Stream<Arguments> cases() {
        return Stream.of(Setting.values()).flatMap(setting -> Stream.of(Sc002.values())
            .map(character -> Arguments.of(setting, character)));
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("cases")
    void startCliReceivesTheValueUnchangedOrNothingStarts(Setting setting, Sc002 character)
        throws Exception {
        Path cliHome = directory.resolve("client");
        Map<String, String> environment = hostEnvironmentWithoutJavaHome();
        TestNodeConfig node = TestNodeConfig.node().cliHome(cliHome);
        if (setting != Setting.SERVER_JAVA_HOME) {
            node.cliJavaHome(Path.of(System.getProperty("java.home")));
        }
        String username = USER;
        String value;
        Optional<Object> typed;
        switch (setting) {
            case USERNAME -> {
                value = character.in("jd", "oe");
                typed = Optional.of(value);
                username = value;
            }
            case TRUST_STORE -> {
                value = character.in(directory + "\\certs", "x\\trust.p12");
                typed = path(value);
                typed.ifPresent(trustStore -> node.trustStore((Path) trustStore));
            }
            case CLI_URL -> {
                // a % reaches a URI only as an escape, which keeps the %
                value = character == Sc002.PERCENT ? "https://inubit.example.test:8443/a%25b"
                    : character.in("https://inubit.example.test:8443/a", "b/ibis");
                typed = uri(value);
                node.cliUrl(typed.isPresent() ? value : URL);
            }
            case CLI_HOME -> {
                value = character.in(directory + "\\cli", "home");
                typed = path(value);
                if (typed.isPresent()) {
                    cliHome = (Path) typed.get();
                    node.cliHome(cliHome);
                }
            }
            case CLI_JAVA_HOME -> {
                value = character.in(directory + "\\jdk", "17");
                typed = path(value);
                typed.ifPresent(javaHome -> node.cliJavaHome((Path) javaHome));
            }
            case SERVER_JAVA_HOME -> {
                value = character.in("C:\\jdk", "17");
                typed = Optional.of(value);
                environment.put("JAVA_HOME", value);
            }
            default -> throw new IllegalStateException(setting.name());
        }

        assertThat(typed.isEmpty()).as("%s cannot hold %s", setting.named, character)
            .isEqualTo(setting.unrepresentable.contains(character));
        if (typed.isEmpty()) {
            return;
        }
        FakeStartCli cli = FakeStartCli.installRecordingInto(cliHome, directory.resolve("record"));
        RecordingLauncher launcher = new RecordingLauncher();
        EffectiveNodeConfig server = node.build();
        CliRunner runner = TestCliRunners.forHost(launcher, environment,
            new CliResources("acme", PrivateFiles.forHost()));
        String login = username;

        if (setting.refuses(character)) {
            ToolError error = errorOf(() -> run(runner, server, login));
            assertThat(error.code()).isEqualTo(setting.refusal);
            assertThat(error.message()).contains(setting.named);
            if (setting == Setting.USERNAME) {
                assertThat(error.toString()).doesNotContain(value);
            }
            assertThat(launcher.launched).isEmpty();
            assertThat(cli.recordFile()).doesNotExist();
            return;
        }

        CliResult result = run(runner, server, login);

        assertThat(result.exitCode()).isZero();
        assertThat(launcher.launched).hasSize(1);
        ProcessLauncher.LaunchSpec spec = launcher.launched.get(0);
        assertThat(cli.commandLine()).isEqualTo(String.join(" ", spec.command()));
        FakeStartCli.Recorded record = cli.record();
        assertThat(record.args()).containsExactlyElementsOf(spec.startCliArguments());
        assertThat(new String(record.stdin(), StandardCharsets.UTF_8)).isEqualTo(PASSWORD + "\n");
        switch (setting) {
            case USERNAME -> assertThat(record.args()).startsWith("-u", value);
            case TRUST_STORE -> assertThat(record.args()).containsSubsequence(
                "--trustStoreFilePath", value);
            case CLI_URL -> assertThat(record.args()).endsWith(value);
            case CLI_HOME -> assertThat(record.workingDirectory()).isEqualTo(value);
            case CLI_JAVA_HOME, SERVER_JAVA_HOME ->
                assertThat(record.environment()).containsEntry("JAVA_HOME", value);
            default -> throw new IllegalStateException(setting.name());
        }
    }

    /**
     * US1 AS5: an installation path with spaces, {@code (} and {@code )} that the server cannot
     * write to works, and nothing is written into it.
     */
    @Test
    void aReadOnlyInstallationPathWithSpacesAndParenthesesWorksAndStaysUntouched()
        throws Exception {
        Path cliHome = directory.resolve("Program Files (x86)").resolve("acme client 8.1");
        FakeStartCli cli = FakeStartCli.installRecordingInto(cliHome, directory.resolve("record"));
        Map<String, String> before = snapshot(cliHome);
        Map<Path, List<AclEntry>> acls = denyWrites(cliHome);
        try {
            assertThatThrownBy(() -> Files.createFile(cliHome.resolve("probe.txt")))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> Files.createFile(cliHome.resolve("bin").resolve("probe.txt")))
                .isInstanceOf(AccessDeniedException.class);
            RecordingLauncher launcher = new RecordingLauncher();
            EffectiveNodeConfig server = TestNodeConfig.node().cliHome(cliHome)
                .cliJavaHome(Path.of(System.getProperty("java.home"))).build();

            CliResult result = run(TestCliRunners.forHost(launcher,
                hostEnvironmentWithoutJavaHome(),
                    new CliResources("acme", PrivateFiles.forHost())), server, USER);

            assertThat(result.exitCode()).isZero();
            FakeStartCli.Recorded record = cli.record();
            assertThat(record.args()).containsExactlyElementsOf(
                launcher.launched.get(0).startCliArguments());
            assertThat(record.workingDirectory()).isEqualTo(cliHome.toString());
            assertThat(cli.commandLine())
                .isEqualTo(String.join(" ", launcher.launched.get(0).command()));
        } finally {
            for (Map.Entry<Path, List<AclEntry>> entry : acls.entrySet()) {
                Files.getFileAttributeView(entry.getKey(), AclFileAttributeView.class)
                    .setAcl(entry.getValue());
            }
        }
        assertThat(snapshot(cliHome)).isEqualTo(before);
    }

    private CliResult run(CliRunner runner, EffectiveNodeConfig server, String username) {
        return runner.run(server, username, Secret.of(PASSWORD),
            CliCommand.processErrorStart("110190387"), Duration.ofSeconds(30),
            new CredentialGuard(server.secretLabels(), clock));
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            assertThat(e.error().toString() + e.getMessage()).doesNotContain(PASSWORD);
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    /** The host's environment; the Java home of StartCLI is each case's own. */
    private static Map<String, String> hostEnvironmentWithoutJavaHome() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.keySet().removeIf(name -> name.equalsIgnoreCase("JAVA_HOME"));
        return environment;
    }

    /** {@code value} as a path, if a Windows path can hold it exactly. */
    private static Optional<Object> path(String value) {
        try {
            Path path = Path.of(value);
            return path.toString().equals(value) ? Optional.of(path) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** {@code value} as a URI, if a URI can hold it exactly. */
    private static Optional<Object> uri(String value) {
        try {
            URI uri = URI.create(value);
            return uri.toString().equals(value) ? Optional.of(uri) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Every file and directory below {@code root} with its size and modification time. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                entries.put(root.relativize(path).toString(), Files.isDirectory(path) ? "dir"
                    : Files.size(path) + "@" + Files.getLastModifiedTime(path));
            }
        }
        return entries;
    }

    /**
     * Denies the current user every write below {@code root} (an ACL entry on each file and
     * directory: Windows does not apply an inherited entry to existing children); returns the
     * previous ACLs.
     */
    private static Map<Path, List<AclEntry>> denyWrites(Path root) throws IOException {
        UserPrincipal user = root.getFileSystem().getUserPrincipalLookupService()
            .lookupPrincipalByName(System.getProperty("user.name"));
        AclEntry deny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(user)
            .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.DELETE_CHILD, AclEntryPermission.DELETE)
            .build();
        Map<Path, List<AclEntry>> previous = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                AclFileAttributeView view =
                    Files.getFileAttributeView(path, AclFileAttributeView.class);
                List<AclEntry> acl = view.getAcl();
                previous.put(path, acl);
                List<AclEntry> denied = new ArrayList<>(List.of(deny));
                denied.addAll(acl);
                view.setAcl(denied);
            }
        }
        return previous;
    }

    /** The real launcher, remembering what it was asked to start. */
    private static final class RecordingLauncher implements ProcessLauncher {

        final List<LaunchSpec> launched = new ArrayList<>();
        private final SystemProcessLauncher system = new SystemProcessLauncher();

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            launched.add(spec);
            return system.launch(spec);
        }
    }
}
