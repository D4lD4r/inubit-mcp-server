package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.VersionGate.Admission;
import de.dadecker.inubit.mcp.application.VersionGate.PartAdmission;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
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
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 (T010, research D-2 "Gate per call", D-12 step 5): the coverage gate of
 * {@link VersionGate#admitCall} and {@link VersionGate#admitParts}. The capability matrix supports
 * every capability on every version, so only the coverage list (a fixture) and the StartCLI client
 * decide.
 */
class VersionGateActionTest {

    private static final NodeId V8_1 = NodeId.parse("dev/node1");
    private static final NodeId V9_0 = NodeId.parse("test/node1");
    private static final NodeId V6_1 = NodeId.parse("old/node1");
    /** A major-only line: no major.minor to look the entries up for. */
    private static final NodeId V9_X = NodeId.parse("prod/node1");

    private static final String TOOL = "get_model_details";

    private static final CoverageKey BY_ID = rest("GET /model/modelById/{id}");
    private static final CoverageKey BY_NAME = rest("GET /model/nodeByName/{diagram}/{module}");
    private static final CoverageKey LICENSE = rest("GET /license");
    private static final CoverageKey CONFIGURATIONS = rest("GET /configurations");
    private static final CoverageKey LLS = new CoverageKey(Channel.CLI, "lls");
    private static final CoverageKey THREADS =
        new CoverageKey(Channel.CLI, "threads showNumberOfWorkflowThreads");

    private static final String LLS_REASON = "lists the local directory of the StartCLI client";

    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        REST\tGET /model/modelById/{id}\tREAD\tE\tUS3\treachable:get_model_details/module\treachable:get_model_details/module\t-
        REST\tGET /model/nodeByName/{diagram}/{module}\tREAD\tE\tUS3\treachable:get_model_details/module\tundocumented\t-
        REST\tGET /license\tREAD\tA\tUS1\treachable:get_server_settings/default\tplanned\t-
        REST\tGET /configurations\tREAD\tA\tUS1\treachable:get_server_settings/default\treachable:get_server_settings/default\t-
        CLI\tlls\tREAD\tA\t-\texcluded:%1$s\texcluded:%1$s\t-
        CLI\tthreads showNumberOfWorkflowThreads\tREAD\tA\tUS1\treachable:get_server_settings/default\treachable:get_server_settings/default\t-
        """.formatted(LLS_REASON);

    private static final Part LICENSE_PART =
        new Part(UnavailablePart.VERSION, Channel.REST, Set.of(LICENSE));
    private static final Part CONFIGURATION_PART =
        new Part(UnavailablePart.SYSTEM_INFO, Channel.REST, Set.of(CONFIGURATIONS));
    private static final Part THREADS_PART =
        new Part(UnavailablePart.THREADS, Channel.CLI, Set.of(THREADS));

    private final NodeVersions versions = new NodeVersions(Clock.systemUTC(),
        Map.of(V8_1, VersionSetting.V8_1, V9_0, VersionSetting.V9_0, V6_1, VersionSetting.V6_1,
            V9_X, VersionSetting.V9_X)::get,
        node -> Duration.ofMinutes(10));
    /** The nodes with a StartCLI installation (none by default). */
    private final Set<NodeId> withClient = new HashSet<>();

    private final VersionGate gate = new VersionGate(versions, new FakeVersionDetection(versions),
        everythingSupported(), (node, version, timeout) -> {
            throw new AssertionError("no client probe expected for " + node);
        }, TestClientSelection.of(withClient::contains), node -> Duration.ZERO,
        Terminology.DEFAULT, false, InterfaceCoverage.load(new StringReader(LIST)));

    /** One action with two variants: by {@code moduleId} or by {@code moduleName}. */
    private static final ActionSpec MODULE = new ActionSpec(TOOL, "module",
        Capability.INVENTORY, ActionSpec.Area.READ, ActionSpec.Kind.READ, Set.of(BY_ID, BY_NAME),
        input -> input.containsKey("moduleId") ? Set.of(BY_ID) : Set.of(BY_NAME), Set.of(),
        false, Optional.empty(), ActionSpec.RestartNotice.NONE);

    /** A multi-part read: two REST parts and one StartCLI part. */
    private static final ActionSpec SETTINGS = new ActionSpec("get_server_settings", "default",
        Capability.INVENTORY, ActionSpec.Area.READ, ActionSpec.Kind.READ, Set.of(),
        input -> Set.of(), Set.of(LICENSE_PART, CONFIGURATION_PART, THREADS_PART), false,
        Optional.empty(), ActionSpec.RestartNotice.NONE);

    // --- admitCall ----------------------------------------------------------------------------

    @Test
    void aReachableEntryIsAdmittedOn90() {
        Admission admission = gate.admitCall(TOOL, V9_0, MODULE, Set.of(BY_ID));

        assertThat(admission.warnings()).isEmpty();
        assertThat(admission.version()).map(InubitVersion::raw).contains("9.0");
    }

    @Test
    void aPlannedEntryIsRefusedAsNotYetVerified() {
        ToolError error = refusal(() -> gate.admitCall(TOOL, V9_0, spec(LICENSE),
            Set.of(LICENSE)));

        assertThat(error.message()).startsWith(TOOL + ": INUBIT 9.0 on " + V9_0 + ": ")
            .contains("via REST GET /license is not yet verified for INUBIT 9.0")
            .endsWith("; nothing was sent");
        assertThat(error.node()).contains(V9_0);
    }

    @Test
    void anExcludedEntryIsRefusedWithItsReason() {
        ToolError error = refusal(() -> gate.admitCall(TOOL, V9_0, spec(LLS), Set.of(LLS)));

        assertThat(error.message()).contains("via CLI lls is excluded for INUBIT 9.0: "
            + LLS_REASON);
    }

    @Test
    void anUndocumentedEntryIsRefusedAsNotDocumentedForThisVersion() {
        ToolError error = refusal(() -> gate.admitCall(TOOL, V9_0, MODULE, Set.of(BY_NAME)));

        assertThat(error.message()).contains("module via REST " + BY_NAME.entry()
            + " is not documented for this version (INUBIT 9.0)");
    }

    @Test
    void a61NodeIsAlwaysRefusedEvenWhenTheCapabilityIsSupported() {
        ToolError error = refusal(() -> gate.admitCall(TOOL, V6_1, MODULE, Set.of(BY_ID)));

        assertThat(error.message()).contains("is not listed for INUBIT 6.1");
    }

    @Test
    void aNodeWithoutAMajorMinorIsRefused() {
        ToolError error = refusal(() -> gate.admitCall(TOOL, V9_X, MODULE, Set.of(BY_ID)));

        assertThat(error.message()).contains("INUBIT 9.x (versionLine V9_X) on " + V9_X)
            .contains("names no major.minor");
    }

    @Test
    void aVariantIsRefusedWhileItsSiblingVariantIsAdmitted() {
        assertThat(gate.admitCall(TOOL, V9_0, MODULE, MODULE.entries(Map.of("moduleId", "42")))
            .warnings()).isEmpty();

        ToolError error = refusal(() -> gate.admitCall(TOOL, V9_0, MODULE,
            MODULE.entries(Map.of("moduleName", "Sales"))));
        assertThat(error.message()).contains(BY_NAME.entry())
            .contains("not documented for this version");

        // both variants on 8.1
        gate.admitCall(TOOL, V8_1, MODULE, MODULE.entries(Map.of("moduleName", "Sales")));
    }

    // --- admitParts ---------------------------------------------------------------------------

    @Test
    void aPartThatIsNotReachableIsUnavailableWhileTheOthersAreAdmitted() {
        withClient.add(V9_0);
        versions.clientProbed(V9_0, new ProbedClient(ClientKey.NONE, compatibleClient()));

        PartAdmission admission = gate.admitParts("get_server_settings", V9_0, SETTINGS,
            Set.of(LICENSE_PART, CONFIGURATION_PART, THREADS_PART));

        assertThat(admission.admitted()).containsExactlyInAnyOrder(CONFIGURATION_PART,
            THREADS_PART);
        assertThat(admission.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.VERSION);
            assertThat(part.reason()).startsWith("UNSUPPORTED_VERSION: ")
                .contains("REST GET /license is not yet verified for INUBIT 9.0");
            assertThat(part.nextStep()).isPresent();
        });
    }

    @Test
    void cliPartsOfANodeWithoutAStartCliClientAreUnavailableAndRestPartsAdmitted() {
        PartAdmission admission = gate.admitParts("get_server_settings", V8_1, SETTINGS,
            Set.of(LICENSE_PART, CONFIGURATION_PART, THREADS_PART));

        assertThat(admission.admitted()).containsExactlyInAnyOrder(LICENSE_PART,
            CONFIGURATION_PART);
        assertThat(admission.unavailable()).singleElement().satisfies(part -> {
            assertThat(part.part()).isEqualTo(UnavailablePart.THREADS);
            assertThat(part.reason()).startsWith("CLI_UNAVAILABLE: ");
        });
    }

    @Test
    void noAdmittedPartRefusesTheCall() {
        ToolError version = refusal(() -> gate.admitParts("get_server_settings", V9_0, SETTINGS,
            Set.of(LICENSE_PART, THREADS_PART)));
        assertThat(version.message()).startsWith("get_server_settings: ")
            .contains("GET /license is not yet verified")
            .contains("no StartCLI client").endsWith("; nothing was sent");

        ToolErrorException client = catchThrowableOfType(ToolErrorException.class,
            () -> gate.admitParts("get_server_settings", V8_1, SETTINGS, Set.of(THREADS_PART)));
        assertThat(client).as("refused").isNotNull();
        assertThat(client.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(client.error().message()).startsWith("get_server_settings: ")
            .endsWith("; nothing was sent");
    }

    // --- helpers ------------------------------------------------------------------------------

    /** A single-variant action that sends {@code entry}. */
    private static ActionSpec spec(CoverageKey entry) {
        return new ActionSpec(TOOL, "module", Capability.INVENTORY, ActionSpec.Area.READ,
            ActionSpec.Kind.READ, Set.of(entry), input -> Set.of(entry), Set.of(), false,
            Optional.empty(), ActionSpec.RestartNotice.NONE);
    }

    private static CoverageKey rest(String entry) {
        return new CoverageKey(Channel.REST, entry);
    }

    private static CapabilityMatrix everythingSupported() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(), states);
    }

    /** A client for INUBIT 9.0 whose patch the 9.0 line cannot be compared with. */
    private static ClientVersion compatibleClient() {
        return new ClientVersion(InubitVersion.parse("9.0.7").orElseThrow(),
            InubitVersion.parse("9.0.0"), InubitVersion.parse("9.0.99"));
    }

    private static ToolError refusal(Runnable call) {
        ToolErrorException e = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(e).as("refused").isNotNull();
        assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        return e.error();
    }
}
