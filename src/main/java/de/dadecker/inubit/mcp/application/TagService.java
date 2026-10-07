package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.DevelopmentGuard.Capability;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.TagOutcome;
import de.dadecker.inubit.mcp.domain.model.TagPreview;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The use case behind {@code tag_artifacts} (feature 004, US4, FR-021, SC-005; research D-16,
 * D-18, D-25 M1, M2). A release is a set of diagram groups; the server makes sure a tag never
 * reaches more than the requested groups:
 *
 * <ol>
 *   <li>inputs: 1–20 distinct diagram groups, none blank and each a name StartCLI quoting can
 *       carry (so no wildcard-like value), the tag likewise, the reason; then the
 *       {@link DevelopmentGuard} and the owner (default {@code inventory.owner}), a user or a
 *       user group (research D-26). Nothing is read from INUBIT before;
 *   <li>the owner's history of all diagram groups and types: a tag that exists on any version
 *       of any diagram or module is {@code INVALID_INPUT} (tags are never moved); a requested
 *       group without technical workflows is {@code NOT_FOUND};
 *   <li>server confirmation: the preview names the groups and the number of workflows, its code
 *       is bound to the inputs and to the head versions of those workflows ({@code CONFLICT}
 *       if they changed);
 *   <li>the {@code PENDING} audit record, then one {@code tag --tagMove} per diagram group;
 *   <li>verification by history exports: the tag must be on the head versions of exactly the
 *       technical workflows of the requested groups and the modules they use. Anything else —
 *       or a failing tag command — removes the tag again ({@code tag --tagDelete}) and the
 *       result is {@code FAILED} with {@code failure{code, step}} and {@code removedAgain}.
 * </ol>
 *
 * <p>Every refusal before anything is sent is a tool error, audited {@code REFUSED}. The
 * workspace is not touched (no lock); a mismatch is listed in
 * {@code .reports/tag-<auditId>.txt}.
 */
public final class TagService {

    private static final Logger LOG = LoggerFactory.getLogger(TagService.class);
    private static final Capability CAPABILITY = Capability.TAG_ARTIFACTS;
    /** The names StartCLI quoting can carry (research R-11, {@code CliCommand.VALUE}). */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");
    private static final Pattern REASON = Pattern.compile("^[^#@\\p{Cntrl}]{1,500}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    private static final int MAX_GROUPS = 20;
    private static final int MAX_AUDITED_INPUT = 500;
    private static final String TECHNICAL = "technical";
    private static final String REPORTS = ".reports";

    /**
     * The collaborators.
     *
     * @param root          the workspace root (reports)
     * @param defaultOwners {@code inventory.owner} of each node
     * @param accounts      the INUBIT account of each node (audit)
     */
    public record Dependencies(Path root, String profile, DevelopmentGuard guard,
        Function<NodeId, TagPort> tags,
        Function<NodeId, Optional<String>> defaultOwners,
        Function<NodeId, ImportService.Account> accounts, WriteChallengeRegistry challenges,
        AuditPort audit, Clock clock, Supplier<UUID> ids) {

        public Dependencies {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(guard, "guard");
            Objects.requireNonNull(tags, "tags");
            Objects.requireNonNull(defaultOwners, "defaultOwners");
            Objects.requireNonNull(accounts, "accounts");
            Objects.requireNonNull(challenges, "challenges");
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(ids, "ids");
        }
    }

    /** The arguments of {@code tag_artifacts}; {@code owner} defaults to inventory.owner. */
    public record TagRequest(String node, Optional<String> owner, List<String> diagramGroups,
        String tag, String reason, Optional<String> confirmationCode,
        Optional<String> mcpClient) {

        public TagRequest {
            node = node == null ? "" : node;
            owner = owner == null ? Optional.empty() : owner;
            diagramGroups = diagramGroups == null ? List.of()
                : java.util.Collections.unmodifiableList(new ArrayList<>(diagramGroups));
            tag = tag == null ? "" : tag;
            reason = reason == null ? "" : reason;
            confirmationCode = confirmationCode == null ? Optional.empty() : confirmationCode;
            mcpClient = mcpClient == null ? Optional.empty() : mcpClient;
        }
    }

    /** Either the preview of the first step or the outcome of an execution. */
    public sealed interface Response {

        /** Nothing was sent; the preview and its code. */
        record Challenge(TagPreview preview) implements Response {
            public Challenge {
                Objects.requireNonNull(preview, "preview");
            }
        }

        /** Tag commands may have been sent: the outcome. */
        record Completed(TagOutcome outcome) implements Response {
            public Completed {
                Objects.requireNonNull(outcome, "outcome");
            }
        }
    }

    private final Dependencies d;

    public TagService(Dependencies dependencies) {
        this.d = Objects.requireNonNull(dependencies, "dependencies");
    }

    /**
     * Runs the sequence for one call (see the class description).
     *
     * @throws ToolErrorException every refusal before anything is sent (after its audit
     *     record), or {@code INTERNAL} if an audit record cannot be written
     */
    public Response tag(TagRequest request) {
        Call call = new Call(request);
        try {
            validate(request);
            DevelopmentPolicy policy = d.guard().admit(request.node(), CAPABILITY);
            call.policy = policy;
            NodeId node = policy.node();
            call.owner = request.owner().or(() -> d.defaultOwners().apply(node))
                .orElseThrow(() -> new ToolErrorException(ToolError.of(ErrorCode.NOT_CONFIGURED,
                    "No owner is given and inventory.owner is not set for " + node
                        + "; nothing was sent", "tag_artifacts needs the owner of the diagram"
                        + " groups", "Give owner, or set inventory.owner for the node")
                    .withNode(node)));
            return prepared(call, policy);
        } catch (ToolErrorException e) {
            if (call.refused) {
                throw e;
            }
            throw call.refuse(e.error());
        } catch (RuntimeException e) {
            if (call.refused) {
                throw e;
            }
            LOG.error("Unexpected failure while preparing tag_artifacts", e);
            throw call.refuse(ToolError.of(ErrorCode.INTERNAL, "Unexpected failure while"
                    + " preparing the tag (" + e.getClass().getSimpleName() + "); nothing was"
                    + " sent", "An internal error of the INUBIT MCP server",
                "Retry; if it persists, check the MCP server log on stderr and report the"
                    + " problem"));
        }
    }

    private Response prepared(Call call, DevelopmentPolicy policy) {
        NodeId node = policy.node();
        TagRequest request = call.request;
        TagPort port = d.tags().apply(node);
        TagPort.History before = port.history(call.owner);
        Set<String> carrying = carrying(before, request.tag());
        if (!carrying.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The tag " + request.tag() + " exists already for " + call.owner + " (on "
                    + cut(carrying) + "); nothing was sent",
                "Tags are never moved: an existing tag marks a state someone relies on",
                "Choose a new tag name").withNode(node));
        }
        Map<String, Integer> heads = new java.util.TreeMap<>();
        Set<String> missing = new TreeSet<>(request.diagramGroups());
        before.diagrams().forEach((name, diagram) -> {
            if (diagram.type().equals(TECHNICAL)
                && request.diagramGroups().contains(diagram.diagramGroup())) {
                missing.remove(diagram.diagramGroup());
                heads.put(name, head(diagram.versions()));
            }
        });
        if (!missing.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                "The diagram group(s) " + String.join(", ", missing) + " have no technical"
                    + " workflow of " + call.owner + " on " + node + "; nothing was sent",
                "The name is wrong, or the group holds no technical workflow",
                "Check the names with list_inventory").withNode(node));
        }
        String inputs = "sha256:" + sha256(String.join("\n", node.value(), call.owner,
            request.tag(), String.join("\u0000", request.diagramGroups()), request.reason()));
        String state = "sha256:" + sha256(heads.toString());
        boolean server = policy.confirmation() == WritePolicy.Confirmation.SERVER;
        if (server && request.confirmationCode().isEmpty()) {
            return challenge(call, node, policy, inputs, state, heads.size());
        }
        if (server) {
            String previewed = d.challenges().redeem(request.confirmationCode().get(),
                CAPABILITY, node, inputs);
            if (!previewed.equals(state)) {
                throw new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
                    "The diagram groups changed on " + node + " after the preview (new head"
                        + " versions); nothing was sent",
                    "A colleague published or imported meanwhile; the tag would mark another"
                        + " state than the previewed one",
                    "Preview again and check the new state first").withNode(node));
            }
        }
        return execute(call, node, port);
    }

    private Response challenge(Call call, NodeId node, DevelopmentPolicy policy, String inputs,
        String state, int workflows) {
        TagRequest request = call.request;
        WriteChallengeRegistry.Issued issued = d.challenges().issue(CAPABILITY, node, inputs,
            state, policy.confirmationTtl());
        Map<String, String> audited = call.inputs();
        audited.put(AuditRecord.ISSUED_CONFIRMATION_CODE, issued.code());
        append(call, d.ids().get(), AuditRecord.Step.PREVIEW, audited,
            AuditOutcome.CHALLENGE_ISSUED, "Preview of the tag " + request.tag() + ", code"
                + " valid until " + issued.expiresAt(), () -> d.challenges().discard(
                    issued.code()));
        LOG.info("tag_artifacts on {}: CHALLENGE_ISSUED", node);
        return new Response.Challenge(new TagPreview(node, call.owner, request.tag(),
            request.diagramGroups(), workflows, issued.code(), issued.expiresAt(),
            "Nothing was sent. To tag the head versions of " + workflows + " technical"
                + " workflow(s) of " + String.join(", ", request.diagramGroups())
                + " (and their modules) with " + request.tag() + " on " + node + ", show this"
                + " preview to the user and, after their explicit approval, call tag_artifacts"
                + " again with the same inputs and confirmationCode before "
                + issued.expiresAt() + "."));
    }

    private Response execute(Call call, NodeId node, TagPort port) {
        TagRequest request = call.request;
        UUID auditId = d.ids().get();
        append(call, auditId, AuditRecord.Step.EXECUTE, call.inputs(), AuditOutcome.PENDING,
            "About to tag " + request.diagramGroups().size() + " diagram group(s) with "
                + request.tag(), () -> { });
        WriteOutcome.Failure failure;
        List<String> reports = new ArrayList<>();
        int workflows = 0;
        int modules = 0;
        String step = "tag";
        try {
            for (String group : request.diagramGroups()) {
                port.tag(request.tag(), group, call.owner);
            }
            step = "verify";
            Set<String> expectedWorkflows = new TreeSet<>();
            Set<String> expectedModules = new TreeSet<>();
            for (String group : request.diagramGroups()) {
                TagPort.History history = port.history(call.owner, group);
                history.diagrams().forEach((name, diagram) -> {
                    if (diagram.type().equals(TECHNICAL) && diagram.diagramGroup().equals(group)) {
                        expectedWorkflows.add(name);
                    }
                });
                expectedModules.addAll(history.modules().keySet());
            }
            TagPort.History after = port.history(call.owner);
            List<String> problems = mismatches(after, request.tag(), expectedWorkflows,
                expectedModules);
            if (problems.isEmpty()) {
                workflows = expectedWorkflows.size();
                modules = expectedModules.size();
                call.removedAgain = Optional.of(false);
                append(call, auditId, AuditRecord.Step.EXECUTE, call.inputs(),
                    AuditOutcome.EXECUTED, "Tagged " + workflows + " workflow(s) and " + modules
                        + " module(s) with " + request.tag() + " and verified them", null);
                LOG.info("tag_artifacts on {}: EXECUTED (audit id {})", node, auditId);
                return new Response.Completed(new TagOutcome(auditId,
                    WriteOutcome.Outcome.EXECUTED, Optional.empty(), request.tag(),
                    request.diagramGroups(), workflows, modules, false, List.of(), List.of()));
            }
            String report = report("tag-" + auditId + ".txt", problems);
            reports.add(report);
            failure = new WriteOutcome.Failure(ErrorCode.VERIFY_MISMATCH, "verify",
                "The tag is not exactly on the head versions of the requested diagram groups ("
                    + cut(new TreeSet<>(problems.stream().map(p -> p.substring(0,
                        p.indexOf(':'))).toList())) + "); see " + report);
        } catch (ToolErrorException e) {
            failure = new WriteOutcome.Failure(step.equals("tag") ? ErrorCode.IMPORT_FAILED
                : ErrorCode.VERIFY_MISMATCH, step, e.error().code() + ": "
                    + e.error().message());
        } catch (RuntimeException e) {
            LOG.error("tag_artifacts on {} failed unexpectedly at {}", node, step, e);
            failure = new WriteOutcome.Failure(ErrorCode.IMPORT_FAILED, step, "INTERNAL: an"
                + " unexpected failure (" + e.getClass().getSimpleName() + ") after the tag"
                + " was sent");
        }
        boolean removed;
        try {
            port.deleteTag(request.tag(), call.owner);
            removed = true;
        } catch (RuntimeException e) {
            LOG.warn("Removing the tag on {} failed ({})", node, e.getClass().getSimpleName());
            removed = false;
        }
        call.removedAgain = Optional.of(removed);
        WriteOutcome.Failure reported = removed ? failure : new WriteOutcome.Failure(
            failure.code(), failure.step(), failure.message() + "; removing the tag again"
                + " failed: remove " + request.tag() + " in the Workbench");
        append(call, auditId, AuditRecord.Step.EXECUTE, call.inputs(), AuditOutcome.FAILED,
            reported.code() + " at " + reported.step() + ": " + reported.message(), null);
        LOG.warn("tag_artifacts on {}: FAILED at {} (audit id {}, removed again {})", node,
            reported.step(), auditId, removed);
        return new Response.Completed(new TagOutcome(auditId, WriteOutcome.Outcome.FAILED,
            Optional.of(reported), request.tag(), request.diagramGroups(), 0, 0, removed,
            reports, removed ? List.of("The tag " + request.tag() + " was removed again")
                : List.of()));
    }

    /** What is wrong: a tag outside the request, or a requested head without it. */
    private static List<String> mismatches(TagPort.History after, String tag,
        Set<String> workflows, Set<String> modules) {
        List<String> problems = new ArrayList<>();
        after.diagrams().forEach((name, diagram) -> check(problems, name, "diagram "
            + diagram.diagramGroup(), diagram.versions(), tag, workflows.contains(name)));
        after.modules().forEach((name, versions) -> check(problems, name, "module", versions,
            tag, modules.contains(name)));
        workflows.stream().filter(name -> !after.diagrams().containsKey(name))
            .forEach(name -> problems.add(name + ": the workflow is missing in the history"));
        modules.stream().filter(name -> !after.modules().containsKey(name))
            .forEach(name -> problems.add(name + ": the module is missing in the history"));
        return problems;
    }

    private static void check(List<String> problems, String name, String what,
        List<VersionEntry> versions, String tag, boolean requested) {
        boolean anywhere = versions.stream().anyMatch(v -> v.tags().contains(tag));
        boolean onHead = !versions.isEmpty() && versions.get(0).tags().contains(tag);
        if (!requested && anywhere) {
            problems.add(name + ": " + what + " outside the request carries the tag");
        } else if (requested && !onHead) {
            problems.add(name + ": the head version of the " + what + " does not carry the tag");
        } else if (requested && versions.stream().skip(1).anyMatch(v -> v.tags().contains(tag))) {
            problems.add(name + ": an older version of the " + what + " carries the tag");
        }
    }

    private static Set<String> carrying(TagPort.History history, String tag) {
        Set<String> names = new TreeSet<>();
        history.diagrams().forEach((name, diagram) -> {
            if (diagram.versions().stream().anyMatch(v -> v.tags().contains(tag))) {
                names.add(name);
            }
        });
        history.modules().forEach((name, versions) -> {
            if (versions.stream().anyMatch(v -> v.tags().contains(tag))) {
                names.add(name);
            }
        });
        return names;
    }

    private static int head(List<VersionEntry> versions) {
        return versions.isEmpty() ? 0 : versions.get(0).version();
    }

    private static void validate(TagRequest request) {
        if (!REASON.matcher(request.reason()).matches() || request.reason().isBlank()) {
            throw invalid("Invalid reason: it must be 1-500 characters without #, @ and control"
                + " characters", "The reason is audited", "Give a short plain-text reason");
        }
        if (request.confirmationCode().filter(code -> !CODE.matcher(code).matches())
            .isPresent()) {
            throw invalid("Invalid confirmationCode", "A confirmation code has 22 URL-safe"
                + " Base64 chars", "Use the code of the preview exactly as returned");
        }
        if (!NAME.matcher(request.tag()).matches()) {
            throw invalid("Invalid tag: it must match " + NAME.pattern(), "The tag is passed"
                + " to StartCLI in single quotes", "Use letters, digits, _ . - and spaces");
        }
        List<String> groups = request.diagramGroups();
        if (groups.isEmpty() || groups.size() > MAX_GROUPS) {
            throw invalid("Give 1 to " + MAX_GROUPS + " diagram groups",
                "Without a diagram group StartCLI would tag every diagram of the owner",
                "Name the diagram groups of the release");
        }
        for (String group : groups) {
            if (group == null || group.isBlank() || !NAME.matcher(group).matches()) {
                throw invalid("Every diagram group must be a non-blank name matching "
                        + NAME.pattern() + " (no wildcards)",
                    "A blank or wildcard-like group could tag every diagram of the owner",
                    "Give the exact names of the diagram groups");
            }
        }
        if (new HashSet<>(groups).size() != groups.size()) {
            throw invalid("A diagram group is named twice", "Each group is tagged once",
                "Name each diagram group once");
        }
    }

    private void append(Call call, UUID auditId, AuditRecord.Step step,
        Map<String, String> inputs, AuditOutcome outcome, String reason, Runnable onFailure) {
        try {
            d.audit().append(call.record(auditId, step, inputs, outcome, reason));
        } catch (RuntimeException e) {
            LOG.error("Audit record of tag_artifacts could not be written ({})",
                e.getClass().getSimpleName());
            if (onFailure == null) {
                return;
            }
            onFailure.run();
            call.refused = true;
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; nothing was sent",
                "The audit directory is not writable, full, or not owned by this user",
                "Fix the audit directory (auditDirectory in the configuration), then retry"));
        }
    }

    private String report(String name, List<String> lines) {
        Path file = d.root().resolve(REPORTS).resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return REPORTS + "/" + name;
    }

    private static String cut(Set<String> names) {
        List<String> list = List.copyOf(names);
        return list.size() <= 10 ? String.join(", ", list)
            : String.join(", ", list.subList(0, 10)) + ", …+" + (list.size() - 10);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolErrorException invalid(String message, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message
            + "; nothing was sent", likelyCause, nextStep));
    }

    /** One call: its request, what is known so far and the refusal path. */
    private final class Call {

        final TagRequest request;
        DevelopmentPolicy policy;
        String owner;
        Optional<Boolean> removedAgain = Optional.empty();
        boolean refused;

        Call(TagRequest request) {
            this.request = request;
        }

        /** The sanitized inputs, in a fixed order; never content or secrets. */
        Map<String, String> inputs() {
            Map<String, String> inputs = new LinkedHashMap<>();
            inputs.put("reason", bounded(request.reason()));
            inputs.put("tag", bounded(request.tag()));
            inputs.put("scope", bounded("diagram groups " + String.join(", ",
                request.diagramGroups().stream().map(String::valueOf).toList())));
            if (owner != null) {
                inputs.put("owner", bounded(owner));
            }
            request.confirmationCode().ifPresent(code ->
                inputs.put(AuditRecord.CONFIRMATION_CODE, code));
            removedAgain.ifPresent(removed -> inputs.put("removedAgain",
                String.valueOf(removed)));
            return inputs;
        }

        AuditRecord record(UUID auditId, AuditRecord.Step step, Map<String, String> inputs,
            AuditOutcome outcome, String reason) {
            Optional<NodeId> node = Optional.ofNullable(policy).map(DevelopmentPolicy::node);
            return new AuditRecord(auditId, d.clock().instant(), d.profile(),
                node.map(NodeId::value).orElse(bounded(request.node())), group(),
                CAPABILITY.toolName(), step, inputs, node.map(id -> d.accounts().apply(id)
                    .user()), outcome, Optional.of(bounded(reason)), request.mcpClient());
        }

        /** Audits {@code error} as {@code REFUSED}; discards a presented code. */
        ToolErrorException refuse(ToolError error) {
            refused = true;
            request.confirmationCode().ifPresent(d.challenges()::discard);
            boolean execute = request.confirmationCode().isPresent() || (policy != null
                && policy.confirmation() == WritePolicy.Confirmation.CLIENT);
            try {
                d.audit().append(record(d.ids().get(), execute ? AuditRecord.Step.EXECUTE
                    : AuditRecord.Step.PREVIEW, inputs(), AuditOutcome.REFUSED,
                    error.code() + ": " + error.message()));
            } catch (RuntimeException e) {
                LOG.error("Audit record of a refusal ({}) could not be written ({})",
                    error.code(), e.getClass().getSimpleName());
                return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                    "The audit record could not be written; the call was refused anyway ("
                        + error.code() + ": " + error.message() + ") and nothing was sent",
                    "The audit directory is not writable, full, or not owned by this user",
                    "Fix the audit directory (auditDirectory in the configuration), then"
                        + " retry"));
            }
            LOG.info("tag_artifacts on {}: REFUSED ({})", bounded(request.node()),
                error.code());
            return new ToolErrorException(error);
        }

        private Optional<String> group() {
            try {
                return Optional.of(switch (Target.parse(request.node())) {
                    case Target.Group group -> group.id().value();
                    case Target.Node node -> node.id().group().value();
                });
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        private static String bounded(String value) {
            return value.length() <= MAX_AUDITED_INPUT ? value
                : value.substring(0, MAX_AUDITED_INPUT);
        }
    }
}
