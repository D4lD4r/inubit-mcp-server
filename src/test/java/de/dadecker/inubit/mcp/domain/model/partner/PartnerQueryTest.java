package de.dadecker.inubit.mcp.domain.model.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T007: every {@code find_partners} input is validated before INUBIT is contacted (FR-002). */
class PartnerQueryTest {

    private static final String NAME_200 = "ä".repeat(199) + "😀";

    @Test
    void aQueryWithOnlyATargetHasNoFiltersAndTheDefaultPaging() {
        PartnerQuery query = new PartnerQuery("qa", null, null, null, null, null, null, 0,
            PartnerQuery.DEFAULT_LIMIT, false, null);

        assertThat(query.target()).isEqualTo("qa");
        assertThat(query.compareWith()).isEmpty();
        assertThat(query.owner()).isEmpty();
        assertThat(query.search()).isEmpty();
        assertThat(query.systemDiagram()).isEmpty();
        assertThat(query.protocol()).isEmpty();
        assertThat(query.workflow()).isEmpty();
        assertThat(query.mcpClient()).isEmpty();
        assertThat(PartnerQuery.DEFAULT_LIMIT).isEqualTo(20);
        assertThat(PartnerQuery.MAX_LIMIT).isEqualTo(100);
    }

    @Test
    void aQueryWithEveryInputKeepsThem() {
        PartnerQuery query = new PartnerQuery("qa/node1", Optional.of("prod"),
            Optional.of("ACME.Owner_1-x y"), Optional.of("Müller & Söhne / Köln"),
            Optional.of("System-0001"), Optional.of("AS2_v-1"), Optional.of("Workflow-0001"), 40,
            PartnerQuery.MAX_LIMIT, true, Optional.of("client/1.0"));

        assertThat(query).extracting(PartnerQuery::compareWith, PartnerQuery::owner,
                PartnerQuery::search, PartnerQuery::systemDiagram, PartnerQuery::protocol,
                PartnerQuery::workflow, PartnerQuery::offset, PartnerQuery::limit,
                PartnerQuery::refresh, PartnerQuery::mcpClient)
            .containsExactly(Optional.of("prod"), Optional.of("ACME.Owner_1-x y"),
                Optional.of("Müller & Söhne / Köln"), Optional.of("System-0001"),
                Optional.of("AS2_v-1"), Optional.of("Workflow-0001"), 40, 100, true,
                Optional.of("client/1.0"));
    }

    @Test
    void theTargetIsRequired() {
        assertInvalid(() -> query(null), "target");
        assertInvalid(() -> query(""), "target");
        assertInvalid(() -> query("  "), "target");
    }

    @Test
    void compareWithMustNotEqualOrOverlapTheTarget() {
        assertInvalid(() -> compare("qa", "qa"), "compareWith");
        assertInvalid(() -> compare("qa/node1", "qa/node1"), "compareWith");
        assertInvalid(() -> compare("qa", "qa/node1"), "compareWith");
        assertInvalid(() -> compare("qa/node1", "qa"), "compareWith");
    }

    @Test
    void compareWithMayNameOtherServersEvenOfTheSameStage() {
        assertThat(compare("qa/node1", "qa/node2").compareWith()).contains("qa/node2");
        assertThat(compare("qa", "prod").compareWith()).contains("prod");
        assertThat(compare("qa", "prod/node1").compareWith()).contains("prod/node1");
        assertThat(compare("qa", "qa2/node1").compareWith()).contains("qa2/node1");
        assertThat(compare("qa2", "qa/node1").compareWith()).contains("qa/node1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACME", "acme.user", "_owner", ".hidden", "Owner 1", "a-b"})
    void ownersOfTheExportArchivesRuleAreAccepted(String owner) {
        assertThat(withOwner(owner).owner()).contains(owner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ACME", "-ACME", "ACME/x", "ACME&x", "Müller", "ACME\n"})
    void ownersOutsideTheExportArchivesRuleAreInvalid(String owner) {
        assertInvalid(() -> withOwner(owner), "owner");
    }

    @Test
    void anOwnerHasAtMost200Characters() {
        assertThat(withOwner("o".repeat(200)).owner()).isPresent();
        assertInvalid(() -> withOwner("o".repeat(201)), "owner");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACME-0001", "Müller & Söhne", "ftp.example.test/in", "x", "ÉÀÇ"})
    void searchTermsOfPrintableCharactersAreAccepted(String search) {
        assertThat(withSearch(search).search()).contains(search);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ACME\n", "ACME\t", "\u0000", "ACME\u007F", "ACME\u0085"})
    void emptySearchTermsAndSearchTermsWithControlCharactersAreInvalid(String search) {
        assertInvalid(() -> withSearch(search), "search");
    }

    @Test
    void aSearchTermHasAtMost200CodePoints() {
        assertThat(withSearch(NAME_200).search()).contains(NAME_200);
        assertInvalid(() -> withSearch(NAME_200 + "a"), "search");
    }

    @ParameterizedTest
    @ValueSource(strings = {"System-0001", "Müller & Co / A", "a"})
    void diagramAndWorkflowNamesOfTheExistingRuleAreAccepted(String name) {
        assertThat(withSystemDiagram(name).systemDiagram()).contains(name);
        assertThat(withWorkflow(name).workflow()).contains(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "System\n0001", "System\t", "\u007F"})
    void diagramAndWorkflowNamesOutsideTheExistingRuleAreInvalid(String name) {
        assertInvalid(() -> withSystemDiagram(name), "systemDiagram");
        assertInvalid(() -> withWorkflow(name), "workflow");
    }

    @Test
    void diagramAndWorkflowNamesHaveAtMost200CodePoints() {
        assertThat(withSystemDiagram(NAME_200).systemDiagram()).contains(NAME_200);
        assertThat(withWorkflow(NAME_200).workflow()).contains(NAME_200);
        assertInvalid(() -> withSystemDiagram(NAME_200 + "a"), "systemDiagram");
        assertInvalid(() -> withWorkflow(NAME_200 + "a"), "workflow");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FTP", "AS2", "HTTP_S-1", "x"})
    void protocolsOfLettersDigitsHyphenAndUnderscoreAreAccepted(String protocol) {
        assertThat(withProtocol(protocol).protocol()).contains(protocol);
    }

    @Test
    void aProtocolHasAtMost64Characters() {
        assertThat(withProtocol("p".repeat(64)).protocol()).isPresent();
        assertInvalid(() -> withProtocol("p".repeat(65)), "protocol");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "FT P", "FTP.", "FTP/S", "Ä", "FTP\n"})
    void otherProtocolsAreInvalid(String protocol) {
        assertInvalid(() -> withProtocol(protocol), "protocol");
    }

    @Test
    void pagingIsBounded() {
        assertThat(paged(0, 1).limit()).isEqualTo(1);
        assertThat(paged(9_999, 100).offset()).isEqualTo(9_999);
        assertInvalid(() -> paged(-1, 20), "offset");
        assertInvalid(() -> paged(0, 0), "limit");
        assertInvalid(() -> paged(0, 101), "limit");
    }

    private static PartnerQuery query(String target) {
        return new PartnerQuery(target, null, null, null, null, null, null, 0, 20, false, null);
    }

    private static PartnerQuery compare(String target, String compareWith) {
        return new PartnerQuery(target, Optional.of(compareWith), null, null, null, null, null, 0,
            20, false, null);
    }

    private static PartnerQuery withOwner(String owner) {
        return new PartnerQuery("qa", null, Optional.of(owner), null, null, null, null, 0, 20,
            false, null);
    }

    private static PartnerQuery withSearch(String search) {
        return new PartnerQuery("qa", null, null, Optional.of(search), null, null, null, 0, 20,
            false, null);
    }

    private static PartnerQuery withSystemDiagram(String name) {
        return new PartnerQuery("qa", null, null, null, Optional.of(name), null, null, 0, 20,
            false, null);
    }

    private static PartnerQuery withProtocol(String protocol) {
        return new PartnerQuery("qa", null, null, null, null, Optional.of(protocol), null, 0, 20,
            false, null);
    }

    private static PartnerQuery withWorkflow(String name) {
        return new PartnerQuery("qa", null, null, null, null, null, Optional.of(name), 0, 20,
            false, null);
    }

    private static PartnerQuery paged(int offset, int limit) {
        return new PartnerQuery("qa", null, null, null, null, null, null, offset, limit, false,
            null);
    }

    private static void assertInvalid(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(e.error().message()).contains(field);
        });
    }
}
