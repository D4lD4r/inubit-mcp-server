package de.dadecker.inubit.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ConfigAbsent;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ConfigError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.ConfigOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Detail;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.DetailsOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.IoListError;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.IoListOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.IoMatch;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.Member;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.MembersOk;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFrames.PartOk;
import de.dadecker.inubit.mcp.domain.model.BinarySignature;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ContentKind;
import de.dadecker.inubit.mcp.domain.model.EntryContentClassifier;
import de.dadecker.inubit.mcp.domain.model.EntryContentClassifier.Classified;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T019 (feature 024, research D-26, D-27): the closed world of the node-tool fixtures in
 * {@code fixtures/node-tools/} as its {@code README.md} describes it. Every file is a root file
 * ({@code README.md}, {@code io-message-ref.vectors}) or a case file
 * {@code <version>/<tool>/<case>.<ext>} with the suffixes of its tool; the case is the file name up
 * to its first dot. Every case has an {@code .origin} whose first line is
 * {@code recorded <date> <version> neutralized}, its version in the line of its version directory,
 * or {@code reconstructed <source>}. For every (version, capability) the production capability
 * matrix says {@code SUPPORTED} exactly when all cases D-27 requires for it are recorded and have
 * their data files: {@code ok} for the cluster nodes, {@code listing} and {@code read_text} for the
 * I/O log, {@code all} for the node configuration, {@code set_ok} and {@code reset_ok} for the log
 * level. The 8.1 cluster-nodes, io-log and node-config cases parse as the helper protocol
 * (contracts/helper-protocol-delta.md) and as their names say.
 */
class NodeToolsFixtureCatalogueTest {

    static final Path ROOT = Path.of("src/test/resources/fixtures/node-tools");

    /** The fixed nonce of the {@code .frames} fixtures, as in the 020 and 021 fixtures. */
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final Set<String> ROOT_FILES = Set.of("README.md", "io-message-ref.vectors");
    /** The version directories and their (major, minor). */
    private static final Map<String, int[]> VERSIONS = Map.of("v8_1", new int[] {8, 1},
        "v9_0", new int[] {9, 0});

    /**
     * A tool directory: its capability, the suffixes of its cases (README "Layout") and the cases
     * that must be recorded before the matrix supports the capability (D-27).
     */
    private record Tool(Capability capability, Set<String> suffixes, List<Required> required) {
    }

    /** A case D-27 requires recorded and the data files it must have besides its .origin. */
    private record Required(String name, Set<String> files) {
    }

    private static final Set<String> HELPER_FILES = Set.of(".frames", ".exit");
    private static final Set<String> STARTCLI_FILES = Set.of(".stdout", ".stderr", ".exit");
    private static final Map<String, Tool> TOOLS = Map.of(
        "cluster-nodes", new Tool(Capability.CLUSTER_NODES_READ,
            Set.of(".frames", ".exit", ".origin"), List.of(new Required("ok", HELPER_FILES))),
        "io-log", new Tool(Capability.IO_LOG_READ,
            Set.of(".frames", ".exit", ".list", ".message", ".origin"),
            List.of(new Required("listing", Set.of(".frames", ".exit", ".list")),
                new Required("read_text", Set.of(".frames", ".exit", ".list", ".message")))),
        "node-config", new Tool(Capability.NODE_CONFIG_READ,
            Set.of(".frames", ".exit", ".origin"), List.of(new Required("all", HELPER_FILES))),
        "log-level", new Tool(Capability.LOG_LEVEL_WRITE,
            Set.of(".stdout", ".stderr", ".exit", ".origin"),
            List.of(new Required("set_ok", STARTCLI_FILES),
                new Required("reset_ok", STARTCLI_FILES))));
    /** Groups 1 and 2: major and minor of a recorded version. */
    private static final Pattern ORIGIN = Pattern.compile("recorded \\d{4}-\\d{2}-\\d{2}"
        + " (\\d+)\\.(\\d+)(?:\\.\\d+)* neutralized|reconstructed \\S.*");

    @Test
    void everyFileBelongsToACaseWithAValidOrigin() throws IOException {
        assertThat(violations(ROOT)).isEmpty();
        assertThat(cases(ROOT)).as("cases").contains("v8_1/cluster-nodes/ok",
            "v8_1/cluster-nodes/no_answer", "v8_1/cluster-nodes/single",
            "v8_1/cluster-nodes/details", "v8_1/cluster-nodes/details_truncated",
            "v8_1/cluster-nodes/details_unknown", "v8_1/cluster-nodes/details_error",
            "v8_1/cluster-nodes/unknown_status", "v8_1/cluster-nodes/api_missing",
            "v8_1/cluster-nodes/garbage", "v8_1/io-log/listing", "v8_1/io-log/empty_listing",
            "v8_1/io-log/read_text", "v8_1/io-log/read_latin1", "v8_1/io-log/read_binary",
            "v8_1/io-log/read_empty", "v8_1/io-log/no_match", "v8_1/io-log/foreign_process",
            "v8_1/io-log/list_error", "v8_1/node-config/all", "v8_1/node-config/secret_value",
            "v8_1/node-config/absent", "v8_1/node-config/one_error",
            "v8_1/node-config/api_missing", "v8_1/log-level/set_ok", "v8_1/log-level/reset_ok",
            "v8_1/log-level/missing_level", "v8_1/log-level/login_failure",
            "v8_1/log-level/forbidden");
    }

    @Test
    void theMatrixSupportsACapabilityExactlyWhenItsRequiredCasesAreRecorded() {
        assertThat(matrixViolations(ROOT, CapabilityMatrix.production())).isEmpty();
    }

    /**
     * Each 8.1 cluster-nodes case: its exit code, its frames (runs of one frame type as
     * {@code Type*n}) and the parse problem; a {@code members ok} or {@code details ok} count is
     * the number of {@code member} or {@code detail} frames before it.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ok | 0 | Start LoginOk NodeOk Member*12 MembersOk End | -",
        "no_answer | 0 | Start LoginOk NodeOk Member*2 MembersOk End | -",
        "single | 0 | Start LoginOk NodeOk Member MembersOk End | -",
        "details | 0 | Start LoginOk NodeOk Member*2 MembersOk Detail*9 DetailsOk End | -",
        "details_truncated | 0 | Start LoginOk NodeOk Member*2 MembersOk Detail*500 DetailsOk"
            + " End | -",
        "details_unknown | 0 | Start LoginOk NodeOk Member*2 MembersOk DetailsUnknown End | -",
        "details_error | 0 | Start LoginOk NodeOk Member*2 MembersOk DetailsError End | -",
        "unknown_status | 0 | Start LoginOk NodeOk Member*2 MembersOk End | -",
        "api_missing | 4 | ApiMissing | -",
        "garbage | 0 | Start LoginOk NodeOk | UNKNOWN_FRAME_TYPE"})
    void aClusterNodesCaseParsesAsItsNameSays(String name, int exit, String summary,
        String problem) throws IOException {
        Path dir = ROOT.resolve("v8_1/cluster-nodes");
        HelperFrames frames = HelperFrames.parse(NONCE,
            Files.readString(dir.resolve(name + ".frames"), StandardCharsets.UTF_8));

        assertThat(Integer.parseInt(firstLine(dir.resolve(name + ".exit")))).isEqualTo(exit);
        assertThat(summary(frames.frames()) + " | "
            + frames.problem().map(Enum::name).orElse("-")).isEqualTo(summary + " | " + problem);
        int members = 0;
        int details = 0;
        for (HelperFrames.Frame frame : frames.frames()) {
            switch (frame) {
                case Member member -> members++;
                case MembersOk ok -> assertThat(ok.count()).as("members ok").isEqualTo(members);
                case Detail detail -> details++;
                case DetailsOk ok -> assertThat(ok.count()).as("details ok").isEqualTo(details);
                default -> { }
            }
        }
    }

    /**
     * The {@code details} case carries an address, a path and a secret-named value for the masking
     * of D-21; {@code details_truncated} hit the helper's cap of 500 entries and has one value cut
     * at the helper's 4,000 characters.
     */
    @Test
    void theDetailsCasesCarryAMaskableValueAndACutValue() throws IOException {
        assertThat(details("details")).noneMatch(Detail::cut).extracting(Detail::value)
            .contains("10.0.0.11:7800", "zone-a")
            .anySatisfy(value -> assertThat(value).startsWith("/opt/inubit/"))
            .anySatisfy(value -> assertThat(value).containsPattern("(?i)password=\\S"));
        assertThat(details("details_truncated")).filteredOn(Detail::cut).singleElement()
            .satisfies(detail -> assertThat(detail.value()).hasSize(4000));
        assertThat(HelperFrames.parse(NONCE, Files.readString(
                ROOT.resolve("v8_1/cluster-nodes/details_truncated.frames"),
                StandardCharsets.UTF_8)).frames())
            .contains(new DetailsOk(500, true));
    }

    /**
     * Each 8.1 io-log case: its exit code, its frames, the parse problem and its data files (the
     * files the helper writes: {@code .list} after a listing, {@code .message} after a download).
     * An {@code io list ok} count is the number of lines of {@code .list}, an {@code io match}
     * names a line of it, and a {@code part message ok} byte count is the size of
     * {@code .message}.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "listing | 0 | Start LoginOk NodeOk IoListOk End | - | .exit .frames .list",
        "empty_listing | 0 | Start LoginOk NodeOk IoListOk End | - | .exit .frames .list",
        "read_text | 0 | Start LoginOk NodeOk IoListOk IoMatch PartOk End | -"
            + " | .exit .frames .list .message",
        "read_latin1 | 0 | Start LoginOk NodeOk IoListOk IoMatch PartOk End | -"
            + " | .exit .frames .list .message",
        "read_binary | 0 | Start LoginOk NodeOk IoListOk IoMatch PartOk End | -"
            + " | .exit .frames .list .message",
        "read_empty | 0 | Start LoginOk NodeOk IoListOk IoMatch PartOk End | -"
            + " | .exit .frames .list .message",
        "no_match | 0 | Start LoginOk NodeOk IoListOk IoMatch End | - | .exit .frames .list",
        "foreign_process | 0 | Start LoginOk NodeOk IoListOk End | - | .exit .frames .list",
        "list_error | 0 | Start LoginOk NodeOk IoListError End | - | .exit .frames"})
    void anIoLogCaseParsesAsItsNameSays(String name, int exit, String summary, String problem,
        String files) throws IOException {
        Path dir = ROOT.resolve("v8_1/io-log");
        HelperFrames frames = ioFrames(name);

        assertThat(Integer.parseInt(firstLine(dir.resolve(name + ".exit")))).isEqualTo(exit);
        assertThat(summary(frames.frames()) + " | "
            + frames.problem().map(Enum::name).orElse("-")).isEqualTo(summary + " | " + problem);
        assertThat(String.join(" ", new TreeSet<>(filesByCase(ROOT, new ArrayList<>())
            .get("v8_1/io-log/" + name)).stream().filter(suffix -> !suffix.equals(".origin"))
            .toList())).isEqualTo(files);
        for (HelperFrames.Frame frame : frames.frames()) {
            switch (frame) {
                case IoListOk ok -> assertThat(listing(name)).as("io list ok")
                    .hasSize(ok.count());
                case IoMatch(Optional<String> entry) when entry.isPresent() ->
                    assertThat(listing(name)).as("io match").contains(entry.get());
                case PartOk ok -> assertThat(Files.size(dir.resolve(name + ".message")))
                    .as("part message ok").isEqualTo(ok.bytes());
                default -> { }
            }
        }
    }

    /**
     * Every listing is of process 4711 (the request of every case), but {@code foreign_process},
     * whose first entry is of 4711 and the others of another process; the {@code .origin} of a
     * read case names the reference it requests, the reference of its matched entry, and that of
     * {@code no_match} is the reference of no listed entry.
     */
    @Test
    void theIoLogCasesListProcess4711AndNameTheReferenceTheyRequest() throws IOException {
        for (String name : List.of("listing", "read_text", "read_latin1", "read_binary",
            "read_empty", "no_match")) {
            assertThat(listing(name)).as(name).isNotEmpty().allMatch(e -> e.startsWith("4711@@@"));
        }
        assertThat(listing("empty_listing")).isEmpty();
        List<String> foreign = listing("foreign_process");
        assertThat(foreign).hasSizeGreaterThan(1);
        assertThat(foreign.get(0)).startsWith("4711@@@");
        assertThat(foreign.subList(1, foreign.size())).noneMatch(e -> e.startsWith("4711@@@"));

        for (String name : List.of("read_text", "read_latin1", "read_binary", "read_empty")) {
            String entry = ioFrames(name).frames().stream().filter(IoMatch.class::isInstance)
                .map(IoMatch.class::cast).findFirst().orElseThrow().entry().orElseThrow();
            assertThat(requestedRef(name)).as(name).isEqualTo(IoMessageRef.of(entry).value());
        }
        assertThat(listing("no_match")).extracting(entry -> IoMessageRef.of(entry).value())
            .doesNotContain(requestedRef("no_match"));
        assertThat(ioFrames("no_match").frames()).contains(new IoMatch(Optional.empty()));
    }

    /**
     * The messages are of the kinds their names say: UTF-8 text with a {@code <password>} element
     * and an {@code Authorization: Basic} line (masking, D-9), ISO-8859-1 text, GZIP and empty.
     */
    @Test
    void theIoLogMessagesAreOfTheKindsTheirNamesSay() throws IOException {
        Classified text = classify("read_text");
        assertThat(text.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(text.charset()).contains(StandardCharsets.UTF_8);
        assertThat(text.text().orElseThrow()).contains("<password>")
            .containsPattern("(?m)^Authorization: Basic \\S+$");

        Classified latin1 = classify("read_latin1");
        assertThat(latin1.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(latin1.charset()).contains(StandardCharsets.ISO_8859_1);
        assertThat(latin1.text().orElseThrow()).containsPattern("[\\u00A0-\\u00FF]");

        Classified binary = classify("read_binary");
        assertThat(binary.kind()).isEqualTo(ContentKind.BINARY);
        assertThat(binary.detected()).contains(BinarySignature.GZIP);

        assertThat(classify("read_empty").kind()).isEqualTo(ContentKind.EMPTY);
    }

    /**
     * {@code list_error} has the shape of every 8.1.17 listing of the T018 spike (research.md
     * "Spike findings"): an account without the needed right, here the fictitious {@code jdoe}.
     */
    @Test
    void theListErrorIsTheNotAllowedAccessAnswerOf8_1() throws IOException {
        assertThat(ioFrames("list_error").frames()).contains(new IoListError(
            List.of("com.inubit.ibis.utils.InubitException"), "Exception from service object:"
                + " @Start@NotAllowedAccess@@@The user \"jdoe\" is not allowed to access the"
                + " requested data.@@@jdoe@End@"));
    }

    /**
     * The message of every 8.1.17 {@code config error} of the T018 spike (research.md "Spike
     * findings"): the server's answer for a property the node does not have.
     */
    private static final String PROPERTY_MISSING = "Exception from service object:"
        + " @Start@IError@@@Internal INUBIT error!@End@Cannot invoke \"Object.getClass()\""
        + " because \"o\" is null";

    /**
     * Each 8.1 node-config case: its exit code, its frames and the parse problem; after a run that
     * reached the node, the {@code config} frames name the requested names (the {@code names=} of
     * its {@code .origin}) in their order, one frame each.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "all | 0 | Start LoginOk NodeOk ConfigError ConfigOk ConfigError ConfigOk*4 ConfigError*3"
            + " ConfigOk ConfigError ConfigOk*3 ConfigError*3 ConfigOk*2 End | -",
        "secret_value | 0 | Start LoginOk NodeOk ConfigError ConfigOk ConfigError ConfigOk*4"
            + " ConfigError*3 ConfigOk ConfigError ConfigOk*3 ConfigError*3 ConfigOk*2 End | -",
        "absent | 0 | Start LoginOk NodeOk ConfigError ConfigAbsent ConfigOk End | -",
        "one_error | 0 | Start LoginOk NodeOk ConfigOk ConfigError ConfigOk End | -",
        "api_missing | 4 | ApiMissing | -"})
    void aNodeConfigCaseParsesAsItsNameSays(String name, int exit, String summary,
        String problem) throws IOException {
        HelperFrames frames = configFrames(name);

        assertThat(Integer.parseInt(firstLine(ROOT.resolve("v8_1/node-config/" + name + ".exit"))))
            .isEqualTo(exit);
        assertThat(summary(frames.frames()) + " | "
            + frames.problem().map(Enum::name).orElse("-")).isEqualTo(summary + " | " + problem);
        if (exit == 0) {
            assertThat(frames.frames().stream().map(frame -> switch (frame) {
                case ConfigOk ok -> ok.name();
                case ConfigAbsent absent -> absent.name();
                case ConfigError error -> error.name();
                default -> null;
            }).filter(Objects::nonNull).toList()).isEqualTo(requestedNames(name));
        }
    }

    /**
     * {@code all} is the recording of the T018 spike on 8.1.17 (T038), {@code secret_value} its
     * reconstructed shape: both ask for every allowlisted name, answer the eleven names T018 saw
     * with their types, editable and not deprecated, and the other nine in the error form of a
     * missing property.
     */
    @ParameterizedTest
    @ValueSource(strings = {"all", "secret_value"})
    void anAllNamesCaseHasTheShapeOfThe8_1Spike(String name) throws IOException {
        assertThat(requestedNames(name)).isEqualTo(Arrays.stream(NodeConfigName.values())
            .map(NodeConfigName::wireName).toList());
        assertThat(answered(name)).extracting(ok -> ok.name() + " " + ok.type() + " "
            + ok.editable() + " " + ok.deprecated()).containsExactly(
            "ClusterName String true false", "NodeName String true false",
            "LocalMaintenanceMode Boolean true false", "ActivateClusterManager Boolean true false",
            "ClusterDebug Boolean true false", "RuntimeDataCacheXMXPercentage Integer true false",
            "indexEnabled Boolean true false", "iterationCount Integer true false",
            "DataSourceLocation String true false", "noOfRetries Integer true false",
            "retryInterval Long true false");
        assertThat(configFrames(name).frames()).filteredOn(ConfigError.class::isInstance)
            .containsExactly(propertyMissing("OperationMode"), propertyMissing("ZoneName"),
                propertyMissing("ContainerShutdownOnNodeFailure"),
                propertyMissing("RegisterMBeans"), propertyMissing("RegisterInternalMBeans"),
                propertyMissing("socketTimeoutInMillisecs"),
                propertyMissing("LogsDBDataSourceLocation"),
                propertyMissing("RepositoryDataSourceLocation"),
                propertyMissing("ClusterBindAddress"));
    }

    /**
     * As recorded on 8.1.17, {@code all} carries the generic vendor texts (display name = name,
     * category = type, no description) and its fictitious values have nothing credential-looking.
     */
    @Test
    void theAllCaseCarriesTheGenericVendorTextsOfTheRecording() throws IOException {
        assertThat(answered("all")).allSatisfy(ok -> {
            assertThat(ok.displayName()).isEqualTo(ok.name());
            assertThat(ok.category()).isEqualTo(ok.type());
            assertThat(ok.description()).isEmpty();
            assertThat(ok.value()).isNotEmpty().doesNotContainPattern("(?i)password|user=");
        });
        assertThat(answered("all")).extracting(ConfigOk::value)
            .contains("acme-cluster", "node1", "java:/comp/env/jdbc/IBISDB");
    }

    /**
     * {@code secret_value} has invented display names, categories and descriptions, and its
     * {@code DataSourceLocation} value carries a credential-looking text for the masking of D-21
     * (defence in depth).
     */
    @Test
    void theSecretValueCaseCarriesAMaskableValue() throws IOException {
        List<ConfigOk> answered = answered("secret_value");
        assertThat(answered).allSatisfy(ok -> assertThat(List.of(ok.displayName(),
            ok.category(), ok.description(), ok.value())).doesNotContain(""));
        assertThat(answered).filteredOn(ok -> ok.name().equals("DataSourceLocation"))
            .singleElement().extracting(ConfigOk::value).asString()
            .startsWith("java:/comp/env/jdbc/IBISDB").containsPattern("(?i)password=\\S");
    }

    /**
     * {@code absent} has both forms of a property the node does not have: the error form 8.1.17
     * answers (T018) and the {@code null} answer the protocol allows; the error of
     * {@code one_error} is another failure, of more than one exception, not that form.
     */
    @Test
    void theAbsentCaseHasBothMissingFormsAndOneErrorAnother() throws IOException {
        assertThat(configFrames("absent").frames()).contains(propertyMissing("OperationMode"),
            new ConfigAbsent("ClusterBindAddress"));
        assertThat(configFrames("one_error").frames()).filteredOn(ConfigError.class::isInstance)
            .map(ConfigError.class::cast).singleElement().satisfies(error -> {
                assertThat(error.exceptionChain()).hasSizeGreaterThan(1);
                assertThat(error.message()).doesNotContain("Object.getClass()");
            });
    }

    /**
     * Each 8.1 log-level case (StartCLI, research D-11): its exit code, the trace form its
     * {@code .origin} names on the first line, and the line of its stdout that says what it is.
     * The reset is the only {@code --reset} form; every other case names a {@code --package} below
     * {@code com.inubit.ibis}.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "set_ok | 0 | trace --nodelevel DEBUG --package 'com.inubit.ibis.acme' | 1-OK: OK",
        "reset_ok | 0 | trace --reset | 1-OK: OK",
        "missing_level | 1 | trace --package 'com.inubit.ibis.acme' without --nodelevel"
            + " | Missing log level.",
        "login_failure | 1 | trace --nodelevel DEBUG --package 'com.inubit.ibis.acme'"
            + " | com.inubit.ibis.cli.CliConnectionException: @Start@LoginFailure@@@The user does"
            + " not exist or password does not match.@End@",
        "forbidden | 1 | trace --nodelevel DEBUG --package 'com.inubit.ibis.acme'"
            + " | com.inubit.ibis.utils.InubitException: @Start@AccessDenied@@@Access"
            + " denied!@End@The user jdoe has no right to change log levels."})
    void aLogLevelCaseIsTheStartCliAnswerItsNameSays(String name, int exit, String form,
        String line) throws IOException {
        Path base = ROOT.resolve("v8_1/log-level/" + name);

        assertThat(Integer.parseInt(firstLine(Path.of(base + ".exit")))).isEqualTo(exit);
        assertThat(firstLine(Path.of(base + ".origin"))).startsWith("reconstructed ")
            .contains(" for " + form + ";");
        assertThat(Files.readAllLines(Path.of(base + ".stdout"), StandardCharsets.UTF_8))
            .startsWith("JAVA_HOME is set", "Password: ").contains(line);
        assertThat(Files.readString(Path.of(base + ".stdout"), StandardCharsets.UTF_8)
            .contains("-OK: ")).isEqualTo(exit == 0);
    }

    private static ConfigError propertyMissing(String name) {
        return new ConfigError(name, List.of("com.inubit.ibis.utils.InubitException"),
            PROPERTY_MISSING);
    }

    private static HelperFrames configFrames(String name) throws IOException {
        return HelperFrames.parse(NONCE, Files.readString(
            ROOT.resolve("v8_1/node-config/" + name + ".frames"), StandardCharsets.UTF_8));
    }

    /**
     * The first {@code names=…} in the {@code .origin} of a node-config case (on its second line
     * for a recorded case, whose first line is only {@code recorded <date> <version> neutralized}).
     */
    private static List<String> requestedNames(String name) throws IOException {
        Matcher names = Pattern.compile(
                "(?<![A-Za-z])names=([A-Za-z]+(?:,[A-Za-z]+)*)(?![A-Za-z,])")
            .matcher(Files.readString(ROOT.resolve("v8_1/node-config/" + name + ".origin"),
                StandardCharsets.UTF_8));
        assertThat(names.find()).as(name + ".origin names names=<name>,...").isTrue();
        return List.of(names.group(1).split(","));
    }

    /** The {@code config ok} frames of a node-config case, in order. */
    private static List<ConfigOk> answered(String name) throws IOException {
        return configFrames(name).frames().stream().filter(ConfigOk.class::isInstance)
            .map(ConfigOk.class::cast).toList();
    }

    private static HelperFrames ioFrames(String name) throws IOException {
        return HelperFrames.parse(NONCE, Files.readString(
            ROOT.resolve("v8_1/io-log/" + name + ".frames"), StandardCharsets.UTF_8));
    }

    /** The entries of the {@code .list} of an io-log case, one per line. */
    private static List<String> listing(String name) throws IOException {
        return Files.readAllLines(ROOT.resolve("v8_1/io-log/" + name + ".list"),
            StandardCharsets.UTF_8);
    }

    /** The {@code ref=<reference>} on the first line of the {@code .origin} of an io-log case. */
    private static String requestedRef(String name) {
        Matcher ref = Pattern.compile(" ref=([A-Za-z0-9_-]{22})(?![A-Za-z0-9_-])")
            .matcher(firstLine(ROOT.resolve("v8_1/io-log/" + name + ".origin")));
        assertThat(ref.find()).as(name + ".origin names ref=<reference>").isTrue();
        return ref.group(1);
    }

    private static Classified classify(String name) throws IOException {
        return EntryContentClassifier.classify(
            Files.readAllBytes(ROOT.resolve("v8_1/io-log/" + name + ".message")));
    }

    private static List<Detail> details(String name) throws IOException {
        return HelperFrames.parse(NONCE, Files.readString(
                ROOT.resolve("v8_1/cluster-nodes/" + name + ".frames"), StandardCharsets.UTF_8))
            .frames().stream().filter(Detail.class::isInstance).map(Detail.class::cast).toList();
    }

    @Test
    void aMissingOriginFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        Files.delete(copy.resolve("v8_1/cluster-nodes/single.origin"));

        assertThat(violations(copy)).containsExactly("v8_1/cluster-nodes/single: no .origin");
    }

    @Test
    void anOriginOfAnInvalidFormatFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        Files.writeString(copy.resolve("v8_1/cluster-nodes/details.origin"), "synthetic\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/ok.origin"), "recorded 8.1.17\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/single.origin"), "reconstructed\n");

        String invalid = ": the first line of .origin is neither 'recorded <date> <version>"
            + " neutralized' nor 'reconstructed <source>'";
        assertThat(violations(copy)).containsExactly("v8_1/cluster-nodes/details" + invalid,
            "v8_1/cluster-nodes/ok" + invalid, "v8_1/cluster-nodes/single" + invalid);
    }

    @Test
    void aFileOfNoCaseFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        Files.writeString(copy.resolve("notes.txt"), "x\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/ok.stdout"), "x\n");
        Files.writeString(copy.resolve("v8_1/ok.origin"), "reconstructed x\n");
        Files.createDirectories(copy.resolve("v9_1/cluster-nodes"));
        Files.writeString(copy.resolve("v9_1/cluster-nodes/ok.origin"), "reconstructed x\n");
        Files.createDirectories(copy.resolve("v8_1/cache"));
        Files.writeString(copy.resolve("v8_1/cache/ok.origin"), "reconstructed x\n");
        Files.createDirectories(copy.resolve("v8_1/log-level"));
        Files.writeString(copy.resolve("v8_1/log-level/set_ok.frames"), "x\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/stray.exit"), "0\n");

        assertThat(violations(copy)).containsExactlyInAnyOrder(
            "notes.txt belongs to no case",
            "v8_1/cache/ok.origin belongs to no case",
            "v8_1/cluster-nodes/ok.stdout belongs to no case",
            "v8_1/log-level/set_ok.frames belongs to no case",
            "v8_1/ok.origin belongs to no case",
            "v9_1/cluster-nodes/ok.origin belongs to no case",
            "v8_1/cluster-nodes/stray: no .origin");
    }

    @Test
    void theMatrixRuleFailsInBothDirections(@TempDir Path temp) throws IOException {
        // T025: v8_1/cluster-nodes/ok is recorded
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.SUPPORTED))
            .isNull();
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.UNSUPPORTED))
            .isEqualTo("8.1 is UNSUPPORTED for CLUSTER_NODES_READ, but its required cases are"
                + " recorded (v8_1/cluster-nodes/ok); enable it in the capability matrix in the"
                + " same change");
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.UNVERIFIED))
            .startsWith("8.1 is UNVERIFIED for CLUSTER_NODES_READ, but its required cases");

        Files.writeString(copy.resolve("v8_1/cluster-nodes/ok.origin"), "reconstructed x\n");
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.SUPPORTED))
            .isEqualTo("8.1 is SUPPORTED for CLUSTER_NODES_READ, but these required cases are not"
                + " recorded: v8_1/cluster-nodes/ok");
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.UNSUPPORTED))
            .isNull();

        // the log level needs both of its cases recorded; the other cases of a tool do not count
        Files.createDirectories(copy.resolve("v9_0/log-level"));
        for (String name : List.of("set_ok", "reset_ok")) {
            for (String suffix : List.of(".stdout", ".stderr", ".exit")) {
                Files.writeString(copy.resolve("v9_0/log-level/" + name + suffix), "x\n");
            }
        }
        Files.writeString(copy.resolve("v9_0/log-level/set_ok.origin"),
            "recorded 2026-11-12 9.0.10 neutralized\n");
        Files.writeString(copy.resolve("v9_0/log-level/forbidden.origin"),
            "recorded 2026-11-12 9.0.10 neutralized\n");
        assertThat(matrixViolation(copy, "v9_0", "log-level", SupportState.UNSUPPORTED)).isNull();
        assertThat(matrixViolation(copy, "v9_0", "log-level", SupportState.SUPPORTED))
            .isEqualTo("9.0 is SUPPORTED for LOG_LEVEL_WRITE, but these required cases are not"
                + " recorded: v9_0/log-level/reset_ok");
        Files.writeString(copy.resolve("v9_0/log-level/reset_ok.origin"),
            "recorded 2026-11-12 9.0.10 neutralized\n");
        assertThat(matrixViolation(copy, "v9_0", "log-level", SupportState.SUPPORTED)).isNull();
        assertThat(matrixViolation(copy, "v9_0", "log-level", SupportState.UNSUPPORTED))
            .isEqualTo("9.0 is UNSUPPORTED for LOG_LEVEL_WRITE, but its required cases are"
                + " recorded (v9_0/log-level/set_ok, v9_0/log-level/reset_ok); enable it in the"
                + " capability matrix in the same change");
    }

    @Test
    void aRecordedRequiredCaseWithoutItsDataFilesDoesNotCount(@TempDir Path temp)
        throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        Files.delete(copy.resolve("v8_1/cluster-nodes/ok.frames"));

        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.SUPPORTED))
            .isEqualTo("8.1 is SUPPORTED for CLUSTER_NODES_READ, but these required cases are not"
                + " recorded: v8_1/cluster-nodes/ok (no .frames)");
        assertThat(matrixViolation(copy, "v8_1", "cluster-nodes", SupportState.UNSUPPORTED))
            .isNull();

        // io-log read_text needs its .list and .message as well
        Files.createDirectories(copy.resolve("v9_0/io-log"));
        for (String file : List.of("listing.origin", "read_text.origin")) {
            Files.writeString(copy.resolve("v9_0/io-log/" + file),
                "recorded 2026-11-12 9.0.10 neutralized\n");
        }
        for (String file : List.of("listing.frames", "listing.exit", "listing.list",
            "read_text.frames", "read_text.exit")) {
            Files.writeString(copy.resolve("v9_0/io-log/" + file), "x\n");
        }
        assertThat(matrixViolation(copy, "v9_0", "io-log", SupportState.SUPPORTED))
            .isEqualTo("9.0 is SUPPORTED for IO_LOG_READ, but these required cases are not"
                + " recorded: v9_0/io-log/read_text (no .list, .message)");
    }

    @Test
    void aRecordedOriginOfAnotherVersionLineFails(@TempDir Path temp) throws IOException {
        Path copy = copy(ROOT, temp.resolve("node-tools"));
        Files.writeString(copy.resolve("v8_1/cluster-nodes/ok.origin"),
            "recorded 2026-11-12 9.0.10 neutralized\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/single.origin"),
            "recorded 2026-11-12 8.10.2 neutralized\n");
        Files.writeString(copy.resolve("v8_1/cluster-nodes/details.origin"),
            "recorded 2026-11-12 8.1 neutralized\n");

        assertThat(violations(copy)).containsExactly(
            "v8_1/cluster-nodes/ok: .origin names version 9.0, but the case is in v8_1",
            "v8_1/cluster-nodes/single: .origin names version 8.10, but the case is in v8_1");
    }

    /** The cases below {@code root} as {@code <version>/<tool>/<case>}, sorted. */
    static Set<String> cases(Path root) throws IOException {
        return new TreeSet<>(filesByCase(root, new ArrayList<>()).keySet());
    }

    /** Every violation of the layout and provenance rules of the class description. */
    static List<String> violations(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        filesByCase(root, violations).forEach((name, suffixes) -> {
            if (!suffixes.contains(".origin")) {
                violations.add(name + ": no .origin");
                return;
            }
            Matcher origin = ORIGIN.matcher(firstLine(root.resolve(name + ".origin")));
            if (!origin.matches()) {
                violations.add(name + ": the first line of .origin is neither 'recorded <date>"
                    + " <version> neutralized' nor 'reconstructed <source>'");
                return;
            }
            String directory = name.substring(0, name.indexOf('/'));
            int[] line = VERSIONS.get(directory);
            if (origin.group(1) != null && (Integer.parseInt(origin.group(1)) != line[0]
                || Integer.parseInt(origin.group(2)) != line[1])) {
                violations.add(name + ": .origin names version " + origin.group(1) + "."
                    + origin.group(2) + ", but the case is in " + directory);
            }
        });
        return violations;
    }

    /** Every (version, capability) whose matrix state and recorded cases do not fit (D-27). */
    static List<String> matrixViolations(Path root, CapabilityMatrix matrix) {
        List<String> violations = new ArrayList<>();
        for (String version : new TreeSet<>(VERSIONS.keySet())) {
            int[] line = VERSIONS.get(version);
            for (String tool : new TreeSet<>(TOOLS.keySet())) {
                String violation = matrixViolation(root, version, tool, matrix.stateOfLine(line[0],
                    OptionalInt.of(line[1]), TOOLS.get(tool).capability()));
                if (violation != null) {
                    violations.add(violation);
                }
            }
        }
        return violations;
    }

    /** The violation of one (version, tool) in {@code state}; {@code null} if they fit. */
    static String matrixViolation(Path root, String version, String tool, SupportState state) {
        List<String> required = new ArrayList<>();
        List<String> notRecorded = new ArrayList<>();
        for (Required each : TOOLS.get(tool).required()) {
            String name = version + "/" + tool + "/" + each.name();
            required.add(name);
            Path origin = root.resolve(name + ".origin");
            List<String> missing = new TreeSet<>(each.files()).stream()
                .filter(suffix -> !Files.isRegularFile(root.resolve(name + suffix))).toList();
            if (!Files.isRegularFile(origin) || !firstLine(origin).startsWith("recorded ")) {
                notRecorded.add(name);
            } else if (!missing.isEmpty()) {
                notRecorded.add(name + " (no " + String.join(", ", missing) + ")");
            }
        }
        int[] line = VERSIONS.get(version);
        String subject = line[0] + "." + line[1] + " is " + state + " for "
            + TOOLS.get(tool).capability();
        if (state == SupportState.SUPPORTED && !notRecorded.isEmpty()) {
            return subject + ", but these required cases are not recorded: "
                + String.join(", ", notRecorded);
        }
        if (state != SupportState.SUPPORTED && notRecorded.isEmpty()) {
            return subject + ", but its required cases are recorded (" + String.join(", ", required)
                + "); enable it in the capability matrix in the same change";
        }
        return null;
    }

    /** The simple class names of {@code frames}, a run of {@code n > 1} as {@code Type*n}. */
    private static String summary(List<HelperFrames.Frame> frames) {
        List<String> runs = new ArrayList<>();
        String type = null;
        int count = 0;
        for (HelperFrames.Frame frame : frames) {
            String name = frame.getClass().getSimpleName();
            if (!name.equals(type) && type != null) {
                runs.add(count > 1 ? type + "*" + count : type);
                count = 0;
            }
            type = name;
            count++;
        }
        if (type != null) {
            runs.add(count > 1 ? type + "*" + count : type);
        }
        return String.join(" ", runs);
    }

    /**
     * The suffixes of each case {@code <version>/<tool>/<case>}; files outside the layout go to
     * {@code violations}.
     */
    private static Map<String, Set<String>> filesByCase(Path root, List<String> violations)
        throws IOException {
        Map<String, Set<String>> cases = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (ROOT_FILES.contains(relative)) {
                    continue;
                }
                String[] parts = relative.split("/");
                String fileName = parts[parts.length - 1];
                int dot = fileName.indexOf('.');
                String name = dot < 0 ? fileName : fileName.substring(0, dot);
                String suffix = dot < 0 ? "" : fileName.substring(dot);
                if (parts.length != 3 || !VERSIONS.containsKey(parts[0])
                    || !TOOLS.containsKey(parts[1]) || name.isEmpty()
                    || !TOOLS.get(parts[1]).suffixes().contains(suffix)) {
                    violations.add(relative + " belongs to no case");
                    continue;
                }
                cases.computeIfAbsent(parts[0] + "/" + parts[1] + "/" + name,
                    key -> new TreeSet<>()).add(suffix);
            }
        }
        return cases;
    }

    private static String firstLine(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return lines.isEmpty() ? "" : lines.get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        return to;
    }
}
