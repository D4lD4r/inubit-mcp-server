package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.UserReadService.UserEntry;
import de.dadecker.inubit.mcp.application.UserReadService.ViewResult;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.PersonalField;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Block;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Finger;
import de.dadecker.inubit.mcp.domain.model.UserFacts.UserGroups;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.mcp.SchemaResources;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 T035: {@code schemas/read_users.output.json} against the serialized results of the
 * three views of {@code read_users}, and the shapes it rejects: an e-mail or other personal field
 * in a listing (FR-011, AS5), an unknown source, channel or unavailable part, a result with both
 * a page and a user or with the page of another view, and a personal field that is neither shown
 * nor masked.
 */
class ReadUsersOutputSchemaTest {

    private static final NodeId NODE = NodeId.parse("dev/node1");
    private static final ToolError UNREADABLE = ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
        "StartCLI: the answer is unknown", "cause", "step");

    @Test
    void everyViewMatchesTheSchema() {
        assertThat(errors(users())).isNull();
        assertThat(errors(user())).isNull();
        assertThat(errors(logins())).isNull();
    }

    @Test
    void whatCouldNotBeReadMatchesTheSchema() {
        Map<String, Object> users = json(ReadUsersTool.Result.users(new ViewResult<>(NODE,
            page(new UserEntry("a", new Block.Unavailable<>(UNREADABLE),
                new Block.Unavailable<>(UNREADABLE))),
            Map.of("logins", Channel.REST), List.of())));
        Map<String, Object> user = json(ReadUsersTool.Result.user(new ViewResult<>(NODE,
            new UserFacts("a", new Block.Unavailable<>(UNREADABLE),
                new Block.Unavailable<>(UNREADABLE)),
            Map.of("logins", Channel.REST), List.of())));

        assertThat(errors(users)).isNull();
        assertThat(errors(user)).isNull();
    }

    @Test
    void aListedUserWithAPersonalFieldIsRejected() {
        for (String field : List.of("email", "personal", "superGroup", "firstName")) {
            Map<String, Object> users = users();
            listed(users).put(field, "x");

            assertThat(errors(users)).as(field).isNotNull();
        }
    }

    @Test
    void anUnknownSourceChannelOrPartIsRejected() {
        Map<String, Object> unknownFact = user();
        unknownFact.put("sources", Map.of("accountState", "CLI"));
        Map<String, Object> unknownChannel = user();
        unknownChannel.put("sources", Map.of("logins", "SOAP"));
        Map<String, Object> listedEmailPart = users();
        listed(listedEmailPart).put("unavailable",
            List.of(Map.of("part", "email", "reason", "TIMEOUT: x")));
        Map<String, Object> healthPart = user();
        detail(healthPart).put("unavailable",
            List.of(Map.of("part", "license", "reason", "TIMEOUT: x")));

        assertThat(errors(unknownFact)).isNotNull();
        assertThat(errors(unknownChannel)).isNotNull();
        assertThat(errors(listedEmailPart)).as("a listing has no e-mail to miss").isNotNull();
        assertThat(errors(healthPart)).isNotNull();
    }

    @Test
    void aResultHasThePageOrTheUserOfItsView() {
        Map<String, Object> both = users();
        both.put("user", user().get("user"));
        Map<String, Object> neither = users();
        neither.remove("page");
        Map<String, Object> loginsAsUsers = logins();
        loginsAsUsers.put("view", "users");
        Map<String, Object> messages = logins();
        messages.put("view", "messages");

        assertThat(errors(both)).isNotNull();
        assertThat(errors(neither)).isNotNull();
        assertThat(errors(loginsAsUsers)).as("a login entry is no listed user").isNotNull();
        assertThat(errors(messages)).isNotNull();
    }

    @Test
    void aPersonalFieldIsShownOrMasked() {
        for (Map<String, Object> field : List.of(Map.<String, Object>of("name", "Name"),
            Map.<String, Object>of("name", "Name", "masked", false),
            Map.<String, Object>of("name", "Name", "value", "Doe", "masked", true))) {
            Map<String, Object> user = user();
            detail(user).put("personal", List.of(field));

            assertThat(errors(user)).as(field.toString()).isNotNull();
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private static Map<String, Object> users() {
        return json(ReadUsersTool.Result.users(new ViewResult<>(NODE,
            page(new UserEntry("a",
                new Block.Parsed<>(new UserGroups("Group-a", "Super", List.of("Extra-a"))),
                new Block.Parsed<>(List.of("Role-a")))),
            Map.of("logins", Channel.REST, "groups", Channel.CLI, "roles", Channel.CLI),
            List.of("a warning"))));
    }

    private static Map<String, Object> user() {
        return json(ReadUsersTool.Result.user(new ViewResult<>(NODE, new UserFacts("a",
            new Block.Parsed<>(new UserGroups("Group-a", "Super", List.of("Extra-a"))),
            new Block.Parsed<>(new Finger("a", List.of("Role-a"), Optional.of("a@example.test"),
                List.of(PersonalField.shown("Name", "Doe"), PersonalField.masked("Token"))))),
            Map.of("logins", Channel.REST, "groups", Channel.CLI, "roles", Channel.CLI,
                "email", Channel.CLI, "personal", Channel.CLI), List.of())));
    }

    private static Map<String, Object> logins() {
        return json(ReadUsersTool.Result.logins(new ViewResult<>(NODE,
            page(new LoginEntry("a", Optional.empty()), new LoginEntry("b", Optional.of("09:00"))),
            Map.of("loggedIn", Channel.CLI), List.of())));
    }

    @SafeVarargs
    private static <T> Page<T> page(T... items) {
        return new Page<>(List.of(items), 0, 25, OptionalLong.of(items.length), false, false,
            OptionalInt.empty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> listed(Map<String, Object> users) {
        return ((List<Map<String, Object>>) ((Map<String, Object>) users.get("page"))
            .get("items")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(Map<String, Object> user) {
        return (Map<String, Object>) user.get("user");
    }

    /** The JSON object the tool returns for {@code result}, as a mutable map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(ReadUsersTool.Result result) {
        return ResultJson.mapper().convertValue(result, Map.class);
    }

    /** The validation errors of {@code result}, or {@code null} if it is valid. */
    @SuppressWarnings("unchecked")
    private static String errors(Map<String, Object> result) {
        Map<String, Object> schema = ResultJson.mapper().readValue(SchemaResources.forClasspath()
            .load("schemas/read_users.output.json"), Map.class);
        JsonSchemaValidator.ValidationResponse validation = new DefaultJsonSchemaValidator()
            .validate(schema, result);
        return validation.valid() ? null : validation.errorMessage();
    }
}
