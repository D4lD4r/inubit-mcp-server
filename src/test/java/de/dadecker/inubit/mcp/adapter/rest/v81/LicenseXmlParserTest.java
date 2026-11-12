package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T016 (feature 022, US1): the license facts of {@code GET /ibis/rest/license}. The fixture
 * {@code admin-license-reconstructed.xml} is reconstructed until T014 records the real answer.
 */
class LicenseXmlParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    private static LicenseFacts parse(String xml) {
        return LicenseXmlParser.parse(DEV, xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theFactsAreReadAndUnknownElementsAreIgnored() {
        LicenseFacts facts = LicenseXmlParser.parse(DEV,
            RestFixtures.bytes("admin-license-reconstructed.xml"));

        assertThat(facts.expiryDate()).contains(LocalDate.of(2027, 6, 30));
        assertThat(facts.licensedThreads()).contains(32L);
        assertThat(facts.licensee()).contains(Masked.MASK_TOKEN);
        assertThat(facts.toString()).doesNotContain("Fictitious Licensee 0001",
            "synthetic-secret-0201", "synthetic-secret-0202", "synthetic-edition",
            "Synthetic-Module-0001");
    }

    @Test
    void theLicenseeIsAlwaysMaskedAndAbsentFactsStayEmpty() {
        assertThat(parse("<license><expiryDate>2027-06-30</expiryDate>"
            + "<licensee>Fictitious Licensee 0002</licensee></license>").licensee())
            .contains(Masked.MASK_TOKEN);

        LicenseFacts bare = parse("<license><expiryDate>2027-06-30</expiryDate>"
            + "<licensee> </licensee></license>");
        assertThat(bare.licensee()).isEmpty();
        assertThat(bare.licensedThreads()).isEmpty();
    }

    @Test
    void anElementThatSecretRulesFlagIsNotReported() {
        LicenseFacts facts = parse("<license><expiryDate>2027-06-30</expiryDate>"
            + "<licensedThreads encrypted=' TRUE '>32</licensedThreads></license>");

        assertThat(facts.licensedThreads()).isEmpty();
        assertThat(parse("<license><expiryDate>2027-06-30</expiryDate>"
            + "<licensedThreads type='password'>32</licensedThreads></license>")
            .licensedThreads()).isEmpty();
    }

    @Test
    void aDtdIsRefusedWithoutEchoingTheContent() {
        String xml = "<?xml version='1.0'?><!DOCTYPE license [<!ENTITY s"
            + " 'synthetic-secret-0203'>]><license><expiryDate>2027-06-30</expiryDate>"
            + "<licensee>&s;</licensee></license>";

        assertThatThrownBy(() -> parse(xml))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().toString()).doesNotContain("synthetic-secret-0203");
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<license><licensedThreads>32</licensedThreads></license>",
        "<license><expiryDate> </expiryDate></license>",
        "<license><expiryDate>synthetic-secret-0204</expiryDate></license>",
        "<license><expiryDate encrypted='true'>2027-06-30</expiryDate></license>",
        "<license><modules><expiryDate>2027-06-30</expiryDate></modules></license>"})
    void withoutAReadableExpiryDateThePartIsUnavailable(String xml) {
        assertThatThrownBy(() -> parse(xml))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().toString()).doesNotContain("synthetic-secret-0204");
                UnavailablePart part = UnavailablePart.of(UnavailablePart.LICENSE, e.error());
                assertThat(part.reason()).startsWith("UNEXPECTED_RESPONSE: ")
                    .contains("expiry date");
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "unlimited", "32.5"})
    void licensedThreadsThatAreNotAPositiveNumberAreAnUnexpectedResponse(String threads) {
        assertThatThrownBy(() -> parse("<license><expiryDate>2027-06-30</expiryDate>"
            + "<licensedThreads>" + threads + "</licensedThreads></license>"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().message()).contains("licensed threads");
            });
    }

    @Test
    void anotherDocumentIsAnUnexpectedResponse() {
        assertThatThrownBy(() -> parse("<SystemInformationList/>"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
            });
    }
}
