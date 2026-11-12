package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.ClockProvider;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 023 T007 (FR-009, SC-005, data-model.md): every location derived from the user home H
 * for profile {@code acme} keeps its layout now that the home is handed in. The
 * {@code Wiring}-derived locations come from {@link Wiring#homeLocations()}, the loader-derived
 * defaults from the loaded {@link ProfileConfig}. The update-check cache
 * {@code H/.inubit-mcp/update-check.json} is asserted by
 * {@code FileUpdateCacheTest#theFileLivesBelowTheDotDirectoryOfTheHome}
 * ({@code test/infra/FileUpdateCacheTest.java:36}).
 */
class WiringHomeLocationsTest {

    @TempDir
    Path home;

    @Test
    void theLocationsBelowTheHomeKeepTheirLayout() throws IOException {
        String yaml = """
            profile:
              name: acme
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
            """;
        ProfileConfig config = new ConfigLoader(Map.of(), home, false)
            .parse(yaml, home.resolve("config.yaml")).config();
        ProcessLauncher failing = spec -> {
            throw new IOException("StartCLI must not be launched in this test");
        };
        Path profile = home.resolve(".inubit-mcp").resolve("acme");

        try (Wiring wiring = new Wiring(config, TestSecrets.store(Map.of()), new SecretScrubber(),
            ClockProvider.system(), path -> false, false, failing, Map.of(), () -> {
                throw new AssertionError("no node reads from the Parameter Store");
            }, TestWiring.TEST_CONFIG_LOCATION, home)) {
            assertThat(wiring.homeLocations()).isEqualTo(new Wiring.HomeLocations(
                profile.resolve("backups"), profile.resolve("deployments"),
                profile.resolve("packages"), home.resolve(".inubit-mcp")));
        }
        assertThat(config.auditDirectory()).isEqualTo(profile.resolve("audit"));
        assertThat(config.workspace()).isEqualTo(profile.resolve("workspace"));
        assertThat(config.knowledge().directory()).isEqualTo(profile.resolve("knowledge"));
    }
}
