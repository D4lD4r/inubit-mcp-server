package de.dadecker.inubit.mcp.domain.model.node;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One member of the INUBIT cluster as {@code getNodes()} returns it (research D-7). The adapter
 * throws {@code UNEXPECTED_RESPONSE} for a member that breaks the rules below; the constructor
 * rejects such a member, too, without quoting it.
 *
 * @param name      1–128 characters of {@code [A-Za-z0-9_.:-]}
 * @param rawStatus the status text INUBIT returned, only beside {@link NodeStatus#UNKNOWN}: 1–64
 *                  characters of {@code [A-Za-z0-9_.-]}
 * @param version   at most {@value #MAX_TEXT_CHARS} characters, as {@code zone} and
 *                  {@code nodeId}
 * @param uptime    not negative; absent for a member that does not answer
 */
public record ClusterNode(
    String name,
    NodeStatus status,
    Optional<String> rawStatus,
    Optional<String> version,
    Optional<String> zone,
    Optional<String> nodeId,
    Optional<Duration> uptime) {

    public static final int MAX_TEXT_CHARS = 200;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern RAW_STATUS = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public ClusterNode {
        checkName(name);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rawStatus, "rawStatus");
        if (rawStatus.isPresent()) {
            if (status != NodeStatus.UNKNOWN) {
                throw new IllegalArgumentException("A raw status is kept only for UNKNOWN");
            }
            if (!RAW_STATUS.matcher(rawStatus.get()).matches()) {
                throw new IllegalArgumentException(
                    "Invalid raw status: expected 1 to 64 characters of [A-Za-z0-9_.-]");
            }
        }
        checkText("version", version);
        checkText("zone", zone);
        checkText("nodeId", nodeId);
        Objects.requireNonNull(uptime, "uptime");
        if (uptime.isPresent() && uptime.get().isNegative()) {
            throw new IllegalArgumentException("Invalid uptime: negative");
        }
    }

    /** True if {@code name} has 1–128 characters of {@code [A-Za-z0-9_.:-]}. */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** The name rule for every INUBIT node name of this package; the message never quotes it. */
    static void checkName(String name) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException(
                "Invalid node name: expected 1 to 128 characters of [A-Za-z0-9_.:-]");
        }
    }

    private static void checkText(String field, Optional<String> text) {
        Objects.requireNonNull(text, field);
        if (text.isPresent() && text.get().length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException(
                "Invalid " + field + ": more than " + MAX_TEXT_CHARS + " characters");
        }
    }
}
