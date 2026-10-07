package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T029 (feature 005): the documentation of the stage chain and {@code deploy_release} — the tool
 * reference with the three new error codes (cause and next step), the setup of the chain, the
 * README's tool table and safety model, the deployment live test and the changelog.
 */
class DeploymentReferenceTest {

    private static String folded(String file) throws IOException {
        return Files.readString(Path.of(file)).replaceAll("\\n> ?", " ").replaceAll("\\s+", " ");
    }

    @Test
    void theToolReferenceDescribesTheDeploymentCompletely() throws IOException {
        String tools = folded("docs/tools.md");

        assertThat(tools).contains("## `deploy_release`", "`PACKAGED`", "`NOT_STARTED`",
            "`ROLLED_BACK`", "`ROLLBACK_FAILED`", "`UNCHANGED`", "`DEPLOYED`",
            "`OUTSIDE_CHAIN`", "`SHARED_MODULE`", "`STAGE_SPECIFIC_VALUE`", "`OLDER_THAN_HEAD`",
            "`OUTSIDE_OWNER_REPOSITORY`", "`deployConfirmationTtl`", "`--importRepositoryPath`",
            "`README.md`", "`diff.txt`", "`warnings.txt`", ".reports/deploy-<auditId>/",
            "step `backup`, `pending` or `recheck`", "`tag.applied: false`",
            "key material", "never deleted")
            .doesNotContain("The full description follows with the documentation of feature 005");
        assertThat(tools).contains("| `CHAIN_VIOLATION` | ", "| `SOURCE_INCONSISTENT` | ",
            "| `DEPLOY_LOCKED` | ", "| Code | Likely cause | Next step |");
        // the widened tools
        assertThat(tools).contains("a deployment backup", "receives deployments");
    }

    @Test
    void theSetupDescribesTheChainModesFilesAndTheWriteRuling() throws IOException {
        String setup = folded("docs/setup.md");

        assertThat(setup).contains("### Stage chain and deployments", "`deploy.from`",
            "`deploy.mode`", "`PACKAGE_ONLY`", "`deploy.exclude`", "`deployConfirmationTtl`",
            "~/.inubit-mcp/<profile>/deployments", "~/.inubit-mcp/<profile>/packages",
            "Chains:", "deploy: from dev (EXECUTE)",
            "the `deploy` record is the write enablement of deployments",
            "`write.productionOptIn`", "never writes");
    }

    @Test
    void theReadmeLiveTestsAndChangelogKnowTheDeployment() throws IOException {
        assertThat(folded("README.md")).contains("| `deploy_release` |", "stage chain",
            "package-only", "server-issued confirmation code");
        assertThat(folded("docs/live-tests.md")).contains("## Deployment live test (feature 005)",
            "DeploymentLiveTest", "INUBIT_LIVE_DEPLOY_TARGET", "INUBIT_LIVE_DEPLOY_TAG",
            "INUBIT_LIVE_DEPLOY_OWNER", "What stays after the deployment live test");
        String changelog = folded("CHANGELOG.md");
        String unreleased = changelog.substring(changelog.indexOf("## [Unreleased]"),
            changelog.indexOf("## [0.3.0]"));
        assertThat(unreleased).contains("### Added", "### Changed", "`deploy_release`",
            "`restore_backup`", "`run_e2e_test`");
    }
}
