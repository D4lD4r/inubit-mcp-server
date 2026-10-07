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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Stage 3 review m2, T025, final review m4 (feature 005, FR-016, SC-004, Constitution II), for
 * every secret form of feature 003 ({@link #forms}): a deployment sends
 * each target node its own secret values — they are inside that node's import archive (and
 * inside the package of a package-only node) and nowhere else: not in the results, the reports,
 * the ledger, the audit, the backup manifests, the workspace or the logs; the source's values
 * never reach a target.
 */
@Timeout(120)
class DeploySecretLeakTest {

    static final String TAG = "TAG-01";
    /** Who holds a value: the source group or one target node. */
    static final String SOURCE = "source";

    /**
     * One secret form of feature 003 (SecretPaths, SC-004), put into Module-0005 with a value
     * that differs on the source and on every target node.
     *
     * @param inject the module text with the form holding {@code value}
     * @param value  the value of the holder ({@link #SOURCE} or a node id)
     */
    record Form(String name, java.util.function.BiFunction<String, String, String> inject,
        java.util.function.Function<String, String> value) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static String b64(String text) {
        return java.util.Base64.getEncoder().encodeToString(text.getBytes(
            StandardCharsets.UTF_8));
    }

    /** {@code element} before the {@code IsModuleTemplate} property. */
    private static String property(String text, String element) {
        String anchor = "<Property name=\"IsModuleTemplate\"";
        if (!text.contains(anchor)) {
            throw new IllegalStateException("module-0005.xml changed");
        }
        return text.replace(anchor, element + "\n\t" + anchor);
    }

    private static Form typed(String name, String element) {
        return new Form(name, (text, value) -> property(text, element.formatted(value)),
            holder -> b64("synthetic-" + name + "-" + holder));
    }

    static List<Form> forms() {
        return List.of(
            typed("legacy", "<Property name=\"Password\" type=\"Password\""
                + " encrypted=\"true\">%s</Property>"),
            new Form("AES-", (text, value) -> property(text, ("<Property name=\"Password\""
                + " type=\"Password\" encrypted=\"true\">%s</Property>").formatted(value)),
                holder -> "AES-" + b64("synthetic-aes-" + holder)),
            new Form("AESG", (text, value) -> property(text, ("<Property name=\"Password\""
                + " type=\"Password\" encrypted=\"true\">%s</Property>").formatted(value)),
                holder -> "AESGs1-y+" + b64("synthetic-aesg-" + holder) + ":U1lOVEhJVjE"),
            typed("plain", "<Property name=\"Login.Password\" type=\"Password\">%s</Property>"),
            typed("masked", "<Property name=\"var.userPassword\" type=\"MaskedString\""
                + " encrypted=\"true\">%s</Property>"),
            typed("keystore", "<Property name=\"WS-Security.Client.KeyStore\""
                + " type=\"KeyStore\">%s</Property>"),
            typed("untyped-keystore", "<Property name=\"SSLKeyStoreRemoteConnector\">%s"
                + "</Property>"),
            typed("untyped-password", "<Property name=\"SSLKeyStorePasswordRemoteConnector\">"
                + "%s</Property>"),
            typed("smime-keystore", "<Property name=\"smime.keystore.data\">%s</Property>"),
            typed("smime-password", "<Property name=\"smime.keystore.alias.password\">%s"
                + "</Property>"),
            typed("x509-with-key", "<Property name=\"Mime.Sign.X509\" type=\"X509\">%s"
                + "</Property>"),
            typed("internal-document", "<Property name=\"clientStore\""
                + " type=\"InternalDocument\" documentName=\"client.pfx\">%s</Property>"),
            typed("pem-private-key", "<Property name=\"signingKey\">-----BEGIN EC PRIVATE"
                + " KEY-----\n%s\n-----END EC PRIVATE KEY-----</Property>"),
            new Form("source-variable", (text, value) -> text.replace(
                "<Property name=\"xslt.sourceVariables\" type=\"Map\" />",
                "<Property name=\"xslt.sourceVariables\" type=\"Map\">\n\t\t<Property"
                    + " name=\"ISServerName\">" + value + "</Property>\n\t</Property>"),
                holder -> "synthetic-sv-" + b64(holder)));
    }

    private Form form;

    /** Every value of the form: the source's and each target node's. */
    private List<String> values() {
        List<String> values = new ArrayList<>(List.of(form.value().apply(SOURCE)));
        TARGETS.forEach(node -> values.add(form.value().apply(node.value())));
        return values;
    }

    static final List<NodeId> TARGETS = List.of(DeployHarness.INT1, DeployHarness.INT2,
        DeployHarness.INT3, DeployHarness.PROD);

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

    /** Module-0005 carries the form: the source's value or the node's own. */
    private void secret(FakeServer server, String holder) {
        String value = form.value().apply(holder);
        server.publishModule("Module-0005", text -> {
            String changed = form.inject().apply(text, value);
            if (changed.equals(text)) {
                throw new IllegalStateException("module-0005.xml changed");
            }
            return changed;
        });
    }

    private void harness(Form secretForm) throws IOException {
        form = secretForm;
        harness = new DeployHarness(temp);
        DeployHarness.SOURCES.forEach(node -> secret(harness.servers.get(node), SOURCE));
        TARGETS.forEach(node -> secret(harness.servers.get(node), node.value()));
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("forms")
    void eachTargetGetsItsOwnSecretValuesAndNoValueLeavesTheImportArchive(Form secretForm)
        throws IOException {
        harness(secretForm);
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
            assertThat(archive).as(node.value()).contains(form.value().apply(node.value()))
                .doesNotContain(form.value().apply(SOURCE));
            TARGETS.forEach(other -> {
                if (!other.equals(node)) {
                    assertThat(archive).as(node + " holds " + other)
                        .doesNotContain(form.value().apply(other.value()));
                }
            });
        }
        assertNoLeak(preview.toString() + result, List.of());
        harness.verifyComplete();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forms")
    void aPackageHoldsTheProductionNodesOwnValuesOnlyInItsArchives(Form secretForm)
        throws IOException {
        // T025: prod receives from int, package only
        harness(secretForm);
        DeployHarness.TARGETS.forEach(node -> {
            harness.servers.get(node).publishModule("Module-0005", text -> text.replace(
                "IsModuleTemplate", "IsModuleTemplateX"));
            harness.servers.get(node).tag(DeployHarness.GROUP, TAG);
        });
        Runnable reads = () -> {
            DeployHarness.TARGETS.forEach(node -> harness.exportRelease(node, TAG));
            harness.exportGroup(DeployHarness.INT1).exportGroup(DeployHarness.PROD);
        };
        reads.run();
        DeploymentPreview preview = harness.deployService(List.of()).deploy(request("prod",
            Optional.empty())).preview().orElseThrow();
        assertThat(preview.executable()).as(preview.toString()).isTrue();
        reads.run();
        harness.exportGroup(DeployHarness.PROD); // the re-check

        DeploymentResult result = harness.deployService(List.of()).deploy(request("prod",
            preview.confirmationCode())).result().orElseThrow();

        assertThat(result.outcome()).isEqualTo(DeploymentResult.Outcome.PACKAGED);
        Path dir = Path.of(result.nodes().get(0).packageDir().orElseThrow());
        List<Path> archives;
        try (Stream<Path> files = Files.list(dir)) {
            archives = files.filter(file -> file.toString().endsWith(".zip")).toList();
        }
        assertThat(archives).isNotEmpty();
        String packaged = archives.stream().map(file -> {
            try {
                return archiveText(Files.readAllBytes(file));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).collect(Collectors.joining("\n"));
        assertThat(packaged).contains(form.value().apply(DeployHarness.PROD.value()));
        TARGETS.forEach(node -> {
            if (!node.equals(DeployHarness.PROD)) {
                assertThat(packaged).as(node.value())
                    .doesNotContain(form.value().apply(node.value()));
            }
        });
        assertThat(packaged).doesNotContain(form.value().apply(SOURCE));
        assertNoLeak(preview.toString() + result, archives);
        assertThat(harness.servers.get(DeployHarness.PROD).imported).isEmpty();
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
        List<String> values = new ArrayList<>(values());
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
