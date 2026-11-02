package de.dadecker.inubit.mcp.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Feature 013 T001 (FR-013, research R-12): the CI workflow builds and tests on Linux and on
 * Windows, both on Java 21, and can be started by hand.
 */
class CiWorkflowTest {

    private static final Path WORKFLOW = Path.of(".github/workflows/ci.yml");

    private static JsonNode workflow() {
        return new YAMLMapper().readTree(WORKFLOW.toFile());
    }

    private static List<JsonNode> steps(JsonNode job) {
        List<JsonNode> steps = new ArrayList<>();
        job.path("steps").forEach(steps::add);
        return steps;
    }

    private static void assertRunsVerifyOnJava21(JsonNode job) {
        assertThat(steps(job))
            .anySatisfy(step -> {
                assertThat(step.path("uses").asString()).startsWith("actions/setup-java@");
                assertThat(step.path("with").path("distribution").asString()).isEqualTo("temurin");
                assertThat(step.path("with").path("java-version").asString()).isEqualTo("21");
            })
            .anySatisfy(step -> assertThat(step.path("run").asString())
                .contains("mvn -B -ntp clean verify"));
    }

    @Test
    void theLinuxJobRunsVerifyOnJava21() {
        JsonNode build = workflow().path("jobs").path("build");

        assertThat(build.path("runs-on").asString()).isEqualTo("ubuntu-latest");
        assertRunsVerifyOnJava21(build);
    }

    @Test
    void theWindowsJobRunsVerifyOnJava21OnWindowsLatest() {
        JsonNode windows = workflow().path("jobs").path("windows");

        assertThat(windows.path("runs-on").asString()).isEqualTo("windows-latest");
        assertRunsVerifyOnJava21(windows);
    }

    @Test
    void theWorkflowCanBeStartedByHand() {
        assertThat(workflow().path("on").has("workflow_dispatch")).isTrue();
    }
}
