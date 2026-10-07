package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage 3 review B1, T025 (feature 005, US4, FR-023, FR-026): no import, tag or other writing
 * command ever reaches a package-only group, whatever its write settings.
 */
class PackageOnlyTest {

    static final String TAG = "TAG-01";

    @TempDir
    Path temp;

    DeployHarness harness;

    void harness() {
        try {
            harness = new DeployHarness(temp);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static DeployGuard.Request request(Optional<String> code) {
        return new DeployGuard.Request("prod", TAG, Optional.empty(), code,
            Optional.of("client/1.0"));
    }

    /** prod receives from int: the int nodes carry the tag and answer the release exports. */
    void reads() {
        DeployHarness.TARGETS.forEach(node -> harness.exportRelease(node, TAG));
        harness.exportGroup(DeployHarness.INT1);
        harness.exportGroup(DeployHarness.PROD);
    }

    /** The release on int changes Module-0003, which prod has in its old state. */
    DeploymentPreview preview() {
        return preview(harness.deployService(List.of()));
    }

    DeploymentPreview preview(DeployService service) {
        DeployHarness.TARGETS.forEach(node -> {
            harness.servers.get(node).publishModule("Module-0003", text -> text.replace(
                "IsModuleTemplate", "IsModuleTemplateX"));
            harness.servers.get(node).tag("GRP-01", TAG);
        });
        reads();
        return service.deploy(request(Optional.empty())).preview().orElseThrow();
    }

    /** The chain of a real configuration: prod is package-only with every write setting on. */
    static de.dadecker.inubit.mcp.domain.model.StageChain configuredChain(Path temp) {
        String yaml = """
            profile:
              name: acme
            defaults:
              cliHome: /opt/client
            groups:
              - name: dev
                nodes:
                  - { name: node1, baseUrl: "https://inubit-dev-1.example.test:8443" }
                  - { name: node2, baseUrl: "https://inubit-dev-2.example.test:8443" }
              - name: int
                deploy: { from: dev }
                nodes:
                  - { name: node1, baseUrl: "https://inubit-int-1.example.test:8443" }
                  - { name: node2, baseUrl: "https://inubit-int-2.example.test:8443" }
                  - { name: node3, baseUrl: "https://inubit-int-3.example.test:8443" }
              - name: prod
                production: true
                deploy: { from: int, mode: PACKAGE_ONLY }
                write: { enabled: true, productionOptIn: true }
                nodes:
                  - { name: node1, baseUrl: "https://inubit-prod-1.example.test:8443" }
            """;
        // valid (ConfigValidatorTest#theDeployRecordIsTheWriteEnablementOfDeployments)
        de.dadecker.inubit.mcp.config.ProfileConfig config =
            new de.dadecker.inubit.mcp.config.ConfigLoader(java.util.Map.of(), temp, false)
                .parse(yaml, temp.resolve("config.yaml")).config();
        assertThat(config.groups().get(2).write().enabled()).contains(true);
        assertThat(config.groups().get(2).write().productionOptIn()).contains(true);
        return config.stageChain();
    }

    /** What the confirmed call reads again: the plans, then the re-check of prod/node1. */
    void executeReads() {
        reads();
        harness.exportGroup(DeployHarness.PROD);
    }

    /** Only exports reached any node; nothing reached prod but exports. */
    void onlyExports() {
        assertThat(harness.launches()).allMatch(line -> line.matches("^[a-z0-9/-]+ export .*"));
        assertThat(harness.servers.get(DeployHarness.PROD).imported).isEmpty();
        assertThat(harness.servers.get(DeployHarness.PROD).repositoryImports).isEmpty();
        harness.verifyComplete();
    }

    @Test
    void aPackageOnlyTargetIsPreviewedLikeAnyOther() {
        harness();

        DeploymentPreview preview = preview();

        assertThat(preview.mode()).isEqualTo(DeployMode.PACKAGE_ONLY);
        assertThat(preview.plans()).extracting(plan -> plan.node().value())
            .containsExactly("prod/node1");
        assertThat(preview.confirmationCode()).isPresent();
        onlyExports();
    }

    @Test
    void theDeployerRefusesAPackageOnlyTargetAsDefenceInDepth() {
        harness();
        DeployGuard.Admitted admitted = new DeployGuard.Admitted(new GroupId("prod"),
            new GroupId("int"), DeployMode.PACKAGE_ONLY, List.of(),
            List.of(DeployHarness.PROD), DeployHarness.TARGETS, "jdoe", TAG);

        ToolErrorException e;
        try {
            harness.deployer(harness.audit::add).deploy(admitted, null, null, null,
                Optional.empty());
            throw new AssertionError("not refused");
        } catch (ToolErrorException refused) {
            e = refused;
        }

        assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(e.error().message()).contains("package-only");
        onlyExports();
    }

    @Test
    void aConfirmedPackageOnlyCallWritesPackagesAndNeverWrites() throws IOException {
        harness();
        DeploymentPreview preview = preview();
        executeReads();

        DeploymentResult result = harness.deployService(List.of()).deploy(request(
            preview.confirmationCode())).result().orElseThrow();

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.PACKAGED);
        assertThat(result.commit()).isEmpty();
        assertThat(result.nodes()).singleElement().satisfies(node -> {
            assertThat(node.state()).isEqualTo(DeploymentResult.State.PACKAGED);
            assertThat(node.imported()).containsExactly("Module-0003");
            assertThat(node.tag()).isEmpty();
            assertThat(node.backupRef()).isEmpty();
            Path dir = Path.of(node.packageDir().orElseThrow());
            assertThat(dir).isEqualTo(harness.profileHome.resolve("packages").resolve(
                result.auditId().toString()).resolve("prod-node1"));
            assertThat(dir.resolve("1-modules.zip")).isRegularFile();
            assertThat(java.nio.file.Files.readString(dir.resolve("README.md")))
                .contains("--importModule --importUser 'jdoe' --returnProtocol");
            assertThat(java.nio.file.Files.readString(dir.resolve("diff.txt")))
                .contains("Module-0003");
        });
        List<AuditRecord> records = harness.audit.stream().filter(r -> r.auditId()
            .equals(result.auditId())).toList();
        assertThat(records).extracting(AuditRecord::node, AuditRecord::outcome)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("prod", AuditOutcome.PENDING),
                org.assertj.core.groups.Tuple.tuple("prod/node1", AuditOutcome.PACKAGED),
                org.assertj.core.groups.Tuple.tuple("prod", AuditOutcome.PACKAGED));
        assertThat(harness.workspace.log()).noneMatch(subject -> subject.startsWith("deploy "));
        onlyExports();
    }

    @Test
    void writeSettingsAndTheProductionOptInNeverMakeAPackageOnlyGroupWritable()
        throws IOException {
        // stage 3 review B1, permanent: the chain of a configuration with every write setting on
        harness();
        DeployService service = harness.deployService(configuredChain(temp));
        DeploymentPreview preview = preview(service);
        assertThat(preview.mode()).isEqualTo(DeployMode.PACKAGE_ONLY);
        executeReads();

        DeploymentResult result = service.deploy(request(preview.confirmationCode())).result()
            .orElseThrow();

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.PACKAGED);
        onlyExports();
    }

}
