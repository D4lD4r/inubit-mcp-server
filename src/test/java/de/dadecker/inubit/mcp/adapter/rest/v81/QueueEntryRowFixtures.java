package de.dadecker.inubit.mcp.adapter.rest.v81;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The synthetic queueLog answers of feature 020 (research D-9) in
 * {@code fixtures/queue-entry/v8_1/rows_<case>.json}. Opening a case without its
 * {@code rows_<case>.origin} (first line {@code reconstructed …} or {@code recorded …}) fails, so
 * no hand-written answer passes for a recording.
 */
final class QueueEntryRowFixtures {

    private static final String DIRECTORY = "/fixtures/queue-entry/v8_1/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private QueueEntryRowFixtures() {
    }

    /** The body of {@code rows_<rowsCase>.json}. */
    static byte[] bytes(String rowsCase) {
        String origin = new String(resource("rows_" + rowsCase + ".origin"),
            StandardCharsets.UTF_8);
        if (!origin.startsWith("reconstructed ") && !origin.startsWith("recorded ")) {
            throw new IllegalStateException("rows_" + rowsCase + ".origin names no provenance");
        }
        return resource("rows_" + rowsCase + ".json");
    }

    /** The first row of the case. */
    static JsonNode firstRow(String rowsCase) {
        return JSON.readTree(bytes(rowsCase)).path("queueLog").path("row").get(0);
    }

    /** The case as HTTP 200 answer of {@code POST /ibis/rest/log/queueLog?format=json}. */
    static ResponseDefinitionBuilder response(String rowsCase) {
        return aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json;charset=UTF-8")
            .withBody(bytes(rowsCase));
    }

    private static byte[] resource(String name) {
        try (InputStream in = QueueEntryRowFixtures.class.getResourceAsStream(DIRECTORY + name)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture " + DIRECTORY + name + " not found");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
