package de.dadecker.inubit.mcp.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RestEndpointTest {

    @Test
    void listsEveryEndpointTheServerSendsToday() {
        assertThat(Arrays.stream(RestEndpoint.values())
            .map(endpoint -> endpoint.method() + " " + endpoint.template()))
            .containsExactlyInAnyOrder(
                "GET /healthcheck",
                "GET /ready",
                "GET /system/info",
                "GET /metrics",
                "POST /log/{name}",
                "GET /model/models",
                "GET /model/modelByName/{name}",
                "GET /model/export/{name}");
    }

    @Test
    void pathWithoutParametersIsTheTemplateBelowTheRestRoot() {
        assertThat(RestEndpoint.HEALTHCHECK.path()).isEqualTo("/ibis/rest/healthcheck");
        assertThat(RestEndpoint.SYSTEM_INFO.path()).isEqualTo("/ibis/rest/system/info");
        assertThat(RestEndpoint.MODELS.method()).isEqualTo(Method.GET);
    }

    @Test
    void pathFillsTheParameterIntoTheTemplate() {
        assertThat(RestEndpoint.LOG.path("queueLog")).isEqualTo("/ibis/rest/log/queueLog");
        assertThat(RestEndpoint.LOG.method()).isEqualTo(Method.POST);
    }

    @Test
    void pathEncodesEachParameterAsOneSegment() {
        assertThat(RestEndpoint.MODEL_EXPORT.path("OWNERS BPD/Ä?x"))
            .isEqualTo("/ibis/rest/model/export/OWNERS%20BPD%2F%C3%84%3Fx");
    }

    @Test
    void tooFewParametersThrow() {
        assertThatThrownBy(() -> RestEndpoint.MODEL_BY_NAME.path())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("MODEL_BY_NAME takes 1 path parameter(s), got 0");
    }

    @Test
    void tooManyParametersThrow() {
        assertThatThrownBy(() -> RestEndpoint.READY.path("x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("READY takes 0 path parameter(s), got 1");
        assertThatThrownBy(() -> RestEndpoint.MODEL_EXPORT.path("a", "b"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("MODEL_EXPORT takes 1 path parameter(s), got 2");
    }

    @Test
    void nullParameterThrows() {
        assertThatThrownBy(() -> RestEndpoint.LOG.path((String) null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("LOG parameter 0");
    }
}
