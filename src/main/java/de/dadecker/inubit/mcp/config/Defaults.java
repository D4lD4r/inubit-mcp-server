package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code defaults} block: values that apply where a group or node does not override them. The
 * {@code BUILTIN_*} constants are the last step of the inheritance chain
 * (data-model.md → Defaults). {@code inventory.owner} has no built-in value (FR-014).
 *
 * @param cliClients feature 017 (research D-1): the StartCLI client per INUBIT major.minor, in
 *     file order; only here, groups and nodes keep {@code cli.home}/{@code cli.javaHome}
 */
public record Defaults(
    Optional<Duration> timeout,
    Optional<Duration> cliTimeout,
    Optional<Duration> hangingThreshold,
    Optional<Duration> confirmationTtl,
    Optional<Duration> cliExportTimeout,
    InventoryConfig inventory,
    Optional<Path> cliHome,
    Optional<Path> cliJavaHome,
    DevelopmentConfig development,
    Optional<E2ePolicy> e2eTests,
    Optional<Duration> deployConfirmationTtl,
    @JsonDeserialize(using = CliClientsDeserializer.class)
    Map<MajorMinor, CliClient> cliClients) {

    public static final Duration BUILTIN_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration BUILTIN_CLI_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration BUILTIN_HANGING_THRESHOLD = Duration.ofMinutes(60);
    public static final Duration BUILTIN_CONFIRMATION_TTL = Duration.ofMinutes(5);
    public static final Duration BUILTIN_CLI_EXPORT_TIMEOUT = Duration.ofSeconds(120);
    public static final Duration BUILTIN_INVENTORY_CACHE_TTL = Duration.ofMinutes(10);
    public static final boolean BUILTIN_WRITE_ENABLED = false;
    public static final boolean BUILTIN_PRODUCTION_OPT_IN = false;
    /** Feature 015 (research R-15): {@code archives.productionOptIn}, group-level only. */
    public static final boolean BUILTIN_ARCHIVES_PRODUCTION_OPT_IN = false;
    public static final ConfirmationMode BUILTIN_CONFIRMATION = ConfirmationMode.SERVER;
    public static final VersionSetting BUILTIN_VERSION_LINE = VersionSetting.AUTO;
    public static final boolean BUILTIN_DEVELOPMENT_ENABLED = false;
    public static final ConfirmationMode BUILTIN_DEVELOPMENT_CONFIRMATION = ConfirmationMode.SERVER;
    /** Feature 008 (research D-1): repository files are not written unless switched on. */
    public static final RepositoryMode BUILTIN_DEVELOPMENT_REPOSITORY = RepositoryMode.READ_ONLY;
    public static final E2ePolicy BUILTIN_E2E_TESTS = E2ePolicy.FORBIDDEN;
    /** Feature 005 (research D-2): lifetime of a deployment's confirmation code. */
    public static final Duration BUILTIN_DEPLOY_CONFIRMATION_TTL = Duration.ofMinutes(30);

    public static final Defaults EMPTY = new Defaults(Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), InventoryConfig.EMPTY,
        Optional.empty(), Optional.empty(), DevelopmentConfig.EMPTY, Optional.empty(),
        Optional.empty(), Map.of());

    public Defaults {
        timeout = Optionals.orEmpty(timeout);
        cliTimeout = Optionals.orEmpty(cliTimeout);
        hangingThreshold = Optionals.orEmpty(hangingThreshold);
        confirmationTtl = Optionals.orEmpty(confirmationTtl);
        cliExportTimeout = Optionals.orEmpty(cliExportTimeout);
        inventory = Optionals.orDefault(inventory, InventoryConfig.EMPTY);
        cliHome = Optionals.orEmpty(cliHome);
        cliJavaHome = Optionals.orEmpty(cliJavaHome);
        development = Optionals.orDefault(development, DevelopmentConfig.EMPTY);
        e2eTests = Optionals.orEmpty(e2eTests);
        deployConfirmationTtl = Optionals.orEmpty(deployConfirmationTtl);
        cliClients = cliClients == null || cliClients.isEmpty() ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(cliClients));
    }

    /** Defaults without {@code cliClients}. */
    public Defaults(Optional<Duration> timeout, Optional<Duration> cliTimeout,
        Optional<Duration> hangingThreshold, Optional<Duration> confirmationTtl,
        Optional<Duration> cliExportTimeout, InventoryConfig inventory, Optional<Path> cliHome,
        Optional<Path> cliJavaHome, DevelopmentConfig development, Optional<E2ePolicy> e2eTests,
        Optional<Duration> deployConfirmationTtl) {
        this(timeout, cliTimeout, hangingThreshold, confirmationTtl, cliExportTimeout, inventory,
            cliHome, cliJavaHome, development, e2eTests, deployConfirmationTtl, Map.of());
    }

    /**
     * {@code deployConfirmationTtl}, or {@link #BUILTIN_DEPLOY_CONFIRMATION_TTL} (feature 005;
     * profile-wide, there is no group or node value).
     */
    public Duration effectiveDeployConfirmationTtl() {
        return deployConfirmationTtl.orElse(BUILTIN_DEPLOY_CONFIRMATION_TTL);
    }
}
