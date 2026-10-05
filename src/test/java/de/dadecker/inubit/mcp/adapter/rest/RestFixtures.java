package de.dadecker.inubit.mcp.adapter.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Recorded (or SYNTHETIC) REST fixtures of {@code fixtures/v8_1/rest/}: {@code <case>.http} holds
 * {@code status:} and {@code content-type:}, the body is {@code <case>.<extension>}.
 */
public final class RestFixtures {

    private static final String DIRECTORY = "/fixtures/v8_1/rest/";

    private RestFixtures() {
    }

    /** The WireMock response of a fixture: recorded status, content type and body. */
    public static ResponseDefinitionBuilder response(String fixtureCase, String extension) {
        ResponseDefinitionBuilder response = aResponse().withBody(bytes(fixtureCase + "."
            + extension));
        for (String line : text(fixtureCase + ".http").split("\\R")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (key.equals("status")) {
                response.withStatus(Integer.parseInt(value));
            } else if (key.equals("content-type")) {
                response.withHeader("Content-Type", value);
            }
        }
        return response;
    }

    public static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    public static byte[] bytes(String name) {
        try (InputStream in = RestFixtures.class.getResourceAsStream(DIRECTORY + name)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture " + name + " not found");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
