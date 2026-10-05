package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.ModuleEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T126 / F1: which technical workflows use which module, derived from the module nodes of the
 * owner's technical workflows ({@code modelByName}), read with bounded concurrency within a
 * budget. Connector modules additionally name their workflow in the module export.
 */
@Timeout(30)
class ModuleUsageTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final Duration BUDGET = Duration.ofSeconds(10);

    private static ModuleRef node(String name, String type) {
        return new ModuleRef(name, type, "1");
    }

    private static ModuleEntry module(String name, String plugin, String connectorWorkflow) {
        return new ModuleEntry(InventoryItem.module(DEV, name, plugin, plugin, "OWNERS",
            Optional.of(true), Optional.of(Instant.parse("2025-01-01T00:00:00Z"))),
            Optional.empty(), Optional.empty(), new ConnectorFlags(false, false, false),
            Optional.ofNullable(connectorWorkflow));
    }

    private static Function<String, List<ModuleRef>> nodes(Map<String, List<ModuleRef>> data) {
        return workflow -> {
            List<ModuleRef> refs = data.get(workflow);
            if (refs == null) {
                throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND, "no " + workflow,
                    "c", "s").withNode(DEV));
            }
            return refs;
        };
    }

    @Test
    void aModuleIsUsedByEveryWorkflowWhoseNodesNameItSortedAndJoinedWithItsConnectorWorkflow() {
        ModuleUsage usage = ModuleUsageIndexer.build(DEV, List.of("W-b", "W-a", "W-c"),
            nodes(Map.of(
                "W-a", List.of(node("Map", "twXSLTConverter"), node("Map", "twXSLTConverter")),
                "W-b", List.of(node("Map", "twXSLTConverter"), node("Out", "twHTTPConnector")),
                "W-c", List.of())),
            8, BUDGET);

        assertThat(usage.complete()).isTrue();
        assertThat(usage.workflowsRead()).isEqualTo(3);
        assertThat(usage.workflowsTotal()).isEqualTo(3);
        assertThat(usage.workflowsOf(module("Map", "XSLT Converter", null), false))
            .containsExactly("W-a", "W-b");
        assertThat(usage.workflowsOf(module("Out", "HTTP Connector", "W-z"), false))
            .as("union with the connector's WorkflowName").containsExactly("W-b", "W-z");
        assertThat(usage.workflowsOf(module("Unused", "XSLT Converter", null), false))
            .isEmpty();
    }

    @Test
    void aModuleNameSharedByTwoModulesIsResolvedByTheNodeType() {
        ModuleUsage usage = ModuleUsageIndexer.build(DEV, List.of("W-1", "W-2"),
            nodes(Map.of(
                "W-1", List.of(node("Same", "twXSLTConverter")),
                "W-2", List.of(node("Same", "twHTTPConnector")))),
            8, BUDGET);

        assertThat(usage.workflowsOf(module("Same", "XSLT Converter", null), true))
            .containsExactly("W-1");
        assertThat(usage.workflowsOf(module("Same", "HTTP Connector", null), true))
            .containsExactly("W-2");
        assertThat(usage.workflowsOf(module("Same", "Assign", null), true)).isEmpty();
    }

    @Test
    void atMostTheGivenNumberOfWorkflowsIsReadAtOnce() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<String> workflows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            workflows.add("W-" + i);
        }

        ModuleUsage usage = ModuleUsageIndexer.build(DEV, workflows, workflow -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return List.of(node("M", "twAssign"));
        }, 4, BUDGET);

        assertThat(usage.complete()).isTrue();
        assertThat(peak.get()).isBetween(2, 4);
        assertThat(usage.workflowsOf(module("M", "Assign", null), false)).hasSize(40);
    }

    @Test
    void aWorkflowThatCannotBeReadMakesTheIndexIncompleteWithTheFirstFailure() {
        ModuleUsage usage = ModuleUsageIndexer.build(DEV, List.of("W-ok", "W-gone", "W-down"),
            workflow -> switch (workflow) {
                case "W-ok" -> List.of(node("M", "twAssign"));
                case "W-gone" -> throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                    "gone", "c", "s").withNode(DEV));
                default -> throw new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
                    "down", "c", "s").withNode(DEV));
            }, 8, BUDGET);

        assertThat(usage.complete()).isFalse();
        assertThat(usage.workflowsRead()).isEqualTo(1);
        assertThat(usage.workflowsTotal()).isEqualTo(3);
        ToolError failure = usage.failure().orElseThrow();
        assertThat(failure.code()).as("the first failure in list order")
            .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(failure.node()).contains(DEV);
        assertThat(failure.message()).contains("2 of 3", "gone");
        assertThat(usage.workflowsOf(module("M", "Assign", null), false))
            .containsExactly("W-ok");
    }

    @Test
    void workflowsNotReadWithinTheBudgetAreStoppedAndMakeTheIndexIncomplete() {
        long start = System.nanoTime();
        ModuleUsage usage = ModuleUsageIndexer.build(DEV, List.of("W-fast", "W-slow"),
            workflow -> {
                if (workflow.equals("W-slow")) {
                    try {
                        Thread.sleep(20_000);
                    } catch (InterruptedException e) {
                        throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                            "interrupted", "c", "s"));
                    }
                }
                return List.of(node("M", "twAssign"));
            }, 8, Duration.ofMillis(300));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        assertThat(usage.complete()).isFalse();
        assertThat(usage.workflowsRead()).isEqualTo(1);
        assertThat(usage.failure().orElseThrow().code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(usage.failure().orElseThrow().nextStep()).contains("refresh");
    }

    // --- follow-up N1: account-lockout protection -----------------------------------------

    @Test
    void theFirstWorkflowIsReadAloneBeforeTheOthersAreStarted() {
        AtomicInteger startedBeforeFirstDone = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean firstDone =
            new java.util.concurrent.atomic.AtomicBoolean();
        List<String> workflows = List.of("W-0", "W-1", "W-2", "W-3", "W-4");

        ModuleUsage usage = ModuleUsageIndexer.build(DEV, workflows, workflow -> {
            if (workflow.equals("W-0")) {
                sleep(150);
                firstDone.set(true);
            } else if (!firstDone.get()) {
                startedBeforeFirstDone.incrementAndGet();
            }
            return List.of(node("M", "twAssign"));
        }, 8, BUDGET);

        assertThat(usage.complete()).isTrue();
        assertThat(startedBeforeFirstDone).as("no read starts before the first has succeeded")
            .hasValue(0);
    }

    @Test
    void aRejectedLoginOnTheFirstWorkflowStopsTheIndexWithoutFurtherReads() {
        AtomicInteger calls = new AtomicInteger();

        ModuleUsage usage = ModuleUsageIndexer.build(DEV, List.of("W-0", "W-1", "W-2", "W-3"),
            workflow -> {
                calls.incrementAndGet();
                throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED, "401", "c", "s")
                    .withNode(DEV));
            }, 8, BUDGET);

        assertThat(calls).as("one failed login, not one per workflow").hasValue(1);
        assertThat(usage.complete()).isFalse();
        assertThat(usage.workflowsRead()).isZero();
        assertThat(usage.failure().orElseThrow().code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(usage.failure().orElseThrow().message()).contains("4 of 4");
    }

    @Test
    void afterARejectedLoginNoFurtherReadIsStarted() {
        AtomicInteger calls = new AtomicInteger();
        List<String> workflows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            workflows.add("W-" + i);
        }

        ModuleUsage usage = ModuleUsageIndexer.build(DEV, workflows, workflow -> {
            calls.incrementAndGet();
            if (workflow.equals("W-1")) {
                throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED, "401", "c", "s")
                    .withNode(DEV));
            }
            return List.of(node("M", "twAssign"));
        }, 1, BUDGET);

        assertThat(calls).as("W-0 (probe) and W-1 (rejected), nothing after").hasValue(2);
        assertThat(usage.workflowsRead()).isEqualTo(1);
        assertThat(usage.failure().orElseThrow().code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(usage.failure().orElseThrow().message()).contains("9 of 10");
    }

    // --- follow-up N4: reads that completed before the budget count --------------------------

    @Test
    void readsCompletedBeforeTheBudgetCountEvenAfterAnEarlierWorkflowTimedOut() {
        ModuleUsage usage = ModuleUsageIndexer.build(DEV, List.of("W-0", "W-slow", "W-2"),
            workflow -> {
                if (workflow.equals("W-slow")) {
                    sleep(20_000);
                }
                return List.of(node("M", "twAssign"));
            }, 8, Duration.ofMillis(400));

        assertThat(usage.workflowsRead()).isEqualTo(2);
        assertThat(usage.failure().orElseThrow().code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(usage.failure().orElseThrow().message()).contains("1 of 3");
        assertThat(usage.workflowsOf(module("M", "Assign", null), false))
            .containsExactly("W-0", "W-2");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, "interrupted", "c",
                "s"));
        }
    }

    @Test
    void anUnavailableIndexKnowsOnlyTheConnectorWorkflows() {
        ModuleUsage usage = ModuleUsage.unavailable(ToolError.of(ErrorCode.UNREACHABLE, "down",
            "c", "s").withNode(DEV));

        assertThat(usage.complete()).isFalse();
        assertThat(usage.workflowsOf(module("Out", "HTTP Connector", "W-z"), false))
            .containsExactly("W-z");
        assertThat(usage.failure().orElseThrow().code()).isEqualTo(ErrorCode.UNREACHABLE);
    }

    @Test
    void aCallerInterruptedWhileBuildingGetsATimeout() throws Exception {
        Thread caller = Thread.currentThread();
        Thread interrupter = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            caller.interrupt();
        });

        assertThatThrownBy(() -> ModuleUsageIndexer.build(DEV, List.of("W"), workflow -> {
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }, 8, BUDGET)).isInstanceOfSatisfying(ToolErrorException.class, e ->
            assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
        assertThat(Thread.interrupted()).as("the interrupt is kept").isTrue();
        interrupter.join();
    }
}
