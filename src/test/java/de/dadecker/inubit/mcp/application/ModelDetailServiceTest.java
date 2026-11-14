package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.ModelDetailService.Libraries;
import de.dadecker.inubit.mcp.application.ModelDetailService.Read;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Connection;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ModelDetailPort;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T043 (feature 022, US3 AS2, AS3, research D-21): the actions {@code module} and
 * {@code libraries} of {@code read_model_details}. The capability matrix supports
 * {@code MODEL_DETAILS}; the coverage list is a fixture whose {@code module} row is reachable on
 * 8.1 and {@code planned} on 9.0 (unlike the production list since T046a) and whose library
 * rows are reachable on both, so each action is admitted with its own entries.
 */
@Timeout(30)
class ModelDetailServiceTest {

    /** INUBIT 8.1 with a StartCLI client. */
    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    /** INUBIT 9.0 with a StartCLI client; the fixture keeps its {@code module} row planned. */
    private static final NodeId TEST1 = NodeId.parse("test/node1");
    private static final String DIAGRAM = "Workflow-0301";

    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        REST\tGET /model/nodeByModel/{model}/{node}\tREAD\tE\tUS3\treachable:read_model_details/module\tplanned\t-
        CLI\tlibrary --list\tREAD\tC\tUS3\treachable:read_model_details/libraries\treachable:read_model_details/libraries\t-
        CLI\tlibrary --type\tREAD\tC\tUS3\treachable:read_model_details/libraries\treachable:read_model_details/libraries\t-
        """;

    private static final ModuleDetail MODULE = new ModuleDetail(DIAGRAM, "100", "Demo-DB-Probe",
        "twDatabaseConnector", Optional.empty(), List.of(new Connection("22", "1")),
        List.of(Property.shown("dbURL", "String", "jdbc:postgresql://db.example.test/demo", false),
            Property.masked("Password", "Password")), 1);

    private final NodeVersions versions = new NodeVersions(Clock.systemUTC(),
        Map.of(DEV1, VersionSetting.V8_1, TEST1, VersionSetting.V9_0)::get,
        node -> Duration.ofMinutes(10));

    private final VersionGate gate = new VersionGate(versions,
        new FakeVersionDetection(versions), everythingSupported(),
        (node, version, timeout) -> {
            throw new AssertionError("no client probe expected for " + node);
        }, TestClientSelection.of(node -> true), node -> Duration.ZERO,
        Terminology.DEFAULT, false, InterfaceCoverage.load(new StringReader(LIST)));

    private final FakeModelDetails port = new FakeModelDetails();

    private final ModelDetailService service = new ModelDetailService(
        new TargetResolver(List.of(DEV1, TEST1)), gate, node -> port);

    {
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE, client("8.1")));
        versions.clientProbed(TEST1, new ProbedClient(ClientKey.NONE, client("9.0")));
    }

    // --- AS2: one module ----------------------------------------------------------------------

    @Test
    void aModuleIsReadOverRestWithItsMaskedProperties() {
        port.module = MODULE;

        Read<ModuleDetail> read = service.module("dev/node1", DIAGRAM, "100");

        assertThat(read.node()).isEqualTo(DEV1);
        assertThat(read.value()).isEqualTo(MODULE);
        assertThat(read.source()).isEqualTo(Channel.REST);
        assertThat(read.warnings()).isEmpty();
        assertThat(port.moduleCalls).containsExactly(DIAGRAM + "/100");
        assertThat(port.libraryCalls).as("no StartCLI run").isEmpty();
    }

    @Test
    void anUnknownModuleIdIsNotFound() {
        port.moduleFailure = new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
            "Module 999 was not found in diagram '" + DIAGRAM + "' on dev/node1",
            "INUBIT reports that the diagram has no module with this id",
            "Check the diagram name and the module id").withNode(DEV1));

        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service.module("dev/node1", DIAGRAM, "999"));

        assertThat(e).isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(e.error().node()).contains(DEV1);
        assertThat(e.error().message()).contains("999", DIAGRAM);
    }

    @Test
    void theModuleIsRefusedOn90AsNotYetVerifiedBeforeAnythingIsSent() {
        port.module = MODULE;

        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service.module("test/node1", DIAGRAM, "100"));

        assertThat(e).isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(e.error().message()).contains("module via REST GET /model/nodeByModel/",
            "not yet verified");
        assertThat(port.moduleCalls).isEmpty();
    }

    @Test
    void malformedModuleInputsAreInvalidBeforeAnythingIsSent() {
        port.module = MODULE;

        assertInvalid(() -> service.module("dev/node1", DIAGRAM, ""));
        assertInvalid(() -> service.module("dev/node1", DIAGRAM, "1a"));
        assertInvalid(() -> service.module("dev/node1", DIAGRAM, "12345678901"));
        assertInvalid(() -> service.module("dev/node1", "", "100"));
        assertInvalid(() -> service.module("dev/node1", "../x", "100"));
        assertInvalid(() -> service.module("dev/node1", "x".repeat(201), "100"));
        assertInvalid(() -> service.module("dev", DIAGRAM, "100"));
        assertThat(port.moduleCalls).isEmpty();
    }

    // --- AS3: the libraries -------------------------------------------------------------------

    @Test
    void oneTypeIsOneRunAndItsFilesArePagedInTheAnswersOrder() {
        port.listings.put("driver", listing("driver", "postgresql-42.7.7.jar", "ojdbc11.jar",
            "mssql-jdbc.jar"));

        Read<Libraries> read = service.libraries("dev/node1", Optional.of("driver"), 1,
            OptionalInt.of(1));

        assertThat(read.source()).isEqualTo(Channel.CLI);
        Page<LibraryEntry> page = read.value().page();
        assertThat(page.items()).containsExactly(new LibraryEntry("ojdbc11.jar", "driver"));
        assertThat(page.offset()).isEqualTo(1);
        assertThat(page.limit()).isEqualTo(1);
        assertThat(page.total()).isEqualTo(OptionalLong.of(3));
        assertThat(page.nextOffset()).isEqualTo(OptionalInt.of(2));
        assertThat(read.value().rowFormatVerified()).isFalse();
        assertThat(port.libraryCalls).containsExactly("driver");
    }

    @Test
    void withoutATypeTheDriversAndThePluginsAreListedOneRunEach() {
        port.listings.put("driver", listing("driver", "ojdbc11.jar"));
        port.listings.put("plugin", listing("plugin", "custom-plugin.jar", "other-plugin.jar"));

        Read<Libraries> read = service.libraries("dev/node1", Optional.empty(), 0,
            OptionalInt.empty());

        Page<LibraryEntry> page = read.value().page();
        assertThat(page.items()).containsExactly(new LibraryEntry("ojdbc11.jar", "driver"),
            new LibraryEntry("custom-plugin.jar", "plugin"),
            new LibraryEntry("other-plugin.jar", "plugin"));
        assertThat(page.limit()).isEqualTo(ModelDetailService.DEFAULT_LIMIT);
        assertThat(page.total()).isEqualTo(OptionalLong.of(3));
        assertThat(page.nextOffset()).isEmpty();
        assertThat(port.libraryCalls).containsExactly("driver", "plugin");
    }

    @Test
    void withoutLibrariesThePageIsEmpty() {
        port.listings.put("driver", listing("driver"));
        port.listings.put("plugin", listing("plugin"));

        Page<LibraryEntry> page = service.libraries("test/node1", Optional.empty(), 0,
            OptionalInt.empty()).value().page();

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(OptionalLong.of(0));
        assertThat(page.nextOffset()).isEmpty();
    }

    @Test
    void theRowFormatIsVerifiedOnlyWhenEveryListingIsVerified() {
        port.listings.put("driver", new LibraryEntry.Listing(List.of(), true));
        port.listings.put("plugin", listing("plugin", "custom-plugin.jar"));

        assertThat(service.libraries("dev/node1", Optional.empty(), 0, OptionalInt.empty())
            .value().rowFormatVerified()).isFalse();
        assertThat(service.libraries("dev/node1", Optional.of("driver"), 0, OptionalInt.empty())
            .value().rowFormatVerified()).isTrue();
    }

    @Test
    void aFailedRunFailsTheCallAlsoAfterAnotherTypeWasRead() {
        port.listings.put("driver", listing("driver", "ojdbc11.jar"));
        port.libraryFailure = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No StartCLI client is installed for dev/node1", "No client", "Install a client")
            .withNode(DEV1));

        ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
            () -> service.libraries("dev/node1", Optional.empty(), 0, OptionalInt.empty()));

        assertThat(e).isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(port.libraryCalls).containsExactly("driver", "plugin");
    }

    @Test
    void malformedLibraryInputsAreInvalidBeforeAnythingRuns() {
        assertInvalid(() -> service.libraries("dev/node1", Optional.of("jar"), 0,
            OptionalInt.empty()));
        assertInvalid(() -> service.libraries("dev/node1", Optional.empty(), -1,
            OptionalInt.empty()));
        assertInvalid(() -> service.libraries("dev/node1", Optional.empty(), 10_000,
            OptionalInt.empty()));
        assertInvalid(() -> service.libraries("dev/node1", Optional.empty(), 0,
            OptionalInt.of(0)));
        assertInvalid(() -> service.libraries("dev/node1", Optional.empty(), 0,
            OptionalInt.of(101)));
        assertThat(port.libraryCalls).isEmpty();
    }

    // --- the actions ----------------------------------------------------------------------------

    @Test
    void theActionsAreModuleAndLibrariesWithTheirOwnEntries() {
        CoverageKey nodeByModel = new CoverageKey(Channel.REST,
            "GET /model/nodeByModel/{model}/{node}");
        CoverageKey list = new CoverageKey(Channel.CLI, "library --list");
        CoverageKey type = new CoverageKey(Channel.CLI, "library --type");

        assertThat(ModelDetailService.SPECS).extracting(ActionSpec::action)
            .containsExactly("module", "libraries");
        assertThat(ModelDetailService.SPECS).allSatisfy(spec -> {
            assertThat(spec.tool()).isEqualTo("read_model_details");
            assertThat(spec.capability()).isEqualTo(Capability.MODEL_DETAILS);
            assertThat(spec.area()).isEqualTo(ActionSpec.Area.READ);
            assertThat(spec.kind()).isEqualTo(ActionSpec.Kind.READ);
            assertThat(spec.parts()).isEmpty();
        });
        assertThat(ModelDetailService.MODULE.callEntries()).containsExactly(nodeByModel);
        assertThat(ModelDetailService.LIBRARIES.callEntries())
            .containsExactlyInAnyOrder(list, type);
        new ActionCatalog(ModelDetailService.SPECS);
    }

    // --- helpers ------------------------------------------------------------------------------

    private void assertInvalid(ThrowingCallable call) {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class, call);
        assertThat(e).isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    private static LibraryEntry.Listing listing(String type, String... files) {
        return new LibraryEntry.Listing(IntStream.range(0, files.length)
            .mapToObj(i -> new LibraryEntry(files[i], type)).toList(), false);
    }

    /** A client for INUBIT {@code line} (e.g. "8.1"), version {@code line}.12. */
    private static ClientVersion client(String line) {
        return new ClientVersion(InubitVersion.parse(line + ".12").orElseThrow(),
            InubitVersion.parse(line + ".0"), InubitVersion.parse(line + ".99"));
    }

    private static CapabilityMatrix everythingSupported() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(), states);
    }

    /** The model details of every node, recording what was asked. */
    private static final class FakeModelDetails implements ModelDetailPort {

        ModuleDetail module;
        ToolErrorException moduleFailure;
        final Map<String, LibraryEntry.Listing> listings = new HashMap<>();
        /** Thrown by the {@code plugin} run. */
        ToolErrorException libraryFailure;
        final List<String> moduleCalls = new CopyOnWriteArrayList<>();
        final List<String> libraryCalls = new CopyOnWriteArrayList<>();

        @Override
        public ModuleDetail module(String diagram, String moduleId) {
            moduleCalls.add(diagram + "/" + moduleId);
            if (moduleFailure != null) {
                throw moduleFailure;
            }
            return module;
        }

        @Override
        public LibraryEntry.Listing libraries(String type) {
            libraryCalls.add(type);
            if (libraryFailure != null && type.equals("plugin")) {
                throw libraryFailure;
            }
            return listings.get(type);
        }
    }
}
