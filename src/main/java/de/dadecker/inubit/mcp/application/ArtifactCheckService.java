package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.CheckReport;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Check;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph.Edge;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph.Node;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph.Reference;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.model.XsltRun;
import de.dadecker.inubit.mcp.domain.port.ArtifactInspectorPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.XsltPort;
import de.dadecker.inubit.mcp.domain.port.XsltPort.XsltRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The structure checks of {@code check_artifacts} (research D-13, FR-027): for every workflow,
 * module and repository file below the requested workspace paths.
 *
 * <ul>
 *   <li>{@code EDGE_TARGET_MISSING} (ERROR): a {@code Connection} targets a node that does not
 *       exist;
 *   <li>{@code ID_COLLISION} (ERROR): a {@code ModuleId} or {@code ConnectionId} is used twice
 *       (both share one id space);
 *   <li>{@code DEMUX_KEY_UNMATCHED} (ERROR): a condition key {@code <Name>(<id>)@@@…} or a
 *       {@code DefaultOutput} {@code <Name>(<id>)} does not name an outgoing edge of that node
 *       to a node with that name and id; {@code DEMUX_KEY_STALE} (WARNING) if no node of the
 *       workflow has that id at all (a condition the Workbench kept after the node was deleted;
 *       INUBIT ignores it);
 *   <li>{@code PARENT_REF_MISSING} (ERROR): {@code ParentModule} or {@code EndLoopId} names a
 *       node that does not exist;
 *   <li>{@code REPOSITORY_REF_MISSING} (ERROR): an {@code inubitrepository:} path is neither a
 *       repository file of the group (any owner) nor withheld as key material ({@code .meta});
 *       {@code REPOSITORY_REF_UNVERIFIED} (WARNING) if it lies in another owner's repository
 *       ({@code Root/<owner>/…}), which an export does not include;
 *   <li>{@code MODULE_MISSING} (ERROR) / {@code MODULE_UNVERIFIED} (WARNING): see
 *       {@link #checkPaths};
 *   <li>{@code VARIABLE_UNRESOLVED} (WARNING): a variable reference names no declared variable;
 *       names starting with {@code IS} are INUBIT's implicit system variables (assumption);
 *   <li>{@code DERIVED_VALUE_MISMATCH} (WARNING): {@code <property>MD5} of an embedded document
 *       or {@code contentMD5}/{@code contentSize} of a repository file no longer matches the
 *       content (research D-4; the rebuild recomputes them).
 * </ul>
 */
public final class ArtifactCheckService {

    private static final Pattern DEMUX_KEY = Pattern.compile("^(.+)\\((\\d+)\\)@@@.*$");
    private static final Pattern DEFAULT_OUTPUT = Pattern.compile("^(.+)\\((\\d+)\\)$");
    private static final Pattern REPOSITORY_REFERENCE =
        Pattern.compile("inubitrepository:/+([^\"'<>&\\s]+)");
    private static final String SYSTEM_VARIABLE_PREFIX = "IS";
    private static final Set<String> SKIPPED = Set.of(".git", ".meta", ".tests", ".reports");
    private static final Set<String> XML_EXTENSIONS = Set.of("xml", "xsd", "xsl", "xslt",
        "wsdl");
    /** The longest finding message (FR-034). */
    static final int MAX_MESSAGE = 500;
    private static final String REPORTS = ".reports";
    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    private final Path root;
    private final ArtifactInspectorPort inspector;
    private final XsltPort xslt;
    private final ResultLimiter limiter;
    private final Clock clock;
    private final Function<GroupId, Optional<NodeId>> firstNode;
    private final Function<NodeId, InventoryPort> inventory;
    private final Function<NodeId, Optional<String>> owners;

    /**
     * @param root      the workspace root
     * @param inspector reads the workspace files
     */
    public ArtifactCheckService(Path root, ArtifactInspectorPort inspector, XsltPort xslt,
        Function<GroupId, Optional<NodeId>> firstNode, Function<NodeId, InventoryPort> inventory,
        Function<NodeId, Optional<String>> owners, ResultLimiter limiter, Clock clock) {
        this.root = Objects.requireNonNull(root, "root");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.xslt = Objects.requireNonNull(xslt, "xslt");
        this.firstNode = Objects.requireNonNull(firstNode, "firstNode");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What {@code check_artifacts} checks: structure below {@code paths}, a stylesheet run. */
    public record CheckRequest(List<String> paths, Optional<XsltCheck> xslt,
        Optional<String> schema, boolean verifyOnServer) {

        public CheckRequest {
            paths = List.copyOf(paths);
            xslt = xslt == null ? Optional.empty() : xslt;
            schema = schema == null ? Optional.empty() : schema;
        }
    }

    /** A stylesheet run of {@code check_artifacts}; paths are workspace-relative. */
    public record XsltCheck(String stylesheet, String input, Map<String, String> params,
        Optional<Instant> now) {

        public XsltCheck {
            Objects.requireNonNull(stylesheet, "stylesheet");
            Objects.requireNonNull(input, "input");
            params = params == null ? Map.of() : Map.copyOf(params);
            now = now == null ? Optional.empty() : now;
        }
    }

    /** The report and, if requested, the stylesheet run. */
    public record CheckOutcome(CheckReport report, Optional<XsltRun> xslt) {
    }

    /**
     * Runs {@code request} under the workspace lock (FR-020: a running export or check refuses
     * it at once): the structure checks below its paths and its stylesheet run
     * ({@link XsltPort#run}, output below {@code .tests/}, findings added to the report). Every
     * path — also stylesheet, input and schema — must stay inside the workspace (no
     * {@code ..}, no absolute path, no symbolic link leaving it) and exist. The findings are
     * sorted by severity, each message cut to {@value #MAX_MESSAGE} characters (FR-034), and
     * bounded by
     * {@code resultLimits.maxItems}; when truncated, all findings go to
     * {@code .reports/check-<timestamp>.json} (research D-10). Nothing else is written.
     *
     * @throws ToolErrorException {@code INVALID_INPUT} for a path outside the workspace or a
     *     missing one, {@code PRECONDITION_FAILED} if the workspace is locked or not usable
     */
    public CheckOutcome check(CheckRequest request) {
        if (request.paths().isEmpty() && request.xslt().isEmpty()) {
            throw invalid("Give paths to check, a stylesheet run (xslt), or both",
                "neither paths nor xslt is given");
        }
        if (request.schema().isPresent() && request.paths().isEmpty()) {
            throw invalid("A schema validates the XML files below paths; give paths too",
                "schema is given without paths");
        }
        List<String> paths = request.paths().stream().map(this::confine).toList();
        Optional<Path> schema = request.schema().map(this::confine).map(Path::of);
        Optional<XsltRequest> run = request.xslt().map(xslt -> new XsltRequest(
            Path.of(confine(xslt.stylesheet())), Path.of(confine(xslt.input())), xslt.params(),
            xslt.now()));
        try (WorkspaceLock lock = WorkspaceLock.acquire(root)) {
            List<CheckFinding> findings = new ArrayList<>(checkPaths(paths,
                request.verifyOnServer(), schema));
            Optional<XsltRun> result = run.map(xslt::run);
            result.ifPresent(r -> findings.addAll(r.findings()));
            return new CheckOutcome(report(findings, result.flatMap(XsltRun::output).stream()
                .toList()), result);
        }
    }

    /** The workspace-relative form of {@code path}, refused if it leaves the workspace. */
    private String confine(String path) {
        String candidate = path.replace('\\', '/').strip();
        if (candidate.isEmpty() || candidate.startsWith("/") || candidate.matches("^[A-Za-z]:.*")
            || List.of(candidate.split("/")).contains("..")) {
            throw invalid("The path " + quote(path) + " is not a workspace-relative path",
                "Paths must be relative to the workspace, without .. and not absolute");
        }
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root) || !Files.exists(resolved)) {
            throw invalid("The path " + quote(path) + " does not exist in the workspace "
                + root, "The file or directory is not there (names are case-sensitive)");
        }
        try {
            if (!resolved.toRealPath().startsWith(root.toRealPath())) {
                throw invalid("The path " + quote(path) + " leaves the workspace through a"
                    + " symbolic link", "Only files inside the workspace are checked");
            }
        } catch (IOException e) {
            throw invalid("The path " + quote(path) + " cannot be read", "The file system"
                + " reported " + e.getClass().getSimpleName());
        }
        return relative(resolved);
    }

    private CheckReport report(List<CheckFinding> found, List<String> outputs) {
        List<CheckFinding> findings = found.stream().map(ArtifactCheckService::bounded)
            .sorted(Comparator.comparing(CheckFinding::severity)).toList();
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        findings.forEach(f -> counts.merge(f.severity(), 1, Integer::sum));
        if (findings.size() <= limiter.maxItems()) {
            return new CheckReport(findings, counts, outputs, false, Optional.empty());
        }
        String name = REPORTS + "/check-" + TIMESTAMP.format(clock.instant()) + ".json";
        try {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, json(counts, findings), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The report " + name + " cannot be written (" + e.getClass().getSimpleName()
                    + ")", "The workspace is not writable",
                "Check the workspace directory's permissions"));
        }
        return new CheckReport(findings.subList(0, limiter.maxItems()), counts, outputs, true,
            Optional.of(name));
    }

    /** {@code finding} with its message cut to {@value #MAX_MESSAGE} characters. */
    private static CheckFinding bounded(CheckFinding finding) {
        String message = finding.message();
        if (message.length() <= MAX_MESSAGE) {
            return finding;
        }
        return new CheckFinding(finding.severity(), finding.check(), finding.path(),
            finding.location(), finding.code(), message.substring(0, MAX_MESSAGE - 1) + "…");
    }

    /** The full report: {@code {"counts": {…}, "findings": [{…}, …]}}. */
    private static String json(Map<Severity, Integer> counts, List<CheckFinding> findings) {
        StringBuilder json = new StringBuilder("{\n  \"counts\": {");
        Severity[] severities = Severity.values();
        for (int i = 0; i < severities.length; i++) {
            json.append(i == 0 ? "" : ", ").append(string(severities[i].name())).append(": ")
                .append(counts.getOrDefault(severities[i], 0));
        }
        json.append("},\n  \"findings\": [");
        for (int i = 0; i < findings.size(); i++) {
            CheckFinding f = findings.get(i);
            json.append(i == 0 ? "\n    " : ",\n    ").append("{\"severity\": ")
                .append(string(f.severity().name())).append(", \"check\": ")
                .append(string(f.check().name())).append(", \"path\": ").append(string(f.path()))
                .append(f.location().map(l -> ", \"location\": " + string(l)).orElse(""))
                .append(", \"code\": ").append(string(f.code())).append(", \"message\": ")
                .append(string(f.message())).append('}');
        }
        return json.append("\n  ]\n}\n").toString();
    }

    private static String string(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String quote(String value) {
        String shown = value.length() > 200 ? value.substring(0, 200) + "…" : value;
        return "\"" + shown + "\"";
    }

    private static ToolErrorException invalid(String message, String likelyCause) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            likelyCause, "Give paths relative to the workspace, e.g. <group>/<owner>/workflows"));
    }

    /**
     * The structure findings of every artifact file below {@code paths} (workspace-relative
     * files or directories), in path order. A module of a technical node must exist in the
     * workspace (any owner of the group) or, with {@code verifyOnServer}, in the module list of
     * the artifact's owner or of {@code inventory.owner} on the group's first node (read once per
     * call): otherwise {@code MODULE_MISSING}; a list that cannot be read, or no server lookups,
     * give {@code MODULE_UNVERIFIED} (FR-028).
     */
    public List<CheckFinding> checkPaths(List<String> paths, boolean verifyOnServer) {
        return checkPaths(paths, verifyOnServer, Optional.empty());
    }

    /**
     * As {@link #checkPaths(List, boolean)}; first every XML document below {@code paths}
     * ({@code .xml}, {@code .xsd}, {@code .xsl}, {@code .xslt}, {@code .wsdl}) must be
     * well-formed, and each {@code .xml} file valid against {@code schema} if given
     * ({@link XsltPort#validate}, research D-12); a document that is not well-formed gets no
     * structure checks.
     */
    public List<CheckFinding> checkPaths(List<String> paths, boolean verifyOnServer,
        Optional<Path> schema) {
        List<CheckFinding> findings = new ArrayList<>();
        ModuleLists lists = new ModuleLists(verifyOnServer);
        for (String file : files(paths)) {
            String extension = file.substring(file.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
            if (XML_EXTENSIONS.contains(extension)) {
                List<CheckFinding> xml = xslt.validate(Path.of(file),
                    extension.equals("xml") ? schema : Optional.empty());
                findings.addAll(xml);
                if (xml.stream().anyMatch(f -> f.code().equals("XML_NOT_WELL_FORMED"))) {
                    continue;
                }
            }
            WorkspacePath path;
            try {
                path = WorkspacePath.parse(Path.of(file));
            } catch (IllegalArgumentException e) {
                continue; // not an artifact file
            }
            switch (path.kind()) {
                case WORKFLOW -> workflow(file, path, lists, findings);
                case MODULE, EMBEDDED -> {
                    if (path.kind() == WorkspacePath.Kind.MODULE) {
                        derived(file, findings);
                    }
                    repositoryReferences(file, path, findings);
                }
                case REPOSITORY -> derived(file, findings);
                default -> {
                    // the index entry carries no references
                }
            }
        }
        return findings;
    }

    /** The workspace-relative artifact files below {@code paths}, sorted, once each. */
    private Set<String> files(List<String> paths) {
        Set<String> files = new TreeSet<>();
        for (String path : paths) {
            Path start = root.resolve(path).normalize();
            if (Files.isRegularFile(start)) {
                files.add(relative(start));
                continue;
            }
            try (Stream<Path> walk = Files.walk(start)) {
                walk.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .map(this::relative)
                    .filter(file -> !SKIPPED.contains(file.split("/", 2)[0]))
                    .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private void workflow(String file, WorkspacePath path, ModuleLists lists,
        List<CheckFinding> findings) {
        WorkflowGraph graph = inspector.workflow(root.resolve(file));
        Map<String, Node> byId = new LinkedHashMap<>();
        graph.nodes().forEach(node -> byId.putIfAbsent(node.moduleId(), node));
        ids(file, graph, findings);
        for (Node node : graph.nodes()) {
            for (Edge edge : node.edges()) {
                if (!byId.containsKey(edge.target())) {
                    findings.add(error(file, node.location() + "/Connection",
                        "EDGE_TARGET_MISSING", "edge to node " + edge.target()
                            + ", which does not exist"));
                }
            }
            demux(file, node, byId, findings);
            for (Reference parent : node.parentReferences()) {
                if (!byId.containsKey(parent.value())) {
                    findings.add(error(file, parent.location(), "PARENT_REF_MISSING",
                        "reference to node " + parent.value() + ", which does not exist"));
                }
            }
        }
        modules(file, path, graph, lists, findings);
        Set<String> reported = new LinkedHashSet<>();
        for (Reference reference : graph.variableReferences()) {
            if (!graph.variables().contains(reference.value())
                && !reference.value().startsWith(SYSTEM_VARIABLE_PREFIX)
                && reported.add(reference.value())) {
                findings.add(new CheckFinding(Severity.WARNING, Check.STRUCTURE, file,
                    Optional.of(reference.location()), "VARIABLE_UNRESOLVED",
                    "variable " + reference.value() + " is not declared in this workflow"));
            }
        }
        Set<String> missing = new LinkedHashSet<>();
        for (Reference reference : graph.repositoryReferences()) {
            if (!repositoryFileExists(path, reference.value())
                && missing.add(reference.value())) {
                findings.add(missingRepositoryFile(file, path, Optional.of(reference.location()),
                    reference.value()));
            }
        }
    }

    /**
     * FR-027/FR-028: each module of a technical node exists in the workspace (any owner of the
     * group) or in a server module list of the artifact's owner or of {@code inventory.owner};
     * otherwise {@code MODULE_MISSING}, or {@code MODULE_UNVERIFIED} if a list could not be
     * read or server lookups are off.
     */
    private void modules(String file, WorkspacePath path, WorkflowGraph graph,
        ModuleLists lists, List<CheckFinding> findings) {
        Set<String> checked = new LinkedHashSet<>();
        for (Node node : graph.nodes()) {
            String name = node.moduleName();
            if (!node.moduleType().equals("technical") || name.isEmpty() || !checked.add(name)
                || moduleInWorkspace(path, name)) {
                continue;
            }
            Lookup lookup = lists.find(path, name);
            if (lookup.found()) {
                continue;
            }
            if (lookup.unverified().isPresent()) {
                findings.add(new CheckFinding(Severity.WARNING, Check.STRUCTURE, file,
                    Optional.of(node.location()), "MODULE_UNVERIFIED", "module " + name
                        + " is not in the workspace and could not be verified: "
                        + lookup.unverified().get()));
            } else {
                findings.add(error(file, node.location(), "MODULE_MISSING", "module " + name
                    + " is neither in the workspace nor in the module lists of "
                    + String.join(" and ", lookup.owners()) + " on " + lookup.node()));
            }
        }
    }

    private boolean moduleInWorkspace(WorkspacePath artifact, String module) {
        Path group = root.resolve(artifact.group().value());
        String encoded = NameCodec.encode(module);
        try (Stream<Path> owners = Files.list(group)) {
            for (Path owner : owners.filter(Files::isDirectory).toList()) {
                Path modules = owner.resolve("modules");
                if (!Files.isDirectory(modules)) {
                    continue;
                }
                try (Stream<Path> types = Files.list(modules)) {
                    if (types.anyMatch(type -> Files.isRegularFile(type.resolve(encoded)
                        .resolve("index.xml")))) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** The outcome of a server lookup of one module. */
    private record Lookup(boolean found, Optional<String> unverified, List<String> owners,
        String node) {
    }

    /** The server module lists read during one check, by node and owner. */
    private final class ModuleLists {

        private final boolean verifyOnServer;
        private final Map<List<String>, Set<String>> lists = new HashMap<>();
        private final Map<List<String>, String> failures = new HashMap<>();

        ModuleLists(boolean verifyOnServer) {
            this.verifyOnServer = verifyOnServer;
        }

        Lookup find(WorkspacePath artifact, String module) {
            if (!verifyOnServer) {
                return new Lookup(false, Optional.of("server lookups are off"
                    + " (verifyOnServer: false)"), List.of(), "");
            }
            Optional<NodeId> node = firstNode.apply(artifact.group());
            if (node.isEmpty()) {
                return new Lookup(false, Optional.of("no node is configured for "
                    + artifact.group().value()), List.of(), "");
            }
            List<String> candidates = new ArrayList<>(List.of(artifact.owner()));
            owners.apply(node.get()).filter(owner -> !candidates.contains(owner))
                .ifPresent(candidates::add);
            String failure = null;
            for (String owner : candidates) {
                List<String> key = List.of(node.get().value(), owner);
                if (!lists.containsKey(key) && !failures.containsKey(key)) {
                    try {
                        Set<String> names = new HashSet<>();
                        inventory.apply(node.get()).listModules(owner)
                            .forEach(entry -> names.add(entry.item().name()));
                        lists.put(key, names);
                    } catch (ToolErrorException e) {
                        failures.put(key, "the module list of " + owner + " on " + node.get()
                            + " could not be read (" + e.error().code() + ")");
                    }
                }
                if (lists.getOrDefault(key, Set.of()).contains(module)) {
                    return new Lookup(true, Optional.empty(), candidates, node.get().value());
                }
                if (failures.containsKey(key)) {
                    failure = failures.get(key);
                }
            }
            return new Lookup(false, Optional.ofNullable(failure), candidates,
                node.get().value());
        }
    }

    /** {@code ModuleId} and {@code ConnectionId} share one id space. */
    private static void ids(String file, WorkflowGraph graph, List<CheckFinding> findings) {
        Map<String, List<String>> uses = new LinkedHashMap<>();
        for (Node node : graph.nodes()) {
            uses.computeIfAbsent(node.moduleId(), id -> new ArrayList<>())
                .add(node.location());
            for (Edge edge : node.edges()) {
                edge.connectionId().ifPresent(id -> uses.computeIfAbsent(id,
                    any -> new ArrayList<>()).add(node.location() + "/Connection"));
            }
        }
        uses.forEach((id, locations) -> {
            if (locations.size() > 1) {
                findings.add(error(file, locations.get(1), "ID_COLLISION", "id " + id
                    + " is used " + locations.size() + " times (ModuleId and ConnectionId"
                    + " share one id space): " + String.join(", ", locations)));
            }
        });
    }

    private static void demux(String file, Node node, Map<String, Node> byId,
        List<CheckFinding> findings) {
        Set<List<String>> keys = new LinkedHashSet<>();
        node.properties().forEach((name, value) -> {
            Matcher key = DEMUX_KEY.matcher(name);
            if (key.matches()) {
                keys.add(List.of(key.group(1), key.group(2)));
            }
            Matcher output = DEFAULT_OUTPUT.matcher(value.strip());
            if (name.equals("DefaultOutput") && output.matches()) {
                keys.add(List.of(output.group(1), output.group(2)));
            }
        });
        for (List<String> key : keys) {
            String name = key.get(0);
            String id = key.get(1);
            boolean edge = node.edges().stream().anyMatch(e -> e.target().equals(id));
            Node target = byId.get(id);
            boolean renamed = target != null && edge && !target.moduleName().equals(name)
                && keys.contains(List.of(target.moduleName(), id));
            if (renamed) {
                // real exports: after a rename the Workbench keeps the old key next to the new
                findings.add(new CheckFinding(Severity.WARNING, Check.STRUCTURE, file,
                    Optional.of(node.location() + "/Properties"), "DEMUX_KEY_STALE",
                    "the condition key " + name + "(" + id + ") uses an old name of node " + id
                        + ", which has a condition under its current name; INUBIT ignores it"));
            } else if (target == null && !edge) {
                // live acceptance: the Workbench keeps conditions of deleted nodes
                findings.add(new CheckFinding(Severity.WARNING, Check.STRUCTURE, file,
                    Optional.of(node.location() + "/Properties"), "DEMUX_KEY_STALE",
                    "the condition key " + name + "(" + id + ") is for a node that no longer"
                        + " exists; INUBIT ignores it"));
            } else if (!edge || target == null || !target.moduleName().equals(name)) {
                findings.add(error(file, node.location() + "/Properties",
                    "DEMUX_KEY_UNMATCHED", "the condition key " + name + "(" + id + ") is not"
                        + " an outgoing edge of this node to a node of that name and id"));
            }
        }
    }

    private void repositoryReferences(String file, WorkspacePath path,
        List<CheckFinding> findings) {
        String text;
        try {
            text = Files.readString(root.resolve(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return; // binary documents carry no references
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = REPOSITORY_REFERENCE.matcher(text);
        while (matcher.find()) {
            if (!repositoryFileExists(path, matcher.group(1)) && missing.add(matcher.group(1))) {
                findings.add(missingRepositoryFile(file, path, Optional.empty(),
                    matcher.group(1)));
            }
        }
    }

    /**
     * {@code REPOSITORY_REF_MISSING} (ERROR), or {@code REPOSITORY_REF_UNVERIFIED} (WARNING) for
     * a path in the repository of another owner ({@code Root/<owner>/…}): an export holds only
     * its owner's repository files.
     */
    private static CheckFinding missingRepositoryFile(String file, WorkspacePath artifact,
        Optional<String> location, String reference) {
        String[] segments = java.net.URLDecoder.decode(reference.replace("+", "%2B"),
            StandardCharsets.UTF_8).split("/");
        if (segments.length > 2 && segments[0].equals("Root")
            && !segments[1].equals(artifact.owner())) {
            return new CheckFinding(Severity.WARNING, Check.STRUCTURE, file, location,
                "REPOSITORY_REF_UNVERIFIED", "inubitrepository:/" + reference + " belongs to"
                    + " the repository of another owner, which is not part of this export;"
                    + " export that owner's artifacts to verify it");
        }
        return new CheckFinding(Severity.ERROR, Check.STRUCTURE, file, location,
            "REPOSITORY_REF_MISSING", "inubitrepository:/" + reference
                + " is not in the repository of the workspace");
    }

    /** A repository file of any owner of the group, or withheld as key material. */
    private boolean repositoryFileExists(WorkspacePath artifact, String repositoryPath) {
        Path group = root.resolve(artifact.group().value());
        List<String> segments = List.of(repositoryPath.split("/"));
        if (segments.stream().anyMatch(s -> s.isEmpty() || s.equals("..") || s.equals("."))) {
            return false;
        }
        List<Path> owners;
        try (Stream<Path> list = Files.list(group)) {
            owners = list.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            return false;
        }
        for (Path owner : owners) {
            WorkspacePath path;
            try {
                path = new WorkspacePath(artifact.group(), NameCodec.decode(owner.getFileName()
                    .toString()), WorkspacePath.Kind.REPOSITORY, segments);
            } catch (IllegalArgumentException e) {
                continue; // not an owner directory
            }
            if (Files.isRegularFile(root.resolve(path.toRelativePath()))
                || Files.isRegularFile(root.resolve(path.metaPath()))) {
                return true;
            }
        }
        return false;
    }

    private void derived(String file, List<CheckFinding> findings) {
        List<String> mismatches = inspector.derivedValueMismatches(root, root.resolve(file));
        if (!mismatches.isEmpty()) {
            findings.add(new CheckFinding(Severity.WARNING, Check.STRUCTURE, file,
                Optional.empty(), "DERIVED_VALUE_MISMATCH", String.join(", ", mismatches)
                    + " no longer match the content; a rebuild recomputes them"));
        }
    }

    private static CheckFinding error(String file, String location, String code,
        String message) {
        return new CheckFinding(Severity.ERROR, Check.STRUCTURE, file, Optional.of(location),
            code, message);
    }
}
