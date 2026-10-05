package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One line of the append-only audit log (FR-023, data-model.md → AuditRecord, research R-14).
 *
 * <p>The audit log scrubs every value and stores a confirmation code only as a SHA-256 prefix;
 * this record may therefore carry the code as the caller received it.
 *
 * @param profile    the name of the profile this MCP server serves (002 research D-7)
 * @param node       the node id as requested (also when it is unknown)
 * @param group      the group of {@code node}, if the id could be parsed
 * @param capability the tool name ({@code restart_process}, {@code kill_process})
 * @param inputs     the call's inputs in insertion order ({@code processId},
 *                   {@code confirmationCode}, {@code reason}, …)
 * @param account    the INUBIT username of the server, if the server is configured
 * @param reason     refusal or failure code and message, or the result text
 * @param mcpClient  the MCP client's {@code name/version} from {@code initialize}, if sent
 */
public record AuditRecord(
    UUID auditId,
    Instant timestamp,
    String profile,
    String node,
    Optional<String> group,
    String capability,
    Step step,
    Map<String, String> inputs,
    Optional<String> account,
    AuditOutcome outcome,
    Optional<String> reason,
    Optional<String> mcpClient) {

    /** {@code PREVIEW}: the first call of a two-step confirmation; {@code EXECUTE}: the action. */
    public enum Step {
        PREVIEW,
        EXECUTE
    }

    /** Input key of the confirmation code; stored only as its SHA-256 prefix. */
    public static final String CONFIRMATION_CODE = "confirmationCode";
    /** Input key of the code issued by a preview; stored only as its SHA-256 prefix. */
    public static final String ISSUED_CONFIRMATION_CODE = "issuedConfirmationCode";

    public AuditRecord {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(node, "node");
        group = group == null ? Optional.empty() : group;
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(step, "step");
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        account = account == null ? Optional.empty() : account;
        Objects.requireNonNull(outcome, "outcome");
        reason = reason == null ? Optional.empty() : reason;
        mcpClient = mcpClient == null ? Optional.empty() : mcpClient;
    }
}
