package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpdateCheckSettingTest {

    private static final UpdateCheckConfig OFF = new UpdateCheckConfig(Optional.of(false));

    @Test
    void withoutKeyAndVariableTheCheckIsOn() {
        UpdateCheckSetting setting =
            UpdateCheckSetting.resolve(UpdateCheckConfig.DEFAULT, Map.of());
        assertThat(setting).isEqualTo(new UpdateCheckSetting(true, Optional.empty(),
            Optional.empty()));
    }

    @Test
    void theKeyTurnsItOff() {
        assertThat(UpdateCheckSetting.resolve(OFF, Map.of()).offBy())
            .contains("updateCheck.enabled: false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "OFF", " false ", "0", "No"})
    void theVariableTurnsItOff(String value) {
        UpdateCheckSetting setting = UpdateCheckSetting.resolve(UpdateCheckConfig.DEFAULT,
            Map.of("INUBIT_MCP_UPDATE_CHECK", value));
        assertThat(setting.on()).isFalse();
        assertThat(setting.offBy()).contains("INUBIT_MCP_UPDATE_CHECK");
        assertThat(setting.warning()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"on", "TRUE", "1", " yes", "", "  "})
    void anOnOrBlankValueLeavesTheKeyInCharge(String value) {
        Map<String, String> env = Map.of("INUBIT_MCP_UPDATE_CHECK", value);
        assertThat(UpdateCheckSetting.resolve(UpdateCheckConfig.DEFAULT, env).on()).isTrue();
        assertThat(UpdateCheckSetting.resolve(OFF, env).on()).as("on cannot override the key")
            .isFalse();
        assertThat(UpdateCheckSetting.resolve(OFF, env).warning()).isEmpty();
    }

    @Test
    void anUnrecognisedValueWarnsWithoutEchoingItAndLeavesTheCheckOn() {
        UpdateCheckSetting setting = UpdateCheckSetting.resolve(UpdateCheckConfig.DEFAULT,
            Map.of("INUBIT_MCP_UPDATE_CHECK", "s3cr3t-typo"));
        assertThat(setting.on()).isTrue();
        assertThat(setting.warning()).contains("INUBIT_MCP_UPDATE_CHECK has an unrecognised value;"
            + " use off or on (the update check stays on)");
        assertThat(setting.warning().orElseThrow()).doesNotContain("s3cr3t");
    }
}
