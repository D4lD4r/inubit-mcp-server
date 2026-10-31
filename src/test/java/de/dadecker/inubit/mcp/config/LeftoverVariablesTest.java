package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Feature 011 T010 (FR-003, research R-10, quickstart Q11). */
class LeftoverVariablesTest {

    private static final Path HOME = Path.of("/home/jdoe");
    private static final String YAML = """
        profile:
          name: acme
        groups:
          - name: dev
            nodes:
              - name: node1
                baseUrl: https://inubit-dev.example.test:8443
          - name: prod
            credentials:
              source: { type: awsParameterStore, region: eu-central-1 }
              parameters:
                username: /inubit/prod/username
                password: /inubit/prod/password
            nodes:
              - name: node1
                baseUrl: https://inubit-prod.example.test:8443
        """;

    private static ProfileConfig config() {
        return new ConfigLoader(Map.of(), HOME, false).parse(YAML, HOME.resolve("acme.yaml"))
            .config();
    }

    @Test
    void namesEverySetVariableOfEveryKindAndNodeButNeverAValue() {
        Map<String, String> environment = Map.of(
            "INUBIT_ACME_DEV_PASSWORD", "leftover-Pw-1",
            "INUBIT_ACME_DEV_NODE1_TRUSTSTORE_PASSWORD", "leftover-Ts-2",
            "INUBIT_ACME_DEV_E2E_USERNAME", "e2e-user",
            "INUBIT_ACME_PROD_USERNAME", "prod-user");

        assertThat(LeftoverVariables.warning(environment, config())).hasValueSatisfying(warning ->
            assertThat(warning).contains("no longer read", "since 0.7.0",
                "INUBIT_ACME_DEV_PASSWORD", "INUBIT_ACME_DEV_NODE1_TRUSTSTORE_PASSWORD",
                "INUBIT_ACME_DEV_E2E_USERNAME", "INUBIT_ACME_PROD_USERNAME",
                "docs/migration-environment-variables.md")
                .doesNotContain("leftover-Pw-1", "leftover-Ts-2", "prod-user"));
    }

    @Test
    void ignoresOtherVariablesAndEmptyValues() {
        Map<String, String> environment = Map.of("INUBIT_GLOBEX_DEV_PASSWORD", "other-Pw-1",
            "INUBIT_ACME_DEV_PASSWORD", "", "INUBIT_MCP_PROFILE", "acme",
            "AWS_SECRET_ACCESS_KEY", "aws-key-1");

        assertThat(LeftoverVariables.warning(environment, config())).isEmpty();
    }
}
