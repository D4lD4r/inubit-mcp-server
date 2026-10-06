package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/** One INUBIT server as written in the YAML; empty values are inherited from its stage. */
public record NodeConfig(
    String name,
    Optional<URI> baseUrl,
    boolean allowInsecureHttp,
    Optional<VersionLine> versionLine,
    WriteConfig write,
    TlsConfig tls,
    CliConfig cli,
    InventoryConfig inventory,
    Optional<Duration> timeout,
    Optional<Duration> cliTimeout,
    Optional<Duration> cliExportTimeout,
    Optional<Duration> hangingThreshold,
    Optional<Duration> confirmationTtl,
    DevelopmentConfig development,
    Optional<E2ePolicy> e2eTests,
    E2eConfig e2e) {

    public NodeConfig {
        name = Optionals.orDefault(name, "");
        baseUrl = Optionals.orEmpty(baseUrl);
        versionLine = Optionals.orEmpty(versionLine);
        write = Optionals.orDefault(write, WriteConfig.EMPTY);
        tls = Optionals.orDefault(tls, TlsConfig.EMPTY);
        cli = Optionals.orDefault(cli, CliConfig.EMPTY);
        inventory = Optionals.orDefault(inventory, InventoryConfig.EMPTY);
        timeout = Optionals.orEmpty(timeout);
        cliTimeout = Optionals.orEmpty(cliTimeout);
        cliExportTimeout = Optionals.orEmpty(cliExportTimeout);
        hangingThreshold = Optionals.orEmpty(hangingThreshold);
        confirmationTtl = Optionals.orEmpty(confirmationTtl);
        development = Optionals.orDefault(development, DevelopmentConfig.EMPTY);
        e2eTests = Optionals.orEmpty(e2eTests);
        e2e = Optionals.orDefault(e2e, E2eConfig.EMPTY);
    }
}
