package de.dadecker.inubit.mcp.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The secret rules of feature 018 (FR-008 there), moved out of the archive adapter so that
 * configuration values, module properties and user records are masked by the same rule set
 * (feature 022 research D-19, FR-010). A value is masked when
 * <ol>
 * <li>it is flagged {@code encrypted}, or</li>
 * <li>its type contains a password, key or certificate type pattern (case-insensitive), or</li>
 * <li>its name contains a secret name pattern (case-insensitive), whatever its value, or</li>
 * <li>its value is key material ({@link KeyMaterial}).</li>
 * </ol>
 * The answer is a yes or no only; which rule matched is not exposed.
 */
public final class SecretRules {

    private static final Pattern SECRET_NAME = Pattern.compile("password|passwd|pwd|secret|token"
        + "|keystore|privatekey|passphrase|credential|apikey|accesskey", Pattern.CASE_INSENSITIVE);

    private static final Pattern SECRET_TYPE = Pattern.compile(
        "password|key|truststore|x509|certificate", Pattern.CASE_INSENSITIVE);

    private SecretRules() {
    }

    /** True if {@code name} contains a secret name pattern. */
    public static boolean isSecretName(String name) {
        return SECRET_NAME.matcher(name).find();
    }

    /**
     * True if any segment of {@code path} contains a secret name pattern, so every value below a
     * secret name is masked. The patterns hold no separator, so the path is matched as a whole.
     */
    public static boolean isSecretPath(String path) {
        return isSecretName(path);
    }

    /**
     * True if the value {@code value} named {@code name} must be masked.
     *
     * @param type      the declared type of the value, if there is one
     * @param encrypted whether the value is flagged as encrypted
     */
    public static boolean masks(String name, Optional<String> type, boolean encrypted,
        String value) {
        return encrypted
            || type.filter(t -> SECRET_TYPE.matcher(t).find()).isPresent()
            || isSecretName(name)
            || isKeyMaterial(type, value);
    }

    /**
     * A keystore or PEM private key as text, base64 of at least 64 characters, or base64 of any
     * length in an {@code InternalDocument}.
     */
    private static boolean isKeyMaterial(Optional<String> type, String value) {
        if (KeyMaterial.isKeyMaterial(value.getBytes(StandardCharsets.ISO_8859_1))) {
            return true;
        }
        boolean decode = type.filter("InternalDocument"::equals).isPresent()
            || KeyMaterial.longBase64(value);
        return decode && KeyMaterial.decodeDocument(value).filter(KeyMaterial::isKeyMaterial)
            .isPresent();
    }
}
