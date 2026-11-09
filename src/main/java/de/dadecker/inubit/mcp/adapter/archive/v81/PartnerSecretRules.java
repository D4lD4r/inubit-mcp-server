package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether a value of a Partner Management must be masked (feature 018 FR-008, research D-3). A
 * value is masked when
 * <ol>
 * <li>the workspace export's redaction sees a secret in it ({@link SecretPaths#kind}), so the
 * masking is at least as strict as feature 003's, or</li>
 * <li>its property has {@code encrypted="true"} (any case, surrounding white space ignored),
 * or</li>
 * <li>its {@code type} contains a password, key or certificate type pattern (case-insensitive),
 * or</li>
 * <li>its name contains a secret name pattern (case-insensitive), whatever its value, or</li>
 * <li>its value is key material ({@link SecretPaths#isKeyMaterial}), or</li>
 * <li>it is a partner field whose {@code fieldType} is missing or not on
 * {@link #PLAIN_FIELD_TYPES}.</li>
 * </ol>
 * The answer is a yes or no only; which rule matched is not exposed.
 */
final class PartnerSecretRules {

    /** The partner field types shown in clear (research D-2 facts, recorded export). */
    static final Set<String> PLAIN_FIELD_TYPES = Set.of("String");

    private static final Pattern SECRET_NAME = Pattern.compile("password|passwd|pwd|secret|token"
        + "|keystore|privatekey|passphrase|credential|apikey|accesskey", Pattern.CASE_INSENSITIVE);

    private static final Pattern SECRET_TYPE = Pattern.compile(
        "password|key|truststore|x509|certificate", Pattern.CASE_INSENSITIVE);

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
            || encrypted
            || SECRET_TYPE.matcher(type).find()
            || SECRET_NAME.matcher(name).find()
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
