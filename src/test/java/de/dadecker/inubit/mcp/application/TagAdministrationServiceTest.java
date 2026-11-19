package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.TagAdministrationService.Request;
import de.dadecker.inubit.mcp.application.TagAdministrationService.Response;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ChangeOutcome;
import de.dadecker.inubit.mcp.domain.model.ChangePreview;
import de.dadecker.inubit.mcp.domain.model.ChangeResult;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.RecordingAuditPort;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 (T064, US4 AS12–AS14, FR-019a, research D-17, D-18): {@code manage_tags} – the
 * active tag of a user with its preview and re-read, the lock key and the state changed since the
 * preview; tag removal and deletion are unknown actions after the live check T055 (owner decision
 * IMC-7). The coverage rows of the active-tag forms are {@code reachable} only in this test's
 * coverage list; the production list keeps them {@code planned}.
 */
class TagAdministrationServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId TEST_CLIENT = NodeId.parse("test/node2");
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final String REASON = "clean up the release tags";

    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        CLI\ttag --tagUser\tWRITE\tE\t-\treachable:tag_artifacts/default\treachable:tag_artifacts/default\t-
        CLI\ttag --tagSetActive\tWRITE\tE\tUS4\treachable:manage_tags/setActiveTag\tplanned\t-
        CLI\ttag --tagRemoveActive\tWRITE\tE\tUS4\treachable:manage_tags/removeActiveTag\tplanned\t-
        """;

    private static WritePolicy policy(NodeId node, Confirmation confirmation) {
        return new WritePolicy(node, false, true, false, confirmation, Duration.ofMinutes(5),
            Optional.of("svc-inubit"));
    }

    private final Map<NodeId, WritePolicy> policies = Map.of(
        DEV, policy(DEV, Confirmation.SERVER),
        TEST_CLIENT, policy(TEST_CLIENT, Confirmation.CLIENT));

    private final FakeTagAdministration server = new FakeTagAdministration();
    private final RecordingAuditPort records = new RecordingAuditPort();
    private final AtomicLong ids = new AtomicLong();
    private final WriteChallengeRegistry registry =
        new WriteChallengeRegistry(Clock.fixed(NOW, ZoneOffset.UTC));

    private TagAdministrationService service;

    private TagAdministrationService service() {
        if (service == null) {
            service = service(InterfaceCoverage.load(new StringReader(LIST)));
        }
        return service;
    }

    private TagAdministrationService service(InterfaceCoverage coverage) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions),
            new CapabilityMatrix(List.of(),
                Map.of(Capability.TAG_ADMINISTRATION, SupportState.SUPPORTED)),
            coverage);
        ChangeAudit audit = new ChangeAudit(records, clock, "acme",
            () -> new UUID(0, ids.incrementAndGet()));
        ChangeGuard guard = new ChangeGuard(new TargetResolver(List.copyOf(policies.keySet())),
            policies::get, (node, area) -> area == ActionSpec.Area.OPERATION, server, gate,
            audit);
        return new TagAdministrationService(guard, new ChangeLocks(),
            new ChangeChallenges(registry), audit, server);
    }

    private Response call(NodeId node, String action, Map<String, Object> fields,
        Optional<String> code) {
        return service().execute(new Request(node.value(), action, fields, REASON, code,
            Optional.of("client/1.0")));
    }

    private ChangePreview preview(String action, Map<String, Object> fields) {
        Response response = call(DEV, action, fields, Optional.empty());
        assertThat(response).isInstanceOf(Response.Preview.class);
        return ((Response.Preview) response).preview();
    }

    private ChangeResult confirm(String action, Map<String, Object> fields,
        ChangePreview preview) {
        Response response = call(DEV, action, fields,
            Optional.of(preview.confirmation().orElseThrow().code()));
        assertThat(response).isInstanceOf(Response.Completed.class);
        return ((Response.Completed) response).result();
    }

    private ToolError refusal(NodeId node, String action, Map<String, Object> fields) {
        return catchThrowableOfType(ToolErrorException.class,
            () -> call(node, action, fields, Optional.empty())).error();
    }

    private List<AuditOutcome> outcomes() {
        return records.records.stream().map(AuditRecord::outcome).toList();
    }

    // --- AS12: the active tag of a user -------------------------------------------------------

    @Test
    void as12ThePreviewNamesTheUserTheCurrentAndTheNewActiveTagAndTheResultReReadsIt() {
        Map<String, Object> fields = Map.of("user", "jdoe", "tag", "REL-2");

        ChangePreview preview = preview("setActiveTag", fields);

        assertThat(preview.tool()).isEqualTo("manage_tags");
        assertThat(preview.area()).isEqualTo("operation");
        assertThat(preview.target()).isEqualTo("active tag of the user jdoe");
        assertThat(preview.before()).isEqualTo("active tag REL-1");
        assertThat(preview.requested()).isEqualTo("active tag REL-2");
        assertThat(preview.affected()).isEmpty();
        assertThat(preview.effects()).containsExactly(
            "The active tag of jdoe stays REL-2 until it is set again or removed.");
        assertThat(preview.backup()).isEqualTo("none: an active tag is a setting of the user,"
            + " not an object INUBIT exports; the preview shows the current one");
        assertThat(preview.confirmation()).isPresent();
        assertThat(server.changes()).isEmpty();

        server.calls.clear();
        ChangeResult result = confirm("setActiveTag", fields, preview);

        assertThat(server.calls).containsExactly("check dev/node1", "user jdoe",
            "tag --tagSetActive REL-2 --tagUser jdoe", "user jdoe");
        assertThat(server.permits).singleElement().satisfies(permit -> {
            assertThat(permit.node()).isEqualTo(DEV);
            assertThat(permit.capability()).isEqualTo(Capability.TAG_ADMINISTRATION);
        });
        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(result.target()).isEqualTo("active tag of the user jdoe");
        assertThat(result.before()).isEqualTo("active tag REL-1");
        assertThat(result.after()).contains("active tag REL-2");
        assertThat(result.inubitAnswer())
            .isEqualTo("tag --tagSetActive was sent; StartCLI reported no error");
        assertThat(result.backup()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void removingTheActiveTagShowsTheCurrentOneAndReReadsNone() {
        Map<String, Object> fields = Map.of("user", "jdoe");

        ChangePreview preview = preview("removeActiveTag", fields);

        assertThat(preview.before()).isEqualTo("active tag REL-1");
        assertThat(preview.requested()).isEqualTo("no active tag");
        assertThat(preview.effects())
            .containsExactly("jdoe has no active tag until one is set again.");
        ChangeResult result = confirm("removeActiveTag", fields, preview);
        assertThat(server.changes()).containsExactly("tag --tagRemoveActive --tagUser jdoe");
        assertThat(result.after()).contains("no active tag");
    }

    @Test
    void anUnknownUserIsRefusedWithNotFoundAndNothingIsSent() {
        ToolError error = refusal(DEV, "setActiveTag", Map.of("user", "nobody", "tag", "REL-2"));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(server.changes()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void anActiveTagChangedSinceThePreviewIsAConflictAndNothingIsSent() {
        Map<String, Object> fields = Map.of("user", "jdoe", "tag", "REL-2");
        ChangePreview preview = preview("setActiveTag", fields);
        server.activeTags.put("jdoe", "REL-3");

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> confirm("setActiveTag", fields, preview));

        assertThat(exception).isInstanceOfSatisfying(ChangeChallenges.Conflict.class,
            conflict -> {
                assertThat(conflict.error().code()).isEqualTo(ErrorCode.CONFLICT);
                assertThat(conflict.preview().before()).isEqualTo("active tag REL-3");
                assertThat(conflict.details()).contains(Map.of("preview", conflict.preview()));
            });
        assertThat(server.changes()).isEmpty();
    }

    // --- AS13, AS14: tag removal and deletion are unknown actions ------------------------------

    @Test
    void as13As14RemoveTagAndDeleteTagAreUnknownActionsRefusedBeforeAnythingIsRead() {
        List<Map<String, Object>> inputs = List.of(Map.of("tag", "REL-1"),
            Map.of("tag", "REL-1", "scope", Map.of("diagramGroup", "Orders", "diagramType",
                "technical")),
            Map.of("tag", "REL-1", "scope", Map.of("repositoryPath", "/Root/acme/mappings")));
        for (String action : List.of("removeTag", "deleteTag")) {
            for (Map<String, Object> fields : inputs) {
                ToolError error = refusal(DEV, action, fields);

                assertThat(error.code()).as(action + " " + fields)
                    .isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(error.message()).as(action + " " + fields).isEqualTo("The action is"
                    + " not one of setActiveTag, removeActiveTag; nothing was read or changed");
            }
        }
        assertThat(server.calls).as("not even the admission's StartCLI check").isEmpty();
        assertThat(outcomes()).hasSize(6).containsOnly(AuditOutcome.REFUSED);
    }

    @Test
    void eachActionTakesOnlyItsOwnFields() {
        assertThat(refusal(DEV, "setActiveTag", Map.of("user", "jdoe")).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refusal(DEV, "removeActiveTag", Map.of("user", "jdoe", "tag", "REL-1"))
            .code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refusal(DEV, "setActiveTag", Map.of("user", "jdoe", "tag", "REL-1", "scope",
            Map.of("repositoryPath", "/Root/acme"))).code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refusal(DEV, "renameTag", Map.of("user", "jdoe")).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refusal(DEV, "setActiveTag", Map.of("user", "-x", "tag", "REL-2")).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(server.calls).isEmpty();
    }

    // --- the change and its re-read ---------------------------------------------------------

    @Test
    void aFailedChangeIsAFailedResultWithTheActiveTagReadAgain() {
        server.changeFailure = new ToolErrorException(ToolError.of(
            ErrorCode.UNEXPECTED_RESPONSE, "StartCLI answered n-NOK", "cause", "next")
            .withNode(DEV));
        Map<String, Object> fields = Map.of("user", "jdoe", "tag", "REL-2");

        ChangeResult result = confirm("setActiveTag", fields, preview("setActiveTag", fields));

        assertThat(result.outcome()).isEqualTo(ChangeOutcome.FAILED);
        assertThat(result.inubitAnswer())
            .isEqualTo("UNEXPECTED_RESPONSE: StartCLI answered n-NOK");
        assertThat(result.after()).contains("active tag REL-1");
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void whenThePendingRecordCannotBeWrittenNothingIsSent() {
        Map<String, Object> fields = Map.of("user", "jdoe");
        ChangePreview preview = preview("removeActiveTag", fields);
        records.failing.add(AuditOutcome.PENDING);

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> confirm("removeActiveTag", fields, preview));

        assertThat(exception.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(exception.error().message())
            .isEqualTo("The audit record could not be written; nothing was sent to INUBIT");
        assertThat(server.changes()).isEmpty();
        assertThat(server.permits).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED);
    }

    // --- CLIENT confirmation and the lock key (D-17) ------------------------------------------

    @Test
    void underClientConfirmationTheFirstCallExecutes() {
        Response response = call(TEST_CLIENT, "removeActiveTag", Map.of("user", "jdoe"),
            Optional.empty());

        assertThat(response).isInstanceOfSatisfying(Response.Completed.class,
            completed -> assertThat(completed.result().outcome())
                .isEqualTo(ChangeOutcome.EXECUTED));
        assertThat(server.changes()).containsExactly("tag --tagRemoveActive --tagUser jdoe");
    }

    @Test
    void aSecondCallOnTheActiveTagOfTheSameUserIsPreconditionFailed() {
        AtomicReference<ToolError> sameUser = new AtomicReference<>();
        AtomicReference<Response> otherUser = new AtomicReference<>();
        server.duringRead = () -> {
            server.duringRead = () -> { };
            sameUser.set(catchThrowableOfType(ToolErrorException.class, () -> call(TEST_CLIENT,
                "removeActiveTag", Map.of("user", "jdoe"), Optional.empty())).error());
            otherUser.set(call(TEST_CLIENT, "setActiveTag", Map.of("user", "asmith", "tag",
                "REL-2"), Optional.empty()));
        };

        call(TEST_CLIENT, "setActiveTag", Map.of("user", "jdoe", "tag", "REL-2"),
            Optional.empty());

        assertThat(sameUser.get().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(sameUser.get().message())
            .startsWith("Another change of activeTag:jdoe (OPERATION) on test/node2");
        assertThat(otherUser.get()).isInstanceOf(Response.Completed.class);
        assertThat(server.changes()).containsExactly(
            "tag --tagSetActive REL-2 --tagUser asmith",
            "tag --tagSetActive REL-2 --tagUser jdoe");
    }

    // --- the production coverage list --------------------------------------------------------

    @Test
    void underTheProductionCoverageAnActiveTagChangeIsRefusedAsNotYetVerified() {
        service = service(InterfaceCoverage.production());

        ToolError error = refusal(DEV, "setActiveTag", Map.of("user", "jdoe", "tag", "REL-2"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).contains("not yet verified");
        assertThat(server.calls).isEmpty();
    }

    // --- leak check (SC-005) ------------------------------------------------------------------

    @Test
    void neitherPreviewNorResultNorAuditRepeatASecretOfINUBITsAnswers() {
        server.changeFailure = new ToolErrorException(ToolError.of(
            ErrorCode.UNEXPECTED_RESPONSE, "StartCLI said " + TestSecrets.FREE_TEXT_SECRETS,
            "cause", "next").withNode(DEV));
        Map<String, Object> fields = Map.of("user", "jdoe", "tag", "REL-2");

        ChangePreview preview = preview("setActiveTag", fields);
        ChangeResult result = confirm("setActiveTag", fields, preview);

        assertThat(result.outcome()).isEqualTo(ChangeOutcome.FAILED);
        TestSecrets.assertNoLeak(preview.toString());
        TestSecrets.assertNoLeak(result.toString());
        TestSecrets.assertNoLeak(records.records.toString());
    }
}
