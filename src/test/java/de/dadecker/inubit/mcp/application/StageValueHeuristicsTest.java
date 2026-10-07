package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T018 (feature 005, US5, research D-5): which changed property looks stage-specific — its
 * name or one of its values looks like a host, URL, port or login.
 */
class StageValueHeuristicsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "EndpointUrl|a|b|true",
        "Host|a|b|true",
        "ftp.port|1|2|true",
        "Login|a|b|true",
        "UserName|a|b|true",
        "server|a|b|true",
        "Note|https://dev.example.test/x|https://int.example.test/x|true",
        "Note|inubit-dev.example.test|x|true",
        "Note|x|inubit-int.example.test:8443|true",
        "Note|192.0.2.10|x|true",
        "Note|a|b|false",
        "xslt.stylesheet|<a/>|<b/>|false",
        "Note|1.0|2.0|false",
        "Note|${secret:a}|${secret:b}|false"})
    void looksStageSpecific(String name, String release, String target, boolean expected) {
        assertThat(StageValueHeuristics.looksStageSpecific(name, release, target))
            .isEqualTo(expected);
    }
}
