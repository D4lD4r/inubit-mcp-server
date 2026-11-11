package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.CloudXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.DiagramXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.EntryXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.FieldXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.Leaf;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.PartnerXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures.TwfXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.application.PartnerService.Answer;
import de.dadecker.inubit.mcp.application.PartnerService.Compared;
import de.dadecker.inubit.mcp.application.PartnerService.EntryView;
import de.dadecker.inubit.mcp.application.PartnerService.Found;
import de.dadecker.inubit.mcp.application.PartnerService.PartnerView;
import de.dadecker.inubit.mcp.application.PartnerService.ServerView;
import de.dadecker.inubit.mcp.application.PartnerService.SettingView;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort.UnreadableDiagram;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T024: paging and size bounds of {@link PartnerService#answer} (FR-017, SC-004): texts cut with
 * the marker, the result within {@code resultLimits.maxChars} (the tool returns it as it is), detail
 * cut before the partners of the page, {@code truncated}, the audit counting what is returned;
 * every result checked as the JSON text of {@link ResultJson} against every synthetic secret.
 */
@Timeout(120)
class PartnerServiceBoundsTest {

    private static final NodeId NODE1 = NodeId.parse("dev/node1");
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final String OWNER = PartnerFixtures.OWNER;
    private static final String GROUP = PartnerFixtures.GROUP;
    private static final String MARKER = ItemBounds.TRUNCATION_MARKER;
    private static final List<FieldXml> ID_AND_NAME =
        List.of(new FieldXml("Id", "String"), new FieldXml("Name", "String"));
    private static final List<TwfXml> WORKFLOW = List.of(new TwfXml("1", "34", "Workflow-0001"));

    private final Map<DiagramRef, PartnerManagementReader.Result> exports = new LinkedHashMap<>();
    /** System diagrams of the owner whose export fails, listed after {@link #exports}. */
    private final List<DiagramRef> unreadable = new ArrayList<>();
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    /** The servers of stage {@code dev}; only {@code dev/node1} has an owner. */
    private List<NodeId> nodes = List.of(NODE1);
    /** When set, the partner port fails with it. */
    private ToolError portFailure;

    // --- SC-004 --------------------------------------------------------------------------------

    @Test
    void everyPageOfAThousandPartnersStaysWithinTheBoundAndThePagesCoverAll() {
        export(PartnerFixtures.list(1000));
        List<String> all = IntStream.rangeClosed(1, 1000)
            .mapToObj("ACME-%04d"::formatted).toList();

        for (int maxChars : List.of(10_000, 50_000)) {
            for (Optional<String> search : List.of(Optional.of("acme"), Optional.<String>empty())) {
                ResultLimiter limiter = new ResultLimiter(100, maxChars);
                List<String> seen = new ArrayList<>();
                boolean anyTruncated = false;
                OptionalInt offset = OptionalInt.of(0);
                while (offset.isPresent()) {
                    Found page = find(query(search, offset.getAsInt(), 100), limiter);
                    String what = maxChars + " " + search + " offset " + offset.getAsInt();
                    assertWithin(page, maxChars, what);
                    assertThat(page.total()).isEqualTo(1000);
                    assertThat(page.partners()).as(what).isNotEmpty();
                    if (page.partners().size() < Math.min(100, 1000 - offset.getAsInt())) {
                        assertThat(page.truncated()).as(what).isTrue();
                        assertThat(page.nextOffset()).as(what)
                            .hasValue(offset.getAsInt() + page.partners().size());
                    }
                    anyTruncated |= page.truncated();
                    page.partners().forEach(partner -> seen.add(id(partner)));
                    offset = page.nextOffset();
                }
                assertThat(seen).as(maxChars + " " + search).isEqualTo(all);
                // 100 partners in full exceed both bounds, 100 summaries only the smaller one
                assertThat(anyTruncated).as(maxChars + " " + search)
                    .isEqualTo(search.isPresent() || maxChars == 10_000);
            }
        }
    }

    // --- texts ---------------------------------------------------------------------------------

    @Test
    void textsAreCutWithTheMarkerAndTheSearchStillMatchesTheFullValue() {
        String name = "ACME Long " + "n".repeat(280) + " TAIL-0001";
        String server = "ftp-" + "h".repeat(300) + ".example.test";
        export(new DiagramXml(GROUP, "SystemDiagram-01", OWNER, List.of(new CloudXml("33",
            "Module-0901", ID_AND_NAME, WORKFLOW, List.of(new PartnerXml(
                List.of(Leaf.plain("Id", "ACME-0001"), Leaf.plain("Name", name)),
                List.of(new EntryXml("FTP", "1", List.of(Leaf.plain("ServerName", server),
                    Leaf.encrypted("Password", PartnerFixtures.AES_PASSWORD)),
                    List.of()))))))));

        Found found = find(query(Optional.of("tail-0001"), 0, 20), ResultLimiter.withDefaults());

        PartnerView partner = found.partners().get(0);
        assertThat(field(partner, "Name")).hasSize(ItemBounds.MAX_FIELD_CHARS)
            .startsWith("ACME Long nnn").endsWith(MARKER);
        EntryView entry = partner.entries().get(0);
        assertThat(entry.protocolSettings().get(0).value()).hasValueSatisfying(value ->
            assertThat(value).hasSize(ItemBounds.MAX_FIELD_CHARS).endsWith(MARKER));
        assertThat(entry.protocolSettings().get(1)).isEqualTo(new SettingView("Password",
            Optional.empty(), Optional.of(true), Optional.of(true)));
        assertThat(found.truncated()).isTrue();
        assertNoSecretIn(found);
    }

    @Test
    void aLongSimilarValueIsCutWithTheMarkerAndTruncated() {
        // two names that differ only after the cut: one suggestion
        export(new DiagramXml(GROUP, "SystemDiagram-01", OWNER, List.of(new CloudXml("33",
            "Module-0901", ID_AND_NAME, WORKFLOW, Stream.of("a", "b")
                .map(tail -> new PartnerXml(List.of(Leaf.plain("Id", "ACME-000" + tail),
                    Leaf.plain("Name", "n".repeat(260) + tail)), List.of()))
                .toList()))));

        // not contained in the name, but within the edit distance of a similar value
        Found found = find(query(Optional.of("n".repeat(199) + "x"), 0, 20),
            ResultLimiter.withDefaults());

        assertThat(found.partners()).isEmpty();
        assertThat(found.similarValues()).hasValueSatisfying(values ->
            assertThat(values).singleElement().satisfies(value -> assertThat(value)
                .hasSize(ItemBounds.MAX_FIELD_CHARS).startsWith("nnn").endsWith(MARKER)));
        assertThat(found.truncated()).isTrue();
    }

    @Test
    void nothingCutIsNotTruncated() {
        export(PartnerFixtures.detail());

        Found found = find(query(Optional.of("acme"), 0, 20), ResultLimiter.withDefaults());

        assertThat(found.partners()).hasSize(3);
        assertThat(found.truncated()).isFalse();
        assertThat(found.nextOffset()).isEmpty();
    }

    // --- cut order -----------------------------------------------------------------------------

    @Test
    void detailIsCutBeforeThePartnersOfThePageFromTheLastPartner() {
        export(PartnerFixtures.list(40));
        PartnerQuery query = query(Optional.of("acme"), 0, 20);
        int full = ResultJson.size(find(query, ResultLimiter.withDefaults()));

        for (int budget = ItemBounds.MAX_ITEM_CHARS; budget <= full + 250; budget += 250) {
            Found found = find(query, new ResultLimiter(100, budget));
            String what = "budget " + budget;
            assertWithin(found, budget, what);
            List<Integer> levels = found.partners().stream()
                .map(PartnerServiceBoundsTest::level).toList();
            assertThat(levels).as(what + ": cut from the last partner").isSorted();
            if (levels.contains(2)) {
                assertThat(levels).as(what + ": parameters of all first").doesNotContain(0);
            }
            if (found.partners().size() < 20) {
                assertThat(levels).as(what + ": detail cut before partners").containsOnly(2);
                assertThat(found.nextOffset()).hasValue(found.partners().size());
            }
            assertThat(found.partners()).as(what + ": no entry cut while partners can go")
                .allSatisfy(partner -> assertThat(partner.entries()).hasSize(1));
            assertThat(found.truncated()).as(what).isEqualTo(budget < full);
            assertNoSecretIn(found);
        }
    }

    @Test
    void aSinglePartnerThatDoesNotFitLosesItsLastEntries() {
        List<EntryXml> entries = IntStream.rangeClosed(1, 80)
            .mapToObj(n -> new EntryXml("FTP", "1", List.of(
                Leaf.plain("ServerName", "host" + n + ".example.test"),
                Leaf.plain("Login", "login-" + n),
                Leaf.encrypted("Password", PartnerFixtures.AES_PASSWORD)), List.of()))
            .toList();
        export(new DiagramXml(GROUP, "SystemDiagram-01", OWNER, List.of(new CloudXml("33",
            "Module-0901", ID_AND_NAME, WORKFLOW, List.of(new PartnerXml(
                List.of(Leaf.plain("Id", "ACME-0001"), Leaf.plain("Name", "ACME Corp")),
                entries))))));

        Found found = find(query(Optional.empty(), 0, 20),
            new ResultLimiter(100, ItemBounds.MAX_ITEM_CHARS));

        assertWithin(found, ItemBounds.MAX_ITEM_CHARS, "one large partner");
        PartnerView partner = found.partners().get(0);
        assertThat(partner.entries()).isNotEmpty().hasSizeLessThan(80);
        assertThat(partner.entries()).extracting(entry -> entry.protocolSettings().get(0)
                .value().orElseThrow())
            .as("the first entries are kept")
            .containsExactlyElementsOf(IntStream.rangeClosed(1, partner.entries().size())
                .mapToObj(n -> "host" + n + ".example.test").toList());
        assertThat(partner.maskedCount()).as("the partner's count").isEqualTo(80);
        assertThat(found.truncated()).isTrue();
    }

    // --- page size, warnings, audit -----------------------------------------------------------

    @Test
    void aPageCutByMaxItemsIsTruncatedAndContinues() {
        export(PartnerFixtures.list(20));

        Found found = find(query(Optional.empty(), 0, 20), new ResultLimiter(5, 50_000));

        assertThat(found.partners()).extracting(PartnerServiceBoundsTest::id)
            .containsExactly("ACME-0001", "ACME-0002", "ACME-0003", "ACME-0004", "ACME-0005");
        assertThat(found.nextOffset()).hasValue(5);
        assertThat(found.limit()).as("the page size applied").isEqualTo(5);
        assertThat(found.truncated()).isTrue();
    }

    @Test
    void hundredsOfSystemDiagramsAreCutToFitWithThoseOfThePageFirst() {
        // even: no Partner Management; odd: an empty cloud; the last one holds the partner
        for (int n = 0; n < 300; n++) {
            List<PartnerXml> partners = n == 299
                ? List.of(new PartnerXml(List.of(Leaf.plain("Id", "ACME-0001"),
                    Leaf.plain("Name", "ACME Corp")), List.of()))
                : List.of();
            export(new DiagramXml(GROUP, "SystemDiagram-%03d".formatted(n), OWNER, n % 2 == 0
                ? List.of() : List.of(new CloudXml("33", "Module-0901", ID_AND_NAME, WORKFLOW,
                    partners))));
        }
        IntStream.rangeClosed(1, 3).forEach(n ->
            unreadable.add(new DiagramRef("SystemDiagram-U%02d".formatted(n), GROUP)));
        ResultLimiter limiter = new ResultLimiter(100, 10_000);

        Found hit = find(query(Optional.of("acme-0001"), 0, 20), limiter);
        Found none = find(query(Optional.of("no-such-partner"), 0, 20), limiter);
        Found listed = find(query(Optional.empty(), 0, 20), limiter);

        for (Found found : List.of(hit, none, listed)) {
            assertWithin(found, 10_000, "300 system diagrams");
            assertThat(found.truncated()).isTrue();
            assertThat(found.servers().get(0).systemDiagramsRead()).isNotEmpty()
                .hasSizeLessThan(300);
            assertThat(found.servers().get(0).unreadableDiagrams())
                .as("the failure details are fitted first").hasSize(3);
        }
        assertThat(hit.partners()).extracting(PartnerServiceBoundsTest::id)
            .containsExactly("ACME-0001");
        assertThat(hit.servers().get(0).systemDiagramsRead())
            .extracting(PartnerService.DiagramRead::name)
            .as("the system diagram of the partner on the page is kept")
            .contains("SystemDiagram-299")
            .startsWith("SystemDiagram-000").as("the others in list order").isSorted();
        assertThat(none.partners()).isEmpty();
        assertThat(none.similarValues()).isPresent();
    }

    @Test
    void everyPartnerReturnedHasItsSystemDiagramListedAlsoWhenTheListIsCut() {
        // one partner per system diagram, listed in reverse name order: the list order of the
        // system diagrams is the reverse of the page order in list mode
        for (int n = 60; n >= 1; n--) {
            int number = n;
            List<CloudXml> clouds = IntStream.rangeClosed(1, 4)
                .mapToObj(c -> new CloudXml(String.valueOf(30 + c), "Module-090" + c,
                    ID_AND_NAME, List.of(), c > 1 ? List.of() : List.of(new PartnerXml(
                        List.of(Leaf.plain("Id", "ACME-%04d".formatted(number)),
                            Leaf.plain("Name", "ACME Partner " + number)), List.of()))))
                .toList();
            export(new DiagramXml(GROUP, "SystemDiagram-%03d-".formatted(n) + "d".repeat(120),
                OWNER, clouds));
        }
        ResultLimiter limiter = new ResultLimiter(100, 10_000);

        for (Optional<String> search : List.of(Optional.of("acme"), Optional.<String>empty())) {
            List<String> seen = new ArrayList<>();
            OptionalInt offset = OptionalInt.of(0);
            while (offset.isPresent()) {
                Found found = find(query(search, offset.getAsInt(), 100), limiter);
                String what = search + " offset " + offset.getAsInt();
                assertWithin(found, 10_000, what);
                List<String> listed = found.servers().get(0).systemDiagramsRead().stream()
                    .map(PartnerService.DiagramRead::name).toList();
                assertThat(listed).as(what + ": cut").hasSizeLessThan(60);
                assertThat(found.partners()).as(what).isNotEmpty().allSatisfy(partner ->
                    assertThat(listed).as(what).contains(partner.systemDiagram().name()));
                if (offset.getAsInt() == 0) {
                    assertThat(found.partners()).as(what).hasSizeGreaterThan(1);
                    assertThat(found.truncated()).as(what).isTrue();
                }
                found.partners().forEach(partner -> seen.add(id(partner)));
                offset = found.nextOffset();
            }
            assertThat(seen).as(search + ": every partner on some page")
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 60)
                    .mapToObj("ACME-%04d"::formatted).toList());
        }
    }

    @Test
    void theReferencableWorkflowsOfACloudAreCutToTheirShare() {
        List<TwfXml> workflows = IntStream.rangeClosed(1, 500)
            .mapToObj(n -> new TwfXml(String.valueOf(n), String.valueOf(1000 + n),
                "Workflow-%04d".formatted(n)))
            .toList();
        export(new DiagramXml(GROUP, "SystemDiagram-01", OWNER, List.of(new CloudXml("33",
            "Module-0901", ID_AND_NAME, workflows, List.of(new PartnerXml(
                List.of(Leaf.plain("Id", "ACME-0001"), Leaf.plain("Name", "ACME Corp")),
                List.of(new EntryXml("FTP", "1", List.of(), List.of()))))))));

        Found found = find(query(Optional.of("acme"), 0, 20), new ResultLimiter(100, 10_000));

        assertWithin(found, 10_000, "500 workflows");
        assertThat(found.partners()).hasSize(1);
        List<String> kept = found.referencableWorkflows().get(0).workflows();
        assertThat(kept).isNotEmpty().hasSizeLessThan(500).startsWith("Workflow-0001");
        assertThat(ResultJson.size(kept))
            .isLessThanOrEqualTo((int) (10_000 * PartnerBounds.WORKFLOW_SHARE));
        assertThat(found.truncated()).isTrue();
    }

    @Test
    void theVersionGatesWarningsAreAlwaysKept() {
        manyUnresolvedEntries();
        NodeVersions versions = new NodeVersions(Clock.systemUTC(), node -> VersionSetting.V9_0,
            node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions), CapabilityMatrix.production());
        String gateWarning = find(query(Optional.of("acme-0001"), 0, 20),
            ResultLimiter.withDefaults(), gate).servers().get(0).warnings().get(0);

        Found found = find(query(Optional.empty(), 0, 20),
            new ResultLimiter(100, ItemBounds.MAX_ITEM_CHARS), gate);

        assertThat(gateWarning).contains("9.0");
        List<String> kept = found.servers().get(0).warnings();
        assertThat(kept).hasSizeGreaterThan(1).hasSizeLessThan(301);
        assertThat(kept.get(0)).isEqualTo(gateWarning);
        assertWithin(found, ItemBounds.MAX_ITEM_CHARS, "300 warnings");
        assertThat(found.truncated()).isTrue();
    }

    @Test
    void aFailedServerKeepsTheVersionGatesWarnings() {
        manyUnresolvedEntries();
        NodeVersions versions = new NodeVersions(Clock.systemUTC(), node -> VersionSetting.V9_0,
            node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions), CapabilityMatrix.production());
        portFailure = ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, "The export is not a ZIP archive",
            "cause", "next step").withNode(NODE1);

        ServerView server = find(query(Optional.empty(), 0, 20), ResultLimiter.withDefaults(), gate)
            .servers().get(0);

        assertThat(server.error()).hasValueSatisfying(error ->
            assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
        assertThat(server.warnings()).singleElement().satisfies(warning ->
            assertThat(warning).contains("dev/node1", "9.0", "not verified for partner management"));
    }

    @Test
    void aResultOverItsBoundOnlyThroughPartsNeverCutIsStillReportedTruncated() {
        // 30 servers without owner: their NOT_CONFIGURED errors are never cut
        nodes = IntStream.rangeClosed(1, 30).mapToObj(n -> NodeId.parse("dev/node" + n))
            .toList();
        export(PartnerFixtures.detail());
        ResultLimiter limiter = new ResultLimiter(100, ItemBounds.MAX_ITEM_CHARS);

        // no match: nothing on the page to cut, only the guard can report it
        Compared found = (Compared) answer(new PartnerQuery("dev", Optional.empty(),
            Optional.empty(), Optional.of("no-such-partner"), Optional.empty(), Optional.empty(),
            Optional.empty(), 0, 20, false, Optional.empty()), limiter,
            FakeVersionDetection.pinned81());

        assertThat(ResultJson.size(found)).as("the remaining case: errors alone exceed it")
            .isGreaterThan(ItemBounds.MAX_ITEM_CHARS);
        assertThat(found.partners()).isEmpty();
        assertThat(found.servers()).hasSize(30);
        assertThat(found.truncated()).isTrue();
    }

    @Test
    void manyWarningsAreCutToTheirShareOfTheBudget() {
        manyUnresolvedEntries();
        int warnings = exports.values().iterator().next().warnings().size();

        Found found = find(query(Optional.empty(), 0, 20), new ResultLimiter(100, 10_000));

        assertWithin(found, 10_000, "300 warnings");
        List<String> kept = found.servers().get(0).warnings();
        assertThat(warnings).isGreaterThanOrEqualTo(300);
        assertThat(kept).isNotEmpty().hasSizeLessThan(warnings);
        assertThat(ResultJson.size(kept))
            .isLessThanOrEqualTo((int) (10_000 * PartnerBounds.WARNING_SHARE));
        assertThat(found.partners()).isNotEmpty();
        assertThat(found.truncated()).isTrue();
    }

    @Test
    void theAuditCountsWhatTheBoundedResultHolds() {
        export(PartnerFixtures.list(40));

        Found found = find(query(Optional.of("acme"), 0, 20), new ResultLimiter(100, 6_000));

        int masked = (int) found.partners().stream()
            .flatMap(partner -> Stream.concat(partner.fields().stream(),
                partner.entries().stream().flatMap(entry -> Stream.concat(
                    entry.protocolSettings().stream(),
                    entry.additionalParameters().stream().flatMap(List::stream)))))
            .filter(setting -> setting.masked().isPresent())
            .count();
        assertThat(found.partners()).hasSizeLessThan(20);
        assertThat(found.partners()).as("summary keys only: no password left")
            .allSatisfy(partner -> assertThat(level(partner)).isEqualTo(2));
        assertThat(masked).isZero();
        assertThat(found.partners().get(0).maskedCount()).as("the partner's own count")
            .isPositive();
        assertThat(audit).singleElement().satisfies(record -> assertThat(record.details())
            .containsEntry("partnersReturned", found.partners().size())
            .containsEntry("valuesMasked", masked));
    }

    // --- support -------------------------------------------------------------------------------

    /** 300 partners whose one entry each is unresolved (no referencable workflow). */
    private void manyUnresolvedEntries() {
        List<PartnerXml> partners = IntStream.rangeClosed(1, 300)
            .mapToObj(n -> new PartnerXml(List.of(Leaf.plain("Id", "ACME-%04d".formatted(n)),
                Leaf.plain("Name", "ACME Partner " + n)), List.of(new EntryXml("FTP", "1",
                    List.of(Leaf.plain("ServerName", "host.example.test")), List.of()))))
            .toList();
        export(new DiagramXml(GROUP, "SystemDiagram-01", OWNER, List.of(new CloudXml("33",
            "Module-0901", ID_AND_NAME, List.of(), partners))));
    }

    /**
     * 0: in full; 1: without additional parameters; 2: protocol settings down to the summary
     * keys.
     */
    private static int level(PartnerView partner) {
        boolean parameters = partner.entries().stream()
            .allMatch(entry -> entry.additionalParameters().isPresent());
        boolean summary = partner.entries().stream()
            .flatMap(entry -> entry.protocolSettings().stream())
            .allMatch(setting -> PartnerService.SUMMARY_KEYS.contains(setting.name()));
        return parameters ? 0 : summary ? 2 : 1;
    }

    /** The result, which the tool returns as it is, within {@code maxChars}. */
    private static void assertWithin(Found found, int maxChars, String what) {
        assertThat(ResultJson.size(found)).as(what).isLessThanOrEqualTo(maxChars);
    }

    /** Reads {@code diagram} with the reader as the adapter does and offers it to the owner. */
    private void export(DiagramXml diagram) {
        DiagramRef ref = new DiagramRef(diagram.name(), diagram.group());
        exports.put(ref, PartnerManagementReader.read(ref,
            XmlTree.parse(PartnerFixtures.workflowXml(diagram)).root()));
    }

    private static PartnerQuery query(Optional<String> search, int offset, int limit) {
        return new PartnerQuery("dev/node1", Optional.empty(), Optional.empty(), search,
            Optional.empty(), Optional.empty(), Optional.empty(), offset, limit, false,
            Optional.empty());
    }

    private Found find(PartnerQuery query, ResultLimiter limiter) {
        return find(query, limiter, FakeVersionDetection.pinned81());
    }

    private Found find(PartnerQuery query, ResultLimiter limiter, VersionGate gate) {
        return (Found) answer(query, limiter, gate);
    }

    private Answer answer(PartnerQuery query, ResultLimiter limiter, VersionGate gate) {
        audit.clear();
        return new PartnerService(new Gateways(), new TargetResolver(nodes), new FanOut(),
            limiter, new InventoryCache(new MutableClock(T0), node -> Duration.ofMinutes(10)),
            node -> node.equals(NODE1) ? Optional.of(OWNER) : Optional.empty(),
            node -> Duration.ofSeconds(5), node -> Duration.ofSeconds(120), ResultJson::size,
            Terminology.DEFAULT, gate, SecretTiming.NONE,
            new PartnerAudit.Dependencies(audit::add, Clock.systemUTC(), "default",
                UUID::randomUUID))
            .answer(query);
    }

    /**
     * The JSON text of the result holds no synthetic secret, not even its first 12 characters (a
     * fingerprint would make the mapper throw).
     */
    private static void assertNoSecretIn(Found found) {
        String json = ResultJson.mapper().writeValueAsString(found);
        for (String secret : PartnerFixtures.SECRETS) {
            assertThat(json).doesNotContain(secret).doesNotContain(secret.substring(0, 12));
        }
    }

    private static String id(PartnerView partner) {
        return field(partner, "Id");
    }

    private static String field(PartnerView partner, String name) {
        return partner.fields().stream()
            .filter(field -> field.name().equals(name))
            .map(field -> field.value().orElseThrow())
            .findFirst()
            .orElseThrow();
    }

    private PartnerManagementPort.Result portRead(List<DiagramRef> diagrams) {
        List<DiagramRef> read = diagrams.stream().filter(exports::containsKey).toList();
        return new PartnerManagementPort.Result(
            read.stream().map(diagram -> exports.get(diagram).partners()).toList(),
            diagrams.stream().filter(unreadable::contains)
                .map(diagram -> new UnreadableDiagram(diagram, ErrorCode.UNEXPECTED_RESPONSE,
                    "the export of this name returned a diagram of another group or owner"))
                .toList(),
            read.stream().collect(Collectors.toMap(diagram -> diagram,
                diagram -> exports.get(diagram).warnings())));
    }

    /** Only the inventory list and the partner port; every other port is an assertion error. */
    private final class Gateways implements GatewayFactory {

        @Override
        public InventoryPort inventory(NodeId server) {
            return new InventoryPort() {
                @Override
                public List<InventoryItem> listDiagrams(String owner) {
                    return Stream.concat(exports.keySet().stream(), unreadable.stream())
                        .map(diagram -> InventoryItem.diagram(server, diagram.name(),
                            "systemdiagram", diagram.diagramGroup(), owner))
                        .toList();
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
                    throw new AssertionError("not used");
                }
            };
        }

        @Override
        public PartnerManagementPort partners(NodeId server) {
            return (owner, diagrams) -> {
                if (portFailure != null) {
                    throw new ToolErrorException(portFailure);
                }
                return portRead(diagrams);
            };
        }

        @Override
        public Gateway forServer(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public InubitVersion detect(NodeId server) {
            throw new AssertionError("the version gate detects through its own factory");
        }

        @Override
        public MonitoringPort monitoring(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public ProcessQueryPort processes(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public LogPort logs(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public ProcessControlPort processControl(NodeId server) {
            throw new AssertionError("not used");
        }

        @Override
        public Optional<Gateway> knownGateway(NodeId server) {
            return Optional.empty();
        }
    }
}
