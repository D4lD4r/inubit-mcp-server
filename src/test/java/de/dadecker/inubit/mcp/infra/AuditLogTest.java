package de.dadecker.inubit.mcp.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T100: the append-only audit log of the write tools (FR-023, research R-14). */
class AuditLogTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant OCTOBER = Instant.parse("2026-10-03T08:15:30Z");
    private static final String CODE = "AbCdEfGhIjKlMnOpQrStUv";

    @TempDir
    Path directory;

    private final SecretScrubber scrubber = new SecretScrubber();

    private static AuditRecord record(Instant timestamp, Map<String, String> inputs,
        AuditOutcome outcome, Optional<String> reason) {
        return new AuditRecord(UUID.fromString("7b0c4f1e-3a52-4d5e-9a40-1f2e3d4c5b6a"),
            timestamp, "acme", "dev/node1", Optional.of("dev"), "restart_process",
            AuditRecord.Step.EXECUTE, inputs, Optional.of("jdoe"), outcome, reason,
            Optional.of("claude-code/2.1.0"));
    }

    private static AuditRecord record(AuditOutcome outcome) {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("processId", "110190387");
        inputs.put("reason", "restart after fix");
        return record(OCTOBER, inputs, outcome, Optional.empty());
    }

    private Path file() {
        return directory.resolve("audit-2026-10.jsonl");
    }

    private List<JsonNode> lines(Path file) throws IOException {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            nodes.add(JSON.readTree(line));
        }
        return nodes;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void writesOneJsonLinePerRecordIntoTheMonthlyFile() throws IOException {
        AuditLog log = new AuditLog(directory, scrubber);

        log.append(record(AuditOutcome.PENDING));
        log.append(record(Instant.parse("2026-11-01T00:00:00Z"), Map.of("processId", "1"),
            AuditOutcome.REFUSED, Optional.of("WRITE_DISABLED: disabled")));

        assertThat(lines(file())).singleElement().satisfies(node -> {
            assertThat(node.path("auditId").asString())
                .isEqualTo("7b0c4f1e-3a52-4d5e-9a40-1f2e3d4c5b6a");
            assertThat(node.path("timestamp").asString()).isEqualTo("2026-10-03T08:15:30Z");
            assertThat(node.path("profile").asString()).isEqualTo("acme");
            assertThat(node.path("node").asString()).isEqualTo("dev/node1");
            assertThat(node.path("group").asString()).isEqualTo("dev");
            assertThat(node.path("capability").asString()).isEqualTo("restart_process");
            assertThat(node.path("step").asString()).isEqualTo("EXECUTE");
            assertThat(node.path("inputs").path("processId").asString()).isEqualTo("110190387");
            assertThat(node.path("inputs").path("reason").asString())
                .isEqualTo("restart after fix");
            assertThat(node.path("account").asString()).isEqualTo("jdoe");
            assertThat(node.path("outcome").asString()).isEqualTo("PENDING");
            assertThat(node.path("mcpClient").asString()).isEqualTo("claude-code/2.1.0");
        });
        assertThat(lines(directory.resolve("audit-2026-11.jsonl"))).singleElement()
            .satisfies(node -> assertThat(node.path("reason").asString())
                .isEqualTo("WRITE_DISABLED: disabled"));
    }

    @Test
    void theFieldsAreExactlyThoseOfTheDataModelInOrder() throws IOException {
        new AuditLog(directory, scrubber).append(record(OCTOBER, Map.of("processId", "1"),
            AuditOutcome.REFUSED, Optional.of("WRITE_DISABLED: disabled")));

        // 002 contracts/mcp-tools-delta.md → Audit record
        assertThat(fieldNames(lines(file()).get(0))).containsExactly("auditId", "timestamp",
            "profile", "node", "group", "capability", "step", "inputs", "account", "outcome",
            "reason", "mcpClient");
    }

    @Test
    void theDefaultDirectoryIsOnePerProfileBelowTheGivenHome() {
        // 002 research D-7
        Path home = Path.of("/home/someone");

        assertThat(AuditLog.defaultDirectory(home, "acme"))
            .isEqualTo(Path.of("/home/someone/.inubit-mcp/acme/audit"));
        assertThat(AuditLog.defaultDirectory(home, "globex"))
            .isEqualTo(Path.of("/home/someone/.inubit-mcp/globex/audit"));
    }

    @Test
    void theDefaultDirectoryAcceptsOnlyValidProfileNames() {
        Path home = Path.of("/home/someone");

        // "audit" is reserved (002 US2 review P3b): it would lead into the 001 directory
        for (String name : List.of("", "..", "../x", "a/b", "Acme", "-acme", "audit")) {
            assertThatThrownBy(() -> AuditLog.defaultDirectory(home, name)).as(name)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void absentOptionalFieldsAreOmitted() throws IOException {
        new AuditLog(directory, scrubber).append(new AuditRecord(UUID.randomUUID(), OCTOBER,
            "acme", "no such server", Optional.empty(), "kill_process", AuditRecord.Step.PREVIEW,
            Map.of(), Optional.empty(), AuditOutcome.REFUSED, Optional.of("INVALID_INPUT: x"),
            Optional.empty()));

        assertThat(fieldNames(lines(file()).get(0))).containsExactly("auditId", "timestamp",
            "profile", "node", "capability", "step", "inputs", "outcome", "reason");
    }

    @Test
    void theFileIsOwnerReadWriteOnly() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

        new AuditLog(directory, scrubber).append(record(AuditOutcome.PENDING));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file())))
            .isEqualTo("rw-------");
    }

    @Test
    void aMissingDirectoryIsCreatedOwnerOnly() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path nested = directory.resolve("a/audit");

        new AuditLog(nested, scrubber).append(record(AuditOutcome.PENDING));

        assertThat(Files.isRegularFile(nested.resolve("audit-2026-10.jsonl"))).isTrue();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(nested)))
            .isEqualTo("rwx------");
    }

    @Test
    void anExistingDirectoryWithWiderPermissionsIsTightened() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path existing = Files.createDirectories(directory.resolve("shared"));
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxr-xr-x"));

        new AuditLog(existing, scrubber).append(record(AuditOutcome.PENDING));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(existing)))
            .isEqualTo("rwx------");
    }

    @Test
    void anExistingFileWithWiderPermissionsIsTightened() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Files.writeString(file(), "");
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-r--r--"));

        new AuditLog(directory, scrubber).append(record(AuditOutcome.PENDING));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file())))
            .isEqualTo("rw-------");
    }

    @Test
    void theConfirmationCodeIsStoredOnlyAsItsSha256Prefix() throws Exception {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("processId", "110190387");
        inputs.put(AuditRecord.CONFIRMATION_CODE, CODE);
        inputs.put(AuditRecord.ISSUED_CONFIRMATION_CODE, CODE);

        new AuditLog(directory, scrubber).append(record(OCTOBER, inputs, AuditOutcome.PENDING,
            Optional.empty()));

        String text = Files.readString(file());
        String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(CODE.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        assertThat(text).doesNotContain(CODE);
        JsonNode stored = lines(file()).get(0).path("inputs");
        assertThat(stored.path(AuditRecord.CONFIRMATION_CODE).asString()).isEqualTo(hash);
        assertThat(stored.path(AuditRecord.ISSUED_CONFIRMATION_CODE).asString()).isEqualTo(hash);
    }

    @Test
    void everyFieldIsScrubbed() throws IOException {
        scrubber.register("s3cr3t-audit-pw");
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("processId", "s3cr3t-audit-pw");
        inputs.put("reason", "token s3cr3t-audit-pw");
        AuditRecord record = new AuditRecord(UUID.randomUUID(), OCTOBER, "acme",
            "x s3cr3t-audit-pw",
            Optional.of("s3cr3t-audit-pw"), "restart_process", AuditRecord.Step.EXECUTE, inputs,
            Optional.of("s3cr3t-audit-pw"), AuditOutcome.FAILED,
            Optional.of("AUTH_FAILED: s3cr3t-audit-pw"), Optional.of("client s3cr3t-audit-pw"));

        new AuditLog(directory, scrubber).append(record);

        String text = Files.readString(file());
        assertThat(text).doesNotContain("s3cr3t-audit-pw").contains(SecretScrubber.MASK);
        assertThat(lines(file())).singleElement().satisfies(node -> {
            assertThat(node.path("node").asString()).isEqualTo("x ***");
            assertThat(node.path("inputs").path("reason").asString()).isEqualTo("token ***");
            assertThat(node.path("mcpClient").asString()).isEqualTo("client ***");
        });
    }

    @Test
    void forceTrueIsCalledAfterTheWriteAndBeforeAppendReturns() {
        List<String> events = new CopyOnWriteArrayList<>();
        AuditLog log = new AuditLog(directory, scrubber, (file, options, attributes) ->
            new RecordingChannel(FileChannel.open(file, options, attributes), events));

        log.append(record(AuditOutcome.PENDING));
        events.add("returned");

        assertThat(events).containsSubsequence("write", "force(true)", "close", "returned");
        assertThat(events.lastIndexOf("write")).isLessThan(events.indexOf("force(true)"));
    }

    @Test
    void aFailingOpenThrowsSoThatTheCallerCanFailClosed() {
        AuditLog log = new AuditLog(directory, scrubber, (file, options, attributes) -> {
            throw new IOException("disk full");
        });

        assertThatThrownBy(() -> log.append(record(AuditOutcome.PENDING)))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aFailingForceThrows() {
        AuditLog log = new AuditLog(directory, scrubber, (file, options, attributes) ->
            new RecordingChannel(FileChannel.open(file, options, attributes),
                new ArrayList<>()) {
                @Override
                public void force(boolean metaData) throws IOException {
                    throw new IOException("fsync failed");
                }
            });

        assertThatThrownBy(() -> log.append(record(AuditOutcome.PENDING)))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void anUnwritableDirectoryThrows() throws IOException {
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "a file, not a directory");

        assertThatThrownBy(() -> new AuditLog(blocked, scrubber)
            .append(record(AuditOutcome.PENDING))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void recordsSurviveAReopenAndAreAppended() throws IOException {
        new AuditLog(directory, scrubber).append(record(AuditOutcome.CHALLENGE_ISSUED));
        new AuditLog(directory, scrubber).append(record(AuditOutcome.PENDING));
        new AuditLog(directory, scrubber).append(record(AuditOutcome.EXECUTED));

        assertThat(lines(file())).extracting(node -> node.path("outcome").asString())
            .containsExactly("CHALLENGE_ISSUED", "PENDING", "EXECUTED");
    }

    @Test
    void concurrentAppendsDoNotInterleave() throws Exception {
        AuditLog log = new AuditLog(directory, scrubber);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                for (int j = 0; j < 10; j++) {
                    log.append(record(AuditOutcome.REFUSED));
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(lines(file())).hasSize(80)
            .allSatisfy(node -> assertThat(node.path("outcome").asString()).isEqualTo("REFUSED"));
    }

    @Test
    void lineBreaksInValuesStayInsideOneLine() throws IOException {
        new AuditLog(directory, scrubber).append(record(OCTOBER,
            Map.of("reason", "line one\nline two\r\n{\"forged\":true}"), AuditOutcome.REFUSED,
            Optional.of("a\nb")));

        assertThat(Files.readAllLines(file())).hasSize(1);
        assertThat(lines(file()).get(0).path("inputs").path("reason").asString())
            .isEqualTo("line one\nline two\r\n{\"forged\":true}");
    }

    /** Delegates to a real channel and records write, force and close calls. */
    private static class RecordingChannel extends FileChannel {

        private final FileChannel delegate;
        private final List<String> events;

        RecordingChannel(FileChannel delegate, List<String> events) {
            this.delegate = delegate;
            this.events = events;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            events.add("write");
            return delegate.write(src);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            events.add("write");
            return delegate.write(srcs, offset, length);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            events.add("force(" + metaData + ")");
            delegate.force(metaData);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target)
            throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count)
            throws IOException {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            events.add("write");
            return delegate.write(src, position);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            events.add("close");
            delegate.close();
        }
    }
}
