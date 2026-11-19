package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import de.dadecker.inubit.mcp.domain.model.Target;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audit records of the changes of feature 022 (FR-023, data-model.md → AuditRecord), built
 * like those of {@code ProcessControlService}. One {@link Call} has one {@code auditId}; the
 * capability is the tool name. A call writes exactly one first record:
 *
 * <ul>
 *   <li>{@code CHALLENGE_ISSUED} for a preview;
 *   <li>{@code REFUSED} for a refusal;
 *   <li>{@code PENDING}, forced to disk before anything is sent (fail closed: if it cannot be
 *       written, the call is {@code INTERNAL} and nothing may be sent), then {@code EXECUTED} or
 *       {@code FAILED} with the same {@code auditId};
 *   <li>{@code EXECUTED} with the detail {@value #NOTHING_TO_DO} when there was nothing to do and
 *       nothing was sent (spec edge case).
 * </ul>
 *
 * <p>The inputs are cleaned: {@code action} first, then the call's inputs in their order, each
 * cut to {@value #MAX_AUDITED_INPUT} characters and the message {@value #MESSAGE_TEXT} to
 * {@value #MAX_AUDITED_TEXT}. A {@code secretEntry} input holds only the entry's name, which is
 * recorded; its value is never given to this class. An upload is recorded by
 * {@link Call#upload path, size and SHA-256}, never by content. The reason text is masked
 * ({@link SecretTextMasker}) and cut to {@value #MAX_AUDITED_INPUT} characters.
 */
public final class ChangeAudit {

    /** The detail of an {@code EXECUTED} record of a call that had nothing to do. */
    public static final String NOTHING_TO_DO = "nothing-to-do";
    /** The input holding the text of a message to INUBIT users ({@code sendMessage}). */
    public static final String MESSAGE_TEXT = "text";
    static final int MAX_AUDITED_INPUT = 500;
    static final int MAX_AUDITED_TEXT = 200;

    private static final Logger LOG = LoggerFactory.getLogger(ChangeAudit.class);
    private static final String AUDIT_CAUSE =
        "The audit directory is not writable, full, or not owned by this user";
    private static final String AUDIT_NEXT_STEP =
        "Fix the audit directory (auditDirectory in the configuration), then retry";

    private final AuditPort port;
    private final Clock clock;
    private final String profile;
    private final Supplier<UUID> ids;

    /**
     * @param port    the append-only audit log
     * @param clock   the time of each record
     * @param profile the name of the profile this MCP server serves
     * @param ids     the source of the {@code auditId}s (one per call)
     */
    public ChangeAudit(AuditPort port, Clock clock, String profile, Supplier<UUID> ids) {
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * Starts the audit of one call of {@code tool}.
     *
     * @param node      the server id as requested (also when it is no server id)
     * @param action    the tool's action
     * @param input     the call's inputs as requested, in their order
     * @param mcpClient the MCP client's {@code name/version}, if sent
     */
    public Call call(String tool, String node, String action, Map<String, ?> input,
        Optional<String> mcpClient) {
        return new Call(tool, node, action, input, mcpClient);
    }

    /** An uploaded file as the audit names it: its real path, size and hex SHA-256. */
    public record Upload(Path path, long size, String sha256) {

        public Upload {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha256, "sha256");
        }

        private String audited() {
            return path + " (" + size + " bytes, sha256:" + sha256 + ")";
        }
    }

    /** The records of one call. */
    public final class Call {

        private final String tool;
        private final String node;
        private final Map<String, String> inputs = new LinkedHashMap<>();
        private final Optional<String> mcpClient;
        private final UUID auditId;
        private Optional<String> account = Optional.empty();
        private boolean pending;
        private boolean finished;

        private Call(String tool, String node, String action, Map<String, ?> input,
            Optional<String> mcpClient) {
            this.tool = Objects.requireNonNull(tool, "tool");
            this.node = bounded(Objects.requireNonNull(node, "node"), MAX_AUDITED_INPUT);
            inputs.put("action", bounded(Objects.requireNonNull(action, "action"),
                MAX_AUDITED_INPUT));
            input.forEach((key, value) -> inputs.put(key, bounded(String.valueOf(value),
                MESSAGE_TEXT.equals(key) ? MAX_AUDITED_TEXT : MAX_AUDITED_INPUT)));
            this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient");
            this.auditId = Objects.requireNonNull(ids.get(), "auditId");
        }

        /** The id of every record of this call. */
        public UUID auditId() {
            return auditId;
        }

        /** The INUBIT username of the server, once known. */
        public void account(Optional<String> serverAccount) {
            this.account = Objects.requireNonNull(serverAccount, "serverAccount");
        }

        /** Records the input {@code key} as {@code upload}'s path, size and SHA-256. */
        public void upload(String key, Upload upload) {
            inputs.put(Objects.requireNonNull(key, "key"),
                bounded(upload.audited(), MAX_AUDITED_INPUT));
        }

        /**
         * Appends the {@code CHALLENGE_ISSUED} record of a preview.
         *
         * @throws ToolErrorException {@code INTERNAL} if the record could not be written; the
         *     code must not be issued then
         */
        public void challengeIssued(String issuedCode, String text) {
            requireFirst();
            finished = true;
            Map<String, String> issued = new LinkedHashMap<>(inputs);
            issued.put(AuditRecord.ISSUED_CONFIRMATION_CODE, issuedCode);
            try {
                port.append(record(AuditRecord.Step.PREVIEW, issued,
                    AuditOutcome.CHALLENGE_ISSUED, text, Map.of()));
            } catch (RuntimeException e) {
                throw internal(AuditOutcome.CHALLENGE_ISSUED, e, "the preview was not issued");
            }
        }

        /**
         * Audits a refusal as one {@code REFUSED} record.
         *
         * @param step {@code EXECUTE} if the call would have executed (it carried a code, or
         *     needs none), otherwise {@code PREVIEW}
         * @return {@code error}, or {@code INTERNAL} naming it if the record could not be written
         */
        public ToolErrorException refused(AuditRecord.Step step, ToolError error) {
            requireFirst();
            finished = true;
            String reason = error.code() + ": " + error.message();
            try {
                port.append(record(step, inputs, AuditOutcome.REFUSED, reason, Map.of()));
            } catch (RuntimeException e) {
                logFailure(AuditOutcome.REFUSED, e);
                return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                    "The audit record could not be written; the call was refused anyway ("
                        + cleaned(reason) + ") and nothing was changed", AUDIT_CAUSE,
                    AUDIT_NEXT_STEP));
            }
            return new ToolErrorException(error);
        }

        /**
         * Appends the one {@code EXECUTED} record of a call that had nothing to do, with the
         * detail {@value #NOTHING_TO_DO}; nothing may have been sent.
         *
         * @throws ToolErrorException {@code INTERNAL} if the record could not be written
         */
        public void nothingToDo(String text) {
            requireFirst();
            finished = true;
            try {
                port.append(record(AuditRecord.Step.EXECUTE, inputs, AuditOutcome.EXECUTED, text,
                    Map.of(NOTHING_TO_DO, true)));
            } catch (RuntimeException e) {
                throw internal(AuditOutcome.EXECUTED, e, "nothing was changed");
            }
        }

        /**
         * Appends the {@code PENDING} record, forced to disk before anything is sent.
         *
         * @throws ToolErrorException {@code INTERNAL} if the record could not be written; nothing
         *     may be sent then
         */
        public void pending(String text) {
            requireFirst();
            try {
                port.append(record(AuditRecord.Step.EXECUTE, inputs, AuditOutcome.PENDING, text,
                    Map.of()));
            } catch (RuntimeException e) {
                finished = true;
                throw internal(AuditOutcome.PENDING, e, "nothing was sent to INUBIT");
            }
            pending = true;
        }

        /**
         * Closes the {@link #pending} record with {@code EXECUTED}.
         *
         * @return a result warning naming the {@code auditId} if the record could not be written
         */
        public Optional<String> executed(String text) {
            return close(AuditOutcome.EXECUTED, text);
        }

        /**
         * Closes the {@link #pending} record with {@code FAILED}.
         *
         * @return a result warning naming the {@code auditId} if the record could not be written
         */
        public Optional<String> failed(String text) {
            return close(AuditOutcome.FAILED, text);
        }

        private Optional<String> close(AuditOutcome outcome, String text) {
            if (!pending || finished) {
                throw new IllegalStateException("No pending audit record to close");
            }
            finished = true;
            try {
                port.append(record(AuditRecord.Step.EXECUTE, inputs, outcome, text, Map.of()));
                return Optional.empty();
            } catch (RuntimeException e) {
                logFailure(outcome, e);
                return Optional.of("The final audit record could not be written (only the pending"
                    + " record " + auditId + " exists); check the audit directory (auditDirectory"
                    + " in the configuration)");
            }
        }

        private void requireFirst() {
            if (pending || finished) {
                throw new IllegalStateException("This call already wrote its first audit record");
            }
        }

        private AuditRecord record(AuditRecord.Step step, Map<String, String> audited,
            AuditOutcome outcome, String text, Map<String, Object> details) {
            return new AuditRecord(auditId, clock.instant(), profile, node, group(), tool, step,
                audited, account, outcome, Optional.of(cleaned(text)), mcpClient,
                Optional.empty(), details);
        }

        private Optional<String> group() {
            try {
                return Optional.of(switch (Target.parse(node)) {
                    case Target.Group group -> group.id().value();
                    case Target.Node server -> server.id().group().value();
                });
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        private ToolErrorException internal(AuditOutcome outcome, RuntimeException e,
            String consequence) {
            logFailure(outcome, e);
            return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The audit record could not be written; " + consequence, AUDIT_CAUSE,
                AUDIT_NEXT_STEP));
        }

        private void logFailure(AuditOutcome outcome, RuntimeException e) {
            LOG.error("Audit record {} of {} ({}) could not be written ({})", auditId, tool,
                outcome, e.getClass().getSimpleName());
        }
    }

    /** {@code text} masked and cut to {@value #MAX_AUDITED_INPUT} characters. */
    private static String cleaned(String text) {
        return bounded(SecretTextMasker.mask(Objects.requireNonNull(text, "text")),
            MAX_AUDITED_INPUT);
    }

    private static String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
