package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 024 (T043, research D-12 … D-14, data-model "Lease lifecycle"): the temporary log level
 * lease and its transitions.
 */
class LeaseTest {

    private static final UUID LEASE_ID = UUID.fromString("3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01");
    private static final UUID AUDIT_ID = UUID.fromString("8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f");
    private static final UUID OTHER_AUDIT_ID =
        UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    private static final NodeId SERVER = NodeId.of("test", "node1");
    private static final Instant SET_AT = Instant.parse("2026-11-12T08:15:30Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-12T08:25:30Z");
    private static final LeaseOwner OWNER =
        new LeaseOwner(4711, Optional.of(Instant.parse("2026-11-12T07:58:02.114Z")));
    private static final LeaseOwner SETTER = new LeaseOwner(4712, Optional.empty());
    private static final LoggerName ORDERS = new LoggerName("com.inubit.ibis.acme.Orders");
    private static final LoggerName INVOICES = new LoggerName("com.inubit.ibis.acme.Invoices");
    private static final LoggerName SHIPPING = new LoggerName("com.inubit.ibis.acme.Shipping");
    private static final LeaseAttempt UNREACHABLE = new LeaseAttempt(
        Instant.parse("2026-11-12T08:26:01Z"), AuditOutcome.FAILED,
        Optional.of(ErrorCode.UNREACHABLE), Optional.of(LeaseReason.NODE_UNREACHABLE));

    private static SortedMap<LoggerName, TemporaryLevel> loggers(Map<LoggerName, TemporaryLevel> map) {
        TreeMap<LoggerName, TemporaryLevel> sorted =
            new TreeMap<>((a, b) -> a.value().compareTo(b.value()));
        sorted.putAll(map);
        return sorted;
    }

    private static Lease active() {
        return new Lease(LEASE_ID, 1, SERVER, "node1", loggers(Map.of(ORDERS, TemporaryLevel.DEBUG)),
            SET_AT, EXPIRES_AT, OWNER, LeaseState.ACTIVE, AUDIT_ID, Optional.empty(),
            Optional.empty());
    }

    private static Lease resetFailed() {
        return active().failed(UNREACHABLE);
    }

    private static Lease ended(LeaseState state) {
        return state == LeaseState.RESET ? active().reset(EXPIRES_AT)
            : active().forgotten(EXPIRES_AT.plus(Duration.ofDays(7)));
    }

    @Test
    void holdsAllFields() {
        Lease lease = active();

        assertThat(lease.leaseId()).isEqualTo(LEASE_ID);
        assertThat(lease.generation()).isEqualTo(1);
        assertThat(lease.server()).isEqualTo(SERVER);
        assertThat(lease.inubitNode()).isEqualTo("node1");
        assertThat(lease.loggers()).containsExactly(Map.entry(ORDERS, TemporaryLevel.DEBUG));
        assertThat(lease.setAt()).isEqualTo(SET_AT);
        assertThat(lease.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(lease.owner()).isEqualTo(OWNER);
        assertThat(lease.state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(lease.auditId()).isEqualTo(AUDIT_ID);
        assertThat(lease.lastAttempt()).isEmpty();
        assertThat(lease.endedAt()).isEmpty();
    }

    @Test
    void keepsLoggersSortedByNameAndUnmodifiable() {
        Map<LoggerName, TemporaryLevel> unsorted = new HashMap<>();
        unsorted.put(SHIPPING, TemporaryLevel.TRACE);
        unsorted.put(ORDERS, TemporaryLevel.DEBUG);
        unsorted.put(INVOICES, TemporaryLevel.INFO);
        SortedMap<LoggerName, TemporaryLevel> given = loggers(unsorted);

        Lease lease = new Lease(LEASE_ID, 1, SERVER, "node1", given, SET_AT, EXPIRES_AT, OWNER,
            LeaseState.ACTIVE, AUDIT_ID, Optional.empty(), Optional.empty());
        given.clear();

        assertThat(lease.loggers().keySet()).containsExactly(INVOICES, ORDERS, SHIPPING);
        assertThatThrownBy(() -> lease.loggers().put(ORDERS, TemporaryLevel.ERROR))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- merge (D-14 step 8) ---

    @Test
    void mergeUnitesTheLoggersWithTheLastLevelPerLogger() {
        Instant at = SET_AT.plusSeconds(60);

        Lease merged = active().merge(
            Map.of(ORDERS, TemporaryLevel.TRACE, INVOICES, TemporaryLevel.WARN), at,
            Duration.ofMinutes(10), SETTER, OTHER_AUDIT_ID);

        assertThat(merged.loggers()).containsExactly(
            Map.entry(INVOICES, TemporaryLevel.WARN), Map.entry(ORDERS, TemporaryLevel.TRACE));
        assertThat(merged.leaseId()).isEqualTo(LEASE_ID);
        assertThat(merged.server()).isEqualTo(SERVER);
        assertThat(merged.inubitNode()).isEqualTo("node1");
        assertThat(merged.state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(merged.setAt()).isEqualTo(at);
    }

    @Test
    void mergeKeepsTheLaterExpiry() {
        Lease lease = active();

        Lease longer = lease.merge(Map.of(INVOICES, TemporaryLevel.DEBUG), SET_AT.plusSeconds(60),
            Duration.ofMinutes(30), SETTER, OTHER_AUDIT_ID);
        Lease shorter = lease.merge(Map.of(INVOICES, TemporaryLevel.DEBUG), SET_AT.plusSeconds(60),
            Duration.ofMinutes(1), SETTER, OTHER_AUDIT_ID);

        assertThat(longer.expiresAt()).isEqualTo(SET_AT.plusSeconds(60).plus(Duration.ofMinutes(30)));
        assertThat(shorter.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void mergeMakesTheSetterTheOwnerAndRecordsItsAuditId() {
        Lease merged = active().merge(Map.of(INVOICES, TemporaryLevel.DEBUG),
            SET_AT.plusSeconds(60), Duration.ofMinutes(10), SETTER, OTHER_AUDIT_ID);

        assertThat(merged.owner()).isEqualTo(SETTER);
        assertThat(merged.auditId()).isEqualTo(OTHER_AUDIT_ID);
    }

    @Test
    void mergeRefusesASetOutsideOneToFiveLoggers() {
        Lease lease = active();
        Map<LoggerName, TemporaryLevel> six = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            six.put(new LoggerName("com.inubit.ibis.acme.L" + i), TemporaryLevel.DEBUG);
        }

        assertThatIllegalArgumentException().isThrownBy(() -> lease.merge(Map.of(),
            SET_AT, Duration.ofMinutes(10), SETTER, OTHER_AUDIT_ID));
        assertThatIllegalArgumentException().isThrownBy(() -> lease.merge(six,
            SET_AT, Duration.ofMinutes(10), SETTER, OTHER_AUDIT_ID));
    }

    @Test
    void mergeRefusesMoreThanFiftyLoggersInTheUnion() {
        Map<LoggerName, TemporaryLevel> fifty = new HashMap<>();
        for (int i = 0; i < Lease.MAX_LOGGERS; i++) {
            fifty.put(new LoggerName("com.inubit.ibis.acme.L" + i), TemporaryLevel.DEBUG);
        }
        Lease full = new Lease(LEASE_ID, 1, SERVER, "node1", loggers(fifty), SET_AT, EXPIRES_AT,
            OWNER, LeaseState.ACTIVE, AUDIT_ID, Optional.empty(), Optional.empty());

        assertThat(full.merge(Map.of(new LoggerName("com.inubit.ibis.acme.L0"),
            TemporaryLevel.TRACE), SET_AT, Duration.ofMinutes(10), SETTER, OTHER_AUDIT_ID)
            .loggers()).hasSize(Lease.MAX_LOGGERS);
        assertThatIllegalArgumentException().isThrownBy(() -> full.merge(Map.of(ORDERS,
            TemporaryLevel.DEBUG), SET_AT, Duration.ofMinutes(10), SETTER, OTHER_AUDIT_ID));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void mergeRefusesANonPositiveDuration(long minutes) {
        assertThatIllegalArgumentException().isThrownBy(() -> active().merge(
            Map.of(ORDERS, TemporaryLevel.DEBUG), SET_AT, Duration.ofMinutes(minutes), SETTER,
            OTHER_AUDIT_ID));
    }

    // --- executed (D-14 step 10, US4-1) ---

    @Test
    void executedCountsTheExpiryFromTheExecutedTime() {
        Instant executedAt = SET_AT.plusSeconds(4);

        Lease executed = active().executed(executedAt, Duration.ofMinutes(10));

        assertThat(executed.setAt()).isEqualTo(executedAt);
        assertThat(executed.expiresAt()).isEqualTo(executedAt.plus(Duration.ofMinutes(10)));
        assertThat(executed.state()).isEqualTo(LeaseState.ACTIVE);
    }

    @Test
    void executedKeepsTheLaterExpiryOfAMergedLease() {
        Lease merged = active().merge(Map.of(INVOICES, TemporaryLevel.DEBUG),
            SET_AT.plusSeconds(60), Duration.ofMinutes(1), SETTER, OTHER_AUDIT_ID);

        Lease executed = merged.executed(SET_AT.plusSeconds(62), Duration.ofMinutes(1));

        assertThat(executed.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(executed.setAt()).isEqualTo(SET_AT.plusSeconds(62));
    }

    @Test
    void mergeAndExecutedClearAnEarlierAttempt() {
        Lease notAudited = active().attempted(new LeaseAttempt(SET_AT.plusSeconds(30),
            AuditOutcome.FAILED, Optional.empty(), Optional.of(LeaseReason.AUDIT_NOT_WRITABLE)));

        Lease merged = notAudited.merge(Map.of(INVOICES, TemporaryLevel.DEBUG),
            SET_AT.plusSeconds(60), Duration.ofMinutes(10), SETTER, OTHER_AUDIT_ID);

        assertThat(merged.lastAttempt()).isEmpty();
        assertThat(notAudited.executed(SET_AT.plusSeconds(60), Duration.ofMinutes(10))
            .lastAttempt()).isEmpty();
    }

    // --- the other transitions ---

    @Test
    void resetEndsTheLease() {
        Instant at = EXPIRES_AT.plusSeconds(20);

        assertThat(active().reset(at)).satisfies(lease -> {
            assertThat(lease.state()).isEqualTo(LeaseState.RESET);
            assertThat(lease.endedAt()).contains(at);
        });
        assertThat(resetFailed().reset(at)).satisfies(lease -> {
            assertThat(lease.state()).isEqualTo(LeaseState.RESET);
            assertThat(lease.endedAt()).contains(at);
            assertThat(lease.lastAttempt()).contains(UNREACHABLE);
        });
    }

    @Test
    void failedRecordsTheAttemptAndMarksTheLeaseResetFailed() {
        Lease failed = active().failed(UNREACHABLE);

        assertThat(failed.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(failed.lastAttempt()).contains(UNREACHABLE);
        assertThat(failed.endedAt()).isEmpty();
        LeaseAttempt again = new LeaseAttempt(Instant.parse("2026-11-12T08:27:01Z"),
            AuditOutcome.FAILED, Optional.of(ErrorCode.UNREACHABLE),
            Optional.of(LeaseReason.NODE_UNREACHABLE));
        assertThat(failed.failed(again).lastAttempt()).contains(again);
    }

    @ParameterizedTest
    @EnumSource(value = LeaseState.class, names = {"ACTIVE", "RESET_FAILED"})
    void attemptedRecordsTheAttemptAndKeepsTheState(LeaseState state) {
        Lease lease = state == LeaseState.ACTIVE ? active() : resetFailed();
        LeaseAttempt notAudited = new LeaseAttempt(Instant.parse("2026-11-12T08:27:01Z"),
            AuditOutcome.FAILED, Optional.empty(), Optional.of(LeaseReason.AUDIT_NOT_WRITABLE));

        Lease attempted = lease.attempted(notAudited);

        assertThat(attempted.state()).isEqualTo(state);
        assertThat(attempted.lastAttempt()).contains(notAudited);
        assertThat(attempted.endedAt()).isEmpty();
    }

    @Test
    void forgottenEndsTheLease() {
        Instant at = EXPIRES_AT.plus(Duration.ofDays(7));

        assertThat(resetFailed().forgotten(at)).satisfies(lease -> {
            assertThat(lease.state()).isEqualTo(LeaseState.FORGOTTEN);
            assertThat(lease.endedAt()).contains(at);
        });
        assertThat(active().forgotten(at).state()).isEqualTo(LeaseState.FORGOTTEN);
    }

    @Test
    void takenOverByReplacesOnlyTheOwner() {
        Lease lease = resetFailed();

        Lease taken = lease.takenOverBy(SETTER);

        assertThat(taken.owner()).isEqualTo(SETTER);
        assertThat(taken.state()).isEqualTo(LeaseState.RESET_FAILED);
        assertThat(taken.loggers()).isEqualTo(lease.loggers());
        assertThat(taken.expiresAt()).isEqualTo(lease.expiresAt());
        assertThat(taken.lastAttempt()).isEqualTo(lease.lastAttempt());
    }

    static Stream<Arguments> transitions() {
        return Stream.of(
            Arguments.of("merge", (UnaryOperator<Lease>) l -> l.merge(
                Map.of(INVOICES, TemporaryLevel.DEBUG), SET_AT, Duration.ofMinutes(10), SETTER,
                OTHER_AUDIT_ID)),
            Arguments.of("executed",
                (UnaryOperator<Lease>) l -> l.executed(SET_AT, Duration.ofMinutes(10))),
            Arguments.of("reset", (UnaryOperator<Lease>) l -> l.reset(EXPIRES_AT)),
            Arguments.of("failed", (UnaryOperator<Lease>) l -> l.failed(UNREACHABLE)),
            Arguments.of("attempted", (UnaryOperator<Lease>) l -> l.attempted(UNREACHABLE)),
            Arguments.of("forgotten", (UnaryOperator<Lease>) l -> l.forgotten(EXPIRES_AT)),
            Arguments.of("takenOverBy", (UnaryOperator<Lease>) l -> l.takenOverBy(SETTER)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitions")
    void everyTransitionIncrementsTheGeneration(String name, UnaryOperator<Lease> transition) {
        Lease lease = new Lease(LEASE_ID, 3, SERVER, "node1",
            loggers(Map.of(ORDERS, TemporaryLevel.DEBUG)), SET_AT, EXPIRES_AT, OWNER,
            LeaseState.ACTIVE, AUDIT_ID, Optional.empty(), Optional.empty());

        Lease next = transition.apply(lease);

        assertThat(next.generation()).isEqualTo(4);
        assertThat(next.leaseId()).isEqualTo(LEASE_ID);
        assertThat(lease.generation()).isEqualTo(3);
    }

    // --- transitions allowed only per the lifecycle ---

    @Test
    void mergeAndExecutedOnlyForAnActiveLease() {
        for (Lease lease : new Lease[] {resetFailed(), ended(LeaseState.RESET),
            ended(LeaseState.FORGOTTEN)}) {
            assertThatIllegalStateException().isThrownBy(() -> lease.merge(
                Map.of(ORDERS, TemporaryLevel.DEBUG), SET_AT, Duration.ofMinutes(10), SETTER,
                OTHER_AUDIT_ID));
            assertThatIllegalStateException().isThrownBy(
                () -> lease.executed(SET_AT, Duration.ofMinutes(10)));
        }
    }

    @ParameterizedTest
    @EnumSource(value = LeaseState.class, names = {"RESET", "FORGOTTEN"})
    void anEndedLeaseAllowsNoTransition(LeaseState state) {
        Lease lease = ended(state);

        transitions().forEach(arguments -> {
            @SuppressWarnings("unchecked")
            UnaryOperator<Lease> transition = (UnaryOperator<Lease>) arguments.get()[1];
            assertThatIllegalStateException().as("%s", arguments.get()[0])
                .isThrownBy(() -> transition.apply(lease));
        });
    }

    // --- sameGeneration (D-16 compare-and-set) ---

    @Test
    void sameGenerationComparesIdAndGeneration() {
        Lease lease = active();
        Lease other = new Lease(UUID.fromString("11111111-2222-4333-8444-555555555555"), 1, SERVER,
            "node1", lease.loggers(), SET_AT, EXPIRES_AT, OWNER, LeaseState.ACTIVE, AUDIT_ID,
            Optional.empty(), Optional.empty());

        assertThat(lease.sameGeneration(active())).isTrue();
        assertThat(lease.sameGeneration(lease.takenOverBy(SETTER))).isFalse();
        assertThat(lease.sameGeneration(other)).isFalse();
    }

    // --- validation of every field (D-12) ---

    @ParameterizedTest
    @ValueSource(strings = {"", "node 1", "node1;rm", "../node1",
        "n123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890"
            + "12345678901234567890123456789012345678901"})
    void refusesAnInvalidNodeName(String inubitNode) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Lease(LEASE_ID, 1, SERVER,
            inubitNode, loggers(Map.of(ORDERS, TemporaryLevel.DEBUG)), SET_AT, EXPIRES_AT, OWNER,
            LeaseState.ACTIVE, AUDIT_ID, Optional.empty(), Optional.empty()));
    }

    @Test
    void refusesAnInvalidServerIdOrLogger() {
        assertThatIllegalArgumentException().isThrownBy(() -> NodeId.parse("test/node1/x"));
        assertThatIllegalArgumentException().isThrownBy(() -> new LoggerName("org.acme.Orders"));
        assertThatThrownBy(() -> new Lease(LEASE_ID, 1, null, "node1",
            loggers(Map.of(ORDERS, TemporaryLevel.DEBUG)), SET_AT, EXPIRES_AT, OWNER,
            LeaseState.ACTIVE, AUDIT_ID, Optional.empty(), Optional.empty()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void refusesMissingIds() {
        assertThatThrownBy(() -> new Lease(null, 1, SERVER, "node1",
            loggers(Map.of(ORDERS, TemporaryLevel.DEBUG)), SET_AT, EXPIRES_AT, OWNER,
            LeaseState.ACTIVE, AUDIT_ID, Optional.empty(), Optional.empty()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Lease(LEASE_ID, 1, SERVER, "node1",
            loggers(Map.of(ORDERS, TemporaryLevel.DEBUG)), SET_AT, EXPIRES_AT, OWNER,
            LeaseState.ACTIVE, null, Optional.empty(), Optional.empty()))
            .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void refusesAGenerationBelowOne(long generation) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Lease(LEASE_ID, generation,
            SERVER, "node1", loggers(Map.of(ORDERS, TemporaryLevel.DEBUG)), SET_AT, EXPIRES_AT,
            OWNER, LeaseState.ACTIVE, AUDIT_ID, Optional.empty(), Optional.empty()));
    }

    @Test
    void refusesNoLoggersOrMoreThanFifty() {
        Map<LoggerName, TemporaryLevel> many = new HashMap<>();
        for (int i = 0; i <= Lease.MAX_LOGGERS; i++) {
            many.put(new LoggerName("com.inubit.ibis.acme.L" + i), TemporaryLevel.DEBUG);
        }

        assertThatIllegalArgumentException().isThrownBy(() -> new Lease(LEASE_ID, 1, SERVER,
            "node1", loggers(Map.of()), SET_AT, EXPIRES_AT, OWNER, LeaseState.ACTIVE, AUDIT_ID,
            Optional.empty(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Lease(LEASE_ID, 1, SERVER,
            "node1", loggers(many), SET_AT, EXPIRES_AT, OWNER, LeaseState.ACTIVE, AUDIT_ID,
            Optional.empty(), Optional.empty()));
    }

    @Test
    void refusesALoggerWithoutLevel() {
        Map<LoggerName, TemporaryLevel> noLevel = new HashMap<>();
        noLevel.put(ORDERS, null);

        assertThatThrownBy(() -> new Lease(LEASE_ID, 1, SERVER, "node1", loggers(noLevel), SET_AT,
            EXPIRES_AT, OWNER, LeaseState.ACTIVE, AUDIT_ID, Optional.empty(), Optional.empty()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void refusesAnExpiryNotAfterTheSetTime() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Lease(LEASE_ID, 1, SERVER,
            "node1", loggers(Map.of(ORDERS, TemporaryLevel.DEBUG)), SET_AT, SET_AT, OWNER,
            LeaseState.ACTIVE, AUDIT_ID, Optional.empty(), Optional.empty()));
    }

    @Test
    void refusesAnEndTimeThatDoesNotMatchTheState() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Lease(LEASE_ID, 1, SERVER,
            "node1", loggers(Map.of(ORDERS, TemporaryLevel.DEBUG)), SET_AT, EXPIRES_AT, OWNER,
            LeaseState.ACTIVE, AUDIT_ID, Optional.empty(), Optional.of(EXPIRES_AT)));
        assertThatIllegalArgumentException().isThrownBy(() -> new Lease(LEASE_ID, 1, SERVER,
            "node1", loggers(Map.of(ORDERS, TemporaryLevel.DEBUG)), SET_AT, EXPIRES_AT, OWNER,
            LeaseState.RESET, AUDIT_ID, Optional.empty(), Optional.empty()));
    }

    // --- the small types ---

    @Test
    void openStatesAreActiveAndResetFailed() {
        assertThat(LeaseState.ACTIVE.open()).isTrue();
        assertThat(LeaseState.RESET_FAILED.open()).isTrue();
        assertThat(LeaseState.RESET.open()).isFalse();
        assertThat(LeaseState.FORGOTTEN.open()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void ownerRefusesANonPositivePid(long pid) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new LeaseOwner(pid, Optional.empty()));
    }

    @Test
    void everyReasonHasAFixedText() {
        assertThat(LeaseReason.OTHER_NODE_ANSWERS.text()).isEqualTo("other node answers");
        assertThat(LeaseReason.NODE_UNREACHABLE.text()).isEqualTo("node unreachable");
        assertThat(LeaseReason.values()).allSatisfy(reason -> assertThat(reason.text()).isNotBlank());
        assertThat(LeaseReason.valueOf("AUDIT_NOT_WRITABLE")).isNotNull();
    }

    @Test
    void attemptRefusesMissingFields() {
        assertThatThrownBy(() -> new LeaseAttempt(null, AuditOutcome.FAILED, Optional.empty(),
            Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LeaseAttempt(SET_AT, null, Optional.empty(),
            Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LeaseAttempt(SET_AT, AuditOutcome.FAILED, null,
            Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LeaseAttempt(SET_AT, AuditOutcome.FAILED, Optional.empty(),
            null)).isInstanceOf(NullPointerException.class);
    }
}
