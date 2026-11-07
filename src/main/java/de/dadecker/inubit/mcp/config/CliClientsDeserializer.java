package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Binds {@code defaults.cliClients} (feature 017, research D-1) token by token: keys must be
 * {@code major.minor} ({@link MajorMinor#parse}), a key may appear once, every entry needs
 * {@code home}. Errors name the key path, never an entry's values.
 */
final class CliClientsDeserializer extends ValueDeserializer<Map<MajorMinor, CliClient>> {

    /** An entry as written; {@code home} is checked here so that its absence names the key. */
    private record Entry(Path home, Optional<Path> javaHome) {
    }

    @Override
    public Map<MajorMinor, CliClient> deserialize(JsonParser parser,
        DeserializationContext context) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw MismatchedInputException.from(parser, Map.class, "expected a mapping");
        }
        Map<MajorMinor, CliClient> clients = new LinkedHashMap<>();
        JsonToken token;
        while ((token = parser.nextToken()) == JsonToken.PROPERTY_NAME) {
            String key = parser.currentName();
            parser.nextToken();
            try {
                MajorMinor version = MajorMinor.parse(key).orElseThrow(() ->
                    MismatchedInputException.from(parser, MajorMinor.class,
                        "expected major.minor"));
                if (clients.containsKey(version)) {
                    throw MismatchedInputException.from(parser, Map.class,
                        "Duplicate cliClients key \"" + key + "\"");
                }
                clients.put(version, entry(parser, context));
            } catch (DatabindException e) {
                throw DatabindException.wrapWithPath(e, clients, key);
            }
        }
        if (token != JsonToken.END_OBJECT) {
            throw MismatchedInputException.from(parser, Map.class, "expected a mapping");
        }
        return Collections.unmodifiableMap(clients);
    }

    private static CliClient entry(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw MismatchedInputException.from(parser, CliClient.class, "expected a mapping");
        }
        Entry entry = context.readValue(parser, Entry.class);
        if (entry.home() == null) {
            throw MismatchedInputException.from(parser, CliClient.class, "home is required");
        }
        return new CliClient(entry.home(), entry.javaHome());
    }
}
