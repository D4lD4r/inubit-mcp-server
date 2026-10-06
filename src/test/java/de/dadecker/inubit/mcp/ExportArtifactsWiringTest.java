package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T023: {@code export_artifacts} is offered only if a node has a CLI installation. */
class ExportArtifactsWiringTest {

    @TempDir
    Path temp;

    private List<String> toolNames(String cli) {
        String yaml = """
            profile:
              name: acme
            workspace: %s
            groups:
              - name: dev
            %s
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
            """.formatted(temp.resolve("workspace"), cli);
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        SecretScrubber scrubber = new SecretScrubber();
        try (TestWiring wiring = TestWiring.of(config, new CredentialResolver(Map.of(), scrubber,
            config.credentialPrefix()).resolve(config.nodeIds()), scrubber, path -> false,
            false)) {
            return wiring.toolHandlers().stream().map(ToolHandler::name).toList();
        }
    }

    @Test
    void withACliHomeTheExportToolIsOffered() {
        assertThat(toolNames("    cli:\n      home: " + temp.resolve("startcli")))
            .contains("export_artifacts");
    }

    @Test
    void withoutACliHomeItIsNot() {
        assertThat(toolNames("")).doesNotContain("export_artifacts");
    }
}
