package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A part of a health report, of server settings or of one user of {@code read_users} (feature
 * 022, FR-013) that could not be obtained, with the reason (FR-008). A tool reports only its own
 * parts; {@code get_health} documents its subset in its output schema.
 *
 * @param part        one of {@link #PARTS}
 * @param reason      starts with a code, e.g. {@code METRICS_NOT_LICENSED: …} or
 *                    {@code TIMEOUT: …}
 * @param likelyCause the likely cause, copied from the {@link ToolError} (Phase 3 review m1)
 * @param nextStep    the suggested next step, copied from the {@link ToolError}
 */
public record UnavailablePart(String part, String reason, Optional<String> likelyCause,
    Optional<String> nextStep) {

    public static final String STATUS = "status";
    public static final String READY = "ready";
    public static final String VERSION = "version";
    public static final String SYSTEM_INFO = "systemInfo";
    public static final String LOAD = "load";
    /** The StartCLI client probe (feature 009, FR-002). */
    public static final String CLIENT = "client";
    /**
     * The thread figures of the tool that reports it: in {@code get_health} threads in use,
     * licensed threads and maximum threads (feature 012, FR-008); in {@code get_server_settings}
     * the configured workflow threads ({@link ServerSettings#workflowThreads()}).
     */
    public static final String THREADS = "threads";
    /** Blocking queue entries, maximum size and percentage (feature 012, FR-008). */
    public static final String BLOCKING_QUEUE = "blockingQueue";
    /** The process counts per state of an INUBIT 6.1 node (feature 012, FR-008). */
    public static final String PROCESS_COUNTS = "processCounts";
    /** The license facts of server settings (feature 022, {@link LicenseFacts}). */
    public static final String LICENSE = "license";
    /** The privileged workflows of server settings (feature 022, {@link PrivilegedWorkflows}). */
    public static final String PRIVILEGED_WORKFLOWS = "privilegedWorkflows";
    /** The DB connection tolerance of server settings (feature 022). */
    public static final String DB_CONNECTION_TOLERANCE = "dbConnectionTolerance";
    /** The server configuration of server settings (feature 022, {@link ConfigurationView}). */
    public static final String CONFIGURATION = "configuration";
    /** The server time of server settings (feature 022). */
    public static final String SERVER_TIME = "serverTime";
    /** The group and super group of one user of {@code read_users} (feature 022, US2). */
    public static final String GROUPS = "groups";
    /** The roles of one user of {@code read_users} (feature 022, US2). */
    public static final String ROLES = "roles";
    /** The e-mail address of the one user of the {@code read_users} view {@code user}. */
    public static final String EMAIL = "email";
    /** The other personal fields of the one user of the {@code read_users} view {@code user}. */
    public static final String PERSONAL = "personal";
    public static final Set<String> PARTS = Set.of(STATUS, READY, VERSION, SYSTEM_INFO, LOAD,
        CLIENT, THREADS, BLOCKING_QUEUE, PROCESS_COUNTS, LICENSE, PRIVILEGED_WORKFLOWS,
        DB_CONNECTION_TOLERANCE, CONFIGURATION, SERVER_TIME, GROUPS, ROLES, EMAIL, PERSONAL);

    /** Reason code of a {@code /metrics} failure that indicates a missing license (R-10). */
    public static final String METRICS_NOT_LICENSED = "METRICS_NOT_LICENSED";
    /** Reason code of a part INUBIT 6.1 does not offer (feature 012, FR-008). */
    public static final String NOT_AVAILABLE_ON_6_1 = "NOT_AVAILABLE_ON_6_1";

    public UnavailablePart {
        if (!PARTS.contains(part)) {
            throw new IllegalArgumentException("unknown part " + Names.quote(part));
        }
        Objects.requireNonNull(reason, "reason");
        likelyCause = likelyCause == null ? Optional.empty() : likelyCause;
        nextStep = nextStep == null ? Optional.empty() : nextStep;
    }

    /** A part with a reason only. */
    public UnavailablePart(String part, String reason) {
        this(part, reason, Optional.empty(), Optional.empty());
    }

    /** The part with {@code CODE: message} of {@code error} as reason, plus cause and step. */
    public static UnavailablePart of(String part, ToolError error) {
        return new UnavailablePart(part, error.code() + ": " + error.message(),
            Optional.of(error.likelyCause()), Optional.of(error.nextStep()));
    }
}
