package de.dadecker.inubit.mcp.domain.model.partner;

import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import java.util.Objects;
import java.util.Optional;

/**
 * The value of a partner setting (data-model.md → SettingValue): shown as found, or masked. A
 * secret value exists only as {@link Masked}.
 */
public sealed interface SettingValue {

    /** A non-secret value; compared uncut, cut only when rendered. */
    record Shown(String value) implements SettingValue {
        public Shown {
            Objects.requireNonNull(value, "value");
        }

        /**
         * The value cut to {@link ItemBounds#MAX_FIELD_CHARS} chars, ending with
         * {@link ItemBounds#TRUNCATION_MARKER} when cut (a surrogate pair is never split).
         */
        public String rendered() {
            int max = ItemBounds.MAX_FIELD_CHARS;
            if (value.length() <= max) {
                return value;
            }
            int end = max - ItemBounds.TRUNCATION_MARKER.length();
            if (Character.isHighSurrogate(value.charAt(end - 1))) {
                end--;
            }
            return value.substring(0, end) + ItemBounds.TRUNCATION_MARKER;
        }
    }

    /**
     * A secret value.
     *
     * @param set         whether the stored value is non-empty
     * @param fingerprint the keyed fingerprint of the stored value; present only when {@code set}
     */
    record Masked(boolean set, Optional<SecretFingerprint> fingerprint) implements SettingValue {
        public Masked {
            fingerprint = fingerprint == null ? Optional.empty() : fingerprint;
            if (!set && fingerprint.isPresent()) {
                throw new IllegalArgumentException("an unset value has no fingerprint");
            }
        }
    }
}
