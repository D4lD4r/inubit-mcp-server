package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * {@code defaults} block: values that apply where a group or node does not override them. The
 * {@code BUILTIN_*} constants are the last step of the inheritance chain
 * (data-model.md → Defaults). {@code inventory.owner} has no built-in value (FR-014).
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
    Optional<Duration> deployConfirmationTtl) {

    public static final Duration BUILTIN_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration BUILTIN_CLI_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration BUILTIN_HANGING_THRESHOLD = Duration.ofMinutes(60);
    public static final Duration BUILTIN_CONFIRMATION_TTL = Duration.ofMinutes(5);
    public static final Duration BUILTIN_CLI_EXPORT_TIMEOUT = Duration.ofSeconds(120);
    public static final Duration BUILTIN_INVENTORY_CACHE_TTL = Duration.ofMinutes(10);
    public static final boolean BUILTIN_WRITE_ENABLED = false;
    public static final boolean BUILTIN_PRODUCTION_OPT_IN = false;
    public static final ConfirmationMode BUILTIN_CONFIRMATION = ConfirmationMode.SERVER;
    public static final VersionLine BUILTIN_VERSION_LINE = VersionLine.AUTO;
    public static final boolean BUILTIN_DEVELOPMENT_ENABLED = false;
    public static final ConfirmationMode BUILTIN_DEVELOPMENT_CONFIRMATION = ConfirmationMode.SERVER;
    public static final E2ePolicy BUILTIN_E2E_TESTS = E2ePolicy.FORBIDDEN;
    /** Feature 005 (research D-2): lifetime of a deployment's confirmation code. */
    public static final Duration BUILTIN_DEPLOY_CONFIRMATION_TTL = Duration.ofMinutes(30);

    public static final Defaults EMPTY = new Defaults(Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), InventoryConfig.EMPTY,
        Optional.empty(), Optional.empty(), DevelopmentConfig.EMPTY, Optional.empty(),
        Optional.empty());

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
    }

    /**
     * {@code deployConfirmationTtl}, or {@link #BUILTIN_DEPLOY_CONFIRMATION_TTL} (feature 005;
     * profile-wide, there is no group or node value).
     */
    public Duration effectiveDeployConfirmationTtl() {
        return deployConfirmationTtl.orElse(BUILTIN_DEPLOY_CONFIRMATION_TTL);
    }
}
