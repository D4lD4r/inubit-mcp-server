package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T021, T029 (feature 004, US4, FR-021, SC-005, research D-16, D-26): {@code tag_artifacts}
 * against a fake tag port — blank, empty and wildcard-like diagram groups are refused before
 * any tag command; only the requested diagram groups are exported (never owner-wide); one tag
 * command per diagram group; an existing tag name is reused (it moves to the current versions
 * within the group, other groups keep it); verification by the groups' history exports; a
 * current version without the tag or a failing tag command is {@code FAILED} and nothing is
 * removed; every call is audited.
 */
class TagServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId TEST = NodeId.parse("test/node1");

    @TempDir
    Path root;

    private final FakeTagPort port = new FakeTagPort();
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
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
            node -> port, node -> Optional.of("jdoe"),
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

    /** The outcome of a call that must not be refused (fails on an assertion otherwise). */
    private static TagOutcome run(TagService service, TagService.TagRequest request) {
        java.util.concurrent.atomic.AtomicReference<TagService.Response> response =
            new java.util.concurrent.atomic.AtomicReference<>();
        assertThat(catchThrowable(() -> response.set(service.tag(request))))
            .as("the call is not refused").isNull();
        return completed(response.get());
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

    /** Research D-26: only the requested groups are exported, and nothing is ever removed. */
    private void assertGroupScopedOnly() {
        assertThat(port.calls).as("no owner-wide history export").doesNotContain("history");
        assertThat(port.calls).as("no tag removal").noneMatch(call -> call.startsWith("delete"));
    }

    @Test
    void eachDiagramGroupIsTaggedOnceAndTheResultVerified() {
        TagOutcome outcome = completed(service().tag(request(List.of("GRP-01", "GRP-02"))));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.workflows()).isEqualTo(3);
        assertThat(outcome.modules()).isEqualTo(3);
        assertThat(outcome.diagramGroups()).containsExactly("GRP-01", "GRP-02");
        assertThat(port.calls).containsExactly("history GRP-01", "history GRP-02",
            "tag REL-1 GRP-01", "tag REL-1 GRP-02", "history GRP-01", "history GRP-02");
        assertThat(port.carrying("REL-1")).containsExactlyInAnyOrder("W-1", "W-2", "W-3", "M-1",
            "M-2", "M-3");
        assertThat(audit).extracting(AuditRecord::capability).containsOnly("tag_artifacts");
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.EXECUTED);
        assertThat(audit.get(1).inputs()).containsEntry("tag", "REL-1")
            .containsEntry("scope", "diagram groups GRP-01, GRP-02")
            .containsEntry("reason", "Tested state").containsEntry("owner", "jdoe")
            .doesNotContainKey("ownerKind").doesNotContainKey("removedAgain");
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
    void anExistingTagNameIsReusedAndMovesToTheCurrentVersionsOfTheGroup() {
        // research D-26: re-tagging a group moves the tag from the old to the head version
        port.tagged("W-1", 1, "REL-1").tagged("M-1", 1, "REL-1");

        TagOutcome outcome = run(service(), request(List.of("GRP-01")));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(port.carrying("REL-1")).containsExactlyInAnyOrder("W-1", "W-2", "M-1",
            "M-2");
        assertGroupScopedOnly();
    }

    @Test
    void theSameTagInAnotherDiagramGroupStaysUntouched() {
        // research D-26 (probed): --tagMove on one group leaves the tag in other groups alone
        port.tagged("W-3", 0, "REL-1").tagged("M-3", 0, "REL-1").tagged("W-4", 1, "REL-1");

        TagOutcome outcome = run(service(), request(List.of("GRP-01")));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.workflows()).isEqualTo(2);
        assertThat(port.carrying("REL-1")).containsExactlyInAnyOrder("W-1", "W-2", "M-1", "M-2",
            "W-3", "M-3", "W-4");
        assertThat(port.calls).containsExactly("history GRP-01", "tag REL-1 GRP-01",
            "history GRP-01");
        assertGroupScopedOnly();
    }

    @Test
    void aDiagramGroupWithoutTechnicalWorkflowsIsNotFound() {
        ToolError error = refusal(service(), request(List.of("GRP-09")));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.message()).contains("GRP-09");
        assertThat(port.calls).containsExactly("history GRP-09");
    }

    @Test
    void aCurrentVersionWithoutTheTagIsAVerifyMismatchAndNothingIsRemoved() {
        port.missed.add("M-2");

        TagOutcome outcome = completed(service().tag(request(List.of("GRP-01"))));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
            assertThat(failure.step()).isEqualTo("verify");
            assertThat(failure.message()).contains("M-2");
        });
        assertThat(port.carrying("REL-1")).as("nothing is removed")
            .containsExactlyInAnyOrder("W-1", "W-2", "M-1");
        assertThat(outcome.reports()).singleElement().satisfies(report ->
            assertThat(root.resolve(report)).isRegularFile());
        assertThat(outcome.warnings()).anyMatch(warning -> warning.contains("tag_artifacts"));
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.FAILED);
        assertThat(audit.get(1).inputs()).doesNotContainKey("removedAgain");
        assertGroupScopedOnly();
    }

    @Test
    void aFailingTagCommandIsReportedAndNothingIsRemoved() {
        port.failOn = "GRP-02";

        TagOutcome outcome = completed(service().tag(request(List.of("GRP-01", "GRP-02"))));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.step()).isEqualTo("tag");
        });
        assertThat(port.carrying("REL-1")).as("GRP-01 keeps the tag")
            .containsExactlyInAnyOrder("W-1", "W-2", "M-1", "M-2");
        assertThat(outcome.warnings()).anyMatch(warning -> warning.contains("GRP-01")
            && warning.contains("tag_artifacts"));
        assertGroupScopedOnly();
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
        assertThat(port.calls).containsExactly("history GRP-01");
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
