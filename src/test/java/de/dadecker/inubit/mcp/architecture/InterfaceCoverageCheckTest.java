package de.dadecker.inubit.mcp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageEntry;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 022 (T008, FR-003, research D-2): checks 1–5 of contracts/coverage-list.md, "Checks".
 * Each check is a function of its inputs; the production tests feed it the real sources
 * ({@code CliCommand.OPTIONS}, the {@code CliSessionCommand} constants, {@code RestEndpoint},
 * the tools of {@link TestWiring}, the input schemas and {@link InterfaceCoverage#production()}),
 * the fixture tests show that each check reports what it should.
 *
 * <p><b>Row keys of check 1</b> (CLI rows are written as the vendor writes them):
 *
 * <ul>
 *   <li>{@code CliCommand.OPTIONS}: one row {@code <command> <option>} per allowed option
 *       ({@code export --exportFile}, {@code trace --download}); a command without options is
 *       its own row ({@code kill}, {@code processErrorStart}).
 *   <li>{@code CliSessionCommand} (INUBIT 6.1): a constant is its own row ({@code uname},
 *       {@code free -m}); a {@code ps} line maps to one row {@code ps <option>} per option, the
 *       row limit dropped ({@code ps -e -l 10} → {@code ps -e}, {@code ps -l};
 *       {@code ps -c -q} → {@code ps -c}, {@code ps -q}); the lines of
 *       {@link #SESSION_WITHOUT_ROW} have no row.
 * </ul>
 */
class InterfaceCoverageCheckTest {

    /**
     * Session lines without a row, with the reason: {@code exit} ends the 6.1 stdin session; it is
     * session control, not an entry of the vendor's command pages.
     */
    private static final Map<String, String> SESSION_WITHOUT_ROW = Map.of("exit",
        "ends the INUBIT 6.1 stdin session (session control, not a documented command entry)");

    /** The inputs that select a tool's action key, in order (research D-2, "Action key"). */
    private static final List<String> ACTION_PROPERTIES = List.of("action", "view", "content");
    private static final String DEFAULT_ACTION = "default";

    private static final Set<String> ALL_STORIES =
        Set.of("US1", "US2", "US3", "US4", "US5", "US6", "US7");

    private static final MajorMinor V8_1 = CoverageEntry.LINES.get(0);
    private static final MajorMinor V9_0 = CoverageEntry.LINES.get(1);
    private static final String HEADER = "channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    static Path temp;

    private static InterfaceCoverage coverage;
    private static Set<String> tools;

    @BeforeAll
    static void readSources() {
        coverage = InterfaceCoverage.production();
        tools = registeredTools();
    }

    // --- the checks on the production sources ------------------------------------------------

    @Test
    void check1EveryCliCommandOptionAndSessionCommandHasARow() throws Exception {
        assertThat(missingRows(coverage, commandKeys(cliCommandOptions())))
            .as("CliCommand.OPTIONS pairs without a row").isEmpty();
        assertThat(missingRows(coverage, sessionKeys(sessionLines())))
            .as("CliSessionCommand lines without a row").isEmpty();
    }

    @Test
    void check2EveryRestEndpointHasARow() {
        assertThat(missingRows(coverage, Arrays.stream(RestEndpoint.values())
            .map(endpoint -> restKey(endpoint.method().name(), endpoint.template())).toList()))
            .as("RestEndpoint values without a row").isEmpty();
    }

    @Test
    void check3EveryNamedToolIsRegisteredAndEveryActionKeyIsOneOfItsInputSchema() {
        SchemaResources schemas = SchemaResources.forClasspath();

        assertThat(toolViolations(coverage.entries(), tools,
            tool -> JSON.readTree(schemas.load(SchemaResources.inputResource(tool)))))
            .as("reachable/covered rows naming no registered tool or an unknown action key")
            .isEmpty();
    }

    /**
     * {@link CoverageEntry} already refuses a row without both states or a planned row without a
     * story ({@code InterfaceCoverageTest}), so no failing fixture can be built for this check;
     * it guards the loaded list should that record change.
     */
    @Test
    void check4EveryRowHasBothVersionStatesAndEveryPlannedRowAStory() {
        assertThat(stateViolations(coverage.entries())).isEmpty();
    }

    @Test
    void check5WaitsOnlyOnPlannedRowsOfDeliveredStories() {
        assertThat(waitsViolations(coverage.entries(), coverage.deliveredStories())).isEmpty();
    }

    /** The production key sets are not empty by mistake; the special rows of check 1 are there. */
    @Test
    void theProductionKeysContainTheRowsOfTheKeyMapping() throws Exception {
        assertThat(commandKeys(cliCommandOptions())).contains(cli("export --exportFile"),
            cli("trace --download"), cli("kill"), cli("processErrorStart"));
        assertThat(sessionKeys(sessionLines())).contains(cli("uname"), cli("free -m"),
            cli("ps -a"), cli("ps -l"), cli("ps -c")).doesNotContain(cli("exit"));
        assertThat(tools).contains("get_health", "export_archives", "download_server_log",
            "tag_artifacts", "deploy_release", "kill_process");
    }

    // --- the checks on fixtures ---------------------------------------------------------------

    /** A stand-in for {@link RestEndpoint}: one listed value and one the list does not have. */
    private enum FixtureEndpoint {
        MODELS("GET", "/model/models"),
        UNLISTED("GET", "/model/unlisted/{name}");

        private final String method;
        private final String template;

        FixtureEndpoint(String method, String template) {
            this.method = method;
            this.template = template;
        }
    }

    @Test
    void check2ReportsARestEndpointWithoutARow() {
        InterfaceCoverage fixture = fixtureCoverage("",
            "REST\tGET /model/models\tREAD\tE\t-\treachable:list_inventory/default\t"
                + "reachable:list_inventory/default\t-");

        assertThat(missingRows(fixture, Arrays.stream(FixtureEndpoint.values())
            .map(endpoint -> restKey(endpoint.method, endpoint.template)).toList()))
            .containsExactly("REST GET /model/unlisted/{name}");
    }

    @Test
    void check1MapsCommandsOptionsAndSessionLinesToRows() {
        assertThat(commandKeys(Map.of("kill", Set.of(), "trace", Set.of("--download"))))
            .containsExactlyInAnyOrder(cli("kill"), cli("trace --download"));
        assertThat(sessionKeys(List.of("ps -e -l 10", "ps -c -q", "free -m", "exit")))
            .containsExactly(cli("ps -e"), cli("ps -l"), cli("ps -c"), cli("ps -q"),
                cli("free -m"));
    }

    @Test
    void check1ReportsACommandOptionOrSessionLineWithoutARow() {
        InterfaceCoverage fixture = fixtureCoverage("",
            cliRow("kill"), cliRow("export --exportFile"), cliRow("uname"), cliRow("ps -e"),
            cliRow("ps -l"), cliRow("ps -c"));

        assertThat(missingRows(fixture, commandKeys(Map.of("kill", Set.of(),
            "export", Set.of("--exportFile", "--unlisted")))))
            .containsExactly("CLI export --unlisted");
        assertThat(missingRows(fixture,
            sessionKeys(List.of("uname", "ps -e -l 10", "ps -c -q", "exit", "unlisted"))))
            .containsExactly("CLI ps -q", "CLI unlisted");
    }

    @Test
    void check3TakesTheActionKeysFromTheFirstEnumOfActionViewOrContent() {
        assertThat(actionKeys(json("""
            {"properties": {"action": {"enum": ["a1", "a2"]}, "view": {"enum": ["v"]}}}""")))
            .containsExactly("a1", "a2");
        assertThat(actionKeys(json("""
            {"properties": {"view": {"enum": ["logins"]}, "content": {"enum": ["c"]}}}""")))
            .containsExactly("logins");
        assertThat(actionKeys(json("""
            {"properties": {"content": {"enum": ["workflows"]}}}"""))).containsExactly("workflows");
        assertThat(actionKeys(json("""
            {"properties": {"content": {"type": "string"}}}"""))).as("free text, no enum")
            .containsExactly(DEFAULT_ACTION);
        assertThat(actionKeys(json("{\"properties\": {}}"))).containsExactly(DEFAULT_ACTION);
    }

    @Test
    void check3ReportsAnUnregisteredToolAndAnUnknownActionKey() {
        InterfaceCoverage fixture = fixtureCoverage("",
            restRow("GET /a", "reachable:list_inventory/default"),
            restRow("GET /b", "reachable:read_users/logins"),
            restRow("GET /c", "covered:list_inventory"),
            restRow("GET /d", "reachable:read_users/default"),
            restRow("GET /e", "reachable:list_inventory/list"),
            restRow("GET /f", "reachable:missing_tool/default"),
            restRow("GET /g", "covered:missing_tool"));
        Map<String, JsonNode> schemas = Map.of(
            "list_inventory", json("{\"properties\": {\"name\": {\"type\": \"string\"}}}"),
            "read_users", json("{\"properties\": {\"view\": {\"enum\": [\"logins\"]}}}"));

        assertThat(toolViolations(fixture.entries(), Set.of("list_inventory", "read_users"),
            schemas::get)).containsExactly(
                "REST GET /d (8.1): action default is not one of [logins]",
                "REST GET /e (8.1): action list is not one of [default]",
                "REST GET /f (8.1): reachable by the unregistered tool missing_tool",
                "REST GET /g (8.1): covered by the unregistered tool missing_tool");
    }

    @Test
    void check5ReportsMissingAndMisplacedWaits() {
        List<CoverageEntry> entries = List.of(
            planned("GET /a", "US1", List.of()),
            planned("GET /b", "US1", List.of("L-1")),
            planned("GET /c", "US2", List.of("L-2")),
            planned("GET /d", "US2", List.of()));

        assertThat(waitsViolations(entries, Set.of("US1"))).containsExactly(
            "REST GET /a: planned row of the delivered story US1 without waits",
            "REST GET /c: waits L-2 on a row that is not a planned row of a delivered story");
        assertThat(waitsViolations(entries, Set.of())).containsExactly(
            "REST GET /b: waits L-1 on a row that is not a planned row of a delivered story",
            "REST GET /c: waits L-2 on a row that is not a planned row of a delivered story");
    }

    @Test
    void check5ReportsAPlannedRowOnceEveryStoryIsDelivered() {
        assertThat(waitsViolations(List.of(planned("GET /b", "US1", List.of("L-1"))),
            ALL_STORIES)).containsExactly(
                "REST GET /b: still planned although every story is delivered (SC-001)");
    }

    // --- the checks ---------------------------------------------------------------------------

    /** Check 1 and 2: the keys of {@code keys} the list has no row for, as text. */
    static List<String> missingRows(InterfaceCoverage list, Collection<CoverageKey> keys) {
        Set<CoverageKey> listed = list.entries().stream().map(CoverageEntry::key)
            .collect(Collectors.toSet());
        return keys.stream().filter(key -> !listed.contains(key))
            .map(key -> key.channel() + " " + key.entry()).toList();
    }

    /**
     * Check 1, {@code CliCommand.OPTIONS}: {@code <command> <option>} per option, or
     * {@code <command>} for a command without options.
     */
    static Set<CoverageKey> commandKeys(Map<String, Set<String>> options) {
        Set<CoverageKey> keys = new LinkedHashSet<>();
        options.forEach((command, commandOptions) -> {
            if (commandOptions.isEmpty()) {
                keys.add(cli(command));
            }
            commandOptions.forEach(option -> keys.add(cli(command + " " + option)));
        });
        return keys;
    }

    /**
     * Check 1, {@code CliSessionCommand}: a {@code ps} line gives {@code ps <option>} per option
     * (the row limit is no option), every other line itself, a line of
     * {@link #SESSION_WITHOUT_ROW} nothing.
     */
    static Set<CoverageKey> sessionKeys(Collection<String> lines) {
        Set<CoverageKey> keys = new LinkedHashSet<>();
        for (String line : lines) {
            if (SESSION_WITHOUT_ROW.containsKey(line)) {
                continue;
            }
            if (line.startsWith("ps ")) {
                Arrays.stream(line.split(" ")).filter(token -> token.startsWith("-"))
                    .forEach(option -> keys.add(cli("ps " + option)));
            } else {
                keys.add(cli(line));
            }
        }
        return keys;
    }

    /** Check 2: {@code <method> <template>}, e.g. {@code GET /model/modelByName/{name}}. */
    static CoverageKey restKey(String method, String template) {
        return new CoverageKey(Channel.REST, method + " " + template);
    }

    /**
     * Check 3: every {@code reachable:} and {@code covered:} tool is in {@code registered}, and
     * every {@code reachable:} action is one of the tool's {@link #actionKeys}.
     */
    static List<String> toolViolations(List<CoverageEntry> entries, Set<String> registered,
        Function<String, JsonNode> inputSchema) {
        List<String> violations = new ArrayList<>();
        for (CoverageEntry entry : entries) {
            for (MajorMinor line : CoverageEntry.LINES) {
                String where = entry.channel() + " " + entry.entry() + " (" + line.text() + "): ";
                CoverageState state = entry.states().get(line);
                if (state instanceof CoverageState.Covered covered
                    && !registered.contains(covered.tool())) {
                    violations.add(where + "covered by the unregistered tool " + covered.tool());
                } else if (state instanceof CoverageState.Reachable reachable) {
                    if (!registered.contains(reachable.tool())) {
                        violations.add(where + "reachable by the unregistered tool "
                            + reachable.tool());
                        continue;
                    }
                    Set<String> keys = actionKeys(inputSchema.apply(reachable.tool()));
                    if (!keys.contains(reachable.action())) {
                        violations.add(where + "action " + reachable.action()
                            + " is not one of " + keys);
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Check 3, the action-key rule (research D-2): the {@code enum} of {@code action},
     * {@code view} or {@code content}, the first the input schema declares one for; otherwise
     * {@code default} (a free-text {@code content}, as in {@code record_knowledge}, selects no
     * action).
     */
    static Set<String> actionKeys(JsonNode inputSchema) {
        for (String property : ACTION_PROPERTIES) {
            JsonNode values = inputSchema.path("properties").path(property).path("enum");
            if (values.isArray()) {
                Set<String> keys = new LinkedHashSet<>();
                values.forEach(value -> keys.add(value.asString()));
                return keys;
            }
        }
        return Set.of(DEFAULT_ACTION);
    }

    /** Check 4: every row has a state for 8.1 and 9.0, every {@code planned} row a story. */
    static List<String> stateViolations(List<CoverageEntry> entries) {
        List<String> violations = new ArrayList<>();
        for (CoverageEntry entry : entries) {
            String where = entry.channel() + " " + entry.entry() + ": ";
            if (!entry.states().keySet().containsAll(CoverageEntry.LINES)) {
                violations.add(where + "states only for " + entry.states().keySet());
            }
            if (entry.planned() && entry.story().isEmpty()) {
                violations.add(where + "planned without a story");
            }
        }
        return violations;
    }

    /**
     * Check 5: a {@code planned} row of a delivered story carries {@code waits}; no other row
     * does; once every story is delivered, no row is {@code planned} (SC-001).
     */
    static List<String> waitsViolations(List<CoverageEntry> entries, Set<String> delivered) {
        boolean allDelivered = delivered.containsAll(ALL_STORIES);
        List<String> violations = new ArrayList<>();
        for (CoverageEntry entry : entries) {
            String where = entry.channel() + " " + entry.entry() + ": ";
            boolean plannedOfDelivered = entry.planned()
                && entry.story().filter(delivered::contains).isPresent();
            if (allDelivered && entry.planned()) {
                violations.add(where + "still planned although every story is delivered (SC-001)");
            } else if (plannedOfDelivered && entry.waits().isEmpty()) {
                violations.add(where + "planned row of the delivered story "
                    + entry.story().orElseThrow() + " without waits");
            } else if (!plannedOfDelivered && !entry.waits().isEmpty()) {
                violations.add(where + "waits " + String.join("+", entry.waits())
                    + " on a row that is not a planned row of a delivered story");
            }
        }
        return violations;
    }

    // --- the production sources ---------------------------------------------------------------

    /** {@code CliCommand.OPTIONS}: the allowlist is private to the adapter, so it is read here. */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> cliCommandOptions()
        throws ReflectiveOperationException {
        Field field = CliCommand.class.getDeclaredField("OPTIONS");
        field.setAccessible(true);
        return (Map<String, Set<String>>) field.get(null);
    }

    /**
     * Every line {@code CliSessionCommand} builds: its public constants and, per
     * {@code Listing}, the listing and the count.
     */
    private static List<String> sessionLines() throws IllegalAccessException {
        List<String> lines = new ArrayList<>();
        for (Field field : CliSessionCommand.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                && field.getType() == CliSessionCommand.class) {
                lines.add(((CliSessionCommand) field.get(null)).text());
            }
        }
        for (CliSessionCommand.Listing listing : CliSessionCommand.Listing.values()) {
            lines.add(CliSessionCommand.psList(listing, 1).text());
            lines.add(CliSessionCommand.psCount(listing).text());
        }
        return lines;
    }

    /**
     * The tools {@link TestWiring} registers for a profile that offers every tool: knowledge
     * recording, a development group with write access, end-to-end tests, a StartCLI client and
     * the queue entry read, and a production group that receives deployments.
     */
    private static Set<String> registeredTools() {
        String yaml = """
            profile:
              name: acme
            auditDirectory: %1$s/audit
            workspace: %1$s/workspace
            knowledge:
              directory: %1$s/knowledge
              recording: true
            groups:
              - name: dev
                versionLine: V8_1
                development:
                  enabled: true
                e2eTests: CONFIRM
                e2e:
                  soap:
                    baseUrl: https://localhost:1
                unsupported:
                  queueEntryRead: true
                cli:
                  home: %1$s/client
                write:
                  enabled: true
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
              - name: prod
                production: true
                deploy:
                  from: dev
                write:
                  enabled: true
                  productionOptIn: true
                nodes:
                  - name: inubit01
                    baseUrl: https://localhost:2
            """.formatted(temp);
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        try (TestWiring wiring = TestWiring.of(config, TestSecrets.store(Map.of(
                "INUBIT_ACME_DEV_USERNAME", "jdoe", "INUBIT_ACME_DEV_PASSWORD", "coverage-pw")),
            new SecretScrubber(), path -> true, false)) {
            return wiring.toolHandlers().stream().map(ToolHandler::name)
                .collect(Collectors.toSet());
        }
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static InterfaceCoverage fixtureCoverage(String deliveredStories, String... rows) {
        return InterfaceCoverage.load(new StringReader("# delivered-stories: " + deliveredStories
            + "\n" + HEADER + "\n" + String.join("\n", rows) + "\n"));
    }

    /** A CLI row, reachable by {@code get_health} on both lines. */
    private static String cliRow(String entry) {
        return "CLI\t" + entry + "\tREAD\tA\t-\treachable:get_health/default\t"
            + "reachable:get_health/default\t-";
    }

    /** A REST row with {@code state8_1} on 8.1 and {@code planned} for US3 on 9.0. */
    private static String restRow(String entry, String state8_1) {
        return "REST\t" + entry + "\tREAD\tA\tUS3\t" + state8_1 + "\tplanned\t-";
    }

    private static CoverageEntry planned(String entry, String story, List<String> waits) {
        return new CoverageEntry(Channel.REST, entry, CoverageEntry.Kind.READ,
            CoverageEntry.Area.A, Optional.of(story),
            Map.of(V8_1, new CoverageState.Planned(), V9_0, new CoverageState.Planned()), waits);
    }

    private static CoverageKey cli(String entry) {
        return new CoverageKey(Channel.CLI, entry);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
