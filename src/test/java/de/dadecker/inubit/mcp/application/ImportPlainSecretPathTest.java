package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace file may hold a plain value at a path that the redaction treats as secret, e.g. a
 * variable name in a property named like a certificate, which cannot be a placeholder while the
 * node has no value for it. The re-export shows that value as its placeholder, so the verify
 * compares the sent file in its redacted form: the import is verified, and a real difference
 * still fails it.
 */
class ImportPlainSecretPathTest {

    private static final String FIXTURE = "module-one.zip";
    private static final String MAPPED = "<Property name=\"x.ClientCertificateFile\">"
        + "FilenameClientCertificate</Property>";

    @TempDir
    Path temp;

    private ImportHarness harness;
    private String name;
    private String pluginType;
    private String moduleFile;

    @BeforeEach
    void setUp() throws IOException {
        var entry = new ArchiveReader().read(ArtifactFixtures.bytes(FIXTURE)).moduleIndex()
            .get(0);
        name = entry.name();
        pluginType = entry.pluginType();
        harness = ImportHarness.module(temp, ArtifactFixtures.bytes(FIXTURE), "OWNERS", name,
            pluginType);
        moduleFile = harness.moduleDirectory(name) + "/module.xml";
        harness.edit(moduleFile, "</Properties>", MAPPED + "</Properties>");
    }

    private WriteOutcome run() {
        ImportService.Response response = harness.service().importArtifacts(
            new ImportService.ImportRequest("dev/node1", Optional.of("OWNERS"),
                Optional.empty(), List.of(new ImportScope.Module(name, Optional.of(pluginType))),
                "Module fix", Optional.empty(), Optional.empty()));
        harness.cli.verifyComplete();
        return ((ImportService.Response.Completed) response).outcome();
    }

    @Test
    void anImportWithAPlainValueAtASecretPathIsVerified() {
        harness.exportModule(pluginType, name)
            .importApplied("--importModule --importUser 'OWNERS' --returnProtocol")
            .exportModule(pluginType, name);

        WriteOutcome outcome = run();

        assertThat(outcome.outcome()).as(outcome.toString())
            .isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.failure()).isEmpty();
        assertThat(outcome.rollback()).isEmpty();
        assertThat(harness.inubit.imported).hasSize(1);
        assertThat(harness.read(moduleFile)).as("the workspace takes the server's state")
            .contains("${secret:x.ClientCertificateFile}")
            .doesNotContain("FilenameClientCertificate");
    }

    @Test
    void anotherDifferenceBesideAPlainValueAtASecretPathFailsTheVerify() {
        harness.inubit.rewriteNextComments = comment -> comment.replace("###Module fix###",
            "###");
        harness.exportModule(pluginType, name)
            .importApplied("--importModule --importUser 'OWNERS' --returnProtocol")
            .exportModule(pluginType, name)
            .importApplied("--importModule --importUser 'OWNERS' --returnProtocol")
            .exportModule(pluginType, name);

        WriteOutcome outcome = run();

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure().orElseThrow().code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
    }
}
