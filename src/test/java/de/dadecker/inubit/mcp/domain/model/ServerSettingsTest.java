package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerSettingsTest {

    private static final NodeId NODE = NodeId.of("prod", "inubit-1");

    @ParameterizedTest
    @ValueSource(strings = {"license", "threads", "privilegedWorkflows", "dbConnectionTolerance",
        "configuration", "serverTime"})
    void everySettingsPartIsAPart(String part) {
        assertThat(UnavailablePart.PARTS).contains(part);
        assertThat(new UnavailablePart(part, "FORBIDDEN: role System Administrator").part())
            .isEqualTo(part);
    }

    @Test
    void theSettingsPartsAreNamedAsInTheContract() {
        assertThat(List.of(UnavailablePart.LICENSE, UnavailablePart.THREADS,
            UnavailablePart.PRIVILEGED_WORKFLOWS, UnavailablePart.DB_CONNECTION_TOLERANCE,
            UnavailablePart.CONFIGURATION, UnavailablePart.SERVER_TIME))
            .containsExactly("license", "threads", "privilegedWorkflows", "dbConnectionTolerance",
                "configuration", "serverTime");
    }

    @Test
    void atMost200PrivilegedWorkflowNamesAreKept() {
        List<String> names = IntStream.range(0, 201).mapToObj(i -> "wf" + i).toList();

        PrivilegedWorkflows cut = PrivilegedWorkflows.of(20, names);

        assertThat(cut.workflows()).hasSize(PrivilegedWorkflows.MAX_WORKFLOWS)
            .isEqualTo(names.subList(0, 200));
        assertThat(cut.truncated()).isTrue();
    }

    @Test
    void exactly200PrivilegedWorkflowNamesAreNotTruncated() {
        List<String> names = IntStream.range(0, 200).mapToObj(i -> "wf" + i).toList();

        PrivilegedWorkflows all = PrivilegedWorkflows.of(20, names);

        assertThat(all.workflows()).isEqualTo(names);
        assertThat(all.truncated()).isFalse();
    }

    @Test
    void moreThan200PrivilegedWorkflowNamesAreRejected() {
        List<String> names = IntStream.range(0, 201).mapToObj(i -> "wf" + i).toList();

        assertThatIllegalArgumentException()
            .isThrownBy(() -> new PrivilegedWorkflows(20, names, true));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void aPercentageOutside0To100IsRejected(int percentage) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> PrivilegedWorkflows.of(percentage, List.of()));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new DbConnectionTolerance(3, percentage, 180));
    }

    @Test
    void negativeToleranceFiguresAreRejected() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new DbConnectionTolerance(-1, 1, 180));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new DbConnectionTolerance(3, 1, -1));
    }

    @Test
    void theVendorToleranceExampleIsAccepted() {
        DbConnectionTolerance tolerance = new DbConnectionTolerance(3, 1, 180);

        assertThat(tolerance.dropRatePercent()).isEqualTo(1);
        assertThat(tolerance.dropIntervalSeconds()).isEqualTo(180);
    }

    @Test
    void aSectionListHasNoValues() {
        ConfigurationView view = ConfigurationView.sections(List.of("Server", "Mail"));

        assertThat(view.sections()).containsExactly("Server", "Mail");
        assertThat(view.section()).isEmpty();
        assertThat(view.values()).isEmpty();
        assertThat(view.maskedCount()).isZero();
        assertThat(view.truncated()).isFalse();
    }

    @Test
    void aConfigurationWithoutSectionsIsAnEmptyList() {
        assertThat(ConfigurationView.sections(List.of()).sections()).isEmpty();
    }

    @Test
    void aSectionCountsItsMaskedValues() {
        ConfigurationView view = ConfigurationView.section("Mail", List.of(
            ConfigurationView.Leaf.shown("/smtp/host", "mail.example.org"),
            ConfigurationView.Leaf.masked("/smtp/password")), true);

        assertThat(view.sections()).isEmpty();
        assertThat(view.section()).contains("Mail");
        assertThat(view.values()).extracting(ConfigurationView.Leaf::value)
            .containsExactly(Optional.of("mail.example.org"), Optional.empty());
        assertThat(view.values()).extracting(ConfigurationView.Leaf::masked)
            .containsExactly(false, true);
        assertThat(view.maskedCount()).isEqualTo(1);
        assertThat(view.truncated()).isTrue();
    }

    @Test
    void aWrongMaskedCountIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ConfigurationView(List.of(),
            Optional.of("Mail"), List.of(ConfigurationView.Leaf.masked("/smtp/password")), 0,
            false));
    }

    @Test
    void aViewIsEitherASectionListOrOneSection() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ConfigurationView(
            List.of("Mail"), Optional.of("Mail"), List.of(), 0, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConfigurationView(
            List.of("Mail"), Optional.empty(), List.of(ConfigurationView.Leaf.shown("/a", "b")),
            0, false));
    }

    @Test
    void licensedThreadsCanBeKnownWithoutTheLicenseResponse() {
        LicenseFacts fromMetrics = new LicenseFacts(Optional.empty(), Optional.of(8L),
            Optional.empty());

        assertThat(fromMetrics.licensedThreads()).contains(8L);
        assertThat(fromMetrics.expiryDate()).isEmpty();
    }

    @Test
    void settingsCarryTheirSourcesAndUnavailableParts() {
        ServerSettings settings = new ServerSettings(NODE,
            Optional.of(new LicenseFacts(Optional.of(LocalDate.of(2027, 3, 31)),
                Optional.of(8L), Optional.of(Masked.MASK_TOKEN))),
            Optional.of(12L), Optional.empty(), Optional.of(new DbConnectionTolerance(3, 1, 180)),
            Optional.of(5000L), Optional.of("8.1.12"), Optional.of(42.5), Optional.of(false),
            Optional.empty(), Optional.empty(),
            Map.of("licensedThreads", Channel.REST, "workflowThreads", Channel.CLI),
            List.of(new UnavailablePart(UnavailablePart.PRIVILEGED_WORKFLOWS,
                    "FORBIDDEN: role System Administrator"),
                new UnavailablePart(UnavailablePart.SERVER_TIME, "TIMEOUT: too slow")));

        assertThat(settings.sources()).containsEntry("licensedThreads", Channel.REST)
            .containsEntry("workflowThreads", Channel.CLI);
        assertThat(settings.unavailable()).extracting(UnavailablePart::part)
            .containsExactly("privilegedWorkflows", "serverTime");
        assertThat(settings.privilegedWorkflows()).isEmpty();
    }

    @Test
    void absentOptionalsBecomeEmpty() {
        ServerSettings settings = new ServerSettings(NODE, null, null, null, null, null, null,
            null, null, null, null, Map.of(), List.of());

        assertThat(settings.license()).isEmpty();
        assertThat(settings.workflowThreads()).isEmpty();
        assertThat(settings.configuration()).isEmpty();
        assertThat(settings.serverTime()).isEmpty();
    }
}
