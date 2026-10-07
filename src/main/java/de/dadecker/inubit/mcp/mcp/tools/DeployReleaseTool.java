package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.DeployGuard;
import de.dadecker.inubit.mcp.application.DeployService;
import de.dadecker.inubit.mcp.domain.model.ArtifactClass;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.NodePlan;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@code deploy_release} (feature 005, contracts/mcp-tools-delta.md, US1–US5): deploys a
 * release — every diagram group that carries a tag on the source — into one target group of the
 * stage chain through {@link DeployService}, which enforces the whole sequence. Without
 * {@code confirmationCode} the output is {@code {"challenge": …}} (always a preview; the
 * confirmation cannot be turned off), with it {@code {"result": …}}. Refusals before anything
 * is sent are tool errors.
 *
 * <p>Lists are bounded by {@code resultLimits.maxItems} (review m5): a longer list keeps its
 * first items and carries {@code <list>Truncated} with the number left out; the files named in
 * the result hold everything. Nodes are never cut.
 *
 * <p>Registered only if at least one group has {@code deploy} (FR-006).
 */
public final class DeployReleaseTool implements ToolHandler {

    static final String DESCRIPTION = "Deploy a release — every diagram group that carries the"
        + " given tag on the source {group} — into ONE target {group}, {node} by {node}. The"
        + " source is always the configured predecessor of the target. The first call returns a"
        + " preview per {node} (new, changed, layout-only, unchanged, excluded, warnings) and a"
        + " confirmation code; the call with the code backs up, imports only what changed with"
        + " the own secrets of each {node}, verifies, rolls back a failing {node} and stops"
        + " there, and tags the deployed groups. For a package-only target the call with the code writes"
        + " import packages instead of importing.";

    private final DeployService service;
    private final int maxItems;

    /** @param maxItems {@code resultLimits.maxItems} */
    public DeployReleaseTool(DeployService service, int maxItems) {
        this.service = Objects.requireNonNull(service, "service");
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be positive");
        }
        this.maxItems = maxItems;
    }

    @Override
    public String name() {
        return "deploy_release";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Destructive, non-idempotent, open world (FR-025, Constitution I). */
    @Override
    public ToolHints annotations() {
        return ToolHints.destructive("Deploy a release into the next group of the stage chain");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        DeployService.Response response = service.deploy(new DeployGuard.Request(
            args.string("target"), args.string("tag"), args.optionalString("owner"),
            args.optionalString("confirmationCode"), context.client()));
        if (response.preview().isPresent()) {
            return Map.of("challenge", challenge(response.preview().get()));
        }
        return Map.of("result", result(response.result().orElseThrow()));
    }

    private Map<String, Object> challenge(DeploymentPreview preview) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("auditId", preview.auditId().toString());
        out.put("target", preview.target().value());
        out.put("source", preview.source().value());
        out.put("tag", preview.tag());
        out.put("owner", preview.owner());
        out.put("mode", preview.mode().name());
        out.put("diagramGroups", preview.diagramGroups());
        bounded(out, "olderThanHead", preview.olderThanHead(), text -> text);
        out.put("nodes", preview.plans().stream().map(this::plan).toList());
        out.put("notes", preview.notes());
        out.put("executable", preview.executable());
        preview.confirmationCode().ifPresent(code -> out.put("confirmationCode", code));
        preview.expiresAt().ifPresent(at -> out.put("expiresAt", at.toString()));
        out.put("message", preview.instruction());
        return out;
    }

    private Map<String, Object> plan(NodePlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", plan.node().value());
        Map<ArtifactClass, Integer> counts = plan.counts();
        Map<String, Object> countMap = new LinkedHashMap<>();
        countMap.put("new", counts.get(ArtifactClass.NEW));
        countMap.put("changed", counts.get(ArtifactClass.CHANGED));
        countMap.put("layoutOnly", counts.get(ArtifactClass.LAYOUT_ONLY));
        countMap.put("unchanged", counts.get(ArtifactClass.UNCHANGED));
        countMap.put("excluded", counts.get(ArtifactClass.EXCLUDED));
        countMap.put("onlyOnTarget", counts.get(ArtifactClass.ONLY_ON_TARGET));
        out.put("counts", countMap);
        bounded(out, "activeFlags", plan.artifacts().stream().filter(a -> a.active()
            .isPresent()).toList(), a -> Map.of("workflow", a.name(), "active",
                a.active().get(), "kept", a.kept()));
        bounded(out, "warnings", plan.warnings(), w -> Map.of("kind", w.kind().name(),
            "artifact", w.artifact(), "detail", w.detail()));
        bounded(out, "errors", plan.errors(), e -> Map.of("code", e.code().name(),
            "artifact", e.artifact(), "message", e.message()));
        out.put("executable", plan.executable());
        out.put("diff", plan.diffFile());
        out.put("summary", plan.summaryFile());
        return out;
    }

    private Map<String, Object> result(DeploymentResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("auditId", result.auditId().toString());
        out.put("outcome", result.outcome().name());
        out.put("target", result.target().value());
        out.put("source", result.source().value());
        out.put("tag", result.tag());
        out.put("nodes", result.nodes().stream().map(this::node).toList());
        result.commit().ifPresent(commit -> out.put("commit", commit));
        out.put("reports", result.reports());
        bounded(out, "warnings", result.warnings(), text -> text);
        return out;
    }

    private Map<String, Object> node(DeploymentResult.NodeOutcome node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node.node().value());
        out.put("state", node.state().name());
        node.backupRef().ifPresent(ref -> out.put("backupRef", ref));
        bounded(out, "imported", node.imported(), text -> text);
        bounded(out, "created", node.created(), text -> text);
        node.tag().ifPresent(tag -> {
            Map<String, Object> tagMap = new LinkedHashMap<>();
            tagMap.put("applied", tag.applied());
            tagMap.put("workflows", tag.workflows());
            tagMap.put("modules", tag.modules());
            tag.failure().ifPresent(failure -> tagMap.put("failure", failure(failure)));
            out.put("tag", tagMap);
        });
        node.failure().ifPresent(failure -> out.put("failure", failure(failure)));
        node.packageDir().ifPresent(dir -> out.put("package", dir));
        return out;
    }

    private static Map<String, Object> failure(WriteOutcome.Failure failure) {
        return Map.of("code", failure.code().name(), "step", failure.step(), "message",
            failure.message());
    }

    /** {@code name} with at most {@link #maxItems} items, and {@code nameTruncated}. */
    private <T> void bounded(Map<String, Object> out, String name, List<T> items,
        Function<? super T, ?> map) {
        out.put(name, items.stream().limit(maxItems).map(map).toList());
        if (items.size() > maxItems) {
            out.put(name + "Truncated", items.size() - maxItems);
        }
    }
}
