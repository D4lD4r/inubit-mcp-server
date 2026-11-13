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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T016, T025 (feature 022, US1): the license facts of {@code GET /ibis/rest/license}, against the
 * recording {@code admin-license.xml} of an 8.1.17 server (T014) and documents in its layout.
 */
class LicenseXmlParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    private static LicenseFacts parse(String xml) {
        return LicenseXmlParser.parse(DEV, xml.getBytes(StandardCharsets.UTF_8));
    }

    /** A {@code <license>} in the recorded layout with {@code licenseType} as limits. */
    private static String license(String licenseType, String licenseInformation) {
        return "<license><LicenseInformation>" + licenseInformation + "</LicenseInformation>"
            + "<inubit><LicenseType>" + licenseType + "</LicenseType></inubit></license>";
    }

    private static String license(String licenseType) {
        return license(licenseType, "");
    }

    @Test
    void theRecordedLicenseGivesExactlyTheThreeFacts() {
        LicenseFacts facts = LicenseXmlParser.parse(DEV, RestFixtures.bytes("admin-license.xml"));

        assertThat(facts).isEqualTo(new LicenseFacts(Optional.of(LocalDate.of(2046, 10, 8)),
            Optional.of(999_999L), Optional.of(Masked.MASK_TOKEN)));
    }

    @Test
    void theLicenseeIsAlwaysMaskedAndAbsentFactsStayEmpty() {
        assertThat(parse(license("<ExpirationDate>2027-06-30</ExpirationDate>",
            "<CompanyName>Fictitious Licensee 0002</CompanyName>")).licensee())
            .contains(Masked.MASK_TOKEN);

        LicenseFacts bare = parse(license("<ExpirationDate>2027-06-30</ExpirationDate>",
            "<CompanyName> </CompanyName>"));
        assertThat(bare.licensee()).isEmpty();
        assertThat(bare.licensedThreads()).isEmpty();
    }

    @Test
    void anElementThatSecretRulesFlagIsNotReported() {
        assertThat(parse(license("<ExpirationDate>2027-06-30</ExpirationDate>"
            + "<ThreadCount encrypted=' TRUE '>32</ThreadCount>")).licensedThreads()).isEmpty();
        assertThat(parse(license("<ExpirationDate>2027-06-30</ExpirationDate>"
            + "<ThreadCount type='password'>32</ThreadCount>")).licensedThreads()).isEmpty();
    }

    @Test
    void aDtdIsRefusedWithoutEchoingTheContent() {
        String xml = "<?xml version='1.0'?><!DOCTYPE license [<!ENTITY s"
            + " 'synthetic-secret-0203'>]>" + license("<ExpirationDate>2027-06-30</ExpirationDate>",
                "<CompanyName>&s;</CompanyName>");

        assertThatThrownBy(() -> parse(xml))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().toString()).doesNotContain("synthetic-secret-0203");
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<license><inubit><LicenseType><ThreadCount>32</ThreadCount></LicenseType></inubit>"
            + "</license>",
        "<license><inubit><LicenseType><ExpirationDate> </ExpirationDate></LicenseType></inubit>"
            + "</license>",
        "<license><inubit><LicenseType><ExpirationDate>synthetic-secret-0204</ExpirationDate>"
            + "</LicenseType></inubit></license>",
        "<license><inubit><LicenseType><ExpirationDate encrypted='true'>2027-06-30"
            + "</ExpirationDate></LicenseType></inubit></license>",
        "<license><inubit><ExpirationDate>2027-06-30</ExpirationDate></inubit></license>",
        "<license><expiryDate>2027-06-30</expiryDate></license>"})
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

    /** T025 (T016 review): the threads are only the /metrics fallback, so they alone go. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "unlimited", "32.5", "99999999999999999999"})
    void licensedThreadsThatAreNotAPositiveNumberLeaveOnlyTheThreadsEmpty(String threads) {
        LicenseFacts facts = parse(license("<ExpirationDate>2027-06-30</ExpirationDate>"
            + "<ThreadCount>" + threads + "</ThreadCount>",
            "<CompanyName>Fictitious Licensee 0002</CompanyName>"));

        assertThat(facts).isEqualTo(new LicenseFacts(Optional.of(LocalDate.of(2027, 6, 30)),
            Optional.empty(), Optional.of(Masked.MASK_TOKEN)));
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
