package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.application.PartnerComparison.Ambiguity;
import de.dadecker.inubit.mcp.application.PartnerComparison.Answered;
import de.dadecker.inubit.mcp.application.PartnerComparison.ComparedEntry;
import de.dadecker.inubit.mcp.application.PartnerComparison.ComparedPartner;
import de.dadecker.inubit.mcp.application.PartnerComparison.Failed;
import de.dadecker.inubit.mcp.application.PartnerComparison.Grouped;
import de.dadecker.inubit.mcp.application.PartnerComparison.Result;
import de.dadecker.inubit.mcp.application.PartnerComparison.ServerInput;
import de.dadecker.inubit.mcp.application.PartnerComparison.SettingComparison;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.partner.CommunicationCloud;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.Partner;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerField;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerKey;
import de.dadecker.inubit.mcp.domain.model.partner.ProtocolEntry;
import de.dadecker.inubit.mcp.domain.model.partner.SecretFingerprint;
import de.dadecker.inubit.mcp.domain.model.partner.Setting;
import de.dadecker.inubit.mcp.domain.model.partner.SettingValue;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import de.dadecker.inubit.mcp.domain.model.partner.WorkflowRef;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link PartnerComparison} (T025, FR-012 … FR-014, research D-10): US3-AS1…6, AS8, AS9 and
 * SC-003, table-driven where the cases share a shape.
 */
class PartnerComparisonTest {

    private static final NodeId T1 = NodeId.of("test", "node1");
    private static final NodeId T2 = NodeId.of("test", "node2");
    private static final NodeId P1 = NodeId.of("prod", "node1");
    private static final NodeId P2 = NodeId.of("prod", "node2");
    private static final Optional<InubitVersion> V81 = InubitVersion.parse("8.1.17");
    private static final Optional<InubitVersion> V90 = InubitVersion.parse("9.0.3");
    private static final DiagramRef DIAGRAM = new DiagramRef("SystemDiagram-01", "GRP-PM");
    private static final List<PartnerField> ID_AND_NAME =
        List.of(new PartnerField("Id", "String"), new PartnerField("Name", "String"));
    private static final String WORKFLOW = "Workflow-0001";

    // --- SC-003: the recorded-shape fixtures of T010 through the reader ---

    static Stream<Arguments> stageComparisons() {
        return Stream.of(
            Arguments.of("two stages, all four servers", List.of(T1, T2, P1, P2), List.of(
                "ACME-0001 FTP/Workflow-0001#0 ServerName differs"
                    + " {test/node1=ftp.test.example.test, test/node2=ftp.test.example.test,"
                    + " prod/node1=ftp.prod.example.test, prod/node2=ftp.prod.example.test}",
                "ACME-0001 FTP/Workflow-0001#0 Login differs {test/node1=login-a,"
                    + " test/node2=login-b, prod/node1=login-a, prod/node2=login-a}",
                "ACME-0002 WebService/Workflow-0002#0 Password same on"
                    + " [[test/node1, test/node2, prod/node1], [prod/node2]]",
                "ACME-0003 only on [test/node1, test/node2]")),
            Arguments.of("the servers of stage test", List.of(T1, T2), List.of(
                "ACME-0001 FTP/Workflow-0001#0 Login differs {test/node1=login-a,"
                    + " test/node2=login-b}")),
            Arguments.of("the servers of stage prod", List.of(P1, P2), List.of(
                "ACME-0002 WebService/Workflow-0002#0 Password same on"
                    + " [[prod/node1], [prod/node2]]")),
            Arguments.of("one server of each stage", List.of(T1, P1), List.of(
                "ACME-0001 FTP/Workflow-0001#0 ServerName differs"
                    + " {test/node1=ftp.test.example.test, prod/node1=ftp.prod.example.test}",
                "ACME-0003 only on [test/node1]")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stageComparisons")
    void everyDifferenceAndNothingElseIsReported(String name, List<NodeId> servers,
        List<String> expected) {
        Result result = PartnerComparison.compare(stages(servers));

        assertThat(differences(result)).containsExactlyElementsOf(expected);
        assertThat(result.incomplete()).isFalse();
    }

    @Test
    void theResultCarriesNoSecretAndNoFingerprint() throws Exception {
        Result result = PartnerComparison.compare(stages(List.of(T1, T2, P1, P2)));

        // the result mapper refuses a SecretFingerprint, so this also proves there is none
        String json = ResultJson.mapper().writeValueAsString(result);

        PartnerFixtures.SECRETS.forEach(secret -> assertThat(json).doesNotContain(secret));
        assertThat(json).doesNotContain("SecretFingerprint");
    }

    // --- US3-AS1, AS2, AS5: equal values once, differing values per server ---

    @Test
    void identicalServersShowEveryValueOnceAsEqual() {
        Partner partner = partner("ACME-0001", "ACME Corp", ftp(0, shown("ServerName", "a"),
            shown("Login", "acme"), secret("Password", 1)));

        Result result = PartnerComparison.compare(List.of(server(T1, cloud(partner)),
            server(T2, cloud(partner))));

        ComparedPartner compared = only(result);
        assertThat(compared.key()).contains(new PartnerKey(DIAGRAM, "Module-0901", "Id",
            "ACME-0001"));
        assertThat(compared.onlyOn()).isEmpty();
        assertThat(compared.fields()).containsExactly(
            plain("Id", new Grouped.Equal<>("ACME-0001")),
            plain("Name", new Grouped.Equal<>("ACME Corp")));
        assertThat(compared.maskedCount()).isEqualTo(new Grouped.Equal<>(1));
        ComparedEntry entry = compared.entries().get(0);
        assertThat(entry.onlyOn()).isEmpty();
        assertThat(entry.protocolSettings()).containsExactly(
            plain("ServerName", new Grouped.Equal<>("a")),
            plain("Login", new Grouped.Equal<>("acme")),
            new SettingComparison.Masked("Password", Map.of(T1, true, T2, true),
                List.of(List.of(T1, T2)), Optional.empty(), List.of()));
        assertThat(differences(result)).isEmpty();
    }

    static Stream<Arguments> plainSettings() {
        return Stream.of(
            Arguments.of("equal on all servers (AS1)", values(T1, "acme", T2, "acme", P1, "acme"),
                plain("Login", new Grouped.Equal<>("acme"))),
            Arguments.of("differs on one server (AS2)", values(T1, "a", T2, "b", P1, "a"),
                plain("Login", new Grouped.Differs<>(values(T1, "a", T2, "b", P1, "a")))),
            Arguments.of("compared uncut", values(T1, "x".repeat(300), T2, "x".repeat(301)),
                plain("Login", new Grouped.Differs<>(values(T1, "x".repeat(300), T2,
                    "x".repeat(301))))),
            Arguments.of("absent on one server", values(T1, "acme", T2, null, P1, "acme"),
                new SettingComparison.Plain("Login", new Grouped.Equal<>("acme"),
                    List.of(T1, P1))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("plainSettings")
    void aShownSettingIsEqualOrPerServer(String name, Map<NodeId, String> logins,
        SettingComparison expected) {
        List<ServerInput> servers = new ArrayList<>();
        logins.forEach((node, login) -> servers.add(server(node, cloud(partner("ACME-0001",
            "ACME Corp", ftp(0, login == null ? new Setting[0]
                : new Setting[] {shown("Login", login)}))))));

        ComparedEntry entry = only(PartnerComparison.compare(servers)).entries().get(0);

        assertThat(entry.protocolSettings()).containsExactly(expected);
    }

    // --- US3-AS4, AS5, FR-012a: masked values as "set" and server groups only ---

    static Stream<Arguments> maskedSettings() {
        SettingValue one = fingerprinted(1);
        SettingValue two = fingerprinted(2);
        SettingValue unset = new SettingValue.Masked(false, Optional.empty());
        SettingValue childElements = new SettingValue.Masked(true, Optional.empty());
        return Stream.of(
            row("same on all servers (AS5)", List.of(on(T1, V81, one), on(T2, V81, one),
                    on(P1, V81, one)),
                set(T1, true, T2, true, P1, true), List.of(List.of(T1, T2, P1)), null),
            row("same on two, differs on the third (AS4)", List.of(on(T1, V81, one),
                    on(T2, V81, one), on(P1, V81, two)),
                set(T1, true, T2, true, P1, true), List.of(List.of(T1, T2), List.of(P1)), null),
            row("a holder on 9.0", List.of(on(T1, V81, one), on(P1, V90, one)),
                set(T1, true, P1, true), List.of(), "not verified on 9.0.3"),
            row("a holder of unknown version", List.of(on(T1, V81, one),
                    on(P1, Optional.empty(), one)),
                set(T1, true, P1, true), List.of(), "not verified on an unknown version"),
            row("set on one server only", List.of(on(T1, V81, one), on(T2, V81, unset),
                    on(P1, V81, unset)),
                set(T1, true, T2, false, P1, false), List.of(), null),
            row("set on one 8.1 server, unset on 9.0", List.of(on(T1, V81, one),
                    on(P1, V90, unset)),
                set(T1, true, P1, false), List.of(), null),
            row("an unset 9.0 server does not block the statement", List.of(on(T1, V81, one),
                    on(T2, V90, unset), on(P1, V81, one)),
                set(T1, true, T2, false, P1, true), List.of(List.of(T1, P1)), null),
            row("not set anywhere", List.of(on(T1, V81, unset), on(T2, V81, unset)),
                set(T1, false, T2, false), List.of(), null),
            // carried over from T011: a set value with child elements has no fingerprint
            row("a set value without fingerprint", List.of(on(T1, V81, one),
                    on(T2, V81, childElements), on(P1, V81, one)),
                set(T1, true, T2, true, P1, true), List.of(), null),
            row("shown on one server, masked on the other", List.of(
                    on(T1, V81, new SettingValue.Shown("visible-on-one-server")),
                    on(T2, V81, one)),
                set(T1, true, T2, true), List.of(), null),
            row("an empty shown value counts as not set", List.of(
                    on(T1, V81, new SettingValue.Shown("")), on(T2, V81, one)),
                set(T1, false, T2, true), List.of(), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maskedSettings")
    void aMaskedSettingIsStatedAsSetAndServerGroupsOnly(String name, List<Holder> holders,
        Map<NodeId, Boolean> set, List<List<NodeId>> sameOn, String equality) {
        List<ServerInput> servers = holders.stream()
            .<ServerInput>map(holder -> server(holder.node(), holder.version(),
                cloud(partner("ACME-0001", "ACME Corp",
                    ftp(0, new Setting("Password", holder.value()))))))
            .toList();

        ComparedEntry entry = only(PartnerComparison.compare(servers)).entries().get(0);

        assertThat(entry.protocolSettings()).containsExactly(new SettingComparison.Masked(
            "Password", set, sameOn, Optional.ofNullable(equality), List.of()));
        assertThat(entry.protocolSettings().toString()).doesNotContain("visible-on-one-server");
    }

    // --- US3-AS3: "only on" for partners and entries ---

    @Test
    void aPartnerOrEntryOnSomeServersIsMarkedOnlyOnThem() {
        Partner both = partner("ACME-0001", "ACME Corp", ftp(0, shown("Login", "a")));
        Partner withSecondEntry = partner("ACME-0001", "ACME Corp", ftp(0, shown("Login", "a")),
            ftp(1, shown("Login", "b")));

        Result result = PartnerComparison.compare(List.of(
            server(T1, cloud(withSecondEntry, partner("ACME-0003", "ACME Retail"))),
            server(P1, cloud(both))));

        assertThat(result.partners()).extracting(p -> p.key().orElseThrow().value(),
                ComparedPartner::onlyOn)
            .containsExactly(tuple("ACME-0001", List.of()),
                tuple("ACME-0003", List.of(T1)));
        assertThat(result.partners().get(0).entries())
            .extracting(ComparedEntry::position, ComparedEntry::onlyOn)
            .containsExactly(tuple(0, List.of()), tuple(1, List.of(T1)));
    }

    @Test
    void entriesAreMatchedByProtocolWorkflowAndPosition() {
        ProtocolEntry first = new ProtocolEntry("FTP", workflow(WORKFLOW), Optional.of("csv"),
            List.of(shown("Login", "a")), List.of(shown("FTPProtocol", "SFTP")), 0);
        ProtocolEntry second = new ProtocolEntry("FTP", workflow(WORKFLOW), Optional.empty(),
            List.of(shown("Login", "b")), List.of(), 1);
        ProtocolEntry otherWorkflow = new ProtocolEntry("FTP", workflow("Workflow-0002"),
            Optional.of("xml"), List.of(shown("Login", "c")), List.of(), 0);

        Result result = PartnerComparison.compare(List.of(
            server(T1, cloud(partner("ACME-0001", "ACME Corp", first, second))),
            server(T2, cloud(partner("ACME-0001", "ACME Corp", second, otherWorkflow,
                new ProtocolEntry("FTP", workflow(WORKFLOW), Optional.of("json"),
                    List.of(shown("Login", "a")), List.of(shown("FTPProtocol", "SFTP")), 0))))));

        List<ComparedEntry> entries = only(result).entries();
        assertThat(entries).extracting(ComparedEntry::workflow, ComparedEntry::position,
                ComparedEntry::onlyOn)
            .containsExactly(tuple(Optional.of(WORKFLOW), 0, List.of()),
                tuple(Optional.of(WORKFLOW), 1, List.of()),
                tuple(Optional.of("Workflow-0002"), 0, List.of(T2)));
        // format is compared like a setting (FR-012)
        assertThat(entries.get(0).format()).contains(plain("format",
            new Grouped.Differs<>(values(T1, "csv", T2, "json"))));
        assertThat(entries.get(0).protocolSettings())
            .containsExactly(plain("Login", new Grouped.Equal<>("a")));
        assertThat(entries.get(0).additionalParameters())
            .containsExactly(plain("FTPProtocol", new Grouped.Equal<>("SFTP")));
        assertThat(entries.get(1).format()).isEmpty();
        assertThat(entries.get(2).format()).contains(new SettingComparison.Plain("format",
            new Grouped.Equal<>("xml"), List.of()));
    }

    @Test
    void aFormatOnOneServerOnlyIsMarkedOnlyOnThatServer() {
        ProtocolEntry withFormat = new ProtocolEntry("FTP", workflow(WORKFLOW), Optional.of("csv"),
            List.of(), List.of(), 0);

        Result result = PartnerComparison.compare(List.of(
            server(T1, cloud(partner("ACME-0001", "ACME Corp", withFormat))),
            server(T2, cloud(partner("ACME-0001", "ACME Corp", ftp(0))))));

        ComparedEntry entry = only(result).entries().get(0);
        assertThat(entry.onlyOn()).isEmpty();
        assertThat(entry.format()).contains(new SettingComparison.Plain("format",
            new Grouped.Equal<>("csv"), List.of(T1)));
    }

    @Test
    void theMaskedCountIsGroupedLikeASetting() {
        Result result = PartnerComparison.compare(List.of(
            server(T1, cloud(partner("ACME-0001", "ACME Corp",
                ftp(0, secret("Password", 1), secret("KeyPassword", 1))))),
            server(T2, cloud(partner("ACME-0001", "ACME Corp", ftp(0, secret("Password", 1)))))));

        assertThat(only(result).maskedCount())
            .isEqualTo(new Grouped.Differs<>(values(T1, 2, T2, 1)));
    }

    // --- US3-AS6, FR-014: failed servers ---

    @Test
    void aFailedServerIsReportedAndLeftOutOfEveryStatement() {
        ToolError unreachable = ToolError.of(ErrorCode.UNREACHABLE, "not reachable", "down",
            "retry").withNode(P1);
        Partner partner = partner("ACME-0001", "ACME Corp", ftp(0, secret("Password", 1)));

        Result result = PartnerComparison.compare(List.of(server(T1, cloud(partner)),
            new Failed(P1, unreachable), server(T2, cloud(partner))));

        assertThat(result.incomplete()).isTrue();
        assertThat(result.notCompared()).containsExactly(new Failed(P1, unreachable));
        ComparedPartner compared = only(result);
        assertThat(compared.onlyOn()).as("not 'only on' the answering servers").isEmpty();
        assertThat(compared.entries().get(0).protocolSettings()).containsExactly(
            new SettingComparison.Masked("Password", Map.of(T1, true, T2, true),
                List.of(List.of(T1, T2)), Optional.empty(), List.of()));
    }

    // --- US3-AS8, FR-013: the partner key ---

    @Test
    void aRenamedPartnerIsMatchedById() {
        Result result = PartnerComparison.compare(List.of(
            server(T1, cloud(partner("ACME-0001", "ACME Corp"))),
            server(P1, cloud(partner("ACME-0001", "ACME Corporation")))));

        assertThat(only(result).fields()).containsExactly(
            plain("Id", new Grouped.Equal<>("ACME-0001")),
            plain("Name", new Grouped.Differs<>(values(T1, "ACME Corp", P1,
                "ACME Corporation"))));
    }

    static Stream<Arguments> keyFields() {
        List<PartnerField> nameOnly = List.of(new PartnerField("Name", "String"),
            new PartnerField("City", "String"));
        Partner byName = new Partner(List.of(shown("Name", "ACME Corp"), shown("City", "X")),
            List.of());
        Partner maskedId = new Partner(List.of(new Setting("Id",
            new SettingValue.Masked(true, Optional.empty())), shown("Name", "ACME Corp")),
            List.of());
        Partner blankIds = new Partner(List.of(shown("Id", " "), shown("Name", "ACME Corp")),
            List.of());
        return Stream.of(
            Arguments.of("Id when declared and shown", ID_AND_NAME,
                partner("ACME-0001", "ACME Corp"), ID_AND_NAME, "Id", "ACME-0001"),
            Arguments.of("without Id, the first declared field", nameOnly, byName, nameOnly,
                "Name", "ACME Corp"),
            Arguments.of("Id declared on one side only", ID_AND_NAME,
                partner("ACME-0001", "ACME Corp"), List.of(new PartnerField("Name", "String")),
                "Name", "ACME Corp"),
            Arguments.of("a masked Id is never a key", ID_AND_NAME, maskedId, ID_AND_NAME,
                "Name", "ACME Corp"),
            Arguments.of("Ids that are blank everywhere are absent", ID_AND_NAME, blankIds,
                ID_AND_NAME, "Name", "ACME Corp"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keyFields")
    void theKeyFieldIsIdOrTheFirstNonMaskedFieldDeclaredOnBothSides(String name,
        List<PartnerField> firstFields, Partner partner, List<PartnerField> secondFields,
        String field, String value) {
        Result result = PartnerComparison.compare(List.of(
            server(T1, new CommunicationCloud("Module-0901", "33", firstFields, List.of(),
                List.of(partner))),
            server(T2, new CommunicationCloud("Module-0901", "33", secondFields, List.of(),
                List.of(partner)))));

        assertThat(only(result).key())
            .contains(new PartnerKey(DIAGRAM, "Module-0901", field, value));
    }

    @Test
    void aBlankIdIsAbsentForItsPartnerAndNeverMatched() {
        // carried over from T006: a present but blank Id gives no key; the cloud keeps Id
        Partner blank = partner("", "ACME Blank");

        Result result = PartnerComparison.compare(List.of(
            server(T1, cloud(partner("ACME-0001", "ACME Corp"), blank)),
            server(T2, cloud(partner("ACME-0001", "ACME Corp"), blank))));

        assertThat(result.partners()).extracting(ComparedPartner::key, ComparedPartner::onlyOn)
            .containsExactly(tuple(Optional.of(new PartnerKey(DIAGRAM, "Module-0901",
                    "Id", "ACME-0001")), List.of()),
                tuple(Optional.empty(), List.of(T1)),
                tuple(Optional.empty(), List.of(T2)));
        assertThat(result.partners().get(1).fields())
            .contains(plain("Name", new Grouped.Equal<>("ACME Blank")));
    }

    // --- FR-013: ambiguity, clouds (AS9), unresolved workflows ---

    @Test
    void aDuplicateKeyIsReportedAndNotMatched() {
        Partner first = partner("ACME-0001", "ACME Corp");
        Partner duplicate = partner("ACME-0001", "ACME Copy");

        Result result = PartnerComparison.compare(List.of(server(T1, cloud(first, duplicate)),
            server(T2, cloud(first))));

        List<Ambiguity> ambiguous = List.of(new Ambiguity(T1, 2, List.of("33")));
        assertThat(result.partners()).extracting(ComparedPartner::onlyOn,
                ComparedPartner::ambiguous)
            .containsExactly(tuple(List.of(T1), ambiguous),
                tuple(List.of(T1), ambiguous),
                tuple(List.of(T2), ambiguous));
        assertThat(result.partners()).extracting(p -> p.fields().get(1))
            .containsExactly(plain("Name", new Grouped.Equal<>("ACME Corp")),
                plain("Name", new Grouped.Equal<>("ACME Copy")),
                plain("Name", new Grouped.Equal<>("ACME Corp")));
    }

    @Test
    void cloudsOfTheSameNameInOneDiagramAreReportedAndToldApartByModuleId() {
        Partner partner = partner("ACME-0001", "ACME Corp");
        CommunicationCloud first = new CommunicationCloud("Module-0901", "33", ID_AND_NAME,
            List.of(), List.of(partner));
        CommunicationCloud second = new CommunicationCloud("Module-0901", "213", ID_AND_NAME,
            List.of(), List.of(partner("ACME-0002", "ACME Logistics")));

        Result result = PartnerComparison.compare(List.of(server(T1, first, second),
            server(T2, cloud(partner))));

        assertThat(result.partners()).extracting(p -> p.key().orElseThrow().value(),
                ComparedPartner::moduleId, ComparedPartner::onlyOn, ComparedPartner::ambiguous)
            .containsExactly(
                tuple("ACME-0001", Optional.of("33"), List.of(T1),
                    List.of(new Ambiguity(T1, 1, List.of("33", "213")))),
                tuple("ACME-0001", Optional.empty(), List.of(T2),
                    List.of(new Ambiguity(T1, 1, List.of("33", "213")))),
                tuple("ACME-0002", Optional.of("213"), List.of(T1),
                    List.of(new Ambiguity(T1, 1, List.of("33", "213")))));
    }

    @Test
    void theSameIdInTwoCloudsIsTwoPartnersComparedPerCloud() {
        // US3-AS9, with the T010 fixture of two clouds through the reader
        SystemDiagramPartners twoClouds = PartnerManagementReader.read(
            new DiagramRef("SystemDiagram-03", PartnerFixtures.GROUP),
            XmlTree.parse(PartnerFixtures.workflowXml(PartnerFixtures.twoClouds())).root())
            .partners();

        Result result = PartnerComparison.compare(List.of(
            new Answered(T1, V81, List.of(twoClouds)), new Answered(P1, V81, List.of(twoClouds))));

        assertThat(result.partners()).extracting(ComparedPartner::cloud,
                p -> p.key().orElseThrow().value(), ComparedPartner::onlyOn,
                ComparedPartner::ambiguous)
            .containsExactly(
                tuple("Module-0901", PartnerFixtures.SHARED_ID, List.of(), List.of()),
                tuple("Module-0902", PartnerFixtures.SHARED_ID, List.of(), List.of()));
        assertThat(result.partners()).extracting(p -> p.entries().get(0).protocolSettings()
                .get(0))
            .containsExactly(plain("ServerName", new Grouped.Equal<>("cloud1.example.test")),
                plain("ServerName", new Grouped.Equal<>("cloud2.example.test")));
        assertThat(differences(result)).isEmpty();
    }

    @Test
    void anUnresolvedEntryIsNeverMatched() {
        ProtocolEntry unresolved = new ProtocolEntry("WebService",
            new WorkflowRef(Optional.empty()), Optional.empty(),
            List.of(shown("ServerName", "a")), List.of(), 0);
        Partner partner = partner("ACME-0003", "ACME Retail", ftp(0, shown("Login", "a")),
            unresolved);

        ComparedPartner compared = only(PartnerComparison.compare(List.of(
            server(T1, cloud(partner)), server(T2, cloud(partner)))));

        assertThat(compared.entries()).extracting(ComparedEntry::workflow, ComparedEntry::onlyOn)
            .containsExactly(tuple(Optional.of(WORKFLOW), List.of()),
                tuple(Optional.empty(), List.of(T1)),
                tuple(Optional.empty(), List.of(T2)));
    }

    // --- helpers ---

    private static List<ServerInput> stages(List<NodeId> servers) {
        Map<String, byte[]> stages = PartnerFixtures.stages();
        return servers.stream().<ServerInput>map(node -> new Answered(node, V81, List.of(
                PartnerManagementReader.read(DIAGRAM,
                    XmlTree.parse(stages.get(node.value())).root()).partners())))
            .toList();
    }

    /** Every difference of {@code result}, one line each, in result order. */
    private static List<String> differences(Result result) {
        List<String> lines = new ArrayList<>();
        for (ComparedPartner partner : result.partners()) {
            String where = partner.key().map(PartnerKey::value).orElse("(no key)");
            if (!partner.onlyOn().isEmpty()) {
                lines.add(where + " only on " + partner.onlyOn());
            }
            differences(where, partner.fields(), lines);
            if (partner.maskedCount() instanceof Grouped.Differs<Integer> counts) {
                lines.add(where + " maskedCount differs " + counts.values());
            }
            for (ComparedEntry entry : partner.entries()) {
                String at = where + " " + entry.protocol() + "/"
                    + entry.workflow().orElse("(unresolved)") + "#" + entry.position();
                if (!entry.onlyOn().isEmpty()) {
                    lines.add(at + " only on " + entry.onlyOn());
                }
                differences(at, Stream.of(entry.format().stream().toList(),
                    entry.protocolSettings(), entry.additionalParameters())
                    .flatMap(List::stream).toList(), lines);
            }
        }
        return lines;
    }

    private static void differences(String where, List<SettingComparison> settings,
        List<String> lines) {
        for (SettingComparison setting : settings) {
            String at = where + " " + setting.name();
            if (!setting.onlyOn().isEmpty()) {
                lines.add(at + " only on " + setting.onlyOn());
            }
            switch (setting) {
                case SettingComparison.Plain plain -> {
                    if (plain.value() instanceof Grouped.Differs<String> differs) {
                        lines.add(at + " differs " + differs.values());
                    }
                }
                case SettingComparison.Masked masked -> {
                    if (masked.set().values().stream().distinct().count() > 1) {
                        lines.add(at + " set " + masked.set());
                    }
                    if (masked.sameOn().size() > 1) {
                        lines.add(at + " same on " + masked.sameOn());
                    }
                    masked.equality().ifPresent(text -> lines.add(at + " " + text));
                }
            }
        }
    }

    private static ComparedPartner only(Result result) {
        assertThat(result.partners()).hasSize(1);
        return result.partners().get(0);
    }

    private static Answered server(NodeId node, CommunicationCloud... clouds) {
        return server(node, V81, clouds);
    }

    private static Answered server(NodeId node, Optional<InubitVersion> version,
        CommunicationCloud... clouds) {
        return new Answered(node, version,
            List.of(new SystemDiagramPartners(DIAGRAM, Arrays.asList(clouds))));
    }

    private static CommunicationCloud cloud(Partner... partners) {
        return new CommunicationCloud("Module-0901", "33", ID_AND_NAME,
            List.of(WORKFLOW, "Workflow-0002"), Arrays.asList(partners));
    }

    private static Partner partner(String id, String name, ProtocolEntry... entries) {
        return new Partner(List.of(shown("Id", id), shown("Name", name)), Arrays.asList(entries));
    }

    private static ProtocolEntry ftp(int position, Setting... settings) {
        return new ProtocolEntry("FTP", workflow(WORKFLOW), Optional.empty(),
            Arrays.asList(settings), List.of(), position);
    }

    private static WorkflowRef workflow(String name) {
        return new WorkflowRef(Optional.of(name));
    }

    private static Setting shown(String name, String value) {
        return new Setting(name, new SettingValue.Shown(value));
    }

    private static Setting secret(String name, int fingerprint) {
        return new Setting(name, fingerprinted(fingerprint));
    }

    private static SettingValue fingerprinted(int fingerprint) {
        return new SettingValue.Masked(true,
            Optional.of(SecretFingerprint.of(new byte[] {(byte) fingerprint})));
    }

    private static SettingComparison plain(String name, Grouped<String> value) {
        return new SettingComparison.Plain(name, value, List.of());
    }

    /** A map in the given order: node, value, node, value, … */
    @SuppressWarnings("unchecked")
    private static <T> Map<NodeId, T> values(Object... pairs) {
        Map<NodeId, T> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((NodeId) pairs[i], (T) pairs[i + 1]);
        }
        return values;
    }

    private static Map<NodeId, Boolean> set(Object... pairs) {
        return values(pairs);
    }

    record Holder(NodeId node, Optional<InubitVersion> version, SettingValue value) {
    }

    private static Holder on(NodeId node, Optional<InubitVersion> version, SettingValue value) {
        return new Holder(node, version, value);
    }

    private static Arguments row(String name, List<Holder> holders, Map<NodeId, Boolean> set,
        List<List<NodeId>> sameOn, String equality) {
        return Arguments.of(name, holders, set, sameOn, equality);
    }
}
