package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T026, T031 (feature 004, research D-23, D-26, SC-006): the development tools against a real
 * <b>development</b> node, on a dedicated test diagram group with a dedicated test workflow only
 * — opt-in, never part of the default build, never on production. Run with
 * {@code INUBIT_MCP_PROFILE=<profile> INUBIT_LIVE_DEV_NODE=dev/node1 INUBIT_LIVE_DEV_OWNER=<owner>
 * INUBIT_LIVE_DEV_DIAGRAM_GROUP=<test group> INUBIT_LIVE_DEV_WORKFLOW=<test workflow>
 * mvn verify -Plive -Dtest=DevelopmentLiveTest}; starting it is the approval of exactly this
 * scenario.
 *
 * <p>The owner may be a user or a user group (research D-26: the server no longer tells them
 * apart, every import uses {@code --importUser}). Because a user group's diagram groups are
 * shared, nothing is chosen by default: the diagram group <b>and</b> the workflow must both be
 * named explicitly, otherwise the test is skipped. Refused before anything is written unless the
 * node is a development node ({@link LiveTarget#resolveDevelopment}). Scenario, into a
 * <b>temporary</b> workspace: export → change one layout
 * value of one workflow → {@code import_artifacts} → verified (the workspace shows the change)
 * → {@code restore_backup} → {@code set_active} off/on (or on/off) → {@code tag_artifacts} of
 * the whole diagram group with the fixed tag {@link #TAG}. The tag is never removed (StartCLI
 * removes a tag only owner-wide, research D-26): it stays as a harmless label on the group's
 * current versions, and the next run reuses the name, which moves it to the then current
 * versions. Every write needs a confirmation code under
 * {@code development.confirmation: SERVER}; the test confirms the preview it got. Only counts
 * and timings are printed, never names or content.
 *
 * <p>T047 (feature 008): the repository case
 * {@link #importAndRestoreAProbeFileInThePersonalTestRepositoryFolder} needs
 * {@code INUBIT_LIVE_DEV_NODE}, {@code INUBIT_LIVE_DEV_OWNER} and
 * {@code INUBIT_LIVE_DEV_REPOSITORY_FOLDER} (the person's test folder below
 * {@code /Root/<owner>/}) and is skipped without them; it writes only the probe file
 * {@link #PROBE} in that folder.
 */
@Tag("live")
@Timeout(1800)
class DevelopmentLiveTest {

    static final String OWNER_VARIABLE = "INUBIT_LIVE_DEV_OWNER";
    static final String GROUP_VARIABLE = "INUBIT_LIVE_DEV_DIAGRAM_GROUP";
    /** The workflow to change; required, never a default (a group may be shared). */
    static final String WORKFLOW_VARIABLE = "INUBIT_LIVE_DEV_WORKFLOW";
    /**
     * The repository case (feature 008): the person's test folder, relative to the owner's
     * repository root {@code /Root/<owner>/}; required, never a default (never a shared folder).
     */
    static final String REPOSITORY_FOLDER_VARIABLE = "INUBIT_LIVE_DEV_REPOSITORY_FOLDER";
    /**
     * The only file the repository case writes, in the test folder: created by the first run,
     * changed by every later one, never deleted (an import cannot delete).
     */
    static final String PROBE = "inubit-mcp-live-probe.xsd";
    /** The tag of every run: reused, never removed, so runs do not pile up tag names. */
    static final String TAG = "LIVE-TEST";
    private static final Duration WAIT = Duration.ofSeconds(600);
    private static final Pattern X_POS = Pattern.compile("xPos=\"(\\d+)\"");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ACTIVE = Pattern.compile("<IsActive>(true|false)</IsActive>");

    @TempDir
    Path workspace;

    @Test
    void importRestoreActivateAndTagOnATestDiagramGroup() throws IOException {
        LiveTarget live = LiveTarget.resolveDevelopment();
        String owner = System.getenv(OWNER_VARIABLE);
        String group = System.getenv(GROUP_VARIABLE);
        String named = System.getenv(WORKFLOW_VARIABLE);
        Assumptions.assumeTrue(given(owner) && given(group) && given(named), OWNER_VARIABLE
            + ", " + GROUP_VARIABLE + " and " + WORKFLOW_VARIABLE + " are not all set; the"
            + " development live test is skipped (nothing is chosen by default)");
        owner = owner.strip();
        group = group.strip();
        NodeId node = live.node();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
            .format(Instant.now());

        try (TestWiring wiring = live.wiring(workspace);
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            long start = System.nanoTime();

            structured(client.callTool("export_artifacts", Map.of("target", node.value(),
                "owner", owner, "diagramGroups", List.of(group)), WAIT));
            Path file = namedWorkflow(node, owner, group, named.strip());
            String original = Files.readString(file, StandardCharsets.UTF_8);
            Matcher x = X_POS.matcher(original);
            assertThat(x.find()).as("a layout value to change").isTrue();
            String moved = original.substring(0, x.start()) + "xPos=\""
                + (Integer.parseInt(x.group(1)) + 10) + "\"" + original.substring(x.end());
            Files.writeString(file, moved, StandardCharsets.UTF_8);

            JsonNode imported = write(client, "import_artifacts", Map.of("node", node.value(),
                "owner", owner, "diagramGroup", group, "reason", "Live test layout " + stamp));
            assertThat(imported.path("outcome").asString()).isEqualTo("EXECUTED");
            assertThat(imported.path("modified").size()).isEqualTo(1);
            assertThat(X_POS.matcher(Files.readString(file)).results().findFirst().orElseThrow()
                .group()).as("the verified state is in the workspace")
                .isEqualTo(X_POS.matcher(moved).results().findFirst().orElseThrow().group());

            JsonNode restored = write(client, "restore_backup", Map.of("node", node.value(),
                "backupRef", imported.path("backupRef").asString(), "reason",
                "Live test restore " + stamp));
            assertThat(restored.path("outcome").asString()).isEqualTo("EXECUTED");
            assertThat(X_POS.matcher(Files.readString(file)).results().findFirst().orElseThrow()
                .group()).isEqualTo(x.group());

            String workflow = file.getFileName().toString().replaceAll("\\.xml$", "");
            Matcher active = ACTIVE.matcher(Files.readString(file));
            assertThat(active.find()).isTrue();
            boolean wasActive = Boolean.parseBoolean(active.group(1));
            for (boolean value : List.of(!wasActive, wasActive)) {
                JsonNode switched = write(client, "set_active", Map.of("node", node.value(),
                    "owner", owner, "diagramGroup", group, "workflow",
                    de.dadecker.inubit.mcp.domain.model.NameCodec.decode(workflow), "active",
                    value, "reason", "Live test active " + value));
                assertThat(switched.path("outcome").asString()).isEqualTo("EXECUTED");
            }

            JsonNode tagResult = write(client, "tag_artifacts", Map.of("node", node.value(),
                "owner", owner, "diagramGroups", List.of(group), "tag", TAG, "reason",
                "Live test tag " + stamp));
            assertThat(tagResult.path("outcome").asString()).isEqualTo("EXECUTED");
            assertThat(tagResult.path("workflows").asInt()).isPositive();

            System.err.println("[live] " + node + ": import, restore, set_active x2 and tag of"
                + " one test diagram group: " + tagResult.path("workflows").asInt()
                + " workflow(s), " + tagResult.path("modules").asInt() + " module(s) carry "
                + TAG + ", " + Duration.ofNanos(System.nanoTime() - start).toSeconds() + " s");
        }
    }

    /**
     * T047 (feature 008, quickstart "Live"): repository files on a development node whose
     * {@code development.repository} is {@code WRITE}, only in the test folder named by
     * {@link #REPOSITORY_FOLDER_VARIABLE} and only on the file {@link #PROBE}. Export of the
     * folder → the probe changed (or, on the first run, created) → {@code import_artifacts} of
     * the probe alone, preview confirmed with its server code → {@code EXECUTED} with a backup →
     * the same node with {@code READ_ONLY} (an in-memory copy of the configuration, a new
     * server) refuses another change with {@code REPOSITORY_WRITE_DENIED} → back on
     * {@code WRITE}, {@code restore_backup} of the import brings the previous content back (a
     * created probe stays and is listed in {@code createdNotRemoved}) → a new export of the
     * folder shows every other file of it unchanged.
     */
    @Test
    void importAndRestoreAProbeFileInThePersonalTestRepositoryFolder() throws IOException {
        LiveTarget live = LiveTarget.resolveDevelopment();
        String owner = System.getenv(OWNER_VARIABLE);
        String folder = System.getenv(REPOSITORY_FOLDER_VARIABLE);
        Assumptions.assumeTrue(given(owner) && given(folder), OWNER_VARIABLE + " and "
            + REPOSITORY_FOLDER_VARIABLE + " are not both set; the repository case of the"
            + " development live test is skipped (no folder is chosen by default)");
        if (!live.repositoryWrite()) {
            throw new AssertionError("Refusing to run the repository case: development.repository"
                + " is not WRITE on " + live.node() + " (use a temporary copy of the profile)");
        }
        owner = owner.strip();
        folder = folder.strip();
        NodeId node = live.node();
        String probePath = folder + "/" + PROBE;
        String fullPath = "/Root/" + owner + "/" + probePath;
        Path probe = workspace.resolve(WorkspacePath.repository(node.group(), owner,
            fullPath).toRelativePath());
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
            .format(Instant.now());
        long start = System.nanoTime();

        Map<Path, byte[]> before;
        boolean probeExisted;
        byte[] imported = probe(stamp).getBytes(StandardCharsets.UTF_8);
        String backupRef;
        try (TestWiring wiring = live.wiring(workspace);
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            structured(client.callTool("export_artifacts", Map.of("target", node.value(),
                "owner", owner, "repositoryPaths", List.of(folder)), WAIT));
            before = files(probe.getParent());
            probeExisted = Files.isRegularFile(probe);
            Files.write(probe, imported);

            Map<String, Object> arguments = Map.of("node", node.value(), "owner", owner,
                "repositoryPaths", List.of(probePath), "reason", "Live test repository " + stamp);
            JsonNode challenge = structured(client.callTool("import_artifacts", arguments, WAIT))
                .path("challenge");
            assertThat(challenge.path("confirmationCode").asString())
                .as("a repository file always needs a server code").isNotEmpty();
            assertThat(challenge.path(probeExisted ? "modify" : "create").toString())
                .contains(fullPath);
            assertThat(challenge.path("repositoryDiff").asString()).isNotEmpty();
            Map<String, Object> confirmed = new HashMap<>(arguments);
            confirmed.put("confirmationCode", challenge.path("confirmationCode").asString());
            JsonNode result = structured(client.callTool("import_artifacts", confirmed, WAIT))
                .path("result");
            assertThat(result.path("outcome").asString()).isEqualTo("EXECUTED");
            assertThat(result.path(probeExisted ? "modified" : "created").toString())
                .contains(fullPath);
            assertThat(Files.readAllBytes(probe)).as("the verified state is in the workspace")
                .isEqualTo(imported);
            backupRef = result.path("backupRef").asString();
        }

        try (TestWiring wiring = live.readOnlyRepositoryWiring(workspace);
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            Files.writeString(probe, probe(stamp + " read-only"), StandardCharsets.UTF_8);
            JsonNode denied = client.callTool("import_artifacts", Map.of("node", node.value(),
                "owner", owner, "repositoryPaths", List.of(probePath), "reason",
                "Live test read-only " + stamp), WAIT);
            assertThat(denied.path("isError").asBoolean()).as(denied::toString).isTrue();
            assertThat(JSON.readTree(denied.path("content").get(0).path("text").asString())
                .path("error").path("code").asString()).isEqualTo("REPOSITORY_WRITE_DENIED");
            Files.write(probe, imported);
        }

        try (TestWiring wiring = live.wiring(workspace);
            McpTestClient client = McpTestClient.start(wiring.toolHandlers(),
                live.scrubber())) {
            client.initialize();
            JsonNode restored = write(client, "restore_backup", Map.of("node", node.value(),
                "backupRef", backupRef, "reason", "Live test repository restore " + stamp));
            assertThat(restored.path("outcome").asString()).isEqualTo("EXECUTED");
            if (!probeExisted) {
                assertThat(restored.path("createdNotRemoved").toString()).contains(fullPath);
            }

            structured(client.callTool("export_artifacts", Map.of("target", node.value(),
                "owner", owner, "repositoryPaths", List.of(folder)), WAIT));
            Map<Path, byte[]> after = files(probe.getParent());
            if (!probeExisted) {
                assertThat(after.remove(probe)).as("a created probe stays").isEqualTo(imported);
            }
            assertThat(after.keySet()).as("the files of the test folder")
                .isEqualTo(before.keySet());
            before.forEach((file, content) -> assertThat(after.get(file))
                .as("restored or unchanged: %s", workspace.relativize(file)).isEqualTo(content));

            System.err.println("[live] " + node + ": repository probe " + (probeExisted
                ? "modified" : "created") + ", refused read-only, restored; " + before.size()
                + " file(s) in the test folder, "
                + Duration.ofNanos(System.nanoTime() - start).toSeconds() + " s");
        }
    }

    /** A synthetic schema with the run's stamp, LF only and no trailing newline. */
    private static String probe(String stamp) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n"
            + "  <!-- inubit-mcp-server live test " + stamp + " -->\n"
            + "  <xs:element name=\"LiveTestProbe\" type=\"xs:string\"/>\n"
            + "</xs:schema>";
    }

    /** Every file below {@code folder} with its content. */
    private static Map<Path, byte[]> files(Path folder) throws IOException {
        Map<Path, byte[]> files = new HashMap<>();
        if (!Files.isDirectory(folder)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(file, Files.readAllBytes(file));
            }
        }
        return files;
    }

    private static boolean given(String value) {
        return value != null && !value.isBlank();
    }

    /** The workflow file named by {@code INUBIT_LIVE_DEV_WORKFLOW} in the temporary workspace. */
    private Path namedWorkflow(NodeId node, String owner, String group, String named) {
        Path file = workspace.resolve(WorkspacePath.workflow(node.group(), owner, group, named)
            .toRelativePath());
        if (!Files.isRegularFile(file)) {
            throw new AssertionError(WORKFLOW_VARIABLE + " names no workflow of the group");
        }
        return file;
    }

    /** The result of a write, confirming a preview with its code (the run is the approval). */
    private static JsonNode write(McpTestClient client, String tool,
        Map<String, Object> arguments) {
        JsonNode first = structured(client.callTool(tool, arguments, WAIT));
        if (!first.has("challenge")) {
            return first.path("result");
        }
        Map<String, Object> confirmed = new HashMap<>(arguments);
        confirmed.put("confirmationCode", first.path("challenge").path("confirmationCode")
            .asString());
        return structured(client.callTool(tool, confirmed, WAIT)).path("result");
    }

    /** The structured result; a failure shows only the error. */
    private static JsonNode structured(JsonNode result) {
        assertThat(result.path("isError").asBoolean())
            .as(() -> result.path("content").toString()).isFalse();
        return result.path("structuredContent");
    }
}
