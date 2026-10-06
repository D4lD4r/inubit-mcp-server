package de.dadecker.inubit.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticSecret;
import de.dadecker.inubit.mcp.application.ImportHarness;
import de.dadecker.inubit.mcp.application.ImportService;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.OwnerKind;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * T017 (feature 004, FR-012, SC-004, Constitution II): imports of every feature-003 fixture
 * with the target's secrets put back — no synthetic secret value reaches the workspace, the
 * history objects, the backup manifests, the audit, the results or the logs; the private
 * temporary directory of the import ZIP is gone; the values inside the captured import archive
 * are the target's.
 */
@Timeout(120)
class ImportSecretLeakTest {

    @TempDir
    Path temp;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private Set<Path> temporaryBefore;

    @BeforeEach
    void captureLogs() throws IOException {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);
        temporaryBefore = importDirectories();
    }

    @AfterEach
    void tearDown() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
        logs.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
    }

    @Test
    void aDiagramGroupWithSecretsLeavesNoSecretOutsideTheImportArchive() throws IOException {
        ImportHarness harness = new ImportHarness(temp, ArtifactFixtures.bytes("grp-b.zip"),
            "OWNERS", "GRP-02");
        harness.owners.put("OWNERS", OwnerKind.USER);
        harness.edit(harness.workflow("Workflow-0006"), "xPos=\"", "xPos=\"1");
        String module = harness.moduleDirectory("Module-0028") + "/module.xml";
        harness.edit(module, "</Properties>",
            "<Property name=\"x.added\">1</Property></Properties>");
        harness.exportGroup().importApplied("--importWorkflow --importUser 'OWNERS'"
            + " --returnProtocol").exportGroup();

        WriteOutcome outcome = run(harness, harness.group("Secret safe change"));

        assertThat(outcome.modified()).containsExactly("Workflow-0006", "Module-0028");
        assertNoLeak(harness, "grp-b.zip", outcome);
        String sent = archiveText(harness.inubit.imported.get(0));
        for (SyntheticSecret secret : secrets("grp-b.zip")) {
            if (secret.location().startsWith("workflow Workflow-0006")
                || secret.location().startsWith("module/module-0028.xml")) {
                assertThat(sent).as(secret.location()).contains(secret.value());
            }
        }
    }

    @Test
    void aDiagramGroupWithoutSecretsLeavesNothing() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();

        assertNoLeak(harness, "grp-a.zip", run(harness, harness.group("No secrets")));
    }

    @Test
    void moduleImportsPutTheTargetsValuesBack() throws IOException {
        for (String fixture : List.of("module-one.zip", "module-smime.zip")) {
            Path dir = Files.createDirectories(temp.resolve(fixture));
            var raw = new ArchiveReader().read(ArtifactFixtures.bytes(fixture));
            var entry = raw.moduleIndex().get(0);
            ImportHarness harness = ImportHarness.module(dir, ArtifactFixtures.bytes(fixture),
                "OWNERS", entry.name(), entry.pluginType());
            harness.owners.put("OWNERS", OwnerKind.USER);
            String module = harness.moduleDirectory(entry.name()) + "/module.xml";
            harness.edit(module, "</Properties>",
                "<Property name=\"x.added\">1</Property></Properties>");
            harness.exportModule(entry.pluginType(), entry.name())
                .importApplied("--importModule --importUser 'OWNERS' --returnProtocol")
                .exportModule(entry.pluginType(), entry.name());

            WriteOutcome outcome = run(harness, new ImportService.ImportRequest("dev/node1",
                Optional.of("OWNERS"), Optional.empty(), List.of(new ImportScope.Module(
                    entry.name(), Optional.of(entry.pluginType()))), "Module secrets",
                Optional.empty(), Optional.empty()));

            assertNoLeak(harness, fixture, outcome);
            String sent = archiveText(harness.inubit.imported.get(0));
            for (SyntheticSecret secret : secrets(fixture)) {
                assertThat(sent).as(fixture + " " + secret.location()).contains(secret.value());
            }
        }
    }

    private static WriteOutcome run(ImportHarness harness,
        ImportService.ImportRequest request) {
        ImportService.Response response = harness.service().importArtifacts(request);
        harness.cli.verifyComplete();
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).as(outcome.toString())
            .isEqualTo(WriteOutcome.Outcome.EXECUTED);
        return outcome;
    }

    private static List<SyntheticSecret> secrets(String fixture) {
        return ArtifactFixtures.syntheticSecrets().stream()
            .filter(secret -> secret.fixture().equals(fixture))
            .filter(secret -> !secret.kind().equals("savedTestMessage")).toList();
    }

    private void assertNoLeak(ImportHarness harness, String fixture, WriteOutcome outcome)
        throws IOException {
        String workspace = workspaceText(harness.root);
        String history = historyObjects(harness.root);
        String manifests = manifestText(harness.backups.root());
        String audit = harness.audit.toString();
        String result = outcome.toString();
        String log = logText();
        for (SyntheticSecret secret : ArtifactFixtures.syntheticSecrets()) {
            String value = secret.value();
            assertThat(workspace).as("workspace: " + secret.location()).doesNotContain(value);
            assertThat(history).as("history: " + secret.location()).doesNotContain(value);
            assertThat(manifests).as("backup manifest: " + secret.location())
                .doesNotContain(value);
            assertThat(audit).as("audit: " + secret.location()).doesNotContain(value);
            assertThat(result).as("result: " + secret.location()).doesNotContain(value);
            assertThat(log).as("log: " + secret.location()).doesNotContain(value);
        }
        assertThat(log).doesNotContain(ImportHarness.PASSWORD);
        assertThat(importDirectories()).as("the private import directory is gone")
            .isEqualTo(temporaryBefore);
        assertThat(fixture).isNotEmpty();
    }

    private static String archiveText(byte[] zip) {
        return ArtifactFixtures.entries(zip).values().stream()
            .map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1))
            .collect(Collectors.joining("\n"));
    }

    private static String workspaceText(Path root) throws IOException {
        StringBuilder text = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile)
                .filter(file -> !root.relativize(file).startsWith(".git")).toList()) {
                text.append(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
            }
        }
        return text.toString();
    }

    private static String manifestText(Path backups) throws IOException {
        StringBuilder text = new StringBuilder();
        if (Files.isDirectory(backups)) {
            try (Stream<Path> files = Files.list(backups)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                    text.append(Files.readString(file));
                }
            }
        }
        return text.toString();
    }

    /** Every object of the history, decompressed ({@code git cat-file --batch-all-objects}). */
    private static String historyObjects(Path root) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("git", "-C", root.toString(), "cat-file",
            "--batch-all-objects", "--batch").redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process git = builder.start();
        git.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = git.getInputStream()) {
            in.transferTo(out);
        }
        try {
            assertThat(git.waitFor()).isZero();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static Set<Path> importDirectories() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> entries = Files.list(tmp)) {
            return entries.filter(p -> p.getFileName().toString()
                .startsWith("inubit-mcp-export-acme-")).collect(Collectors.toSet());
        }
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return text.toString();
    }
}
