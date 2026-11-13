package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.SecretRules;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a value of a Partner Management must be masked (feature 018 FR-008, research D-3). A
 * value is masked when
 * <ol>
 * <li>the workspace export's redaction sees a secret in it ({@link SecretPaths#kind}), so the
 * masking is at least as strict as feature 003's, or</li>
 * <li>{@link SecretRules} masks it (feature 022 research D-19: the {@code encrypted} flag, the
 * secret type and name patterns and key material, moved there from here); its property's
 * {@code encrypted} attribute counts as {@code true} in any case and without surrounding white
 * space, or</li>
 * <li>it is key material by its property ({@link SecretPaths#isKeyMaterial}, e.g. the
 * {@code documentName} of an {@code InternalDocument}), or</li>
 * <li>it is a partner field whose {@code fieldType} is missing or not on
 * {@link #PLAIN_FIELD_TYPES}.</li>
 * </ol>
 * The answer is a yes or no only; which rule matched is not exposed.
 */
final class PartnerSecretRules {

    /** The partner field types shown in clear (research D-2 facts, recorded export). */
    static final Set<String> PLAIN_FIELD_TYPES = Set.of("String");

    private PartnerSecretRules() {
    }

    /** True if the value of the leaf {@code Property} element {@code property} must be masked. */
    static boolean masked(Element property) {
        String name = property.attribute("name").orElse("");
        String type = property.attribute("type").orElse("");
        boolean encrypted = property.attribute("encrypted").map(String::strip)
            .filter("true"::equalsIgnoreCase).isPresent();
        String value = property.text();
        return SecretPaths.kind(name, type, encrypted, value) != null
            || SecretRules.masks(name, Optional.of(type), encrypted, value)
            // the documentName of an InternalDocument, which SecretRules does not see
            || SecretPaths.isKeyMaterial(property, type, value);
    }

    /**
     * True if the value of the partner field {@code property} must be masked.
     *
     * @param fieldType the {@code fieldType} declared for the field in {@code PartnerFields}, or
     *                  {@code null} or {@code ""} if the field is not declared there (masked)
     */
    static boolean maskedField(Element property, String fieldType) {
        return fieldType == null || !PLAIN_FIELD_TYPES.contains(fieldType) || masked(property);
    }
}
