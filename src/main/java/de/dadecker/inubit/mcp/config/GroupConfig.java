package de.dadecker.inubit.mcp.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * One stage as written in the YAML. Its settings are inherited by all its servers. Names are not
 * validated here, so that {@link ConfigValidator} can report every problem at once.
 */
public record GroupConfig(
    String name,
    boolean production,
    List<NodeConfig> nodes,
    WriteConfig write,
    TlsConfig tls,
    CliConfig cli,
    InventoryConfig inventory,
    Optional<VersionLine> versionLine,
    Optional<Duration> timeout,
    Optional<Duration> cliTimeout,
    Optional<Duration> cliExportTimeout,
    Optional<Duration> hangingThreshold,
    Optional<Duration> confirmationTtl) {

    public GroupConfig {
        name = Optionals.orDefault(name, "");
        nodes = Optionals.copyOrEmpty(nodes);
        write = Optionals.orDefault(write, WriteConfig.EMPTY);
        tls = Optionals.orDefault(tls, TlsConfig.EMPTY);
        cli = Optionals.orDefault(cli, CliConfig.EMPTY);
        inventory = Optionals.orDefault(inventory, InventoryConfig.EMPTY);
        versionLine = Optionals.orEmpty(versionLine);
        timeout = Optionals.orEmpty(timeout);
        cliTimeout = Optionals.orEmpty(cliTimeout);
        cliExportTimeout = Optionals.orEmpty(cliExportTimeout);
        hangingThreshold = Optionals.orEmpty(hangingThreshold);
        confirmationTtl = Optionals.orEmpty(confirmationTtl);
    }
}
