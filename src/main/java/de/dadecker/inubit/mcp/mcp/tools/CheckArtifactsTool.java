package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ArtifactCheckService;
import de.dadecker.inubit.mcp.application.ArtifactCheckService.CheckOutcome;
import de.dadecker.inubit.mcp.application.ArtifactCheckService.CheckRequest;
import de.dadecker.inubit.mcp.application.ArtifactCheckService.XsltCheck;
import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import de.dadecker.inubit.mcp.domain.model.CheckReport;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.XsltRun.Outcome;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
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
        Optional<XsltCheck> xslt = Optional.empty();
        if (arguments.get("xslt") instanceof Map<?, ?> run) {
            Map<String, String> params = new LinkedHashMap<>();
            if (run.get("params") instanceof Map<?, ?> values) {
                values.forEach((name, value) -> params.put(String.valueOf(name),
                    String.valueOf(value)));
            }
            Optional<Instant> now;
            try {
                now = Optional.ofNullable(run.get("now")).map(String::valueOf)
                    .map(Instant::parse);
            } catch (DateTimeParseException e) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "xslt.now must be an ISO-8601 instant, e.g. 2026-10-06T12:00:00Z",
                    "The value is not a date-time with a zone",
                    "Give the time in UTC with a trailing Z"));
            }
            xslt = Optional.of(new XsltCheck(String.valueOf(run.get("stylesheet")),
                String.valueOf(run.get("input")), params, now));
        }
        return Result.of(checks.check(new CheckRequest(args.strings("paths"), xslt,
            args.optionalString("schema"),
            !Boolean.FALSE.equals(arguments.get("verifyOnServer")))));
    }

    /** The result of contracts/mcp-tools-delta.md. */
    record Result(Map<Severity, Integer> counts, List<CheckFinding> findings,
        Optional<Xslt> xslt, boolean truncated, Optional<String> fullReport) {

        /** The {@code xslt} block. */
        record Xslt(Outcome outcome, Optional<String> output, List<String> standInsUsed) {
        }

        static Result of(CheckOutcome outcome) {
            CheckReport report = outcome.report();
            return new Result(report.counts(), report.findings(), outcome.xslt().map(run ->
                new Xslt(run.outcome(), run.output(), run.standInsUsed())), report.truncated(),
                report.fullReport());
        }
    }
}
