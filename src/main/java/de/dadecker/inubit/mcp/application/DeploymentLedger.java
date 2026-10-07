package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The deployment ledger (feature 005, FR-013, research D-8):
 * {@code ~/.inubit-mcp/<profile>/deployments/ledger.json} records per node and artifact (e.g.
 * {@code workflow:GRP-01/Workflow-0001}, {@code module:Assign/Module-0003},
 * {@code repository:/Root/jdoe/xsd/a.xsd}) the fingerprint of the rendered state the last
 * verified deployment of this server wrote, with its audit id, tag and time. A preview compares
 * it with the node's current state to find changes made outside the chain.
 *
 * <ul>
 *   <li>Names and hashes only: an {@link Entry} accepts a {@code sha256:} fingerprint, a UUID and
 *       a tag of the StartCLI value rule, never content or a secret.
 *   <li>Owner-only: the directory {@code rwx------}, the file {@code rw-------}; written to a
 *       temporary file in the same directory and moved atomically into place.
 *   <li>An unreadable ledger is {@code PRECONDITION_FAILED} and never overwritten.
 * </ul>
 *
 * <p>Calls run under the deploy lock of the target group (one writer per group); records of
 * another group's nodes are kept as read.
 */
public final class DeploymentLedger {

    /** The file name below the deployments directory. */
    public static final String FILE = "ledger.json";
    private static final Pattern FINGERPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern AUDIT_ID = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** What a verified deployment wrote: hashes and names only. */
    public record Entry(String fingerprint, String auditId, String tag, Instant at) {
        public Entry {
            if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
                throw new IllegalArgumentException("A ledger entry holds a sha256 fingerprint");
            }
            if (auditId == null || !AUDIT_ID.matcher(auditId).matches()) {
                throw new IllegalArgumentException("A ledger entry holds an audit id");
            }
            if (tag == null || !DeployGuard.VALUE.matcher(tag).matches()) {
                throw new IllegalArgumentException("A ledger entry holds a tag");
            }
            Objects.requireNonNull(at, "at");
        }
    }

    private final Path directory;

    /** @param deployments {@code ~/.inubit-mcp/<profile>/deployments} */
    public DeploymentLedger(Path deployments) {
        this.directory = Objects.requireNonNull(deployments, "deployments");
    }

    /**
     * The entries of {@code node} (artifact → entry), empty if it has none.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if the ledger cannot be read
     */
    public synchronized Map<String, Entry> entries(NodeId node) {
        return read().getOrDefault(node.value(), Map.of());
    }

    /**
     * Records {@code entries} for {@code node}, replacing the entries of the same artifacts.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if the ledger cannot be read or
     *     written
     */
    public synchronized void record(NodeId node, Map<String, Entry> entries) {
        Map<String, Map<String, Entry>> ledger = read();
        ledger.computeIfAbsent(node.value(), n -> new TreeMap<>()).putAll(entries);
        write(ledger);
    }

    private Path file() {
        return directory.resolve(FILE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Entry>> read() {
        Map<String, Map<String, Entry>> ledger = new TreeMap<>();
        if (!Files.exists(file())) {
            return ledger;
        }
        try {
            Map<String, Object> nodes = MiniJson.parse(Files.readString(file(),
                StandardCharsets.UTF_8));
            nodes.forEach((node, artifacts) -> {
                Map<String, Entry> entries = new TreeMap<>();
                ((Map<String, Object>) artifacts).forEach((artifact, value) -> {
                    Map<String, Object> entry = (Map<String, Object>) value;
                    entries.put(artifact, new Entry((String) entry.get("fingerprint"),
                        (String) entry.get("auditId"), (String) entry.get("tag"),
                        Instant.parse((String) entry.get("at"))));
                });
                ledger.put(node, entries);
            });
            return ledger;
        } catch (IOException | IllegalArgumentException | ClassCastException
            | NullPointerException | DateTimeException e) {
            throw unusable("cannot be read (" + e.getClass().getSimpleName() + ")",
                "The file was edited by hand or damaged",
                "Move it aside (the next deployment starts a new one) and retry");
        }
    }

    private void write(Map<String, Map<String, Entry>> ledger) {
        StringBuilder json = new StringBuilder("{\n");
        int n = 0;
        for (Map.Entry<String, Map<String, Entry>> node : ledger.entrySet()) {
            json.append("  ").append(MiniJson.quote(node.getKey())).append(": {\n");
            int a = 0;
            for (Map.Entry<String, Entry> artifact : node.getValue().entrySet()) {
                Entry entry = artifact.getValue();
                json.append("    ").append(MiniJson.quote(artifact.getKey())).append(": {")
                    .append("\"fingerprint\": ").append(MiniJson.quote(entry.fingerprint()))
                    .append(", \"auditId\": ").append(MiniJson.quote(entry.auditId()))
                    .append(", \"tag\": ").append(MiniJson.quote(entry.tag()))
                    .append(", \"at\": ").append(MiniJson.quote(entry.at().toString()))
                    .append('}').append(++a < node.getValue().size() ? ",\n" : "\n");
            }
            json.append("  }").append(++n < ledger.size() ? ",\n" : "\n");
        }
        json.append("}\n");
        try {
            if (!Files.isDirectory(directory)) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            }
            Path temporary = Files.createTempFile(directory, ".ledger-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(
                    "rw-------")));
            try {
                Files.writeString(temporary, json, StandardCharsets.UTF_8);
                Files.move(temporary, file(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | UnsupportedOperationException e) {
            throw unusable("cannot be written (" + e.getClass().getSimpleName() + ")",
                "The directory is not writable or does not support owner-only permissions",
                "Check ~/.inubit-mcp/<profile>/deployments, then retry");
        }
    }

    private ToolErrorException unusable(String what, String likelyCause, String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            "The deployment ledger " + file() + " " + what, likelyCause, nextStep));
    }
}
