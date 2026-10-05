package de.dadecker.inubit.mcp.infra;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The spellings in which a secret can appear in text the server forwards:
 *
 * <ul>
 *   <li>raw;
 *   <li>JSON-escaped, with and without {@code \/}, and with non-ASCII characters as
 *       <code>&#92;uXXXX</code> (upper- and lowercase hex, surrogate pairs for non-BMP characters);
 *   <li>XML-escaped: attribute form (all five entities), text-content form (only {@code &},
 *       {@code <}, {@code >}), and numeric character references for quotes
 *       ({@code &#34;}/{@code &#39;} and {@code &#x22;}/{@code &#x27;});
 *   <li>URL-encoded ({@code +} and {@code %20} for spaces), with upper- and lowercase hex;
 *   <li>Base64.
 * </ul>
 */
final class SecretForms {

    private SecretForms() {
    }

    static Set<String> of(String value) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(value);
        String json = jsonEscape(value);
        forms.add(json);
        forms.add(json.replace("/", "\\/"));
        forms.add(unicodeEscapeNonAscii(json, false));
        forms.add(unicodeEscapeNonAscii(json, true));
        String xmlText = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        forms.add(xmlText);
        forms.add(xmlText.replace("\"", "&quot;").replace("'", "&apos;"));
        forms.add(xmlText.replace("\"", "&#34;").replace("'", "&#39;"));
        forms.add(xmlText.replace("\"", "&#x22;").replace("'", "&#x27;"));
        String url = URLEncoder.encode(value, StandardCharsets.UTF_8);
        for (String form : new String[] {url, url.replace("+", "%20")}) {
            forms.add(form);
            forms.add(lowercaseHexEscapes(form));
        }
        forms.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        return forms;
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Replaces each non-ASCII char (UTF-16 unit) of JSON text by <code>&#92;uXXXX</code>. */
    private static String unicodeEscapeNonAscii(String json, boolean upperCase) {
        StringBuilder out = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x80) {
                out.append(c);
            } else {
                String hex = String.format("%04x", (int) c);
                out.append("\\u").append(upperCase ? hex.toUpperCase(Locale.ROOT) : hex);
            }
        }
        return out.toString();
    }

    /** {@code %2F} → {@code %2f}; everything else unchanged. */
    private static String lowercaseHexEscapes(String urlEncoded) {
        StringBuilder out = new StringBuilder(urlEncoded);
        for (int i = urlEncoded.indexOf('%'); i >= 0 && i + 2 < out.length();
            i = urlEncoded.indexOf('%', i + 1)) {
            out.setCharAt(i + 1, Character.toLowerCase(out.charAt(i + 1)));
            out.setCharAt(i + 2, Character.toLowerCase(out.charAt(i + 2)));
        }
        return out.toString();
    }
}
