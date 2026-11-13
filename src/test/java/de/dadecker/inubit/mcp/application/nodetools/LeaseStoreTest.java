package de.dadecker.inubit.mcp.application.nodetools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.Lease;
import de.dadecker.inubit.mcp.domain.model.node.LeaseAttempt;
import de.dadecker.inubit.mcp.domain.model.node.LeaseOwner;
import de.dadecker.inubit.mcp.domain.model.node.LeaseReason;
import de.dadecker.inubit.mcp.domain.model.node.LeaseState;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.port.LeaseView;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 024-T045 (research D-12, FR-043, FR-045): the lease store - format 1 with generation, owner-only,
 * atomic replace under the store lock (also across store instances), an unreadable or linked file
 * never overwritten, ended leases removed 7 days after their end, reads never create a file.
 */
@Timeout(60)
class LeaseStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Duration SHORT = Duration.ofMillis(300);
    private static final Duration LONG = Duration.ofSeconds(20);
    private static final NodeId SERVER = NodeId.of("test", "node1");
    private static final LoggerName ORDERS = new LoggerName("com.inubit.ibis.acme.Orders");
    private static final LoggerName INVOICES = new LoggerName("com.inubit.ibis.acme.Invoices");
    private static final LeaseOwner OWNER =
        new LeaseOwner(4711, Optional.of(Instant.parse("2026-11-12T07:58:02.114Z")));
    private static final LeaseAttempt UNREACHABLE = new LeaseAttempt(
        Instant.parse("2026-11-12T08:26:01Z"), AuditOutcome.FAILED,
        Optional.of(ErrorCode.UNREACHABLE), Optional.of(LeaseReason.NODE_UNREACHABLE));

    /** The lease of research D-12 in state RESET_FAILED, as the store writes it. */
    private static final String LEASE_JSON = """
                {
                  "leaseId": "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01",
                  "generation": 3,
                  "server": "test/node1",
                  "inubitNode": "node1",
                  "loggers": { "com.inubit.ibis.acme.Orders": "DEBUG" },
                  "setAt": "2026-11-12T08:15:30Z",
                  "expiresAt": "2026-11-12T08:25:30Z",
                  "owner": { "pid": 4711, "startedAt": "2026-11-12T07:58:02.114Z" },
                  "state": "RESET_FAILED",
                  "auditId": "8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                  "lastAttempt": { "at": "2026-11-12T08:26:01Z", "outcome": "FAILED", "code": "UNREACHABLE", "reason": "NODE_UNREACHABLE" },
                  "endedAt": null
                }\
            """;
    private static final String VALID = "{\n  \"format\": 1,\n  \"leases\": [\n" + LEASE_JSON
        + "\n  ]\n}\n";

    @TempDir
    Path home;

    private Path directory() {
        return LeaseStore.defaultDirectory(home, "acme");
    }

    private Path file() {
        return directory().resolve("leases.json");
    }

    private LeaseStore store() {
        return store(NOW);
    }

    private LeaseStore store(Instant now) {
        return store(now, PrivateFiles.forHost());
    }

    private LeaseStore store(Instant now, PrivateFileSystem files) {
        return new LeaseStore(directory(), files, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** The lease of {@link #LEASE_JSON}. */
    private static Lease d12Lease() {
        return new Lease(UUID.fromString("3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01"), 3, SERVER,
            "node1", debugOrders(),
            Instant.parse("2026-11-12T08:15:30Z"), Instant.parse("2026-11-12T08:25:30Z"), OWNER,
            LeaseState.RESET_FAILED, UUID.fromString("8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"),
            Optional.of(UNREACHABLE), Optional.empty());
    }

    private static SortedMap<LoggerName, TemporaryLevel> debugOrders() {
        SortedMap<LoggerName, TemporaryLevel> loggers =
            new TreeMap<>(Comparator.comparing(LoggerName::value));
        loggers.put(ORDERS, TemporaryLevel.DEBUG);
        return loggers;
    }

    private static Lease active(NodeId server, Instant setAt) {
        return new Lease(UUID.randomUUID(), 1, server, "node1",
            debugOrders(), setAt,
            setAt.plus(Duration.ofMinutes(10)), OWNER, LeaseState.ACTIVE, UUID.randomUUID(),
            Optional.empty(), Optional.empty());
    }

    private void writeFile(String text) throws IOException {
        Files.createDirectories(directory());
        Files.writeString(file(), text, StandardCharsets.UTF_8);
    }

    private static List<Lease> leasesOf(LeaseView.Snapshot snapshot) {
        assertThat(snapshot).isInstanceOf(LeaseView.Snapshot.Leases.class);
        return ((LeaseView.Snapshot.Leases) snapshot).leases();
    }

    @Test
    void theStoreLivesInTheLogLevelsDirectoryOfTheProfile() {
        assertThat(LeaseStore.defaultDirectory(home, "acme"))
            .isEqualTo(home.resolve(".inubit-mcp").resolve("acme").resolve("log-levels"));
    }

    @Test
    void readingWithoutAFileIsEmptyAndCreatesNothing() {
        assertThat(leasesOf(store().leases())).isEmpty();
        assertThat(store().read()).isEmpty();

        assertThat(home.resolve(".inubit-mcp")).doesNotExist();
    }

    @Test
    void anUpdateThatStoresNoLeaseOnAFreshHomeCreatesNothing() {
        // r1: the scheduler updates on every tick; D-12 creates the files with the first set
        Lease endedLongAgo = active(SERVER, NOW.minus(Duration.ofDays(9)))
            .reset(NOW.minus(Duration.ofDays(8)));

        assertThat(store().update(leases -> leases, SHORT)).isEmpty();
        assertThat(store().update(leases -> List.of(endedLongAgo), SHORT)).isEmpty();

        assertThat(home.resolve(".inubit-mcp")).doesNotExist();
    }

    @Test
    void anUpdateThatChangesNothingWritesNoFile() throws IOException {
        store().update(leases -> List.of(d12Lease()), SHORT);
        FileTime written = FileTime.from(Instant.parse("2026-11-01T00:00:00Z"));
        Files.setLastModifiedTime(file(), written);

        assertThat(store().update(leases -> leases, SHORT)).containsExactly(d12Lease());

        assertThat(Files.getLastModifiedTime(file())).isEqualTo(written);
    }

    @Test
    void theFirstWriteAppliesTheChangeOnce() {
        AtomicInteger calls = new AtomicInteger();

        store().update(leases -> {
            calls.incrementAndGet();
            return List.of(d12Lease());
        }, SHORT);

        assertThat(calls).hasValue(1);
        assertThat(store().read()).containsExactly(d12Lease());
    }

    @Test
    void aFirstWriteOfAnotherInstanceMeanwhileIsKept() {
        // the change is decided on no leases without the lock; another instance stores the first
        // lease before this one takes the lock, so the change is applied again to what is stored
        Lease other = active(NodeId.of("test", "node2"), NOW);
        AtomicInteger calls = new AtomicInteger();

        List<Lease> stored = store().update(leases -> {
            if (calls.incrementAndGet() == 1) {
                store().update(theirs -> List.of(other), SHORT);
            }
            List<Lease> mine = new ArrayList<>(leases);
            mine.add(d12Lease());
            return mine;
        }, SHORT);

        assertThat(calls).hasValue(2);
        assertThat(stored).containsExactly(other, d12Lease());
        assertThat(store().read()).containsExactly(other, d12Lease());
    }

    @Test
    void theStoreWritesFormat1AsResearchD12() throws IOException {
        store().update(leases -> List.of(d12Lease()), SHORT);

        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(VALID);
    }

    @Test
    void format1OfResearchD12IsRead() throws IOException {
        writeFile(VALID);

        assertThat(leasesOf(store().leases())).containsExactly(d12Lease());
    }

    @Test
    void everyFieldAndTheGenerationSurviveARoundTripThroughAnotherInstance() {
        Lease merged = active(SERVER, NOW.minusSeconds(60)).merge(
            Map.of(INVOICES, TemporaryLevel.TRACE), NOW.minusSeconds(30), Duration.ofMinutes(15),
            new LeaseOwner(4712, Optional.empty()), UUID.randomUUID());
        Lease attempted = active(NodeId.of("test", "node2"), NOW.minusSeconds(600)).attempted(
            new LeaseAttempt(NOW.minusSeconds(5), AuditOutcome.REFUSED, Optional.empty(),
                Optional.of(LeaseReason.AUDIT_NOT_WRITABLE)));
        Lease noReason = active(NodeId.of("dev", "node1"), NOW.minusSeconds(900)).failed(
            new LeaseAttempt(NOW.minusSeconds(7), AuditOutcome.FAILED,
                Optional.of(ErrorCode.TIMEOUT), Optional.empty()));
        Lease reset = active(NodeId.of("dev", "node2"), NOW.minusSeconds(3600))
            .reset(NOW.minusSeconds(1800));
        Lease forgotten = active(NodeId.of("dev", "node3"), NOW.minus(Duration.ofDays(6)))
            .failed(UNREACHABLE).forgotten(NOW.minus(Duration.ofDays(5)));
        List<Lease> leases = List.of(merged, attempted, noReason, reset, forgotten, d12Lease());

        assertThat(store().update(stored -> leases, SHORT)).containsExactlyElementsOf(leases);

        assertThat(leasesOf(store().leases())).containsExactlyElementsOf(leases);
        assertThat(store().read()).containsExactlyElementsOf(leases);
        assertThat(merged.generation()).isEqualTo(2);
        assertThat(forgotten.generation()).isEqualTo(3);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theDirectoryAndTheFileAreOwnerOnlyAndNoTemporaryFileStays() throws IOException {
        store().update(leases -> List.of(d12Lease()), SHORT);
        store().update(leases -> List.of(), SHORT);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory())))
            .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file())))
            .isEqualTo("rw-------");
        try (Stream<Path> names = Files.list(directory())) {
            assertThat(names.map(path -> path.getFileName().toString()))
                .containsExactlyInAnyOrder("leases.json", ".lock");
        }
    }

    @Test
    void concurrentUpdatesFromTwoThreadsLoseNothing() throws Exception {
        LeaseStore store = store();
        assertThat(concurrentGenerations(store, store)).isEqualTo(1 + 2 * 25);
    }

    @Test
    void concurrentUpdatesFromTwoStoreInstancesOnOneDirectoryLoseNothing() throws Exception {
        assertThat(concurrentGenerations(store(), store())).isEqualTo(1 + 2 * 25);
    }

    /** 25 read-modify-writes per store, each raising the one lease's generation by one. */
    private static long concurrentGenerations(LeaseStore first, LeaseStore second)
        throws Exception {
        first.update(leases -> List.of(active(SERVER, NOW.minusSeconds(60))), LONG);
        LeaseAttempt attempt = new LeaseAttempt(NOW, AuditOutcome.FAILED, Optional.empty(),
            Optional.of(LeaseReason.CLI_FAILED));
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (LeaseStore store : List.of(first, second)) {
            runs.add(CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 25; i++) {
                    store.update(leases -> List.of(leases.get(0).attempted(attempt)), LONG);
                }
            }));
        }
        start.countDown();
        CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).get(40, TimeUnit.SECONDS);
        return first.read().get(0).generation();
    }

    @Test
    void anUpdateWaitsAtMostItsWaitForTheStoreLockAndChangesNothing() throws Exception {
        store().update(leases -> List.of(d12Lease()), SHORT);
        byte[] before = Files.readAllBytes(file());
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<List<Lease>> holder = CompletableFuture.supplyAsync(
            () -> store().update(leases -> {
                inside.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return leases;
            }, LONG));
        try {
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();

            assertThatThrownBy(() -> store().update(leases -> List.of(), SHORT))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                    assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                    assertThat(e.error().message()).isEqualTo(
                        "Another change of the temporary log level leases is in progress;"
                            + " nothing was changed");
                });

            assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(SHORT).isLessThan(Duration.ofSeconds(5));
        } finally {
            release.countDown();
        }
        holder.get(10, TimeUnit.SECONDS);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    static Stream<Arguments> invalidFiles() {
        return Stream.of(
            invalid("not JSON", VALID, "{ \"format\": 1, ", "not valid JSON"),
            invalid("trailing content", VALID, VALID + "[]", "not valid JSON"),
            invalid("format 2", "\"format\": 1", "\"format\": 2", "format"),
            invalid("format as text", "\"format\": 1", "\"format\": \"1\"", "format"),
            invalid("format missing", "\"format\": 1,\n", "", "format"),
            invalid("leases null", VALID, "{\"format\": 1, \"leases\": null}", "leases"),
            invalid("unknown top-level field", "\"format\": 1,", "\"format\": 1, \"x\": \"y\",",
                "unknown field x"),
            invalid("lease not an object", VALID, "{\"format\": 1, \"leases\": [\"x\"]}",
                "lease 1"),
            invalid("leaseId 1-1-1-1-1", "\"3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01\"",
                "\"1-1-1-1-1\"", "leaseId"),
            invalid("leaseId without dashes", "\"3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01\"",
                "\"3f2b8a1e0c4d4e7a9b1f2a6c5d8e9f01\"", "leaseId"),
            invalid("leaseId not a UUID", "\"3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01\"",
                "\"not-a-uuid\"", "leaseId"),
            invalid("auditId 1-1-1-1-1", "\"8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f\"",
                "\"1-1-1-1-1\"", "auditId"),
            invalid("auditId as number", "\"8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f\"", "7",
                "auditId"),
            invalid("generation 0", "\"generation\": 3", "\"generation\": 0", "generation"),
            invalid("generation negative", "\"generation\": 3", "\"generation\": -1",
                "generation"),
            invalid("generation as text", "\"generation\": 3", "\"generation\": \"3\"",
                "generation"),
            invalid("generation fraction", "\"generation\": 3", "\"generation\": 1.5",
                "not valid JSON"),
            invalid("generation overflow", "\"generation\": 3",
                "\"generation\": 99999999999999999999", "not valid JSON"),
            invalid("server without node", "\"test/node1\"", "\"test\"", "server"),
            invalid("server upper case", "\"test/node1\"", "\"Test/node1\"", "server"),
            invalid("inubitNode empty", "\"inubitNode\": \"node1\"", "\"inubitNode\": \"\"",
                "node name"),
            invalid("loggers empty", "{ \"com.inubit.ibis.acme.Orders\": \"DEBUG\" }", "{}",
                "loggers"),
            invalid("logger name invalid", "\"com.inubit.ibis.acme.Orders\"", "\"org.acme\"",
                "loggers"),
            invalid("level invalid", "\"DEBUG\" }", "\"VERBOSE\" }", "loggers"),
            invalid("setAt invalid", "\"2026-11-12T08:15:30Z\"", "\"yesterday\"", "setAt"),
            invalid("expiresAt not after setAt", "\"2026-11-12T08:25:30Z\"",
                "\"2026-11-12T08:15:30Z\"", "expiry"),
            invalid("owner pid 0", "\"pid\": 4711", "\"pid\": 0", "owner"),
            invalid("owner pid as text", "\"pid\": 4711", "\"pid\": \"4711\"", "owner"),
            invalid("owner startedAt invalid", "\"2026-11-12T07:58:02.114Z\"", "\"x\"", "owner"),
            invalid("owner startedAt missing", ", \"startedAt\": \"2026-11-12T07:58:02.114Z\"",
                "", "owner"),
            invalid("state invalid", "\"RESET_FAILED\"", "\"DONE\"", "state"),
            invalid("state null", "\"RESET_FAILED\"", "null", "state"),
            invalid("lastAttempt not an object", "{ \"at\": \"2026-11-12T08:26:01Z\","
                + " \"outcome\": \"FAILED\", \"code\": \"UNREACHABLE\","
                + " \"reason\": \"NODE_UNREACHABLE\" }", "\"x\"", "lastAttempt"),
            invalid("lastAttempt at invalid", "\"at\": \"2026-11-12T08:26:01Z\"", "\"at\": \"x\"",
                "lastAttempt"),
            invalid("lastAttempt outcome invalid", "\"FAILED\"", "\"MAYBE\"", "lastAttempt"),
            invalid("lastAttempt code invalid", "\"UNREACHABLE\"", "\"NOPE\"", "lastAttempt"),
            invalid("lastAttempt reason invalid", "\"NODE_UNREACHABLE\"", "\"NOPE\"",
                "lastAttempt"),
            invalid("endedAt of an open lease", "\"endedAt\": null",
                "\"endedAt\": \"2026-11-12T09:00:00Z\"", "end time"),
            invalid("RESET without endedAt", "\"RESET_FAILED\"", "\"RESET\"", "end time"),
            invalid("field missing", ",\n      \"endedAt\": null", "", "missing field endedAt"),
            invalid("unknown lease field", "\"endedAt\": null",
                "\"endedAt\": null, \"note\": \"x\"", "unknown field note"),
            invalid("duplicate leaseId", LEASE_JSON, LEASE_JSON + ",\n" + LEASE_JSON,
                "duplicate leaseId"));
    }

    private static Arguments invalid(String name, String from, String to, String reason) {
        assertThat(VALID).contains(from);
        return Arguments.of(name, VALID.replace(from, to), reason);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFiles")
    void anInvalidFileIsUnreadableAndNeverOverwritten(String name, String text, String reason)
        throws IOException {
        writeFile(text);

        LeaseView.Snapshot snapshot = store().leases();

        assertThat(snapshot).isInstanceOfSatisfying(LeaseView.Snapshot.Unreadable.class,
            unreadable -> assertThat(unreadable.reason()).contains(reason));
        assertUnreadableError(() -> store().read());
        assertUnreadableError(() -> store().update(leases -> List.of(d12Lease()), SHORT));
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(text);
    }

    private void assertUnreadableError(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(e.error().message()).startsWith("The lease store " + file()
                + " cannot be read (");
            assertThat(e.error().nextStep()).contains("fix or move it");
        });
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkAtTheFileIsRefusedAndNotReplaced() throws IOException {
        Path target = home.resolve("elsewhere.json");
        Files.writeString(target, VALID, StandardCharsets.UTF_8);
        Files.createDirectories(directory());
        Files.createSymbolicLink(file(), target);

        assertThat(store().leases()).isEqualTo(
            new LeaseView.Snapshot.Unreadable("leases.json is a symbolic link"));
        assertUnreadableError(() -> store().update(leases -> List.of(), SHORT));

        assertThat(Files.isSymbolicLink(file())).isTrue();
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(VALID);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkAtTheDirectoryIsRefusedAndNothingIsWrittenThere() throws IOException {
        Path target = Files.createDirectories(home.resolve("elsewhere"));
        Files.createDirectories(directory().getParent());
        Files.createSymbolicLink(directory(), target);

        assertThat(store().leases()).isEqualTo(
            new LeaseView.Snapshot.Unreadable("the log-levels directory is a symbolic link"));
        assertThatThrownBy(() -> store().update(leases -> List.of(d12Lease()), SHORT))
            .isInstanceOf(ToolErrorException.class);

        try (Stream<Path> names = Files.list(target)) {
            assertThat(names).isEmpty();
        }
    }

    @Test
    void aFailedWriteIsInternalAndKeepsTheStoredFile() throws IOException {
        store().update(leases -> List.of(d12Lease()), SHORT);
        byte[] before = Files.readAllBytes(file());

        assertThatThrownBy(() -> store(NOW, PrivateFiles.failingFiles())
            .update(leases -> List.of(), SHORT))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INTERNAL);
                assertThat(e.error().message()).startsWith("The lease store " + file()
                    + " cannot be written (");
            });

        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        try (Stream<Path> names = Files.list(directory())) {
            assertThat(names.map(path -> path.getFileName().toString()))
                .containsExactlyInAnyOrder("leases.json", ".lock");
        }
    }

    @Test
    void endedLeasesAreRemoved7DaysAfterTheirEnd() {
        Duration week = Duration.ofDays(7);
        Lease resetLongAgo = active(SERVER, NOW.minus(Duration.ofDays(9)))
            .reset(NOW.minus(week).minusSeconds(1));
        Lease forgottenExactly = active(NodeId.of("dev", "node2"), NOW.minus(Duration.ofDays(9)))
            .forgotten(NOW.minus(week));
        Lease resetRecently = active(NodeId.of("test", "node2"), NOW.minus(Duration.ofDays(8)))
            .reset(NOW.minus(week).plusSeconds(1));
        Lease failedLongAgo = d12Lease();
        Lease activeNow = active(NodeId.of("dev", "node1"), NOW);
        List<Lease> all = List.of(resetLongAgo, forgottenExactly, resetRecently, failedLongAgo,
            activeNow);
        store(NOW.minus(Duration.ofDays(1))).update(leases -> all, SHORT);
        assertThat(store().read()).hasSize(5);

        List<Lease> kept = store().update(leases -> leases, SHORT);

        assertThat(kept).containsExactly(resetRecently, failedLongAgo, activeNow);
        assertThat(store().read()).containsExactly(resetRecently, failedLongAgo, activeNow);
    }

    @Test
    void anUpdateMayNotStoreTwoLeasesWithOneId() {
        assertThatThrownBy(() -> store().update(leases -> List.of(d12Lease(), d12Lease()), SHORT))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(file()).doesNotExist();
    }
}
