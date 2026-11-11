package de.dadecker.inubit.mcp.domain.model.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T006: partners, protocol entries and the partner key of a communication cloud. */
class CommunicationCloudTest {

    private static final DiagramRef DIAGRAM = new DiagramRef("SD_Partner", "Group A");
    private static final SettingValue SECRET = new SettingValue.Masked(false, Optional.empty());

    @Test
    void theKeyOfAPartnerIsDiagramCloudAndItsId() {
        CommunicationCloud cloud =
            cloud("Cloud_A", partner(shown("Id", "P-001"), shown("Name", "Alpha")));

        assertThat(cloud.keyOf(DIAGRAM, cloud.partners().get(0)))
            .contains(new PartnerKey(DIAGRAM, "Cloud_A", "Id", "P-001"));
    }

    @Test
    void theKeyUsesTheUncutId() {
        String longId = "x".repeat(250);
        CommunicationCloud cloud = cloud("Cloud_A", partner(shown("Id", longId)));

        assertThat(cloud.keyOf(DIAGRAM, cloud.partners().get(0)))
            .map(PartnerKey::value).contains(longId);
    }

    @Test
    void thereIsNoKeyWhenIdIsAbsent() {
        CommunicationCloud cloud = cloud("Cloud_A", partner(shown("Name", "Alpha")));

        assertThat(cloud.keyOf(DIAGRAM, cloud.partners().get(0))).isEmpty();
    }

    @Test
    void thereIsNoKeyWhenIdIsMasked() {
        CommunicationCloud cloud = cloud("Cloud_A",
            new Partner(List.of(new Setting("Id", SECRET), shown("Name", "Alpha")), List.of()));

        assertThat(cloud.keyOf(DIAGRAM, cloud.partners().get(0))).isEmpty();
    }

    @Test
    void theSameIdInAnotherCloudOrDiagramIsAnotherKey() {
        Partner partner = partner(shown("Id", "P-001"));
        Optional<PartnerKey> key = cloud("Cloud_A", partner).keyOf(DIAGRAM, partner);

        assertThat(cloud("Cloud_B", partner).keyOf(DIAGRAM, partner)).isNotEqualTo(key);
        DiagramRef otherGroup = new DiagramRef("SD_Partner", "Group B");
        assertThat(cloud("Cloud_A", partner).keyOf(otherGroup, partner)).isNotEqualTo(key);
    }

    @Test
    void maskedCountCountsMaskedValuesInFieldsAndEntries() {
        ProtocolEntry entry = entry("FTP", "TWF_Send", 0,
            List.of(shown("Host", "ftp.example.test"), new Setting("Password", SECRET)),
            List.of(new Setting("Token", SECRET)));
        Partner partner = new Partner(
            List.of(shown("Id", "P-001"), new Setting("Secret", SECRET)), List.of(entry));

        assertThat(partner.maskedCount()).isEqualTo(3);
        assertThat(new Partner(partner.fields(), partner.entries(), 3)).isEqualTo(partner);
    }

    @Test
    void aMaskedCountThatDoesNotMatchTheValuesIsRejected() {
        List<Setting> fields = List.of(new Setting("Id", SECRET));

        assertThatIllegalArgumentException().isThrownBy(() -> new Partner(fields, List.of(), 0))
            .withMessageContaining("maskedCount");
    }

    @Test
    void entriesKeepExportOrderAndAreNeverMerged() {
        ProtocolEntry first = entry("FTP", "TWF_Send", 0, List.of(shown("Host", "a")), List.of());
        ProtocolEntry second = entry("FTP", "TWF_Send", 1, List.of(shown("Host", "b")), List.of());

        assertThat(new Partner(List.of(), List.of(first, second)).entries())
            .containsExactly(first, second);
    }

    @Test
    void anEntryHasANonNegativePosition() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> entry("FTP", "TWF_Send", -1, List.of(), List.of()))
            .withMessageContaining("position");
    }

    @Test
    void anEntryWithoutFormatOrResolvedWorkflowIsEmptyThere() {
        ProtocolEntry entry = new ProtocolEntry(
            "WebService", new WorkflowRef(Optional.empty()), null, List.of(), List.of(), 0);

        assertThat(entry.format()).isEmpty();
        assertThat(entry.workflow().name()).isEmpty();
    }

    @Test
    void listsAreCopied() {
        List<Setting> settings = new ArrayList<>(List.of(shown("Host", "a")));
        List<String> workflows = new ArrayList<>(List.of("TWF_Send"));
        ProtocolEntry entry = entry("FTP", "TWF_Send", 0, settings, settings);
        CommunicationCloud cloud = new CommunicationCloud("Cloud_A", "m1",
            List.of(new PartnerField("Id", "String")), workflows, List.of());

        settings.add(shown("Port", "21"));
        workflows.add("TWF_Other");

        assertThat(entry.protocolSettings()).hasSize(1);
        assertThat(entry.additionalParameters()).hasSize(1);
        assertThat(cloud.referencableWorkflows()).containsExactly("TWF_Send");
        assertThatThrownBy(() -> cloud.partners().add(partner()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aDiagramWithoutPartnerManagementHasNoClouds() {
        SystemDiagramPartners read = new SystemDiagramPartners(DIAGRAM, List.of());

        assertThat(read.diagram()).isEqualTo(DIAGRAM);
        assertThat(read.clouds()).isEmpty();
    }

    private static CommunicationCloud cloud(String moduleName, Partner... partners) {
        return new CommunicationCloud(moduleName, "m-" + moduleName,
            List.of(new PartnerField("Id", "String"), new PartnerField("Name", "String")),
            List.of("TWF_Send"), List.of(partners));
    }

    private static Partner partner(Setting... fields) {
        return new Partner(List.of(fields), List.of());
    }

    private static ProtocolEntry entry(String protocol, String workflow, int position,
            List<Setting> protocolSettings, List<Setting> additionalParameters) {
        return new ProtocolEntry(protocol, new WorkflowRef(Optional.of(workflow)), Optional.empty(),
            protocolSettings, additionalParameters, position);
    }

    private static Setting shown(String name, String value) {
        return new Setting(name, new SettingValue.Shown(value));
    }
}
