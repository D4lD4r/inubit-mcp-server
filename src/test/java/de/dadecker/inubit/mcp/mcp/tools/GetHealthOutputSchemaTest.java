package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.HealthReport;
import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.MemoryFigures;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessCounts;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code schemas/get_health.output.json} against the serialized reports: the 8.1 report as before
 * and the 6.1 report of contracts/mcp-tools-delta.md (feature 011, FR-008).
 */
class GetHealthOutputSchemaTest {

    private static final NodeId NODE = NodeId.of("legacy", "node1");
    private static final Instant CHECKED_AT = Instant.parse("2026-11-02T09:00:00Z");

    @Test
    void an81ReportMatchesTheSchema() {
        HealthReport report = new HealthReport(NODE, NODE.group(), CHECKED_AT, true,
            HealthStatus.OK, Optional.of(true), Optional.of("Ready to serve."),
            Optional.of(false), Optional.of("8.1.17"), Optional.empty(),
            Optional.of(LoadFigures.of(5500, 3716, 9216, 0, 999_999, 401, 0, 200_000)),
            Optional.empty(), Optional.empty(), List.of(), Optional.empty(), List.of());

        assertThat(errors(json(report))).isNull();
    }

    @Test
    void a61ReportMatchesTheSchema() {
        assertThat(errors(json(report61()))).isNull();
    }

    @Test
    void processCountsNeedEveryState() {
        Map<String, Object> report = json(report61());
        report.put("processCounts", Map.of("error", 3, "processing", 0, "waiting", 12));

        assertThat(errors(report)).contains("queued");
    }

    @Test
    void processCountsTakeNoOtherState() {
        Map<String, Object> report = json(report61());
        report.put("processCounts",
            Map.of("error", 3, "processing", 0, "waiting", 12, "queued", 0, "active", 1));

        assertThat(errors(report)).contains("active");
    }

    @Test
    void anUnknownUnavailablePartIsRejected() {
        Map<String, Object> report = json(report61());
        report.put("unavailable",
            List.of(Map.of("part", "processCounts", "reason", "TIMEOUT: x")));

        assertThat(errors(report)).isNotNull();
    }

    /** The 6.1 report of contracts/mcp-tools-delta.md. */
    private static HealthReport report61() {
        return new HealthReport(NODE, NODE.group(), CHECKED_AT, true, HealthStatus.OK,
            Optional.empty(), Optional.empty(), Optional.of(false), Optional.of("6.1.0.89"),
            Optional.empty(), Optional.of(LoadFigures.of(new MemoryFigures(1210, 838, 2048))),
            Optional.of(new ProcessCounts(3, 0, 12, 0)),
            Optional.of(new HealthReport.Client(Optional.of("6.1.0"), Optional.of("4.0.1"),
                Optional.of("6.1.0"), Optional.of("match"), Optional.empty())),
            List.of(new UnavailablePart(UnavailablePart.READY,
                    "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 has no readiness check"),
                new UnavailablePart(UnavailablePart.THREADS,
                    "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no thread figures"),
                new UnavailablePart(UnavailablePart.BLOCKING_QUEUE,
                    "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no blocking queue")),
            Optional.empty(), List.of());
    }

    /** {@code report} as the JSON object the tool returns for it, as a mutable map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(HealthReport report) {
        return ResultJson.mapper().convertValue(report, Map.class);
    }

    /** The validation errors of {@code {reports: [report]}}, or {@code null} if it is valid. */
    @SuppressWarnings("unchecked")
    private static String errors(Map<String, Object> report) {
        Map<String, Object> schema = ResultJson.mapper().readValue(SchemaResources.forClasspath()
            .load("schemas/get_health.output.json"), Map.class);
        JsonSchemaValidator.ValidationResponse validation = new DefaultJsonSchemaValidator()
            .validate(schema, Map.of("reports", List.of(report)));
        return validation.valid() ? null : validation.errorMessage();
    }
}
