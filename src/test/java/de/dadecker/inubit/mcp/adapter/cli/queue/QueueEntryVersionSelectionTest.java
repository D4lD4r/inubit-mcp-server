package de.dadecker.inubit.mcp.adapter.cli.queue;

import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.classPath;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.clientClassPath;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.java;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * US3 at the adapter (feature 020, FR-006, T031): one profile with a client installation and JDK
 * per version ({@link QueueEntryVersionProfile}); the version the gate admitted picks the variant,
 * the {@code defaults.cliClients} entry, its JDK and the address. 6.1, 9.1, a missing 9.0 client
 * and an 8.1 client chosen for a 9.0 node are refused, the first three without a launch. The
 * undetectable version is the gate's (see {@code QueueEntryServiceVersionTest}). No process, no
 * network.
 */
@Timeout(60)
class QueueEntryVersionSelectionTest {

    private static final InubitVersion V81 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final InubitVersion V90 = InubitVersion.parse("9.0.10").orElseThrow();
    private static final Set<EntryPart> BOTH = Set.of(EntryPart.DATA, EntryPart.VARIABLES);
    private static final String WORKFLOW = "Workflow-0001";
    private static final String FILE = "Module-0001(4711)@1234@0a1b2c3d";

    @TempDir
    Path root;

    private QueueEntryVersionProfile profile;

    @BeforeEach
    void setUp() throws IOException {
        profile = new QueueEntryVersionProfile(root);
    }

    @AfterEach
    void tearDown() {
        profile.close();
    }

    static Stream<VersionSetting> autoAndPinned81() {
        return Stream.of(VersionSetting.AUTO, VersionSetting.V8_1);
    }

    static Stream<VersionSetting> autoAndPinned90() {
        return Stream.of(VersionSetting.AUTO, VersionSetting.V9_0);
    }

    /** US3-1. */
    @ParameterizedTest
    @MethodSource("autoAndPinned81")
    void eightOneUsesSoapWithThe81ClientItsJdkAndTheServletAddress(VersionSetting line) {
        EffectiveNodeConfig server = profile.node("test/n81", line).build();
        FakeProcessLauncher launcher = QueueEntryVersionProfile.helperReading();

        RawEntryContent content = profile.adapter(server, launcher).read(ref(server), V81, BOTH);

        assertThat(content.data()).isInstanceOf(PartResult.Read.class);
        assertThat(content.variables()).isInstanceOf(PartResult.Read.class);
        assertThat(launcher.launchCount()).isOne();
        assertLaunchedOn(launcher.last().spec(), profile.client81(), profile.jdk17(),
            profile.client90(), profile.jdk21());
        assertThat(request(launcher.last().stdinBytes()))
            .containsEntry("variant", "SOAP_81")
            .containsEntry("url", "http://n81.example.test:8000/ibis/servlet/IBISSoapServlet");
        profile.assertTempRootEmpty();
    }

    /** US3-2: the adapter consults no matrix; the gate opens 9.0 (service test). */
    @ParameterizedTest
    @MethodSource("autoAndPinned90")
    void nineZeroUsesRestWithThe90ClientItsJdkAndTheBaseUrl(VersionSetting line) {
        EffectiveNodeConfig server = profile.node("test/n90", line).build();
        FakeProcessLauncher launcher = QueueEntryVersionProfile.helperReading();

        RawEntryContent content = profile.adapter(server, launcher).read(ref(server), V90, BOTH);

        assertThat(content.data()).isInstanceOf(PartResult.Read.class);
        assertThat(content.variables()).isInstanceOf(PartResult.Read.class);
        assertThat(launcher.launchCount()).isOne();
        assertLaunchedOn(launcher.last().spec(), profile.client90(), profile.jdk21(),
            profile.client81(), profile.jdk17());
        assertThat(request(launcher.last().stdinBytes()))
            .containsEntry("variant", "REST_90")
            .containsEntry("url", "http://n90.example.test:8000");
        profile.assertTempRootEmpty();
    }

    /** US3-3: refused before the credentials, the client and the launch. */
    @ParameterizedTest
    @ValueSource(strings = {"6.1.5", "9.1.0"})
    void anotherVersionIsUnsupportedNamingItAndTheSupportedLinesWithoutLaunch(String raw) {
        InubitVersion version = InubitVersion.parse(raw).orElseThrow();
        EffectiveNodeConfig server = profile.node("test/other", VersionSetting.AUTO).build();
        FakeProcessLauncher launcher = QueueEntryVersionProfile.helperReading();

        ToolError error = failure(profile.adapter(server, launcher), server, version);

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo("Queue Manager entries cannot be read on INUBIT "
            + raw.substring(0, 3) + "; the queue-entry helper knows INUBIT 8.1 and 9.0 only;"
            + " nothing was sent");
        assertThat(launcher.launchCount()).isZero();
        profile.assertTempRootEmpty();
    }

    /** US3-5: no client installation for 9.0, although 8.1 has one. */
    @Test
    void withoutA90ClientANineZeroNodeIsUnavailableNamingTheEntry() {
        EffectiveNodeConfig server = profile.withoutClients("test/n90", VersionSetting.AUTO)
            .cliClient("8.1", profile.client81(), Optional.of(profile.jdk17())).build();
        FakeProcessLauncher launcher = QueueEntryVersionProfile.helperReading();

        ToolError error = failure(profile.adapter(server, launcher), server, V90);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The queue-entry helper cannot be started for"
            + " test/n90 (INUBIT 9.0.10): no INUBIT client installation is configured");
        assertThat(error.likelyCause()).contains("defaults.cliClients.\"9.0\"");
        assertThat(error.nextStep()).startsWith("Add defaults.cliClients.\"9.0\".home");
        assertThat(launcher.launchCount()).isZero();
        profile.assertTempRootEmpty();
    }

    /** US3-5: {@code defaults.cliClients."9.0"} names the 8.1 installation; the helper runs. */
    @Test
    void anEightOneClientChosenForANineZeroNodeIsUnavailable() {
        EffectiveNodeConfig server = profile.withoutClients("test/n90", VersionSetting.AUTO)
            .cliClient("9.0", profile.client81(), Optional.of(profile.jdk17())).build();
        FakeProcessLauncher launcher = QueueEntryVersionProfile.helperLacking(
            QueueEntryVersionProfile.REST_CLIENT_FACTORY);

        ToolError error = failure(profile.adapter(server, launcher), server, V90);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The INUBIT client installation "
            + profile.client81() + " (chosen for INUBIT 9.0) lacks "
            + QueueEntryVersionProfile.REST_CLIENT_FACTORY);
        assertThat(error.likelyCause()).startsWith("The client installation is not an INUBIT 9.0"
            + " client");
        assertThat(error.nextStep()).isEqualTo("Check that defaults.cliClients.\"9.0\" points to"
            + " an INUBIT 9.0 client");
        assertThat(launcher.launchCount()).isOne();
        assertThat(request(launcher.last().stdinBytes())).containsEntry("variant", "REST_90");
        assertThat(launcher.last().spec().workingDirectory()).isEqualTo(profile.client81());
        profile.assertTempRootEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------

    private static QueueEntryRef ref(EffectiveNodeConfig server) {
        return new QueueEntryRef(server.id(), WORKFLOW, FILE, 772);
    }

    /** The error of a read of both parts; it carries the node and leaks nothing. */
    private static ToolError failure(CliQueueEntryAdapter adapter, EffectiveNodeConfig server,
        InubitVersion version) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            () -> adapter.read(ref(server), version, BOTH));
        assertThat(thrown).as("the read fails").isNotNull();
        ToolError error = thrown.error();
        assertThat(error.node()).contains(server.id());
        TestSecrets.assertNoLeak(error.toString());
        return error;
    }

    /** JDK, class path, working directory and {@code JAVA_HOME} of {@code home} only. */
    private static void assertLaunchedOn(LaunchSpec spec, Path home, Path jdk, Path otherHome,
        Path otherJdk) {
        assertThat(spec.command().get(0)).isEqualTo(java(jdk));
        assertThat(classPath(spec)).contains(clientClassPath(home))
            .doesNotContain(otherHome.toString());
        assertThat(spec.workingDirectory()).isEqualTo(home);
        assertThat(spec.environment()).containsEntry("JAVA_HOME", jdk.toString());
        assertThat(String.join(" ", spec.command()) + spec.environment())
            .doesNotContain(otherJdk.toString());
        QueueEntryVersionProfile.assertLaunchedWithoutSecrets(spec);
    }
}
