package de.dadecker.inubit.mcp.infra;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-safe registry of secret values that replaces every occurrence of a registered value with
 * {@code ***} (Constitution II, research R-12).
 *
 * <p>Each secret is registered in all the spellings of {@link SecretForms} (JSON, XML and URL
 * escaped, Base64), so that it is also found inside encoded payloads.
 *
 * <p>All occurrences of all secrets are located first, including overlapping ones; every maximal
 * run of characters covered by at least one occurrence becomes a single {@code ***}. This gives
 * longest-match behaviour and leaves no fragment of an overlapping occurrence behind.
 */
public final class SecretScrubber {

    public static final String MASK = "***";

    private static final SecretScrubber GLOBAL = new SecretScrubber();

    private final Object lock = new Object();
    private final Set<String> values = new LinkedHashSet<>();
    /** Longest first; replaced as a whole on every registration (copy-on-write). */
    private volatile List<String> snapshot = List.of();

    /** The process-wide scrubber used by logging and result mapping. */
    public static SecretScrubber global() {
        return GLOBAL;
    }

    /** Registers {@code value} in all its encoded forms and returns it as a {@link Secret}. */
    public Secret register(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.isEmpty()) {
            synchronized (lock) {
                if (values.addAll(SecretForms.of(value))) {
                    snapshot = values.stream()
                        .sorted(Comparator.comparingInt(String::length).reversed())
                        .toList();
                }
            }
        }
        return Secret.of(value);
    }

    /** Registers the value of an existing secret. */
    public Secret register(Secret secret) {
        register(secret.reveal());
        return secret;
    }

    /** Registers and returns the HTTP Basic-auth token {@code base64(username:password)}. */
    public Secret registerBasicAuth(String username, Secret password) {
        String credentials = username + ":" + password.reveal();
        return register(Base64.getEncoder()
            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }

    /** Returns {@code text} with every registered secret replaced by {@code ***}. */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> secrets = snapshot;
        if (secrets.isEmpty()) {
            return text;
        }
        boolean[] covered = null;
        for (String secret : secrets) {
            int from = text.indexOf(secret);
            while (from >= 0) {
                if (covered == null) {
                    covered = new boolean[text.length()];
                }
                Arrays.fill(covered, from, from + secret.length(), true);
                from = text.indexOf(secret, from + 1);
            }
        }
        return covered == null ? text : mask(text, covered);
    }

    /** Returns whether {@code text} contains any registered secret. */
    public boolean containsSecret(String text) {
        if (text == null) {
            return false;
        }
        for (String secret : snapshot) {
            if (text.contains(secret)) {
                return true;
            }
        }
        return false;
    }

    private static String mask(String text, boolean[] covered) {
        StringBuilder result = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (covered[i]) {
                result.append(MASK);
                while (i < text.length() && covered[i]) {
                    i++;
                }
            } else {
                result.append(text.charAt(i++));
            }
        }
        return result.toString();
    }
}
