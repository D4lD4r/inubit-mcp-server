package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.git.GitCli;
import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T021/T022 test support: a {@link WorkspaceService} on a real git history and the real archive
 * codec, with a fake StartCLI ({@link FakeArtifacts}) and a fake module list; plus helpers that
 * derive export archives from the fixtures and inspect the workspace.
 */
public final class ExportHarness {

    public static final NodeId DEV = NodeId.parse("dev/node1");

    public final Path root;
    public final FakeArtifacts artifacts = new FakeArtifacts();
    public final Map<String, String> pluginTypes = new LinkedHashMap<>();
    public final GitCli history;

    public ExportHarness(Path root) {
        this.root = root;
        SystemProcessLauncher system = new SystemProcessLauncher();
        this.history = new GitCli(root, "acme", system::launch,
            Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin")));
    }

    public WorkspaceService service() {
        return service(new ArchiveCodec());
    }

    public WorkspaceService service(ArchiveCodecPort codec) {
        return new WorkspaceService(root, history, codec, node -> {
            assertThat(node).isEqualTo(DEV);
            return artifacts;
        }, node -> inventory());
    }

    static WorkspaceService.ExportRequest diagramGroups(String owner, String... names) {
        return new WorkspaceService.ExportRequest(DEV, owner, List.of(names), List.of());
    }

    static WorkspaceService.ExportRequest modules(String owner,
        WorkspaceService.ModuleRef... modules) {
        return new WorkspaceService.ExportRequest(DEV, owner, List.of(), List.of(modules));
    }

    private InventoryPort inventory() {
        return new InventoryPort() {
            @Override
            public List<InventoryItem> listDiagrams(String owner) {
                throw new AssertionError("not used");
            }

            @Override
            public DiagramDetail diagramDetail(String owner, String name) {
                throw new AssertionError("not used");
            }

            @Override
            public DiagramMetadata diagramMetadata(String name) {
                throw new AssertionError("not used");
            }

            @Override
            public VersionHistory versionHistory(String owner, String type, String group) {
                throw new AssertionError("not used");
            }

            @Override
            public List<ModuleEntry> listModules(String owner) {
                artifacts.calls.add("listModules " + owner);
                return pluginTypes.entrySet().stream().map(e -> new ModuleEntry(
                    new InventoryItem(DEV, InventoryKind.MODULE, e.getKey(), e.getValue(),
                        e.getValue(), owner, Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                    Optional.empty(), Optional.empty(), new ConnectorFlags(false, false, false),
                    Optional.empty())).toList();
            }
        };
    }

    /** StartCLI stand-in: returns the archive registered for a diagram group or module. */
    public static final class FakeArtifacts implements ArtifactPort {

        public final Map<String, byte[]> exports = new LinkedHashMap<>();
        public final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public byte[] exportWorkflowGroup(String owner, String diagramGroup) {
            calls.add("group " + owner + " " + diagramGroup);
            return answer(diagramGroup);
        }

        @Override
        public byte[] exportModule(String owner, String pluginType, String name) {
            calls.add("module " + owner + " " + pluginType + " " + name);
            return answer(name);
        }

        private byte[] answer(String name) {
            byte[] archive = exports.get(name);
            if (archive == null) {
                throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                    name + " does not exist", "fake", "fake").withNode(DEV));
            }
            return archive;
        }
    }

    /** The files of the workspace (without {@code .git} and the lock file), by relative path. */
    public SortedMap<String, String> snapshot() {
        SortedMap<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!relative.startsWith(".git/") && !relative.equals(".lock")) {
                    files.put(relative, new String(Files.readAllBytes(file),
                        StandardCharsets.ISO_8859_1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** The subjects of the history, newest first (empty without a history). */
    public List<String> log() {
        if (!Files.isDirectory(root.resolve(".git"))) {
            return List.of();
        }
        String output = git("log", "--format=%s");
        return output.isBlank() ? List.of() : List.of(output.strip().split("\n"));
    }

    /** Plain git (outside the classes under test), failing the test on a non-zero exit. */
    public String git(String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as("git %s: %s", String.join(" ", arguments), output)
                .isZero();
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** {@code fixture} with the text of entry {@code entry} rewritten. */
    static byte[] rewrite(String fixture, String entry, UnaryOperator<String> change) {
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries(fixture));
        String text = new String(entries.get(entry), StandardCharsets.UTF_8);
        String changed = change.apply(text);
        assertThat(changed).as("the change of %s applies", entry).isNotEqualTo(text);
        entries.put(entry, changed.getBytes(StandardCharsets.UTF_8));
        return ArtifactFixtures.zip(entries);
    }

    /** {@code grp-a.zip} without module {@code Module-0009} (a used module of another owner). */
    static byte[] withoutModule0009() {
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries(
            "grp-a.zip"));
        entries.remove("module/module-0009.xml");
        String index = new String(entries.get("module/module.xml"), StandardCharsets.UTF_8);
        String changed = index.replaceFirst("(?s)<Module type=\"technical\" version=\"head\">"
            + "\\s*<ModuleName>Module-0009</ModuleName>.*?</Module>\\s*", "");
        assertThat(changed).isNotEqualTo(index);
        entries.put("module/module.xml", changed.getBytes(StandardCharsets.UTF_8));
        return ArtifactFixtures.zip(entries);
    }

    private static final Pattern MODULE_GROUP = Pattern.compile(
        "(?s)<ModuleGroup>\\s*<ModuleGroupName>([^<]*)</ModuleGroupName>(.*?)</ModuleGroup>");
    private static final Pattern MODULE = Pattern.compile(
        "(?s)<Module type=\"technical\".*?<ModuleName>(Module-\\d{4})</ModuleName>.*?</Module>");
    private static final Pattern WORKFLOW = Pattern.compile(
        "(?s)<Workflow version=.*?</Workflow>");

    /**
     * SC-006: one diagram group {@code GRP-01} with {@code workflows} workflows (copies of
     * {@code Workflow-0001}) and {@code modules} modules (renamed copies of the nine modules of
     * {@code grp-a.zip}); workflow {@code i} uses the modules {@code Module-1<i*4+k>}.
     */
    static byte[] large(int workflows, int modules) {
        Map<String, byte[]> source = ArtifactFixtures.entries("grp-a.zip");
        String index = text(source, "module/module.xml");
        Map<String, String> templates = new LinkedHashMap<>();
        Map<String, String> groupOf = new LinkedHashMap<>();
        Matcher groups = MODULE_GROUP.matcher(index);
        while (groups.find()) {
            Matcher module = MODULE.matcher(groups.group(2));
            while (module.find()) {
                templates.put(module.group(1), module.group());
                groupOf.put(module.group(1), groups.group(1));
            }
        }
        List<String> names = new ArrayList<>(templates.keySet());
        Map<String, StringBuilder> byGroup = new LinkedHashMap<>();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("archive.properties", source.get("archive.properties"));
        entries.put("Repository.zip", source.get("Repository.zip"));
        Map<String, byte[]> moduleFiles = new LinkedHashMap<>();
        for (int j = 0; j < modules; j++) {
            String template = names.get(j % names.size());
            String name = "Module-%04d".formatted(1000 + j);
            byGroup.computeIfAbsent(groupOf.get(template), g -> new StringBuilder())
                .append(templates.get(template).replace(template, name)).append('\n');
            moduleFiles.put("module/" + name.toLowerCase() + ".xml",
                source.get("module/" + template.toLowerCase() + ".xml"));
        }
        String workflowXml = text(source, "workflow/workflow.xml");
        Matcher first = WORKFLOW.matcher(workflowXml);
        assertThat(first.find()).isTrue();
        int start = first.start();
        Matcher last = WORKFLOW.matcher(workflowXml);
        int end = start;
        while (last.find()) {
            end = last.end();
        }
        StringBuilder copies = new StringBuilder();
        for (int i = 0; i < workflows; i++) {
            int base = 1000 + i * 4;
            copies.append(first.group().replace("Workflow-0001", "Workflow-%04d".formatted(
                2000 + i)).replaceAll("Module-000([1-4])", "Module-X$1")
                .replace("Module-X1", "Module-%04d".formatted(base))
                .replace("Module-X2", "Module-%04d".formatted(base + 1))
                .replace("Module-X3", "Module-%04d".formatted(base + 2))
                .replace("Module-X4", "Module-%04d".formatted(base + 3)));
        }
        entries.put("workflow/workflow.xml", (workflowXml.substring(0, start) + copies
            + workflowXml.substring(end)).getBytes(StandardCharsets.UTF_8));
        StringBuilder moduleIndex = new StringBuilder(index.substring(0, index.indexOf(
            "<ModuleGroup>")));
        byGroup.forEach((group, xml) -> moduleIndex.append("<ModuleGroup><ModuleGroupName>")
            .append(group).append("</ModuleGroupName>").append(xml).append("</ModuleGroup>"));
        moduleIndex.append(index.substring(index.lastIndexOf("</ModuleGroup>")
            + "</ModuleGroup>".length()));
        entries.put("module/module.xml", moduleIndex.toString().getBytes(
            StandardCharsets.UTF_8));
        entries.putAll(moduleFiles);
        return ArtifactFixtures.zip(entries);
    }

    private static String text(Map<String, byte[]> entries, String name) {
        return new String(entries.get(name), StandardCharsets.UTF_8);
    }
}
