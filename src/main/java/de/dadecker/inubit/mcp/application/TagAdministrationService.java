package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ChangeOutcome;
import de.dadecker.inubit.mcp.domain.model.ChangePreview;
import de.dadecker.inubit.mcp.domain.model.ChangeResult;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageEntry;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import de.dadecker.inubit.mcp.domain.model.TaggedVersion;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort.Scope;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort.TaggedObjects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The actions of {@code manage_tags} on one server (feature 022, US4 AS12–AS14, FR-018, FR-019a,
 * research D-12–D-15, D-17, D-18; contracts/mcp-tools-delta.md → manage_tags): the active tag of
 * a user, and the removal ({@code tag --tagRemove}) or deletion ({@code tag --tagDelete}) of a tag
 * in one diagram group. A call runs in this order:
 *
 * <ol>
 *   <li>the inputs are checked before anything is read; a removal without a scope is
 *       {@code INVALID_INPUT} (AS13);
 *   <li>{@link ChangeGuard#admit} (D-12; it audits its own refusals): a removal whose coverage
 *       rows are not {@code reachable} is refused there ({@code UNSUPPORTED_VERSION});
 *   <li>the key {@code node|OPERATION|activeTag:<user>} or
 *       {@code node|OPERATION|tag:<tag>@<scope>} is taken for the rest of the call
 *       ({@link ChangeLocks}, D-17; held: {@code PRECONDITION_FAILED}); the scope is
 *       {@code <type>/<group>} or the repository path;
 *   <li>a removal in a repository path is refused ({@code UNSUPPORTED_VERSION}): its backup must
 *       list the tagged repository files, and no StartCLI form that reads them is recorded yet
 *       (D-18, live check T055). A removal needs {@code inventory.owner} of the node
 *       ({@code NOT_CONFIGURED});
 *   <li>the state before is read: the user's active tag, or the versions carrying the tag in the
 *       diagram group of the owner and the count outside it
 *       ({@link TagAdministrationPort#tagged});
 *   <li>under server-side confirmation the first call returns the {@link ChangePreview} with a
 *       code; the call with the code compares the state read now with the preview's ({@code CONFLICT}
 *       with a fresh preview, D-14). Under {@code write.confirmation: CLIENT} (never on
 *       production) the first call executes (D-13);
 *   <li>before a removal the versions carrying the tag in the scope are stored as a tag list
 *       backup ({@link BackupStore#writeTagList}, D-15); if that fails, the call is refused and
 *       nothing is sent (FR-018);
 *   <li>{@code PENDING} is audited, the change is sent with the call's {@link WritePermit}, the
 *       state is read again (FR-024) and {@code EXECUTED} or {@code FAILED} is audited; the backup
 *       manifest gets the same outcome, or {@code NOT_STARTED} if {@code PENDING} cannot be
 *       written. The result names the versions still carrying the tag in the scope.
 * </ol>
 *
 * <p>The removal sends no {@code --tagUser}; the owner whose diagram group the preview, backup and
 * re-read read is {@code inventory.owner}, the owner {@code tag_artifacts} tags for by default.
 * That the removal acts on that owner's diagram group is an assumption the live check T055
 * confirms (D-18). Every refusal after the admission is audited {@code REFUSED}.
 */
public final class TagAdministrationService {

    public static final String TOOL = "manage_tags";

    static final String SET_ACTIVE_TAG = "setActiveTag";
    static final String REMOVE_ACTIVE_TAG = "removeActiveTag";
    static final String REMOVE_TAG = "removeTag";
    static final String DELETE_TAG = "deleteTag";

    static final String USER = "user";
    static final String TAG = "tag";
    static final String SCOPE = "scope";
    static final String DIAGRAM_GROUP = "diagramGroup";
    static final String DIAGRAM_TYPE = "diagramType";
    static final String REPOSITORY_PATH = "repositoryPath";

    private static final Logger LOG = LoggerFactory.getLogger(TagAdministrationService.class);
    /** The names StartCLI quoting can carry (research R-11, {@code CliCommand.VALUE}). */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");
    /** Repository paths (feature 005 research D-1, {@code CliCommand.REPOSITORY_PATH}). */
    private static final Pattern PATH =
        Pattern.compile("^/Root(/[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199})+$");
    private static final Pattern REASON = Pattern.compile("^[^\\r\\n]{1,500}$");
    /** The backup manifest's outcome when nothing was sent after it, as in {@code NodeDeployer}. */
    private static final String NOT_STARTED = "NOT_STARTED";
    private static final String NOT_EXPORTED = "none: an active tag is a setting of the user, not"
        + " an object INUBIT exports; the preview shows the current one";

    public static final ActionSpec SET_ACTIVE_TAG_SPEC =
        activeTagSpec(SET_ACTIVE_TAG, "--tagSetActive");
    public static final ActionSpec REMOVE_ACTIVE_TAG_SPEC =
        activeTagSpec(REMOVE_ACTIVE_TAG, "--tagRemoveActive");
    public static final ActionSpec REMOVE_TAG_SPEC = removalSpec(REMOVE_TAG, "--tagRemove");
    public static final ActionSpec DELETE_TAG_SPEC = removalSpec(DELETE_TAG, "--tagDelete");

    /** The specs of the actions, for the coverage gate (research D-2). */
    public static final List<ActionSpec> SPECS = List.of(SET_ACTIVE_TAG_SPEC,
        REMOVE_ACTIVE_TAG_SPEC, REMOVE_TAG_SPEC, DELETE_TAG_SPEC);

    private final ChangeGuard guard;
    private final ChangeLocks locks;
    private final ChangeChallenges challenges;
    private final ChangeAudit audit;
    private final GatewayFactory gateways;
    private final BackupStore backups;
    private final Function<NodeId, Optional<String>> owners;

    /**
     * @param backups where the tag list is stored before a removal
     * @param owners  {@code inventory.owner} of each server, the owner of the diagram groups
     */
    public TagAdministrationService(ChangeGuard guard, ChangeLocks locks,
        ChangeChallenges challenges, ChangeAudit audit, GatewayFactory gateways,
        BackupStore backups, Function<NodeId, Optional<String>> owners) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.backups = Objects.requireNonNull(backups, "backups");
        this.owners = Objects.requireNonNull(owners, "owners");
    }

    /**
     * One call of an action.
     *
     * @param node             the server id as requested
     * @param action           e.g. {@code removeTag}
     * @param fields           the action's inputs: {@code user}, {@code tag}, {@code scope}
     * @param reason           why the caller makes the change (audited)
     * @param confirmationCode the code of the preview, for the second call
     * @param mcpClient        the MCP client's {@code name/version}, if sent
     */
    public record Request(String node, String action, Map<String, Object> fields, String reason,
        Optional<String> confirmationCode, Optional<String> mcpClient) {

        public Request {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            Objects.requireNonNull(confirmationCode, "confirmationCode");
            Objects.requireNonNull(mcpClient, "mcpClient");
        }
    }

    /** The preview of a first call, or the result of an executed call. */
    public sealed interface Response {

        record Preview(ChangePreview preview) implements Response {
            public Preview {
                Objects.requireNonNull(preview, "preview");
            }
        }

        /**
         * @param warnings the version gate's warnings, a backup manifest or audit record that
         *     could not be updated or written
         */
        record Completed(ChangeResult result, List<String> warnings) implements Response {
            public Completed {
                Objects.requireNonNull(result, "result");
                warnings = List.copyOf(warnings);
            }
        }
    }

    /** The checked inputs; {@code scope} only for a removal, {@code user} for an active tag. */
    private record Checked(ActionSpec spec, Optional<String> user, Optional<String> tag,
        Optional<Scope> scope) {

        String action() {
            return spec.action();
        }
    }

    /**
     * A state as read: {@code compared} is the preview state of research D-14, {@code shown} the
     * text of {@code before} / {@code after}; {@code tagged} the versions of a removal's scope.
     */
    private record State(String compared, String shown, Optional<TaggedObjects> tagged) {
    }

    /**
     * Which removals the coverage list offers: {@code removeTag} and {@code deleteTag} each when
     * its form in a diagram group is {@code reachable} on some line (research D-18). In the
     * production list they stay {@code planned} until the live check T055.
     */
    public static List<String> offeredRemovals(InterfaceCoverage coverage) {
        return List.of(REMOVE_TAG_SPEC, DELETE_TAG_SPEC).stream()
            .filter(spec -> CoverageEntry.LINES.stream().anyMatch(line -> coverage.offered(
                removalEntries(option(spec.action()), false), line)))
            .map(ActionSpec::action).toList();
    }

    /**
     * Runs one call: a preview, or the change with its result.
     *
     * @throws ToolErrorException every refusal, after its audit record ({@code INVALID_INPUT},
     *     the codes of {@link ChangeGuard#admit}, {@code PRECONDITION_FAILED},
     *     {@code UNSUPPORTED_VERSION} for a repository path, {@code NOT_CONFIGURED},
     *     {@code CONFIRMATION_INVALID}, {@link ChangeChallenges.Conflict}, the error of a failed
     *     read before the change, {@code INTERNAL} for a backup that cannot be stored)
     */
    public Response execute(Request request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> input = new LinkedHashMap<>(request.fields());
        input.put("reason", request.reason());
        request.confirmationCode().ifPresent(code -> input.put(AuditRecord.CONFIRMATION_CODE,
            code));
        Checked checked;
        try {
            checked = check(request);
        } catch (ToolErrorException e) {
            throw refuse(audit.call(TOOL, String.valueOf(request.node()),
                String.valueOf(request.action()), input, request.mcpClient()),
                request.confirmationCode().isPresent() ? AuditRecord.Step.EXECUTE
                    : AuditRecord.Step.PREVIEW, e);
        }
        ActionSpec spec = checked.spec();
        WritePolicy policy = guard.admit(TOOL, request.node(), spec, input);
        NodeId node = policy.node();
        boolean needsCode = ChangeChallenges.needsCode(policy, spec.area());
        ChangeAudit.Call call = audit.call(TOOL, node.value(), spec.action(), input,
            request.mcpClient());
        call.account(policy.account());
        AuditRecord.Step step = !needsCode || request.confirmationCode().isPresent()
            ? AuditRecord.Step.EXECUTE : AuditRecord.Step.PREVIEW;
        ChangeLocks.Lock lock;
        try {
            lock = locks.acquire(node, spec.area(), lockObject(checked));
        } catch (ToolErrorException e) {
            throw refuse(call, step, e);
        }
        try (lock) {
            Optional<String> owner;
            try {
                owner = owner(checked, node);
            } catch (ToolErrorException e) {
                throw refuse(call, step, e);
            }
            State before = readBefore(call, step, checked, node, owner);
            Map<String, Object> canonical = new LinkedHashMap<>(request.fields());
            canonical.put("action", spec.action());
            String fingerprint = ChangeChallenges.fingerprint(canonical);
            ChangePreview preview = preview(checked, node, owner, before, policy);
            if (needsCode && request.confirmationCode().isEmpty()) {
                return new Response.Preview(issue(call, step, preview, before, fingerprint,
                    policy));
            }
            if (needsCode) {
                try {
                    String previewed = challenges.redeem(request.confirmationCode().get(), TOOL,
                        node, fingerprint);
                    challenges.confirmOrConflict(previewed, previewState(checked, before),
                        fingerprint, preview, policy.confirmationTtl());
                } catch (ToolErrorException e) {
                    throw refuse(call, step, e);
                }
            }
            return run(call, step, checked, node, owner, before, policy);
        }
    }

    /**
     * The owner of a removal's diagram group; empty for an active tag.
     *
     * @throws ToolErrorException {@code UNSUPPORTED_VERSION} for a repository path,
     *     {@code NOT_CONFIGURED} without {@code inventory.owner}
     */
    private Optional<String> owner(Checked checked, NodeId node) {
        if (checked.scope().isEmpty()) {
            return Optional.empty();
        }
        if (checked.scope().get() instanceof Scope.RepositoryPath) {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
                checked.action() + " in a repository path is not supported yet on " + node
                    + ": the tag list backup before a removal must list the tagged repository"
                    + " files, and no StartCLI form that reads them is recorded yet; nothing was"
                    + " read or sent",
                "A tag is removed only after the versions carrying it are stored (FR-018)",
                "Name a technical diagram group, or remove the tag in the Workbench")
                .withNode(node));
        }
        return Optional.of(owners.apply(node).orElseThrow(() -> new ToolErrorException(
            ToolError.of(ErrorCode.NOT_CONFIGURED, checked.action() + " needs the owner of the"
                + " diagram group, and inventory.owner is not set for " + node + "; nothing was"
                + " read or sent", "The diagram groups belong to inventory.owner, the owner"
                + " tag_artifacts tags for", "Set inventory.owner for the server").withNode(
                node))));
    }

    private ChangePreview issue(ChangeAudit.Call call, AuditRecord.Step step,
        ChangePreview preview, State before, String fingerprint, WritePolicy policy) {
        ChangePreview.Confirmation confirmation;
        try {
            confirmation = challenges.issue(TOOL, preview.node(), fingerprint,
                preview.action() + "=" + before.compared(), policy.confirmationTtl());
        } catch (ToolErrorException e) {
            throw refuse(call, step, e);
        }
        try {
            call.challengeIssued(confirmation.code(), "Preview of " + preview.action() + " on "
                + preview.node() + ": " + before.shown() + " -> " + preview.requested()
                + ", code valid until " + confirmation.expiresAt());
        } catch (ToolErrorException e) {
            challenges.discard(confirmation.code());
            throw e;
        }
        LOG.info("{} {} on {}: CHALLENGE_ISSUED (audit id {})", TOOL, preview.action(),
            preview.node(), call.auditId());
        return new ChangePreview(preview.node(), preview.tool(), preview.action(), preview.area(),
            preview.target(), preview.before(), preview.requested(), preview.affected(),
            preview.effects(), preview.backup(), Optional.of(confirmation), preview.warnings());
    }

    private Response.Completed run(ChangeAudit.Call call, AuditRecord.Step step, Checked checked,
        NodeId node, Optional<String> owner, State before, WritePolicy policy) {
        String action = checked.action();
        Optional<BackupStore.Manifest> manifest = before.tagged().isPresent()
            ? Optional.of(backUp(call, step, checked, node, before.tagged().get()))
            : Optional.empty();
        try {
            call.pending("About to " + action + " on " + node + ": " + before.shown() + " -> "
                + requested(checked));
        } catch (ToolErrorException e) {
            manifest.ifPresent(written -> recordOutcome(written, NOT_STARTED));
            throw e;
        }
        ChangeOutcome outcome;
        String answer;
        try {
            send(checked, node, policy.permit());
            answer = "tag " + option(action) + " was sent; StartCLI reported no error";
            outcome = ChangeOutcome.EXECUTED;
        } catch (ToolErrorException e) {
            answer = e.error().code() + ": " + e.error().message();
            outcome = ChangeOutcome.FAILED;
        } catch (RuntimeException e) {
            LOG.error("{} {} on {} failed unexpectedly", TOOL, action, node, e);
            answer = ErrorCode.INTERNAL + ": " + action + " failed unexpectedly ("
                + e.getClass().getSimpleName() + "); check the tags in the Workbench and the MCP"
                + " server log on stderr";
            outcome = ChangeOutcome.FAILED;
        }
        Optional<String> after = Optional.empty();
        Optional<String> reReadFailed = Optional.empty();
        try {
            after = Optional.of(after(read(checked, node, owner)));
        } catch (ToolErrorException e) {
            reReadFailed = Optional.of(e.error().code() + ": " + e.error().message());
        } catch (RuntimeException e) {
            LOG.error("{} {} on {}: reading the state again failed unexpectedly", TOOL, action,
                node, e);
            reReadFailed = Optional.of(ErrorCode.INTERNAL + ": reading the state again failed"
                + " unexpectedly (" + e.getClass().getSimpleName() + ")");
        }
        List<String> warnings = new ArrayList<>(policy.warnings());
        ChangeOutcome done = outcome;
        manifest.flatMap(written -> recordOutcome(written, done.name())).ifPresent(warnings::add);
        String text = action + " on " + node + ": " + outcome + " (" + answer + "); after: "
            + after.orElse("re-read failed");
        Optional<String> auditWarning = outcome == ChangeOutcome.EXECUTED ? call.executed(text)
            : call.failed(text);
        auditWarning.ifPresent(warnings::add);
        LOG.info("{} {} on {}: {} (audit id {})", TOOL, action, node, outcome, call.auditId());
        return new Response.Completed(new ChangeResult(node, TOOL, action, target(checked, owner),
            outcome, before.shown(), answer, after, reReadFailed, OptionalInt.empty(),
            manifest.map(written -> new ChangeResult.Backup(
                backups.manifestFile(written.auditId()).toString(), written.auditId())),
            ChangeResult.RestartNeeded.FALSE, call.auditId()), warnings);
    }

    /**
     * Stores the versions carrying the tag in the scope (D-15).
     *
     * @throws ToolErrorException {@code INTERNAL}, audited {@code REFUSED}, if they cannot be
     *     stored; nothing is sent then (FR-018)
     */
    private BackupStore.Manifest backUp(ChangeAudit.Call call, AuditRecord.Step step,
        Checked checked, NodeId node, TaggedObjects tagged) {
        try {
            return backups.writeTagList(call.auditId().toString(),
                new BackupStore.AdministrationScope(node, BackupStore.Manifest.ObjectKind.TAG_LIST,
                    checked.tag().orElseThrow() + "@" + scopeKey(checked.scope().orElseThrow())),
                tagged.inScope());
        } catch (RuntimeException e) {
            LOG.error("{} {} on {}: the tag list backup could not be stored ({})", TOOL,
                checked.action(), node, e.getClass().getSimpleName());
            throw refuse(call, step, new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The tag list backup for " + checked.action() + " on " + node + " could not be"
                    + " stored; nothing was sent",
                "A tag is removed only after the versions carrying it are stored (FR-018)",
                "Check the backup directory and the MCP server log on stderr, then call again")
                .withNode(node)));
        }
    }

    /** Sets the backup manifest's outcome; a warning if that fails. */
    private Optional<String> recordOutcome(BackupStore.Manifest manifest, String outcome) {
        try {
            backups.update(manifest.with(outcome, Map.of()));
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.error("The backup manifest {} could not be updated to {} ({})", manifest.auditId(),
                outcome, e.getClass().getSimpleName());
            return Optional.of("The backup manifest " + manifest.auditId() + " could not be"
                + " updated to " + outcome + "; it still says PENDING");
        }
    }

    private State readBefore(ChangeAudit.Call call, AuditRecord.Step step, Checked checked,
        NodeId node, Optional<String> owner) {
        try {
            return read(checked, node, owner);
        } catch (ToolErrorException e) {
            throw refuse(call, step, e);
        } catch (RuntimeException e) {
            LOG.error("{} {} on {}: reading the state failed unexpectedly", TOOL,
                checked.action(), node, e);
            throw refuse(call, step, new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "Reading the tags of " + node + " failed unexpectedly ("
                    + e.getClass().getSimpleName() + "); nothing was changed",
                "A defect of the MCP server", "Check the MCP server log on stderr")
                .withNode(node)));
        }
    }

    /** The active tag of the user, or the versions carrying the tag in and outside the scope. */
    private State read(Checked checked, NodeId node, Optional<String> owner) {
        TagAdministrationPort port = gateways.tagAdministration(node);
        if (checked.scope().isEmpty()) {
            Optional<String> active = port.activeTag(checked.user().orElseThrow());
            return new State(active.map(tag -> "tag:" + tag).orElse("none"),
                active.map(tag -> "active tag " + tag).orElse("no active tag"), Optional.empty());
        }
        TaggedObjects tagged = port.tagged(checked.tag().orElseThrow(), owner.orElseThrow(),
            checked.scope().get());
        long inScope = tagged.inScope().size();
        long outside = tagged.outsideScopeCount();
        String shown = inScope + (inScope == 1 ? " version carries" : " versions carry")
            + " the tag in the diagram group; " + outside + (outside == 1
                ? " workflow or module outside it carries" : " workflows and modules outside it"
                    + " carry") + " it too";
        return new State(ChangeChallenges.fingerprint(Map.of("inScope", versions(tagged),
            "outside", outside)), shown, Optional.of(tagged));
    }

    /**
     * The state read after the change; a removal that left versions carrying the tag in the scope
     * names the first {@value ChangePreview.Affected#MAX_IDS} of them and how many more.
     */
    private static String after(State state) {
        List<String> remaining = state.tagged().map(TagAdministrationService::versions)
            .orElse(List.of());
        if (remaining.isEmpty()) {
            return state.shown();
        }
        int listed = Math.min(remaining.size(), ChangePreview.Affected.MAX_IDS);
        return state.shown() + "; still carrying it in the diagram group: "
            + String.join(", ", remaining.subList(0, listed))
            + (remaining.size() > listed ? " and " + (remaining.size() - listed) + " more" : "");
    }

    private static List<String> versions(TaggedObjects tagged) {
        return tagged.inScope().stream().map(version -> SecretTextMasker.mask(
            version.kind().name().toLowerCase(Locale.ROOT) + " " + version.name() + " version "
                + version.version())).toList();
    }

    private void send(Checked checked, NodeId node, WritePermit permit) {
        TagAdministrationPort port = gateways.tagAdministration(node);
        switch (checked.action()) {
            case SET_ACTIVE_TAG -> port.setActiveTag(checked.user().orElseThrow(),
                checked.tag().orElseThrow(), permit);
            case REMOVE_ACTIVE_TAG -> port.removeActiveTag(checked.user().orElseThrow(), permit);
            default -> port.removeTag(checked.action().equals(REMOVE_TAG)
                ? TagAdministrationPort.Removal.REMOVE : TagAdministrationPort.Removal.DELETE,
                checked.tag().orElseThrow(), checked.scope().orElseThrow(), permit);
        }
    }

    /** The preview without its code; {@link #issue} adds it. */
    private static ChangePreview preview(Checked checked, NodeId node, Optional<String> owner,
        State before, WritePolicy policy) {
        return new ChangePreview(node, TOOL, checked.action(),
            checked.spec().area().name().toLowerCase(Locale.ROOT), target(checked, owner),
            before.shown(), requested(checked),
            before.tagged().map(tagged -> ChangePreview.Affected.of(versions(tagged))),
            effects(checked), before.tagged().map(tagged -> "the list of the "
                + tagged.inScope().size()
                + (tagged.inScope().size() == 1 ? " version" : " versions")
                + " carrying the tag in the diagram group, stored as a tag list backup before"
                + " anything is sent; nothing is sent if it cannot be stored")
                .orElse(NOT_EXPORTED), Optional.empty(), policy.warnings());
    }

    /** The canonical state the preview showed (research D-14). */
    private static String previewState(Checked checked, State state) {
        return checked.action() + "=" + state.compared();
    }

    private static String target(Checked checked, Optional<String> owner) {
        return switch (checked.scope().orElse(null)) {
            case null -> "active tag of the user " + checked.user().orElseThrow();
            case Scope.DiagramGroup group -> "tag " + checked.tag().orElseThrow()
                + " in the diagram group " + group.group() + " (" + group.type() + ") of "
                + owner.orElseThrow();
            case Scope.RepositoryPath path -> "tag " + checked.tag().orElseThrow()
                + " in the repository path " + path.path();
        };
    }

    private static String requested(Checked checked) {
        return switch (checked.action()) {
            case SET_ACTIVE_TAG -> "active tag " + checked.tag().orElseThrow();
            case REMOVE_ACTIVE_TAG -> "no active tag";
            case REMOVE_TAG -> "remove the tag in the diagram group (tag --tagRemove)";
            default -> "delete the tag in the diagram group (tag --tagDelete)";
        };
    }

    /** The fixed effect sentences (StartCLI's help, research D-18). */
    private static List<String> effects(Checked checked) {
        String stays = "The tag is meant to stay on the workflows and modules outside the diagram"
            + " group; the result reads both again.";
        String references = "A diagram group references repository files that other diagram"
            + " groups may reference too; the removal may take the tag from them as well.";
        return switch (checked.action()) {
            case SET_ACTIVE_TAG -> List.of("The active tag of " + checked.user().orElseThrow()
                + " stays " + checked.tag().orElseThrow() + " until it is set again or removed.");
            case REMOVE_ACTIVE_TAG -> List.of(checked.user().orElseThrow()
                + " has no active tag until one is set again.");
            case REMOVE_TAG -> List.of("StartCLI's help says that --tagRemove removes the tag only"
                + " from the repository.", stays, references);
            default -> List.of("StartCLI's help says that --tagDelete removes the tag from"
                + " diagrams, modules and the repository.", stays, references);
        };
    }

    /** {@code activeTag:<user>} or {@code tag:<tag>@<scope>} (D-17). */
    private static String lockObject(Checked checked) {
        return checked.scope().map(scope -> "tag:" + checked.tag().orElseThrow() + "@"
            + scopeKey(scope)).orElseGet(() -> "activeTag:" + checked.user().orElseThrow());
    }

    /** {@code <type>/<group>} or the repository path (which starts with {@code /Root}). */
    private static String scopeKey(Scope scope) {
        return switch (scope) {
            case Scope.DiagramGroup group -> group.type() + "/" + group.group();
            case Scope.RepositoryPath path -> path.path();
        };
    }

    // --- input validation (FR-020) -------------------------------------------------------------

    private static Checked check(Request request) {
        ActionSpec spec = SPECS.stream().filter(candidate -> candidate.action()
            .equals(request.action())).findFirst().orElseThrow(() -> invalid(
                "The action is not one of " + SPECS.stream().map(ActionSpec::action)
                    .collect(Collectors.joining(", ")) + "; nothing was read or changed"));
        String action = spec.action();
        Map<String, Object> fields = request.fields();
        Set<String> allowed = switch (action) {
            case SET_ACTIVE_TAG -> Set.of(USER, TAG);
            case REMOVE_ACTIVE_TAG -> Set.of(USER);
            default -> Set.of(TAG, SCOPE);
        };
        if (!allowed.containsAll(fields.keySet())) {
            throw invalid(action + " takes only " + String.join(", ", new TreeSet<>(allowed))
                + " besides reason; nothing was read or changed");
        }
        Optional<String> user = allowed.contains(USER) ? Optional.of(name(fields, USER))
            : Optional.empty();
        Optional<String> tag = allowed.contains(TAG) ? Optional.of(name(fields, TAG))
            : Optional.empty();
        Optional<Scope> scope = allowed.contains(SCOPE)
            ? Optional.of(scope(action, fields.get(SCOPE))) : Optional.empty();
        if (request.reason() == null || !REASON.matcher(request.reason()).matches()) {
            throw invalid("reason must be 1 to 500 characters on one line; nothing was read or"
                + " changed");
        }
        return new Checked(spec, user, tag, scope);
    }

    /** The scope of a removal: exactly one diagram group with its type, or one repository path. */
    private static Scope scope(String action, Object value) {
        if (value == null) {
            throw invalid(action + " needs a scope: a diagramGroup with its diagramType, or a"
                + " repositoryPath; without one StartCLI acts on every diagram group of the"
                + " owner. Nothing was read or changed");
        }
        if (value instanceof Map<?, ?> map) {
            if (map.keySet().equals(Set.of(DIAGRAM_GROUP, DIAGRAM_TYPE))) {
                return new Scope.DiagramGroup(name(map, DIAGRAM_GROUP), name(map, DIAGRAM_TYPE));
            }
            if (map.keySet().equals(Set.of(REPOSITORY_PATH))
                && map.get(REPOSITORY_PATH) instanceof String path
                && PATH.matcher(path).matches()) {
                return new Scope.RepositoryPath(path);
            }
        }
        throw invalid("scope must hold either diagramGroup and diagramType, or repositoryPath (a"
            + " path below /Root); nothing was read or changed");
    }

    private static String name(Map<?, ?> fields, String key) {
        if (fields.get(key) instanceof String value && NAME.matcher(value).matches()) {
            return value;
        }
        throw invalid(key + " must be 1 to 200 letters, digits, spaces or the characters _ . -,"
            + " not starting with a space or -; nothing was read or changed");
    }

    private static ToolErrorException invalid(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            "The input does not match the manage_tags action",
            "Correct the input and call manage_tags again"));
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * Audits {@code e} as {@code REFUSED} and returns it unchanged (a
     * {@link ChangeChallenges.Conflict} keeps its preview), or {@code INTERNAL} if the record
     * could not be written.
     */
    private static ToolErrorException refuse(ChangeAudit.Call call, AuditRecord.Step step,
        ToolErrorException e) {
        ToolErrorException refusal = call.refused(step, e.error());
        return refusal.error() == e.error() ? e : refusal;
    }

    /** The StartCLI option of {@code action}, e.g. {@code --tagRemove}. */
    private static String option(String action) {
        return switch (action) {
            case SET_ACTIVE_TAG -> "--tagSetActive";
            case REMOVE_ACTIVE_TAG -> "--tagRemoveActive";
            case REMOVE_TAG -> "--tagRemove";
            default -> "--tagDelete";
        };
    }

    private static CoverageKey cli(String entry) {
        return new CoverageKey(Channel.CLI, entry);
    }

    /** {@code tag <option>} with the scope options of a diagram group or a repository path. */
    private static Set<CoverageKey> removalEntries(String option, boolean repositoryPath) {
        return repositoryPath ? Set.of(cli("tag " + option), cli("tag --tagRepositoryPath"))
            : Set.of(cli("tag " + option), cli("tag --tagWorkflowGroup"),
                cli("tag --tagWorkflowType"));
    }

    private static ActionSpec activeTagSpec(String action, String option) {
        Set<CoverageKey> entries = Set.of(cli("tag " + option), cli("tag --tagUser"));
        return new ActionSpec(TOOL, action, Capability.TAG_ADMINISTRATION,
            ActionSpec.Area.OPERATION, ActionSpec.Kind.CHANGE, entries, input -> entries,
            Set.of(), false, Optional.empty(), ActionSpec.RestartNotice.NONE);
    }

    /** A removal; the call's entries follow its scope (a missing scope is refused before). */
    private static ActionSpec removalSpec(String action, String option) {
        Set<CoverageKey> group = removalEntries(option, false);
        Set<CoverageKey> path = removalEntries(option, true);
        Set<CoverageKey> all = new HashSet<>(group);
        all.addAll(path);
        return new ActionSpec(TOOL, action, Capability.TAG_ADMINISTRATION,
            ActionSpec.Area.OPERATION, ActionSpec.Kind.CHANGE, all,
            input -> input.get(SCOPE) instanceof Map<?, ?> scope
                && scope.containsKey(REPOSITORY_PATH) ? path : group,
            Set.of(), true, Optional.of(ActionSpec.BackupKind.TAG_LIST),
            ActionSpec.RestartNotice.NONE);
    }
}
