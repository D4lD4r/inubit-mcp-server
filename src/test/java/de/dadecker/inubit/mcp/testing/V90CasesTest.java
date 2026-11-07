package de.dadecker.inubit.mcp.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.testing.V90Cases.Kind;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T030 (feature 017, research D-10): the registry refuses a case without provenance. */
class V90CasesTest {

    private final Map<String, byte[]> files = new HashMap<>();
    private final V90Cases cases = new V90Cases(Map.of("ready", Kind.REST, "help", Kind.CLI),
        path -> Optional.ofNullable(files.get(path)));

    private void put(String path, String content) {
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCaseWithoutItsOriginFileCannotBeOpened() {
        put("rest/ready.http", "status: 200\n");
        put("rest/ready.json", "{}");
        put("cli/help.stdout", "usage\n");
        put("cli/help.stderr", "");
        put("cli/help.exit", "0\n");

        assertThatThrownBy(() -> cases.bodyOf("ready")).hasMessageContaining("ready.origin");
        assertThatThrownBy(() -> cases.responseOf("ready")).hasMessageContaining("ready.origin");
        assertThatThrownBy(() -> cases.cliResultOf("help")).hasMessageContaining("help.origin");
        assertThatThrownBy(() -> cases.fileOf("help", "stdout"))
            .hasMessageContaining("help.origin");
    }

    @Test
    void anOriginMustNameItsProvenance() {
        put("rest/ready.origin", "copied from somewhere\n");
        put("rest/ready.json", "{}");

        assertThatThrownBy(() -> cases.bodyOf("ready"))
            .hasMessageContaining("'reconstructed ' or 'recorded '");
    }

    @Test
    void aCaseWithItsOriginOpens() {
        put("rest/ready.origin", "reconstructed docs/research/inubit-interfaces.md §4\n");
        put("rest/ready.json", "{\"status\":\"READY\"}");
        put("cli/help.origin", "recorded 2026-12-01 9.0.7\n");
        put("cli/help.stdout", "usage\n");
        put("cli/help.stderr", "");
        put("cli/help.exit", "0\n");

        assertThat(new String(cases.bodyOf("ready"), StandardCharsets.UTF_8))
            .isEqualTo("{\"status\":\"READY\"}");
        assertThat(cases.originOf("ready"))
            .isEqualTo("reconstructed docs/research/inubit-interfaces.md §4");
        assertThat(cases.cliResultOf("help").exitCode()).isZero();
        assertThat(cases.originOf("help")).isEqualTo("recorded 2026-12-01 9.0.7");
    }

    @Test
    void anUnregisteredCaseOrTheWrongKindCannotBeOpened() {
        put("rest/metrics.origin", "reconstructed x\n");
        put("rest/metrics.json", "{}");

        assertThatThrownBy(() -> cases.bodyOf("metrics")).hasMessageContaining("not registered");
        assertThatThrownBy(() -> cases.cliResultOf("ready")).hasMessageContaining("not a CLI");
    }

    @Test
    void theRegistryHoldsTheCasesOfD10() {
        assertThat(V90Cases.names()).contains("healthcheck", "ready", "system_info", "metrics",
            "log_systemLog", "log_systemLog_filtered", "model_models_owner",
            "modelByName_sample", "model_export_sample", "rest_auth_failed", "rest_not_found",
            "rest_forbidden", "rest_internal_error", "rest_unavailable", "version", "help",
            "processErrorStart_ok", "processErrorStart_unknown", "kill_ok", "kill_unknown",
            "unknown_command", "login_failed", "unreachable", "ps_all", "export_history_sample",
            "export_modules_sample", "import_created", "tag_ok");
    }
}
