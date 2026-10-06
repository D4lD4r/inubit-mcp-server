package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ArtifactCheckService;
import de.dadecker.inubit.mcp.application.ArtifactCheckService.CheckRequest;
import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import de.dadecker.inubit.mcp.domain.model.CheckReport;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code check_artifacts} (contracts/mcp-tools-delta.md, US3–US5): checks workspace files offline
 * ({@link ArtifactCheckService#check}); only the module lookups read from INUBIT. Read-only and
 * idempotent: it writes nothing but test outputs and reports. Always offered.
 */
public final class CheckArtifactsTool implements ToolHandler {

    static final String DESCRIPTION = "Check workspace files before an import: workflow"
        + " structure (edges, ids, branch conditions, referenced modules, variables, repository"
        + " references), run a stylesheet against an input file, or validate XML against a"
        + " schema. Never changes INUBIT; writes only test outputs.";

    private final ArtifactCheckService checks;

    public CheckArtifactsTool(ArtifactCheckService checks) {
        this.checks = Objects.requireNonNull(checks, "checks");
    }

    @Override
    public String name() {
        return "check_artifacts";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Check workspace artifacts", true, true);
    }

    /** The arguments are validated by the SDK against {@code check_artifacts.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        return Result.of(checks.check(new CheckRequest(args.strings("paths"),
            !Boolean.FALSE.equals(arguments.get("verifyOnServer")))));
    }

    /** The result of contracts/mcp-tools-delta.md. */
    record Result(Map<Severity, Integer> counts, List<CheckFinding> findings, boolean truncated,
        Optional<String> fullReport) {

        static Result of(CheckReport report) {
            return new Result(report.counts(), report.findings(), report.truncated(),
                report.fullReport());
        }
    }
}
