package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The preview of a change tool (feature 022, data-model.md → ChangePreview; generalises
 * {@link WritePreview}): nothing was sent. Under server-side confirmation the change runs only
 * when the same call is repeated with the code of {@code confirmation}.
 *
 * @param area         the administration area of the action (research D-12), e.g.
 *                     {@code operation}
 * @param target       object kind and name, path, user, operation id, or {@code bulk}
 * @param before       the current state as shown, masked by {@link SecretTextMasker#mask}
 * @param requested    the requested state, masked the same way
 * @param affected     for bulk actions: the affected objects; empty otherwise
 * @param effects      fixed sentences on the effects of the change
 * @param backup       what will be stored first, or {@code none: <reason>}
 * @param confirmation the code and its expiry; empty in {@code CLIENT} mode
 */
public record ChangePreview(NodeId node, String tool, String action, String area, String target,
    String before, String requested, Optional<Affected> affected, List<String> effects,
    String backup, Optional<Confirmation> confirmation, List<String> warnings) {

    public ChangePreview {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(target, "target");
        before = SecretTextMasker.mask(before);
        requested = SecretTextMasker.mask(requested);
        affected = affected == null ? Optional.empty() : affected;
        effects = List.copyOf(effects);
        Objects.requireNonNull(backup, "backup");
        confirmation = confirmation == null ? Optional.empty() : confirmation;
        warnings = List.copyOf(warnings);
    }

    /**
     * The objects a bulk action affects: their number and up to {@value #MAX_IDS} of their ids or
     * names. Where INUBIT gives no ids, {@code ids} is empty and {@code count} alone tells the size.
     *
     * @param truncated true if {@code ids} holds fewer than {@code count} entries
     */
    public record Affected(int count, List<String> ids, boolean truncated) {

        /** The largest number of ids a preview lists. */
        public static final int MAX_IDS = 50;

        public Affected {
            ids = List.copyOf(ids);
            if (ids.size() > MAX_IDS) {
                throw new IllegalArgumentException("more than " + MAX_IDS + " affected ids");
            }
            if (count < ids.size()) {
                throw new IllegalArgumentException("count " + count + " is below the "
                    + ids.size() + " listed ids");
            }
            if (truncated != (count > ids.size())) {
                throw new IllegalArgumentException("truncated must be true exactly when count "
                    + count + " exceeds the " + ids.size() + " listed ids");
            }
        }

        /** All of {@code ids} counted, the first {@value #MAX_IDS} listed. */
        public static Affected of(List<String> ids) {
            List<String> listed = ids.size() > MAX_IDS ? ids.subList(0, MAX_IDS) : ids;
            return new Affected(ids.size(), listed, ids.size() > MAX_IDS);
        }
    }

    /** The confirmation code of research D-13 and when it expires. */
    public record Confirmation(String code, Instant expiresAt) {

        public Confirmation {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
