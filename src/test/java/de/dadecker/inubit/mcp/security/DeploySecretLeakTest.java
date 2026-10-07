package de.dadecker.inubit.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.application.DeployGuard;
import de.dadecker.inubit.mcp.application.DeployHarness;
import de.dadecker.inubit.mcp.application.FakeServer;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Stage 3 review m2, T025 (feature 005, FR-016, SC-004, Constitution II): a deployment sends
 * each target node its own secret values — they are inside that node's import archive (and
 * inside the package of a package-only node) and nowhere else: not in the results, the reports,
 * the ledger, the audit, the backup manifests, the workspace or the logs; the source's values
 * never reach a target.
 */
@Timeout(120)
class DeploySecretLeakTest {

    static final String TAG = "TAG-01";
    static final String SOURCE_VALUE = "AES-U1lOVEgtREVQTE9ZLVNPVVJDRS0wMQ";
    static final Map<NodeId, String> TARGET_VALUES = new LinkedHashMap<>();

    static {
        TARGET_VALUES.put(DeployHarness.INT1, "AES-U1lOVEgtREVQTE9ZLUlOVDEtMDE");
        TARGET_VALUES.put(DeployHarness.INT2, "AES-U1lOVEgtREVQTE9ZLUlOVDItMDE");
        TARGET_VALUES.put(DeployHarness.INT3, "AES-U1lOVEgtREVQTE9ZLUlOVDMtMDE");
        TARGET_VALUES.put(DeployHarness.PROD, "AES-U1lOVEgtREVQTE9ZLVBST0QtMDE");
    }

    static final String MODULE_IMPORT = "--importModule --importUser 'jdoe' --returnProtocol";

    @TempDir
    Path temp;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private DeployHarness harness;

    @BeforeEach
    void captureLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
        logs.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
    }

    /** Module-0005 carries a password property: the source's value or the node's own. */
    private static void password(FakeServer server, String value) {
        server.publishModule("Module-0005", text -> {
            String anchor = "<Property name=\"IsModuleTemplate\"";
            if (!text.contains(anchor)) {
                throw new IllegalStateException("module-0005.xml changed");
            }
            return text.replace(anchor, "<Property name=\"Password\" type=\"Password\""
                + " encrypted=\"true\">" + value + "</Property>\n\t" + anchor);
        });
    }

    private void harness() throws IOException {
        harness = new DeployHarness(temp);
        DeployHarness.SOURCES.forEach(node -> password(harness.servers.get(node),
            SOURCE_VALUE));
        TARGET_VALUES.forEach((node, value) -> password(harness.servers.get(node), value));
        harness.tagged(DeployHarness.GROUP, TAG);
    }

    private static DeployGuard.Request request(String target, Optional<String> code) {
        return new DeployGuard.Request(target, TAG, Optional.empty(), code,
            Optional.of("client/1.0"));
    }

    private void intReads() {
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, TAG));
        harness.exportGroup(DeployHarness.SOURCE);
        DeployHarness.TARGETS.forEach(node -> harness.exportGroup(node)
            .exportRepository(node, DeployHarness.RELEASE_XSL));
    }

    @Test
    void eachTargetGetsItsOwnSecretValuesAndNoValueLeavesTheImportArchive() throws IOException {
        harness();
        intReads();
        DeploymentPreview preview = harness.deployService(List.of()).deploy(request("int",
            Optional.empty())).preview().orElseThrow();
        assertThat(preview.executable()).as(preview.toString()).isTrue();
        intReads();
        DeployHarness.TARGETS.forEach(node -> {
            harness.exportGroup(node).exportRepository(node, DeployHarness.RELEASE_XSL);
            harness.importRepositoryApplied(node).importApplied(node, MODULE_IMPORT);
            harness.exportGroup(node).exportRepository(node, DeployHarness.RELEASE_XSL);
            harness.tagVerified(node, DeployHarness.GROUP, TAG);
        });

        DeploymentResult result = harness.deployService(List.of()).deploy(request("int",
            preview.confirmationCode())).result().orElseThrow();

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.EXECUTED);
        for (NodeId node : DeployHarness.TARGETS) {
            List<byte[]> sent = harness.servers.get(node).imported;
            assertThat(sent).hasSize(1);
            String archive = archiveText(sent.get(0));
            assertThat(archive).as(node.value()).contains(TARGET_VALUES.get(node))
                .doesNotContain(SOURCE_VALUE);
            TARGET_VALUES.forEach((other, value) -> {
                if (!other.equals(node)) {
                    assertThat(archive).as(node + " holds " + other).doesNotContain(value);
                }
            });
        }
        assertNoLeak(preview.toString() + result, List.of());
        harness.verifyComplete();
    }

    /** No value outside the import archives (and the given package directories). */
    void assertNoLeak(String answers, List<Path> allowed) throws IOException {
        Map<String, String> places = new LinkedHashMap<>();
        places.put("results", answers);
        places.put("workspace and reports", text(harness.root, path -> !path.startsWith(
            harness.root.resolve(".git"))));
        places.put("git history", ArtifactHistory.objects(harness.root));
        places.put("deployments (ledger, locks)", text(harness.deployments(), path -> true));
        places.put("backup manifests", text(harness.backups.root(), path -> path.toString()
            .endsWith(".json")));
        places.put("packages", text(harness.profileHome.resolve("packages"), path -> allowed
            .stream().noneMatch(path::startsWith)));
        places.put("audit", harness.audit.toString());
        places.put("logs", logText());
        List<String> values = new ArrayList<>(TARGET_VALUES.values());
        values.add(SOURCE_VALUE);
        values.add(DeployHarness.PASSWORD);
        places.forEach((place, text) -> values.forEach(value ->
            assertThat(text).as(place).doesNotContain(value)));
    }

    static String archiveText(byte[] zip) {
        return ArtifactFixtures.entries(zip).values().stream()
            .map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1))
            .collect(Collectors.joining("\n"));
    }

    /** Every regular file below {@code root} that {@code include} accepts; ZIPs unpacked. */
    static String text(Path root, java.util.function.Predicate<Path> include)
        throws IOException {
        StringBuilder text = new StringBuilder();
        if (!Files.isDirectory(root)) {
            return "";
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(include).toList()) {
                byte[] bytes = Files.readAllBytes(file);
                text.append(file.toString().endsWith(".zip") ? archiveText(bytes)
                    : new String(bytes, StandardCharsets.ISO_8859_1)).append('\n');
            }
        }
        return text.toString();
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

    /** The objects of the workspace history ({@code git cat-file --batch-all-objects}). */
    static final class ArtifactHistory {
        private ArtifactHistory() {
        }

        static String objects(Path root) throws IOException {
            ProcessBuilder builder = new ProcessBuilder("git", "-C", root.toString(), "cat-file",
                "--batch-all-objects", "--batch").redirectErrorStream(true);
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            Process git = builder.start();
            git.getOutputStream().close();
            byte[] out = git.getInputStream().readAllBytes();
            try {
                git.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new String(out, StandardCharsets.ISO_8859_1);
        }
    }
}
