package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T030 (feature 022, US2): the user list of {@code GET /user/users?type=processEngineUser}, against
 * the recording {@code admin-users.xml} of an 8.1.17 server (T028, 58 users, each with a planted
 * {@code *.example.test} e-mail and 26 with first and last name) and documents in its layout.
 */
class UserListXmlParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    private static List<ListedUser> parse(String xml) {
        return UserListXmlParser.parse(DEV, xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theRecordedListGivesEveryLoginWithItsTypeInDocumentOrder() {
        List<ListedUser> users = UserListXmlParser.parse(DEV, RestFixtures.bytes("admin-users.xml"));

        assertThat(users).hasSize(58);
        assertThat(users.getFirst()).isEqualTo(new ListedUser("User01", "ProcessEngineUser"));
        assertThat(users).extracting(ListedUser::type).containsOnly("ProcessEngineUser");
        assertThat(users).extracting(ListedUser::login).doesNotHaveDuplicates()
            .contains("jdoe", "batch-user", "qa_robot")
            .isNotEqualTo(users.stream().map(ListedUser::login).sorted().toList());
    }

    @Test
    void noPersonalFieldOfTheRecordingReachesTheParsedObjects() {
        String recording = new String(RestFixtures.bytes("admin-users.xml"),
            StandardCharsets.UTF_8);
        assertThat(recording).contains("@corp.example.test", "@partner.example.test",
            "firstName=\"First01\"", "lastName=\"Last01\"");

        List<ListedUser> users = UserListXmlParser.parse(DEV, recording.getBytes(
            StandardCharsets.UTF_8));

        assertThat(users.toString()).doesNotContain("@", "example.test", "First", "Last");
        assertThat(users).allSatisfy(user -> assertThat(user.toString())
            .doesNotContain("@", "example.test", "First", "Last"));
    }

    @Test
    void unknownAttributesAndChildElementsAreNotRead() {
        List<ListedUser> users = parse("<UserList xmlns='inubit.com/ibis/external/user'>"
            + "<User id='u1' type='ProcessEngineUser' email='planted-0301@users.example.test'"
            + " firstName='Planted0302' lastName='Planted0303' phone='planted-0304'>"
            + "<Comment>planted-0305</Comment></User>"
            + "<Unknown id='planted-0306' type='ProcessEngineUser'/></UserList>");

        assertThat(users).containsExactly(new ListedUser("u1", "ProcessEngineUser"));
        assertThat(users.toString()).doesNotContain("planted", "Planted", "@");
    }

    @Test
    void theFeature004FormWithTheNs2PrefixReadsTheSame() {
        List<ListedUser> users = parse("<ns2:UserList xmlns:ns2='inubit.com/ibis/external/user'>"
            + "<ns2:User id='b-user' type='ProcessEngineUser' email='b@users.example.test'/>"
            + "<ns2:User id='a_user' type='ProcessEngineUser'/></ns2:UserList>");

        assertThat(users).containsExactly(new ListedUser("b-user", "ProcessEngineUser"),
            new ListedUser("a_user", "ProcessEngineUser"));
    }

    @Test
    void aMissingTypeIsEmptyAndAnEmptyListIsEmpty() {
        assertThat(parse("<UserList><User id='u1'/></UserList>"))
            .containsExactly(new ListedUser("u1", ""));
        assertThat(parse("<UserList/>")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<UserList><User email='planted-0311@users.example.test'/></UserList>",
        "<UserList><User id=' ' email='planted-0311@users.example.test'/></UserList>",
        "<ModelList><Model name='planted-0311'/></ModelList>",
        "<UserList><User id='planted-0311'></UserList>",
        "planted-0311 is no XML"})
    void anUnexpectedDocumentIsUnexpectedResponseWithoutEchoingIt(String xml) {
        assertThatThrownBy(() -> parse(xml))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().toString()).doesNotContain("planted-0311", "example.test");
            });
    }

    @Test
    void aDtdIsRefusedWithoutResolvingOrEchoingIt() {
        String xml = "<?xml version='1.0'?><!DOCTYPE UserList [<!ENTITY s"
            + " 'synthetic-secret-0312'>]><UserList><User id='&s;' type='ProcessEngineUser'/>"
            + "</UserList>";

        assertThatThrownBy(() -> parse(xml))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().toString()).doesNotContain("synthetic-secret-0312");
            });
    }

    @Test
    void anExternalEntityIsRefused() {
        String xml = "<?xml version='1.0'?><!DOCTYPE UserList [<!ENTITY x SYSTEM"
            + " 'file:///etc/passwd'>]><UserList><User id='&x;'/></UserList>";

        assertThatThrownBy(() -> parse(xml))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }
}
