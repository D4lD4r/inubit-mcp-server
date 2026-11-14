package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.ServerSettingsService.NodeSettings;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 T022: {@code schemas/get_server_settings.output.json} against the serialized
 * entries of {@code get_server_settings}, and the shapes it rejects: unknown parts, sources and
 * channels, an entry that is neither settings nor an error, and a configuration value that is
 * neither shown nor masked (data-model.md "ServerSettings", "ConfigurationView").
 */
class GetServerSettingsOutputSchemaTest {

    private static final NodeId NODE = NodeId.parse("test/node1");

    @Test
    void aFullEntryMatchesTheSchema() {
        assertThat(errors(entry())).isNull();
    }

    @Test
    void aFailedServerMatchesTheSchema() {
        Map<String, Object> failed = json(NodeResult.failure(NODE, ToolError.of(
            ErrorCode.UNSUPPORTED_VERSION, "not yet verified", "cause", "step")));

        assertThat(errors(failed)).isNull();
    }

    @Test
    void everyPartOfTheToolAndOfGetHealthsMonitoringReadsMayBeUnavailable() {
        for (String part : List.of("license", "threads", "privilegedWorkflows",
            "dbConnectionTolerance", "configuration", "serverTime", "status", "version",
            "load")) {
            Map<String, Object> entry = entry();
            entry.put("unavailable", List.of(Map.of("part", part, "reason", "TIMEOUT: x")));

            assertThat(errors(entry)).as(part).isNull();
        }
    }

    @Test
    void anUnknownUnavailablePartIsRejected() {
        Map<String, Object> entry = entry();
        entry.put("unavailable", List.of(Map.of("part", "processCounts", "reason", "TIMEOUT: x")));

        assertThat(errors(entry)).isNotNull();
    }

    @Test
    void anUnknownSourceOrChannelIsRejected() {
        Map<String, Object> unknownField = entry();
        unknownField.put("sources", Map.of("licenseFile", "REST"));
        Map<String, Object> unknownChannel = entry();
        unknownChannel.put("sources", Map.of("license", "SOAP"));

        assertThat(errors(unknownField)).isNotNull();
        assertThat(errors(unknownChannel)).isNotNull();
    }

    @Test
    void anEntryIsEitherSettingsOrAnError() {
        Map<String, Object> neither = entry();
        neither.remove("sources");
        Map<String, Object> both = entry();
        both.put("error", Map.of("code", "TIMEOUT", "message", "m", "likelyCause", "c",
            "nextStep", "s"));

        assertThat(errors(neither)).isNotNull();
        assertThat(errors(both)).isNotNull();
    }

    @Test
    void aConfigurationValueIsShownOrMasked() {
        Map<String, Object> shown = section(Map.of("path", "/Host", "value", "smtp"));
        Map<String, Object> masked = section(Map.of("path", "/Password", "masked", true));
        Map<String, Object> neither = section(Map.of("path", "/Password"));
        Map<String, Object> notMasked = section(Map.of("path", "/Password", "masked", false));

        assertThat(errors(shown)).isNull();
        assertThat(errors(masked)).isNull();
        assertThat(errors(neither)).isNotNull();
        assertThat(errors(notMasked)).isNotNull();
    }

    @Test
    void aConfigurationListsSectionsOrShowsOneSection() {
        Map<String, Object> both = entry();
        both.put("configuration", Map.of("sections", List.of("A"), "section", "A",
            "values", List.of(), "maskedCount", 0, "truncated", false));

        assertThat(errors(both)).isNotNull();
    }

    /** The full entry of {@link GetServerSettingsToolTest#full}, as a mutable map. */
    private static Map<String, Object> entry() {
        return json(NodeResult.success(NODE, new NodeSettings(GetServerSettingsToolTest.full(NODE),
            List.of("a warning"))));
    }

    /** {@link #entry()} showing a section with the one value {@code value}. */
    private static Map<String, Object> section(Map<String, Object> value) {
        Map<String, Object> entry = entry();
        entry.put("configuration", Map.of("section", "SMTPProperties", "values", List.of(value),
            "maskedCount", 0, "truncated", false));
        return entry;
    }

    /** The JSON object the tool returns for {@code result}, as a mutable map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(NodeResult<NodeSettings> result) {
        GetServerSettingsTool.Result serialized =
            GetServerSettingsTool.Result.of(List.of(result), Optional.empty());
        return ResultJson.mapper().convertValue(serialized.settings().get(0), Map.class);
    }

    /** The validation errors of {@code {settings: [entry]}}, or {@code null} if it is valid. */
    @SuppressWarnings("unchecked")
    private static String errors(Map<String, Object> entry) {
        Map<String, Object> schema = ResultJson.mapper().readValue(SchemaResources.forClasspath()
            .load("schemas/get_server_settings.output.json"), Map.class);
        JsonSchemaValidator.ValidationResponse validation = new DefaultJsonSchemaValidator()
            .validate(schema, Map.of("settings", List.of(entry)));
        return validation.valid() ? null : validation.errorMessage();
    }
}
