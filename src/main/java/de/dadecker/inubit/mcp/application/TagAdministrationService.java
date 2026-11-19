package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ChangeOutcome;
import de.dadecker.inubit.mcp.domain.model.ChangePreview;
import de.dadecker.inubit.mcp.domain.model.ChangeResult;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The actions of {@code manage_tags} on one server (feature 022, US4 AS12–AS14, FR-019a,
 * research D-12–D-14, D-17, D-18; contracts/mcp-tools-delta.md → manage_tags): the active tag
 * of a user. Tag removal and deletion are excluded after the live check T055 (owner decision
 * IMC-7): {@code removeTag} and {@code deleteTag} are unknown actions like any other. A call runs
 * in this order:
 *
 * <ol>
 *   <li>the inputs are checked before anything is read; an unknown action is
 *       {@code INVALID_INPUT} (AS13, AS14);
 *   <li>{@link ChangeGuard#admit} (D-12; it audits its own refusals): an action whose coverage
 *       rows are not {@code reachable} is refused there ({@code UNSUPPORTED_VERSION});
 *   <li>the key {@code node|OPERATION|activeTag:<user>} is taken for the rest of the call
 *       ({@link ChangeLocks}, D-17; held: {@code PRECONDITION_FAILED});
 *   <li>the user's active tag is read;
 *   <li>under server-side confirmation the first call returns the {@link ChangePreview} with a
 *       code; the call with the code compares the state read now with the preview's ({@code CONFLICT}
 *       with a fresh preview, D-14). Under {@code write.confirmation: CLIENT} (never on
 *       production) the first call executes (D-13);
 *   <li>{@code PENDING} is audited, the change is sent with the call's {@link WritePermit}, the
 *       active tag is read again (FR-024) and {@code EXECUTED} or {@code FAILED} is audited.
 * </ol>
 *
 * <p>An active tag is a setting of the user, not an object INUBIT exports, so nothing is backed
 * up (FR-018). Every refusal after the admission is audited {@code REFUSED}.
 */
public final class TagAdministrationService {

    public static final String TOOL = "manage_tags";

    static final String SET_ACTIVE_TAG = "setActiveTag";
    static final String REMOVE_ACTIVE_TAG = "removeActiveTag";

    static final String USER = "user";
    static final String TAG = "tag";

    private static final Logger LOG = LoggerFactory.getLogger(TagAdministrationService.class);
    /** The names StartCLI quoting can carry (research R-11, {@code CliCommand.VALUE}). */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");
    private static final Pattern REASON = Pattern.compile("^[^\\r\\n]{1,500}$");
    private static final String NOT_EXPORTED = "none: an active tag is a setting of the user, not"
        + " an object INUBIT exports; the preview shows the current one";

    public static final ActionSpec SET_ACTIVE_TAG_SPEC =
        activeTagSpec(SET_ACTIVE_TAG, "--tagSetActive");
    public static final ActionSpec REMOVE_ACTIVE_TAG_SPEC =
        activeTagSpec(REMOVE_ACTIVE_TAG, "--tagRemoveActive");

    /** The specs of the actions, for the coverage gate (research D-2). */
    public static final List<ActionSpec> SPECS = List.of(SET_ACTIVE_TAG_SPEC,
        REMOVE_ACTIVE_TAG_SPEC);

    private final ChangeGuard guard;
    private final ChangeLocks locks;
    private final ChangeChallenges challenges;
    private final ChangeAudit audit;
    private final GatewayFactory gateways;

    public TagAdministrationService(ChangeGuard guard, ChangeLocks locks,
        ChangeChallenges challenges, ChangeAudit audit, GatewayFactory gateways) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
    }

    /**
     * One call of an action.
     *
     * @param node             the server id as requested
     * @param action           e.g. {@code setActiveTag}
     * @param fields           the action's inputs: {@code user}, {@code tag}
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
         * @param warnings the version gate's warnings, an audit record that could not be written
         */
        record Completed(ChangeResult result, List<String> warnings) implements Response {
            public Completed {
                Objects.requireNonNull(result, "result");
                warnings = List.copyOf(warnings);
            }
        }
    }

    /** The checked inputs; {@code tag} only for {@code setActiveTag}. */
    private record Checked(ActionSpec spec, String user, Optional<String> tag) {

        String action() {
            return spec.action();
        }
    }

    /**
     * A state as read: {@code compared} is the preview state of research D-14, {@code shown} the
     * text of {@code before} / {@code after}.
     */
    private record State(String compared, String shown) {
    }

    /**
     * Runs one call: a preview, or the change with its result.
     *
     * @throws ToolErrorException every refusal, after its audit record ({@code INVALID_INPUT},
     *     the codes of {@link ChangeGuard#admit}, {@code PRECONDITION_FAILED},
     *     {@code CONFIRMATION_INVALID}, {@link ChangeChallenges.Conflict}, the error of a failed
     *     read before the change)
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
            State before = readBefore(call, step, checked, node);
            Map<String, Object> canonical = new LinkedHashMap<>(request.fields());
            canonical.put("action", spec.action());
            String fingerprint = ChangeChallenges.fingerprint(canonical);
            ChangePreview preview = preview(checked, node, before, policy);
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
            return run(call, checked, node, before, policy);
        }
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

    private Response.Completed run(ChangeAudit.Call call, Checked checked, NodeId node,
        State before, WritePolicy policy) {
        String action = checked.action();
        call.pending("About to " + action + " on " + node + ": " + before.shown() + " -> "
            + requested(checked));
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
            after = Optional.of(read(checked, node).shown());
        } catch (ToolErrorException e) {
            reReadFailed = Optional.of(e.error().code() + ": " + e.error().message());
        } catch (RuntimeException e) {
            LOG.error("{} {} on {}: reading the state again failed unexpectedly", TOOL, action,
                node, e);
            reReadFailed = Optional.of(ErrorCode.INTERNAL + ": reading the state again failed"
                + " unexpectedly (" + e.getClass().getSimpleName() + ")");
        }
        List<String> warnings = new ArrayList<>(policy.warnings());
        String text = action + " on " + node + ": " + outcome + " (" + answer + "); after: "
            + after.orElse("re-read failed");
        Optional<String> auditWarning = outcome == ChangeOutcome.EXECUTED ? call.executed(text)
            : call.failed(text);
        auditWarning.ifPresent(warnings::add);
        LOG.info("{} {} on {}: {} (audit id {})", TOOL, action, node, outcome, call.auditId());
        return new Response.Completed(new ChangeResult(node, TOOL, action, target(checked),
            outcome, before.shown(), answer, after, reReadFailed, OptionalInt.empty(),
            Optional.empty(), ChangeResult.RestartNeeded.FALSE, call.auditId()), warnings);
    }

    private State readBefore(ChangeAudit.Call call, AuditRecord.Step step, Checked checked,
        NodeId node) {
        try {
            return read(checked, node);
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

    /** The active tag of the user. */
    private State read(Checked checked, NodeId node) {
        Optional<String> active = gateways.tagAdministration(node).activeTag(checked.user());
        return new State(active.map(tag -> "tag:" + tag).orElse("none"),
            active.map(tag -> "active tag " + tag).orElse("no active tag"));
    }

    private void send(Checked checked, NodeId node, WritePermit permit) {
        TagAdministrationPort port = gateways.tagAdministration(node);
        switch (checked.action()) {
            case SET_ACTIVE_TAG -> port.setActiveTag(checked.user(), checked.tag().orElseThrow(),
                permit);
            default -> port.removeActiveTag(checked.user(), permit);
        }
    }

    /** The preview without its code; {@link #issue} adds it. */
    private static ChangePreview preview(Checked checked, NodeId node, State before,
        WritePolicy policy) {
        return new ChangePreview(node, TOOL, checked.action(),
            checked.spec().area().name().toLowerCase(Locale.ROOT), target(checked),
            before.shown(), requested(checked), Optional.empty(), effects(checked), NOT_EXPORTED,
            Optional.empty(), policy.warnings());
    }

    /** The canonical state the preview showed (research D-14). */
    private static String previewState(Checked checked, State state) {
        return checked.action() + "=" + state.compared();
    }

    private static String target(Checked checked) {
        return "active tag of the user " + checked.user();
    }

    private static String requested(Checked checked) {
        return switch (checked.action()) {
            case SET_ACTIVE_TAG -> "active tag " + checked.tag().orElseThrow();
            default -> "no active tag";
        };
    }

    /** The fixed effect sentences. */
    private static List<String> effects(Checked checked) {
        return switch (checked.action()) {
            case SET_ACTIVE_TAG -> List.of("The active tag of " + checked.user() + " stays "
                + checked.tag().orElseThrow() + " until it is set again or removed.");
            default -> List.of(checked.user() + " has no active tag until one is set again.");
        };
    }

    /** {@code activeTag:<user>} (D-17). */
    private static String lockObject(Checked checked) {
        return "activeTag:" + checked.user();
    }

    // --- input validation (FR-020) -------------------------------------------------------------

    private static Checked check(Request request) {
        ActionSpec spec = SPECS.stream().filter(candidate -> candidate.action()
            .equals(request.action())).findFirst().orElseThrow(() -> invalid(
                "The action is not one of " + SPECS.stream().map(ActionSpec::action)
                    .collect(Collectors.joining(", ")) + "; nothing was read or changed"));
        String action = spec.action();
        Map<String, Object> fields = request.fields();
        Set<String> allowed = action.equals(SET_ACTIVE_TAG) ? Set.of(USER, TAG) : Set.of(USER);
        if (!allowed.containsAll(fields.keySet())) {
            throw invalid(action + " takes only " + String.join(", ", new TreeSet<>(allowed))
                + " besides reason; nothing was read or changed");
        }
        String user = name(fields, USER);
        Optional<String> tag = allowed.contains(TAG) ? Optional.of(name(fields, TAG))
            : Optional.empty();
        if (request.reason() == null || !REASON.matcher(request.reason()).matches()) {
            throw invalid("reason must be 1 to 500 characters on one line; nothing was read or"
                + " changed");
        }
        return new Checked(spec, user, tag);
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

    /** The StartCLI option of {@code action}, e.g. {@code --tagSetActive}. */
    private static String option(String action) {
        return switch (action) {
            case SET_ACTIVE_TAG -> "--tagSetActive";
            default -> "--tagRemoveActive";
        };
    }

    private static CoverageKey cli(String entry) {
        return new CoverageKey(Channel.CLI, entry);
    }

    private static ActionSpec activeTagSpec(String action, String option) {
        Set<CoverageKey> entries = Set.of(cli("tag " + option), cli("tag --tagUser"));
        return new ActionSpec(TOOL, action, Capability.TAG_ADMINISTRATION,
            ActionSpec.Area.OPERATION, ActionSpec.Kind.CHANGE, entries, input -> entries,
            Set.of(), false, Optional.empty(), ActionSpec.RestartNotice.NONE);
    }
}
