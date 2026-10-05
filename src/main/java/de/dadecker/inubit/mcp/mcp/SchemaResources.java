package de.dadecker.inubit.mcp.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the JSON schemas of the tools from the classpath: {@code schemas/<tool>.input.json} and
 * {@code schemas/<tool>.output.json} (copied from contracts/mcp-tools.md). A missing or malformed
 * schema is a programming error and fails the server start.
 */
public final class SchemaResources {

    private static final String DIRECTORY = "schemas/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ClassLoader loader;

    public SchemaResources(ClassLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** Schemas from the class path of this application. */
    public static SchemaResources forClasspath() {
        return new SchemaResources(SchemaResources.class.getClassLoader());
    }

    public static String inputResource(String tool) {
        return DIRECTORY + tool + ".input.json";
    }

    public static String outputResource(String tool) {
        return DIRECTORY + tool + ".output.json";
    }

    /**
     * Returns the schema text of {@code resource}.
     *
     * @throws IllegalStateException if the resource is missing or not a JSON object
     */
    public String load(String resource) {
        String text;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource " + resource + " not found");
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read schema resource " + resource, e);
        }
        JsonNode schema;
        try {
            schema = JSON.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalStateException("Schema resource " + resource + " is not valid JSON",
                e);
        }
        if (schema == null || !schema.isObject()) {
            throw new IllegalStateException("Schema resource " + resource
                + " must contain a JSON object");
        }
        return text;
    }
}
