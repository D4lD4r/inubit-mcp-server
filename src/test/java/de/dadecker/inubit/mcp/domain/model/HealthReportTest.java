package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.infra.ResultJson;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HealthReportTest {

    private static final NodeId NODE = NodeId.of("dev", "node1");
    private static final Instant CHECKED_AT = Instant.parse("2026-11-02T09:00:00Z");

    /** An 8.1 report with every part as {@code get_health} reports it today. */
    private static HealthReport report81() {
        return new HealthReport(NODE, NODE.group(), CHECKED_AT, true, HealthStatus.OK,
            Optional.of(true), Optional.of("Ready to serve."), Optional.of(false),
            Optional.of("8.1.17"), Optional.empty(),
            Optional.of(LoadFigures.of(5500, 3716, 9216, 0, 999_999, 401, 0, 200_000)),
            Optional.empty(),
            Optional.of(new HealthReport.Client(Optional.of("8.1.17"), Optional.of("4.0.1"),
                Optional.of("8.1.17"), Optional.of("match"), Optional.empty())),
            List.of(new UnavailablePart(UnavailablePart.SYSTEM_INFO, "TIMEOUT: too slow",
                Optional.of("the node is busy"), Optional.of("try again"))),
            Optional.empty(), List.of("a warning"));
    }

    @Test
    void an81ReportSerializesAsBefore() {
        assertThat(ResultJson.mapper().writeValueAsString(report81())).isEqualTo("{"
            + "\"node\":\"dev/node1\",\"group\":\"dev\",\"checkedAt\":\"2026-11-02T09:00:00Z\","
            + "\"reachable\":true,\"status\":\"OK\",\"ready\":true,"
            + "\"readyMessage\":\"Ready to serve.\",\"maintenanceMode\":false,"
            + "\"version\":\"8.1.17\","
            + "\"load\":{\"usedMemoryMb\":5500.0,\"freeMemoryMb\":3716.0,\"maxMemoryMb\":9216.0,"
            + "\"memoryUsedPercent\":59.7,\"threadsInUse\":0,\"licensedThreads\":999999,"
            + "\"maxThreads\":401,\"blockingQueueEntries\":0,\"maxBlockingQueueSize\":200000,"
            + "\"blockingQueuePercent\":0.0},"
            + "\"client\":{\"version\":\"8.1.17\",\"supportedFrom\":\"4.0.1\","
            + "\"supportedTo\":\"8.1.17\",\"comparison\":\"match\"},"
            + "\"unavailable\":[{\"part\":\"systemInfo\",\"reason\":\"TIMEOUT: too slow\","
            + "\"likelyCause\":\"the node is busy\",\"nextStep\":\"try again\"}],"
            + "\"warnings\":[\"a warning\"]}");
    }

    @Test
    void a61ReportCarriesProcessCountsAndOnlyTheMemoryFigures() {
        HealthReport report = new HealthReport(NODE, NODE.group(), CHECKED_AT, true,
            HealthStatus.OK, Optional.empty(), Optional.empty(), Optional.of(false),
            Optional.of("6.1.0.89"), Optional.empty(),
            Optional.of(LoadFigures.of(new MemoryFigures(1210, 838, 2048))),
            Optional.of(new ProcessCounts(3, 0, 12, 0)), Optional.empty(),
            List.of(new UnavailablePart(UnavailablePart.READY,
                    "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 has no readiness check"),
                new UnavailablePart(UnavailablePart.THREADS,
                    "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no thread figures"),
                new UnavailablePart(UnavailablePart.BLOCKING_QUEUE,
                    "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no blocking queue")),
            Optional.empty(), List.of());

        assertThat(ResultJson.mapper().writeValueAsString(report)).isEqualTo("{"
            + "\"node\":\"dev/node1\",\"group\":\"dev\",\"checkedAt\":\"2026-11-02T09:00:00Z\","
            + "\"reachable\":true,\"status\":\"OK\",\"maintenanceMode\":false,"
            + "\"version\":\"6.1.0.89\","
            + "\"load\":{\"usedMemoryMb\":1210.0,\"freeMemoryMb\":838.0,\"maxMemoryMb\":2048.0,"
            + "\"memoryUsedPercent\":59.1},"
            + "\"processCounts\":{\"error\":3,\"processing\":0,\"waiting\":12,\"queued\":0},"
            + "\"unavailable\":["
            + "{\"part\":\"ready\","
            + "\"reason\":\"NOT_AVAILABLE_ON_6_1: INUBIT 6.1 has no readiness check\"},"
            + "{\"part\":\"threads\","
            + "\"reason\":\"NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no thread figures\"},"
            + "{\"part\":\"blockingQueue\","
            + "\"reason\":\"NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no blocking queue\"}],"
            + "\"warnings\":[]}");
    }

    @Test
    void aMissingProcessCountsBecomesEmpty() {
        HealthReport report = new HealthReport(NODE, NODE.group(), CHECKED_AT, true,
            HealthStatus.OK, null, null, null, null, null, null, null, null, List.of(), null,
            List.of());

        assertThat(report.processCounts()).isEmpty();
    }

    @Test
    void memoryOnlyLoadFiguresHaveNoThreadOrQueueFigures() {
        LoadFigures load = LoadFigures.of(new MemoryFigures(1210, 838, 2048));

        assertThat(load.usedMemoryMb()).isEqualTo(1210.0);
        assertThat(load.freeMemoryMb()).isEqualTo(838.0);
        assertThat(load.maxMemoryMb()).isEqualTo(2048.0);
        assertThat(load.memoryUsedPercent()).contains(59.1);
        assertThat(load.threadsInUse()).isEmpty();
        assertThat(load.licensedThreads()).isEmpty();
        assertThat(load.maxThreads()).isEmpty();
        assertThat(load.blockingQueueEntries()).isEmpty();
        assertThat(load.maxBlockingQueueSize()).isEmpty();
        assertThat(load.blockingQueuePercent()).isEmpty();
    }

    @Test
    void the81LoadFiguresKeepEveryThreadAndQueueFigure() {
        LoadFigures load = LoadFigures.of(5500, 3716, 9216, 0, 999_999, 401, 50_000, 200_000);

        assertThat(load.threadsInUse()).contains(0L);
        assertThat(load.licensedThreads()).contains(999_999L);
        assertThat(load.maxThreads()).contains(401L);
        assertThat(load.blockingQueueEntries()).contains(50_000L);
        assertThat(load.maxBlockingQueueSize()).contains(200_000L);
        assertThat(load.blockingQueuePercent()).contains(25.0);
    }

    @Test
    void memoryUsedPercentIsRoundedToOneDecimal() {
        assertThat(new MemoryFigures(1210, 838, 2048).usedPercent()).contains(59.1);
        assertThat(new MemoryFigures(2048, 0, 2048).usedPercent()).contains(100.0);
    }

    @Test
    void memoryWithoutAPositiveMaximumHasNoPercentage() {
        assertThat(new MemoryFigures(0, 0, 0).usedPercent()).isEmpty();
    }

    @Test
    void negativeMemoryFiguresAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MemoryFigures(-1, 0, 2048));
        assertThatIllegalArgumentException().isThrownBy(() -> new MemoryFigures(0, -1, 2048));
        assertThatIllegalArgumentException().isThrownBy(() -> new MemoryFigures(0, 0, -1));
    }

    @Test
    void negativeProcessCountsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProcessCounts(-1, 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProcessCounts(0, -1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProcessCounts(0, 0, -1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProcessCounts(0, 0, 0, -1));
    }
}
