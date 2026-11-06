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
 * Issue #55: INUBIT 8.1.17 stores an {@code xslt.transformer} that names the plain Saxon factory
 * as a Saxon-EE factory of {@code com.saxonica.config} when a module is imported. The verify of
 * the import and the verify of its rollback accept that rewrite. The fixtures stand in
 * {@code ProfessionalTransformerFactory} for the Saxon-EE factory (see the fixture README).
 */
class ImportTransformerRewriteTest {

    private static final String PLAIN = "net.sf.saxon.TransformerFactoryImpl";
    private static final String SAXONICA = "com.saxonica.config.ProfessionalTransformerFactory";
    private static final String FIXTURE = "module-one.zip";

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
        String file = "module/" + name.toLowerCase(java.util.Locale.ROOT) + ".xml";
        byte[] plain = ExportHarness.rewrite(FIXTURE, file, xml -> {
            assertThat(xml).contains(">" + SAXONICA + "<");
            return xml.replace(">" + SAXONICA + "<", ">" + PLAIN + "<");
        });
        harness = ImportHarness.module(temp, plain, "OWNERS", name, pluginType);
        moduleFile = harness.moduleDirectory(name) + "/module.xml";
        assertThat(harness.read(moduleFile)).contains(">" + PLAIN + "<");
        harness.edit(moduleFile, "</Properties>",
            "<Property name=\"x.added\">1</Property></Properties>");
        harness.inubit.saxonFactoryOnImport = SAXONICA;
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
    void anImportWhoseTransformerInubitRewritesIsVerified() {
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
            .contains(">" + SAXONICA + "<").contains("x.added");
    }

    @Test
    void theRollbackOfAModuleWhoseTransformerInubitRewritesSucceeds() {
        // another verify difference: the check-in comment lost the reason
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
        assertThat(outcome.failure().orElseThrow().message())
            .doesNotContain("the rollback failed");
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
    }
}
