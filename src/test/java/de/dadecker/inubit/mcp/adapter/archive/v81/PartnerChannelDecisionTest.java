package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.partner.CommunicationCloud;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.Setting;
import de.dadecker.inubit.mcp.domain.model.partner.SettingValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T011, regression of the channel decision (feature 018 research D-1, "T-record results"): the
 * REST diagram export and the StartCLI group export of the recorded system diagram give equal
 * masked models per communication cloud, which is what made REST the channel.
 */
class PartnerChannelDecisionTest {

    @Test
    void theRestAndCliExportsGiveEqualMaskedModelsPerCloud() {
        DiagramRef diagram = new DiagramRef("SystemDiagram-01", "GRP-PM");

        PartnerManagementReader.Result rest = PartnerManagementReader.read(diagram,
            PartnerManagementReaderTest.recordedWorkflowXml(
                PartnerManagementReaderTest.REST_FIXTURE));
        PartnerManagementReader.Result cli = PartnerManagementReader.read(diagram,
            PartnerManagementReaderTest.recordedWorkflowXml(
                PartnerManagementReaderTest.CLI_FIXTURE));

        List<CommunicationCloud> restClouds = rest.partners().clouds();
        List<CommunicationCloud> cliClouds = cli.partners().clouds();
        assertThat(restClouds).extracting(CommunicationCloud::moduleName)
            .containsExactly("Module-0901", "Module-0902");
        assertThat(cliClouds).extracting(CommunicationCloud::moduleName)
            .containsExactlyElementsOf(restClouds.stream().map(CommunicationCloud::moduleName)
                .toList());
        for (int i = 0; i < restClouds.size(); i++) {
            CommunicationCloud cloud = restClouds.get(i);
            assertThat(cliClouds.get(i)).as(cloud.moduleName()).isEqualTo(cloud);
            assertThat(cloud.partners()).as("the comparison covers partners with masked values")
                .anySatisfy(partner -> assertThat(partner.maskedCount()).isPositive());
            assertThat(cloud.partners().stream().flatMap(p -> p.entries().stream())
                .flatMap(e -> e.protocolSettings().stream())
                .map(Setting::value).filter(SettingValue.Masked.class::isInstance))
                .as("fingerprints are part of the equality").anySatisfy(value -> assertThat(
                    ((SettingValue.Masked) value).fingerprint()).isPresent());
        }
        assertThat(cli.partners()).isEqualTo(rest.partners());
        assertThat(cli.warnings()).isEqualTo(rest.warnings());
    }
}
