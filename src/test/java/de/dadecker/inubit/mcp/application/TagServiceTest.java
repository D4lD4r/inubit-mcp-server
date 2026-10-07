package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.OwnerKind;
import de.dadecker.inubit.mcp.domain.model.TagOutcome;
import de.dadecker.inubit.mcp.domain.model.TagPreview;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T021 (feature 004, US4, FR-021, SC-005, research D-16, D-25 M1, M2): {@code tag_artifacts}
 * against a fake tag port — blank, empty and wildcard-like diagram groups and existing tags
 * are refused before any tag command; one tag command per diagram group; verification by
 * history export; a tag that reached anything else (or a failing tag command) is removed again
 * and reported as {@code FAILED}; user-group owners are refused; every call is audited.
 */
class TagServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId TEST = NodeId.parse("test/node1");

    @TempDir
    Path root;

    private final FakeTagPort port = new FakeTagPort();
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
    private final Map<String, OwnerKind> owners = new HashMap<>();
    private WritePolicy.Confirmation confirmation = WritePolicy.Confirmation.CLIENT;

    @BeforeEach
    void setUp() {
        port.diagram("W-1", "GRP-01", "technical", 3, "M-1", "M-2")
            .diagram("W-2", "GRP-01", "technical", 1, "M-2")
            .diagram("W-3", "GRP-02", "technical", 2, "M-3")
            .diagram("W-4", "GRP-03", "technical", 2, "M-4")
            .diagram("B-1", "GRP-01", "bpd", 1);
    }

    private TagService service() {
        DevelopmentPolicy dev = new DevelopmentPolicy(DEV, false, true, confirmation,
            Duration.ofMinutes(5), E2ePolicy.FORBIDDEN, Optional.empty());
        DevelopmentPolicy test = new DevelopmentPolicy(TEST, false, false,
            WritePolicy.Confirmation.SERVER, Duration.ofMinutes(5), E2ePolicy.FORBIDDEN,
            Optional.empty());
        Map<NodeId, DevelopmentPolicy> policies = Map.of(DEV, dev, TEST, test);
        return new TagService(new TagService.Dependencies(root, "acme",
            new DevelopmentGuard(new TargetResolver(List.of(DEV, TEST)), policies::get,
                node -> port.checkAvailable()),
            node -> port, new OwnerKindResolver(owners, node -> () -> Set.of("jdoe")),
            node -> Optional.of("jdoe"),
            node -> new ImportService.Account("jdoe", "inubit-dev-1.example.test"),
            new WriteChallengeRegistry(clock), audit::add, clock, UUID::randomUUID));
    }

    private static TagService.TagRequest request(List<String> groups) {
        return new TagService.TagRequest("dev/node1", Optional.empty(), groups, "REL-1",
            "Tested state", Optional.empty(), Optional.empty());
    }

    private static TagOutcome completed(TagService.Response response) {
        assertThat(response).isInstanceOf(TagService.Response.Completed.class);
        return ((TagService.Response.Completed) response).outcome();
    }

    private ToolError refusal(TagService service, TagService.TagRequest request) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.tag(request));
        assertThat(exception).as("expected a refusal").isNotNull();
        assertThat(port.calls).noneMatch(call -> call.startsWith("tag ")
            || call.startsWith("delete "));
        assertThat(audit).last().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        return exception.error();
    }

    @Test
    void eachDiagramGroupIsTaggedOnceAndTheResultVerified() {
        TagOutcome outcome = completed(service().tag(request(List.of("GRP-01", "GRP-02"))));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.workflows()).isEqualTo(3);
        assertThat(outcome.modules()).isEqualTo(3);
        assertThat(outcome.removedAgain()).isFalse();
        assertThat(outcome.diagramGroups()).containsExactly("GRP-01", "GRP-02");
        assertThat(port.calls).containsExactly("history", "tag REL-1 GRP-01",
            "tag REL-1 GRP-02", "history GRP-01", "history GRP-02", "history");
        assertThat(port.carrying("REL-1")).containsExactlyInAnyOrder("W-1", "W-2", "W-3", "M-1",
            "M-2", "M-3");
        assertThat(audit).extracting(AuditRecord::capability).containsOnly("tag_artifacts");
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.EXECUTED);
        assertThat(audit.get(1).inputs()).containsEntry("tag", "REL-1")
            .containsEntry("scope", "diagram groups GRP-01, GRP-02")
            .containsEntry("reason", "Tested state").containsEntry("owner", "jdoe")
            .containsEntry("ownerKind", "USER");
    }

    @Test
    void blankEmptyAndWildcardLikeGroupsAreRefusedBeforeAnythingIsRead() {
        // SC-005: StartCLI would tag every diagram of the owner
        TagService service = service();
        List<List<String>> inputs = new ArrayList<>(List.of(List.of(), List.of(""),
            List.of(" "), List.of("*"), List.of("GRP-%"), List.of(".*"), List.of("GRP-01", ""),
            List.of("GRP-01", "GRP-01")));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add("GRP-" + i);
        }
        inputs.add(many);
        for (List<String> groups : inputs) {
            ToolError error = refusal(service, request(groups));
            assertThat(error.code()).as(groups.toString()).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        assertThat(port.calls).isEmpty();
    }

    @Test
    void aTagThatExistsAnywhereForTheOwnerIsNeverMoved() {
        port.tagged("M-4", 1, "REL-1");

        ToolError error = refusal(service(), request(List.of("GRP-01")));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("REL-1", "exists");
        assertThat(port.calls).containsExactly("history");
    }

    @Test
    void aDiagramGroupWithoutTechnicalWorkflowsIsNotFound() {
        ToolError error = refusal(service(), request(List.of("GRP-09")));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.message()).contains("GRP-09");
    }

    @Test
    void aTagThatReachedOtherArtifactsIsRemovedAgainAndReported() {
        // SC-005 post-check
        port.tagEverything = true;

        TagOutcome outcome = completed(service().tag(request(List.of("GRP-01"))));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
            assertThat(failure.step()).isEqualTo("verify");
            assertThat(failure.message()).contains("W-4");
        });
        assertThat(outcome.removedAgain()).isTrue();
        assertThat(port.calls).last().isEqualTo("delete REL-1");
        assertThat(port.carrying("REL-1")).isEmpty();
        assertThat(outcome.reports()).singleElement().satisfies(report ->
            assertThat(root.resolve(report)).isRegularFile());
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.FAILED);
        assertThat(audit.get(1).inputs()).containsEntry("removedAgain", "true");
    }

    @Test
    void aFailingTagCommandRemovesTheTagAgain() {
        port.failOn = "GRP-02";

        TagOutcome outcome = completed(service().tag(request(List.of("GRP-01", "GRP-02"))));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.step()).isEqualTo("tag");
        });
        assertThat(outcome.removedAgain()).isTrue();
        assertThat(port.carrying("REL-1")).isEmpty();
    }

    @Test
    void aUserGroupOwnerIsRefusedAsNotYetVerified() {
        owners.put("jdoe", OwnerKind.USER_GROUP);

        ToolError error = refusal(service(), request(List.of("GRP-01")));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("not yet verified");
        assertThat(port.calls).isEmpty();
    }

    @Test
    void aNodeThatIsNoDevelopmentStageIsRefused() {
        ToolError error = refusal(service(), new TagService.TagRequest("test/node1",
            Optional.empty(), List.of("GRP-01"), "REL-1", "x", Optional.empty(),
            Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_DEVELOPMENT);
    }

    @Test
    void underServerConfirmationTheTagIsPreviewedAndAChangeSinceIsAConflict() {
        confirmation = WritePolicy.Confirmation.SERVER;
        TagService service = service();

        TagPreview preview = ((TagService.Response.Challenge) service.tag(request(List.of(
            "GRP-01")))).preview();

        assertThat(preview.workflows()).isEqualTo(2);
        assertThat(preview.tag()).isEqualTo("REL-1");
        assertThat(port.calls).containsExactly("history");
        TagOutcome outcome = completed(service.tag(new TagService.TagRequest("dev/node1",
            Optional.empty(), List.of("GRP-01"), "REL-1", "Tested state",
            Optional.of(preview.confirmationCode()), Optional.empty())));
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);

        TagPreview second = ((TagService.Response.Challenge) service.tag(new TagService
            .TagRequest("dev/node1", Optional.empty(), List.of("GRP-02"), "REL-2", "Again",
            Optional.empty(), Optional.empty()))).preview();
        port.publish("W-3");
        port.calls.clear();
        ToolError error = refusal(service, new TagService.TagRequest("dev/node1",
            Optional.empty(), List.of("GRP-02"), "REL-2", "Again",
            Optional.of(second.confirmationCode()), Optional.empty()));
        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
    }
}
