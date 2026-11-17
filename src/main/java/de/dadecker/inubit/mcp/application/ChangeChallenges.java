package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ChangePreview;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The server-side confirmation of the changes of feature 022 (research D-13, D-14), on the
 * {@link WriteChallengeRegistry} keyed by tool name and node:
 *
 * <ul>
 *   <li>the preview issues a code bound to the tool, the node and the {@link #fingerprint} of the
 *       inputs, carrying the state the preview showed ({@code previewState});
 *   <li>the confirming call redeems it, reads the state again right before sending and passes
 *       both to {@link #confirmOrConflict}: a different state is {@code CONFLICT} with a fresh
 *       preview and code;
 *   <li>{@code write.confirmation: CLIENT} lets the first call execute only for
 *       {@link ActionSpec.Area#OPERATION}; the administration areas always need a server code
 *       ({@link #needsCode}).
 * </ul>
 */
public final class ChangeChallenges {

    /** The inputs the fingerprint leaves out: they may differ between preview and confirmation. */
    private static final Set<String> UNBOUND_INPUTS = Set.of(AuditRecord.CONFIRMATION_CODE,
        "reason");

    private final WriteChallengeRegistry registry;

    public ChangeChallenges(WriteChallengeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Whether a call of {@code area} on the node of {@code policy} must be confirmed with a server
     * code: always, except {@code OPERATION} under {@code CLIENT} confirmation.
     */
    public static boolean needsCode(WritePolicy policy, ActionSpec.Area area) {
        return area != ActionSpec.Area.OPERATION
            || policy.confirmation() == WritePolicy.Confirmation.SERVER;
    }

    /**
     * The hex SHA-256 of the canonical form of {@code input} without {@code confirmationCode} and
     * {@code reason}: maps sorted by key, lists in order, strings quoted and escaped, so that
     * values that print alike (the string {@code "1"} and the number {@code 1}) differ.
     */
    public static String fingerprint(Map<String, ?> input) {
        Map<String, Object> bound = new TreeMap<>();
        input.forEach((key, value) -> {
            if (!UNBOUND_INPUTS.contains(key)) {
                bound.put(key, value);
            }
        });
        StringBuilder canonical = new StringBuilder();
        append(canonical, bound);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String text -> quote(out, text);
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                map.forEach((key, entry) -> sorted.put(String.valueOf(key), entry));
                out.append('{');
                String separator = "";
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    out.append(separator);
                    quote(out, entry.getKey());
                    out.append(':');
                    append(out, entry.getValue());
                    separator = ",";
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    append(out, list.get(i));
                }
                out.append(']');
            }
            default -> out.append(value);
        }
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"').append(text.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }

    /**
     * Issues the code of a preview of {@code tool} on {@code node}.
     *
     * @param fingerprint  the {@link #fingerprint} of the call's inputs
     * @param previewState the canonical state the preview showed (research D-14)
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if too many codes are pending
     */
    public ChangePreview.Confirmation issue(String tool, NodeId node, String fingerprint,
        String previewState, Duration ttl) {
        WriteChallengeRegistry.Issued issued = registry.issue(tool, node, fingerprint,
            previewState, ttl);
        return new ChangePreview.Confirmation(issued.code(), issued.expiresAt());
    }

    /**
     * Redeems {@code code} for exactly this tool, node and inputs; the code is used up in any
     * case.
     *
     * @return the state the preview showed
     * @throws ToolErrorException {@code CONFIRMATION_INVALID} if the code is unknown, used,
     *     expired, or bound to another tool, node or inputs
     */
    public String redeem(String code, String tool, NodeId node, String fingerprint) {
        return registry.redeem(code, tool, node, fingerprint);
    }

    /**
     * Returns if the state read again right before sending is the state of the preview.
     * Otherwise issues a fresh code for the re-read state and refuses.
     *
     * @param redeemedState the state {@link #redeem} returned
     * @param rereadState   the state read again now, in the same canonical form
     * @param fingerprint   the fingerprint the code was redeemed with
     * @param freshPreview  the preview of the re-read state; its confirmation is replaced by the
     *                      fresh code
     * @throws Conflict {@code CONFLICT}, carrying {@code freshPreview} with the fresh code
     */
    public void confirmOrConflict(String redeemedState, String rereadState, String fingerprint,
        ChangePreview freshPreview, Duration ttl) {
        Objects.requireNonNull(redeemedState, "redeemedState");
        Objects.requireNonNull(rereadState, "rereadState");
        if (redeemedState.equals(rereadState)) {
            return;
        }
        NodeId node = freshPreview.node();
        ChangePreview.Confirmation fresh = issue(freshPreview.tool(), node, fingerprint,
            rereadState, ttl);
        ChangePreview preview = new ChangePreview(node, freshPreview.tool(),
            freshPreview.action(), freshPreview.area(), freshPreview.target(),
            freshPreview.before(), freshPreview.requested(), freshPreview.affected(),
            freshPreview.effects(), freshPreview.backup(), Optional.of(fresh),
            freshPreview.warnings());
        throw new Conflict(ToolError.of(ErrorCode.CONFLICT,
            "The state of " + preview.target() + " on " + node + " changed since the preview;"
                + " nothing was sent",
            "Someone else changed " + preview.target() + " on " + node + " after the preview",
            "Check the new preview in the error details and confirm it with its code before "
                + fresh.expiresAt()).withNode(node), preview);
    }

    /** The {@code CONFLICT} of {@link #confirmOrConflict}, with the fresh preview and code. */
    public static final class Conflict extends ToolErrorException {

        private static final long serialVersionUID = 1L;

        private final transient ChangePreview preview;

        private Conflict(ToolError error, ChangePreview preview) {
            super(error);
            this.preview = preview;
        }

        /** The preview of the re-read state, with the fresh code. */
        public ChangePreview preview() {
            return preview;
        }
    }
}
