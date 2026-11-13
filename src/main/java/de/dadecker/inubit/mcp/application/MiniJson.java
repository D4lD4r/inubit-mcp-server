package de.dadecker.inubit.mcp.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small JSON subset of the server's own state files (backup manifests, the deployment
 * ledger, the log-level leases): objects, arrays, strings, integers ({@link Long}) and
 * {@code null}; no fractions, exponents or booleans. The application layer depends on the JDK only
 * (Constitution V), hence no JSON library here.
 */
public final class MiniJson {

    private final String text;
    private int position;

    private MiniJson(String text) {
        this.text = text;
    }

    Map<String, Object> object() {
        expect('{');
        Map<String, Object> object = new LinkedHashMap<>();
        if (peek() == '}') {
            position++;
            return object;
        }
        do {
            String name = string();
            expect(':');
            object.put(name, value());
        } while (comma());
        expect('}');
        return object;
    }

    void end() {
        skipWhitespace();
        if (position != text.length()) {
            throw new IllegalArgumentException("Trailing content in a JSON document");
        }
    }

    private Object value() {
        return switch (peek()) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 'n' -> literalNull();
            default -> integer();
        };
    }

    private Object literalNull() {
        if (!text.startsWith("null", position)) {
            throw new IllegalArgumentException("Unexpected value");
        }
        position += 4;
        return null;
    }

    /** {@code -?(0|[1-9][0-9]*)} within the range of a {@code long}. */
    private Long integer() {
        int start = position;
        if (position < text.length() && text.charAt(position) == '-') {
            position++;
        }
        int digits = position;
        while (position < text.length() && text.charAt(position) >= '0'
            && text.charAt(position) <= '9') {
            position++;
        }
        if (position == digits || (text.charAt(digits) == '0' && position > digits + 1)) {
            throw new IllegalArgumentException("Unexpected value");
        }
        return Long.parseLong(text, start, position, 10);
    }

    private List<Object> array() {
        expect('[');
        List<Object> array = new ArrayList<>();
        if (peek() == ']') {
            position++;
            return array;
        }
        do {
            array.add(value());
        } while (comma());
        expect(']');
        return array;
    }

    private String string() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return value.toString();
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw new IllegalArgumentException("Truncated escape");
                    }
                    value.append((char) Integer.parseInt(
                        text.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> throw new IllegalArgumentException("Invalid escape");
            }
        }
    }

    private boolean comma() {
        if (peek() == ',') {
            position++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw new IllegalArgumentException("Malformed JSON document");
        }
        position++;
    }

    private char peek() {
        skipWhitespace();
        if (position >= text.length()) {
            throw new IllegalArgumentException("Truncated JSON document");
        }
        return text.charAt(position);
    }

    private char next() {
        if (position >= text.length()) {
            throw new IllegalArgumentException("Truncated JSON document");
        }
        return text.charAt(position++);
    }

    private void skipWhitespace() {
        while (position < text.length() && " \n\r\t".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
    }

    /**
     * Parses one object; anything else, or trailing content, is refused. A {@code null} member is
     * kept as a key with a {@code null} value.
     */
    public static Map<String, Object> parse(String text) {
        MiniJson parser = new MiniJson(text);
        Map<String, Object> object = parser.object();
        parser.end();
        return object;
    }

    /** {@code value} as a JSON string literal. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
