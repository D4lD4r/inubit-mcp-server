package de.dadecker.inubit.mcp.adapter.rest;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * An HTTP response from INUBIT: status, content type and the raw body bytes.
 *
 * @param contentType the {@code Content-Type} header, if any (the ZIP export has none, S-6b)
 */
public record RestResponse(int status, Optional<String> contentType, byte[] body) {

    public RestResponse {
        Objects.requireNonNull(contentType, "contentType");
        body = Objects.requireNonNull(body, "body").clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** The body decoded with the charset of the content type, UTF-8 by default. */
    public String bodyText() {
        return new String(body, charset());
    }

    private Charset charset() {
        return contentType.flatMap(RestResponse::charsetOf).orElse(StandardCharsets.UTF_8);
    }

    private static Optional<Charset> charsetOf(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.strip();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String name = trimmed.substring("charset=".length()).replace("\"", "").strip();
                try {
                    return Optional.of(Charset.forName(name));
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RestResponse that && status == that.status
            && contentType.equals(that.contentType) && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, contentType, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "RestResponse[status=" + status + ", contentType=" + contentType.orElse("-")
            + ", bytes=" + body.length + "]";
    }
}
