package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Feature 024 (T043, data-model "LeaseSummary", contracts {@code $defs/LeaseSummary}): the view of
 * an open lease in {@code get_health} and the summary.
 */
class LeaseSummaryTest {

    private static final UUID LEASE_ID = UUID.fromString("3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01");
    private static final Instant SET_AT = Instant.parse("2026-11-12T08:15:31Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-12T08:25:31Z");
    private static final LoggerName ORDERS = new LoggerName("com.inubit.ibis.acme.Orders");
    private static final LoggerName INVOICES = new LoggerName("com.inubit.ibis.acme.Invoices");
    private static final LeaseAttempt UNREACHABLE = new LeaseAttempt(
        Instant.parse("2026-11-12T08:26:01Z"), AuditOutcome.FAILED,
        Optional.of(ErrorCode.UNREACHABLE), Optional.of(LeaseReason.NODE_UNREACHABLE));

    private static Lease active() {
        TreeMap<LoggerName, TemporaryLevel> loggers =
            new TreeMap<>((a, b) -> a.value().compareTo(b.value()));
        loggers.put(ORDERS, TemporaryLevel.DEBUG);
        loggers.put(INVOICES, TemporaryLevel.TRACE);
        return new Lease(LEASE_ID, 2, NodeId.of("test", "node1"), "node1", loggers, SET_AT,
            EXPIRES_AT, new LeaseOwner(4711, Optional.empty()), LeaseState.ACTIVE,
            UUID.fromString("8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"), Optional.empty(),
            Optional.empty());
    }

    @Test
    void ofTakesTheVisibleFieldsOfAnOpenLease() {
        Lease failed = active().failed(UNREACHABLE);

        LeaseSummary summary = LeaseSummary.of(failed);

        assertThat(summary.leaseId()).isEqualTo(LEASE_ID);
        assertThat(summary.inubitNode()).isEqualTo("node1");
        assertThat(summary.loggers()).containsExactly(
            new LeaseSummary.Logger(INVOICES, TemporaryLevel.TRACE),
            new LeaseSummary.Logger(ORDERS, TemporaryLevel.DEBUG));
        assertThat(summary.setAt()).isEqualTo(SET_AT);
        assertThat(summary.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(summary.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(summary.lastAttempt()).contains(UNREACHABLE);
    }

    @Test
    void hasNoOwnerAndNoGeneration() {
        assertThat(Arrays.stream(LeaseSummary.class.getRecordComponents())
            .map(component -> component.getName()))
            .containsExactly("leaseId", "inubitNode", "loggers", "setAt", "expiresAt", "state",
                "lastAttempt");
    }

    @Test
    void refusesAnEndedLease() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> LeaseSummary.of(active().reset(EXPIRES_AT)));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> LeaseSummary.of(active().forgotten(EXPIRES_AT)));
    }

    @Test
    void refusesAnInvalidNodeNameOrNoLoggers() {
        List<LeaseSummary.Logger> one = List.of(new LeaseSummary.Logger(ORDERS, TemporaryLevel.DEBUG));

        assertThatIllegalArgumentException().isThrownBy(() -> new LeaseSummary(LEASE_ID, "node 1",
            one, SET_AT, EXPIRES_AT, LeaseState.ACTIVE, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new LeaseSummary(LEASE_ID, "node1",
            List.of(), SET_AT, EXPIRES_AT, LeaseState.ACTIVE, Optional.empty()));
        assertThatThrownBy(() -> new LeaseSummary(null, "node1", one, SET_AT, EXPIRES_AT,
            LeaseState.ACTIVE, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LeaseSummary.Logger(ORDERS, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sortsTheLoggersByName() {
        LeaseSummary summary = new LeaseSummary(LEASE_ID, "node1",
            List.of(new LeaseSummary.Logger(ORDERS, TemporaryLevel.DEBUG),
                new LeaseSummary.Logger(INVOICES, TemporaryLevel.TRACE)),
            SET_AT, EXPIRES_AT, LeaseState.ACTIVE, Optional.empty());

        assertThat(summary.loggers()).containsExactly(
            new LeaseSummary.Logger(INVOICES, TemporaryLevel.TRACE),
            new LeaseSummary.Logger(ORDERS, TemporaryLevel.DEBUG));
    }

    @Test
    void copiesTheLoggers() {
        List<LeaseSummary.Logger> loggers = new ArrayList<>(
            List.of(new LeaseSummary.Logger(ORDERS, TemporaryLevel.DEBUG)));

        LeaseSummary summary = new LeaseSummary(LEASE_ID, "node1", loggers, SET_AT, EXPIRES_AT,
            LeaseState.ACTIVE, Optional.empty());
        loggers.clear();

        assertThat(summary.loggers()).hasSize(1);
    }
}
