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
import de.dadecker.inubit.mcp.domain.model.TaggedVersion;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.RecordingAuditPort;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 022 (T064, US4 AS12–AS14, FR-018, FR-019a, research D-15, D-17, D-18):
 * {@code manage_tags} – the active tag of a user with its preview and re-read, the scope a removal
 * needs before anything is read, the tag list backup before a removal (nothing sent when it
 * fails), the re-read inside and outside the scope, the refused repository path scope, the lock
 * keys and the state changed since the preview. The coverage rows of the tag forms are
 * {@code reachable} only in this test's coverage list; the production list keeps them
 * {@code planned} until the live check T055.
 */
class TagAdministrationServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId TEST_CLIENT = NodeId.parse("test/node2");
    private static final NodeId NO_OWNER = NodeId.parse("dev/node3");
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final String REASON = "clean up the release tags";
    private static final Map<String, Object> GROUP = Map.of("diagramGroup", "Orders",
        "diagramType", "technical");
    private static final String GROUP_OPTIONS = "--tagWorkflowGroup Orders --tagWorkflowType"
        + " technical";
    /** A deletion in {@link #GROUP} sends its owner, inventory.owner (8.1.17, T055 run 2). */
    private static final String OWNER_OPTION = " --tagUser acme-dev";
    private static final List<TaggedVersion> TAGGED = List.of(
        new TaggedVersion(TaggedVersion.Kind.WORKFLOW, "acme-orders", 3),
        new TaggedVersion(TaggedVersion.Kind.WORKFLOW, "acme-orders", 4),
        new TaggedVersion(TaggedVersion.Kind.MODULE, "acme-mapping", 2));
    private static final PrivateFileSystem FILES = PrivateFiles.forHost();

    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        CLI\ttag --tagUser\tWRITE\tE\t-\treachable:tag_artifacts/default\treachable:tag_artifacts/default\t-
        CLI\ttag --tagWorkflowGroup\tWRITE\tE\t-\treachable:tag_artifacts/default\treachable:tag_artifacts/default\t-
        CLI\ttag --tagWorkflowType\tWRITE\tE\t-\treachable:tag_artifacts/default\treachable:tag_artifacts/default\t-
        CLI\ttag --tagSetActive\tWRITE\tE\tUS4\treachable:manage_tags/setActiveTag\tplanned\t-
        CLI\ttag --tagRemoveActive\tWRITE\tE\tUS4\treachable:manage_tags/removeActiveTag\tplanned\t-
        CLI\ttag --tagRemove\tWRITE\tE\tUS4\treachable:manage_tags/removeTag\tplanned\t-
        CLI\ttag --tagDelete\tWRITE\tE\tUS4\treachable:manage_tags/deleteTag\tplanned\t-
        CLI\ttag --tagRepositoryPath\tWRITE\tE\tUS4\treachable:manage_tags/removeTag\tplanned\t-
        """;

    private static WritePolicy policy(NodeId node, Confirmation confirmation) {
        return new WritePolicy(node, false, true, false, confirmation, Duration.ofMinutes(5),
            Optional.of("svc-inubit"));
    }

    private final Map<NodeId, WritePolicy> policies = Map.of(
        DEV, policy(DEV, Confirmation.SERVER),
        TEST_CLIENT, policy(TEST_CLIENT, Confirmation.CLIENT),
        NO_OWNER, policy(NO_OWNER, Confirmation.SERVER));

    private final FakeTagAdministration server = new FakeTagAdministration();
    private final RecordingAuditPort records = new RecordingAuditPort();
    private final AtomicLong ids = new AtomicLong();
    private final WriteChallengeRegistry registry =
        new WriteChallengeRegistry(Clock.fixed(NOW, ZoneOffset.UTC));

    @TempDir
    Path home;

    private TagAdministrationService service;

    private Path backups() {
        return home.resolve("backups");
    }

    private TagAdministrationService service() {
        if (service == null) {
            service = service(FILES);
        }
        return service;
    }

    private TagAdministrationService service(PrivateFileSystem files) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions),
            new CapabilityMatrix(List.of(),
                Map.of(Capability.TAG_ADMINISTRATION, SupportState.SUPPORTED)),
            InterfaceCoverage.load(new StringReader(LIST)));
        ChangeAudit audit = new ChangeAudit(records, clock, "acme",
            () -> new UUID(0, ids.incrementAndGet()));
        ChangeGuard guard = new ChangeGuard(new TargetResolver(List.copyOf(policies.keySet())),
            policies::get, (node, area) -> area == ActionSpec.Area.OPERATION, server, gate,
            audit);
        return new TagAdministrationService(guard, new ChangeLocks(),
            new ChangeChallenges(registry), audit, server,
            new BackupStore(backups(), clock, files),
            node -> node.equals(NO_OWNER) ? Optional.empty() : Optional.of("acme-dev"));
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

    private static Map<String, Object> removal(Map<String, Object> scope) {
        return Map.of("tag", "REL-1", "scope", scope);
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

    // --- AS13: no scope, no read --------------------------------------------------------------

    @Test
    void as13ARemovalWithoutAScopeIsInvalidInputBeforeAnythingIsRead() {
        for (String action : List.of("removeTag", "deleteTag")) {
            ToolError error = refusal(DEV, action, Map.of("tag", "REL-1"));

            assertThat(error.code()).as(action).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(error.message()).as(action).isEqualTo(action + " needs a scope: a"
                + " diagramGroup with its diagramType, or a repositoryPath; without one StartCLI"
                + " acts on every diagram group of the owner. Nothing was read or changed");
        }
        assertThat(server.calls).as("not even the admission's StartCLI check").isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED, AuditOutcome.REFUSED);
    }

    @Test
    void aScopeThatNamesNeitherOrBothFormsIsInvalidInputBeforeAnythingIsRead() {
        List<Object> scopes = List.of(Map.of(), Map.of("diagramGroup", "Orders"),
            Map.of("diagramType", "technical"), Map.of("diagramGroup", "Orders",
                "diagramType", "technical", "repositoryPath", "/Root/acme"),
            Map.of("repositoryPath", "acme"), Map.of("diagramGroup", "", "diagramType",
                "technical"), "Orders");
        for (Object scope : scopes) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("tag", "REL-1");
            fields.put("scope", scope);

            assertThat(refusal(DEV, "removeTag", fields).code()).as(String.valueOf(scope))
                .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        assertThat(server.calls).isEmpty();
    }

    @Test
    void as13ARemoveTagInADiagramGroupIsInvalidInputBeforeAnythingIsRead() {
        ToolError error = refusal(DEV, "removeTag", removal(GROUP));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).isEqualTo("removeTag takes only a repositoryPath: INUBIT"
            + " 8.1.17 refuses --tagRemove in a diagram group ('tagRepositoryPath is needed');"
            + " use deleteTag for a diagram group. Nothing was read or changed");
        assertThat(server.calls).as("not even the admission's StartCLI check").isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void eachActionTakesOnlyItsOwnFields() {
        assertThat(refusal(DEV, "setActiveTag", Map.of("user", "jdoe")).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refusal(DEV, "removeActiveTag", Map.of("user", "jdoe", "tag", "REL-1"))
            .code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refusal(DEV, "removeTag", Map.of("tag", "REL-1", "scope", GROUP, "user",
            "jdoe")).code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refusal(DEV, "renameTag", removal(GROUP)).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refusal(DEV, "setActiveTag", Map.of("user", "-x", "tag", "REL-2")).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(server.calls).isEmpty();
    }

    // --- AS14: removal in one diagram group ---------------------------------------------------

    @Test
    void as14ThePreviewNamesEveryTaggedVersionInTheGroupAndTheCountOutsideIt() {
        server.inScope = TAGGED;
        server.outsideScopeCount = 2;

        ChangePreview preview = preview("deleteTag", removal(GROUP));

        assertThat(server.calls).containsExactly("check dev/node1",
            "tagged REL-1 of acme-dev " + GROUP_OPTIONS);
        assertThat(preview.target())
            .isEqualTo("tag REL-1 in the diagram group Orders (technical) of acme-dev");
        assertThat(preview.before()).isEqualTo("3 versions carry the tag in the diagram group;"
            + " 2 workflows and modules outside it carry it too");
        assertThat(preview.requested()).isEqualTo("delete the tag in the diagram group (tag"
            + " --tagDelete)");
        assertThat(preview.affected()).contains(new ChangePreview.Affected(3, List.of(
            "workflow acme-orders version 3", "workflow acme-orders version 4",
            "module acme-mapping version 2"), false));
        assertThat(preview.effects()).containsExactly(
            "StartCLI's help says that --tagDelete removes the tag from diagrams, modules and the"
                + " repository.",
            "The tag is meant to stay on the workflows and modules outside the diagram group;"
                + " the result reads both again.",
            "A diagram group references repository files that other diagram groups may"
                + " reference too; the removal may take the tag from them as well.");
        assertThat(preview.backup()).isEqualTo("the list of the 3 versions carrying the tag in"
            + " the diagram group, stored as a tag list backup before anything is sent; nothing"
            + " is sent if it cannot be stored");
        assertThat(server.changes()).isEmpty();
        assertThat(Files.exists(backups())).as("no backup for a preview").isFalse();
    }

    @Test
    void as14TheBackupHoldsTheListBeforeSendingAndTheResultReReadsInsideAndOutside()
        throws IOException {
        server.inScope = TAGGED;
        server.outsideScopeCount = 2;
        ChangePreview preview = preview("deleteTag", removal(GROUP));
        server.calls.clear();

        ChangeResult result = confirm("deleteTag", removal(GROUP), preview);

        assertThat(server.calls).containsExactly("check dev/node1",
            "tagged REL-1 of acme-dev " + GROUP_OPTIONS,
            "tag --tagDelete REL-1 " + GROUP_OPTIONS + OWNER_OPTION,
            "tagged REL-1 of acme-dev " + GROUP_OPTIONS);
        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(result.before()).isEqualTo(preview.before());
        assertThat(result.after()).contains("0 versions carry the tag in the diagram group;"
            + " 2 workflows and modules outside it carry it too");
        assertThat(result.inubitAnswer())
            .isEqualTo("tag --tagDelete was sent; StartCLI reported no error");
        String auditId = result.auditId().toString();
        assertThat(result.backup()).contains(new ChangeResult.Backup(
            backups().resolve(auditId + ".json").toString(), auditId));

        BackupStore.Manifest manifest = new BackupStore(backups(), Clock.systemUTC(), FILES)
            .find(auditId).orElseThrow();
        assertThat(manifest.objectKind()).contains(BackupStore.Manifest.ObjectKind.TAG_LIST);
        assertThat(manifest.scope()).isEqualTo("REL-1@technical/Orders");
        assertThat(manifest.outcome()).isEqualTo("EXECUTED");
        assertThat(Files.readString(backups().resolve(auditId + "-1.json"))).isEqualTo("""
            {
              "versions": [
                {"kind": "WORKFLOW", "name": "acme-orders", "version": 3},
                {"kind": "WORKFLOW", "name": "acme-orders", "version": 4},
                {"kind": "MODULE", "name": "acme-mapping", "version": 2}
              ]
            }
            """);
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void whenTheBackupCannotBeWrittenNothingIsSent() {
        service = service(PrivateFiles.failingFiles());
        server.inScope = TAGGED;
        ChangePreview preview = preview("deleteTag", removal(GROUP));

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> confirm("deleteTag", removal(GROUP), preview));

        assertThat(exception.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(exception.error().message()).isEqualTo("The tag list backup for deleteTag on"
            + " dev/node1 could not be stored; nothing was sent");
        assertThat(server.changes()).isEmpty();
        assertThat(server.permits).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.REFUSED);
    }

    @Test
    void whenThePendingRecordCannotBeWrittenNothingIsSentAndTheBackupSaysNotStarted()
        throws IOException {
        server.inScope = TAGGED;
        ChangePreview preview = preview("deleteTag", removal(GROUP));
        records.failing.add(AuditOutcome.PENDING);

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> confirm("deleteTag", removal(GROUP), preview));

        assertThat(exception.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(exception.error().message())
            .isEqualTo("The audit record could not be written; nothing was sent to INUBIT");
        assertThat(server.changes()).isEmpty();
        assertThat(server.permits).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED);
        List<String> manifests;
        try (Stream<Path> files = Files.list(backups())) {
            manifests = files.map(file -> file.getFileName().toString())
                .filter(name -> name.matches("[0-9a-f-]{36}\\.json"))
                .map(name -> name.substring(0, 36)).toList();
        }
        assertThat(manifests).hasSize(1);
        assertThat(new BackupStore(backups(), Clock.systemUTC(), FILES).find(manifests.get(0))
            .orElseThrow().outcome()).isEqualTo("NOT_STARTED");
    }

    @Test
    void aTaggedListChangedSinceThePreviewIsAConflictAndNothingIsSentOrBackedUp() {
        server.inScope = TAGGED;
        server.outsideScopeCount = 2;
        ChangePreview preview = preview("deleteTag", removal(GROUP));
        server.outsideScopeCount = 1;

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> confirm("deleteTag", removal(GROUP), preview));

        assertThat(exception).isInstanceOf(ChangeChallenges.Conflict.class);
        assertThat(((ChangeChallenges.Conflict) exception).preview().before()).isEqualTo("3"
            + " versions carry the tag in the diagram group; 1 workflow or module outside it"
            + " carries it too");
        assertThat(server.changes()).isEmpty();
        assertThat(Files.exists(backups())).isFalse();
    }

    @Test
    void aFailedRemovalIsAFailedResultWithItsBackupAndTheListsReadAgain() {
        server.inScope = TAGGED;
        server.changeFailure = new ToolErrorException(ToolError.of(
            ErrorCode.UNEXPECTED_RESPONSE, "StartCLI answered n-NOK", "cause", "next")
            .withNode(DEV));

        ChangeResult result = confirm("deleteTag", removal(GROUP),
            preview("deleteTag", removal(GROUP)));

        assertThat(result.outcome()).isEqualTo(ChangeOutcome.FAILED);
        assertThat(result.inubitAnswer())
            .isEqualTo("UNEXPECTED_RESPONSE: StartCLI answered n-NOK");
        assertThat(result.after()).contains("3 versions carry the tag in the diagram group;"
            + " 0 workflows and modules outside it carry it too; still carrying it in the diagram"
            + " group: workflow acme-orders version 3, workflow acme-orders version 4, module"
            + " acme-mapping version 2");
        assertThat(result.backup()).isPresent();
        assertThat(new BackupStore(backups(), Clock.systemUTC(), FILES)
            .find(result.auditId().toString()).orElseThrow().outcome()).isEqualTo("FAILED");
        assertThat(outcomes()).containsExactly(AuditOutcome.CHALLENGE_ISSUED,
            AuditOutcome.PENDING, AuditOutcome.FAILED);
    }

    @Test
    void aPartialRemovalNamesTheFirst50VersionsStillCarryingTheTagAndHowManyMore() {
        List<TaggedVersion> remaining = IntStream.rangeClosed(1, 53).mapToObj(version ->
            new TaggedVersion(TaggedVersion.Kind.WORKFLOW, "acme-orders", version)).toList();
        server.inScope = remaining;
        server.remainingAfterRemoval = remaining;

        ChangeResult result = confirm("deleteTag", removal(GROUP),
            preview("deleteTag", removal(GROUP)));

        assertThat(result.outcome()).isEqualTo(ChangeOutcome.EXECUTED);
        assertThat(result.after()).contains("53 versions carry the tag in the diagram group;"
            + " 0 workflows and modules outside it carry it too; still carrying it in the diagram"
            + " group: " + IntStream.rangeClosed(1, 50).mapToObj(version ->
                "workflow acme-orders version " + version).collect(Collectors.joining(", "))
            + " and 3 more");
    }

    @Test
    void aRepositoryPathScopeIsRefusedWithNothingReadOrSent() {
        ToolError error = refusal(DEV, "removeTag",
            removal(Map.of("repositoryPath", "/Root/acme/mappings")));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).isEqualTo("removeTag in a repository path is not supported"
            + " yet on dev/node1: the tag list backup before a removal must list the tagged"
            + " repository files, and no StartCLI form that reads them is recorded yet;"
            + " nothing was read or sent");
        assertThat(server.calls).containsExactly("check dev/node1");
        assertThat(server.changes()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void aRemovalWithoutInventoryOwnerIsNotConfiguredBeforeAnythingIsRead() {
        ToolError error = refusal(NO_OWNER, "deleteTag", removal(GROUP));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(error.message()).isEqualTo("deleteTag needs the owner of the diagram group,"
            + " and inventory.owner is not set for dev/node3; nothing was read or sent");
        assertThat(server.calls).containsExactly("check dev/node3");
    }

    // --- CLIENT confirmation and the lock keys (D-17) -----------------------------------------

    @Test
    void underClientConfirmationTheFirstCallRemovesWithItsBackup() {
        server.inScope = TAGGED;

        Response response = call(TEST_CLIENT, "deleteTag", removal(GROUP), Optional.empty());

        assertThat(response).isInstanceOfSatisfying(Response.Completed.class,
            completed -> assertThat(completed.result().backup()).isPresent());
        assertThat(server.changes())
            .containsExactly("tag --tagDelete REL-1 " + GROUP_OPTIONS + OWNER_OPTION);
    }

    @Test
    void aSecondCallOnTheSameTagAndScopeIsPreconditionFailedOthersRun() {
        server.inScope = TAGGED;
        AtomicReference<ToolError> sameKey = new AtomicReference<>();
        AtomicReference<Response> otherGroup = new AtomicReference<>();
        server.duringRead = () -> {
            server.duringRead = () -> { };
            // the key node|OPERATION|tag:<tag>@<scope> (removeTag would share it, but takes
            // only a repository path)
            sameKey.set(catchThrowableOfType(ToolErrorException.class, () -> call(TEST_CLIENT,
                "deleteTag", removal(GROUP), Optional.empty())).error());
            otherGroup.set(call(TEST_CLIENT, "deleteTag", removal(Map.of("diagramGroup",
                "Billing", "diagramType", "technical")), Optional.empty()));
        };

        call(TEST_CLIENT, "deleteTag", removal(GROUP), Optional.empty());

        assertThat(sameKey.get().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(sameKey.get().message()).startsWith("Another change of"
            + " tag:REL-1@technical/Orders (OPERATION) on test/node2 is running");
        assertThat(otherGroup.get()).isInstanceOf(Response.Completed.class);
        assertThat(server.changes()).containsExactly(
            "tag --tagDelete REL-1 --tagWorkflowGroup Billing --tagWorkflowType technical"
                + OWNER_OPTION,
            "tag --tagDelete REL-1 " + GROUP_OPTIONS + OWNER_OPTION);
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

    // --- the coverage rows decide what the description offers --------------------------------

    @Test
    void deleteTagIsOfferedOnlyWhereItsRowsInADiagramGroupAreReachable() {
        assertThat(TagAdministrationService.deleteTagOffered(
            InterfaceCoverage.load(new StringReader(LIST)))).isTrue();
        assertThat(TagAdministrationService.deleteTagOffered(InterfaceCoverage.load(
            new StringReader(LIST.replace("reachable:manage_tags/deleteTag", "planned")))))
            .isFalse();
        assertThat(TagAdministrationService.deleteTagOffered(InterfaceCoverage.load(
            new StringReader(LIST.lines().filter(line -> !line.startsWith("CLI\ttag --tagUser\t"))
                .collect(Collectors.joining("\n"))))))
            .as("--tagUser is sent with the group").isFalse();
        assertThat(TagAdministrationService.deleteTagOffered(InterfaceCoverage.production()))
            .as("planned until the live check T055").isFalse();
    }

    @Test
    void underTheProductionCoverageARemovalIsRefusedAsNotYetVerified() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions), new CapabilityMatrix(List.of(),
                Map.of(Capability.TAG_ADMINISTRATION, SupportState.SUPPORTED)),
            InterfaceCoverage.production());
        ChangeAudit audit = new ChangeAudit(records, clock, "acme", UUID::randomUUID);
        service = new TagAdministrationService(new ChangeGuard(new TargetResolver(
            List.copyOf(policies.keySet())), policies::get, (node, area) -> true, server, gate,
            audit), new ChangeLocks(), new ChangeChallenges(registry), audit, server,
            new BackupStore(backups(), clock, FILES), node -> Optional.of("acme-dev"));

        ToolError error = refusal(DEV, "deleteTag", removal(GROUP));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).contains("not yet verified");
        assertThat(server.calls).isEmpty();
    }

    // --- leak check (SC-005) ------------------------------------------------------------------

    @Test
    void neitherPreviewNorResultNorAuditRepeatASecretOfINUBITsAnswers() {
        server.inScope = List.of(new TaggedVersion(TaggedVersion.Kind.WORKFLOW,
            "acme " + TestSecrets.FREE_TEXT_SECRETS, 1));
        server.changeFailure = new ToolErrorException(ToolError.of(
            ErrorCode.UNEXPECTED_RESPONSE, "StartCLI said " + TestSecrets.FREE_TEXT_SECRETS,
            "cause", "next").withNode(DEV));

        ChangePreview preview = preview("deleteTag", removal(GROUP));
        ChangeResult result = confirm("deleteTag", removal(GROUP), preview);

        assertThat(result.after().orElseThrow()).contains("still carrying it in the diagram"
            + " group: workflow acme ");
        TestSecrets.assertNoLeak(preview.toString());
        TestSecrets.assertNoLeak(result.toString());
        TestSecrets.assertNoLeak(records.records.toString());
    }
}
