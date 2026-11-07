package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.UpdateCache;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import de.dadecker.inubit.mcp.domain.model.ServerUpdate;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class UpdateNoticeTest {

    private static final Instant T0 = Instant.parse("2026-11-06T12:00:00Z");
    private static final CallToolResult OK = new CallToolResult(
        List.of(new TextContent("{\"a\":1}")), false, Map.of("a", 1), null);
    private static final CallToolResult ERROR = new CallToolResult(
        List.of(new TextContent("{\"code\":\"X\"}")), true, null, null);

    private Optional<ServerUpdate> status = Optional.empty();
    private final MemoryCache cache = new MemoryCache();
    private final List<Runnable> dialogs = new ArrayList<>();
    private final UpdateNotice notice = new UpdateNotice(() -> status, cache, dialogs::add);

    @Test
    void withoutAKnownNewerReleaseResultsStayUnchanged() {
        assertThat(notice.decorate(OK, client(r -> null))).isSameAs(OK);
        status = update("0.7.1", "0.7.1");
        assertThat(notice.decorate(OK, client(r -> null))).isSameAs(OK);
        assertThat(notice.instructionsSentence()).isEmpty();
        assertThat(dialogs).isEmpty();
    }

    @Test
    void theFirstSuccessfulResultGetsTheNoteOnce() {
        status = update("0.7.1", "0.8.0");

        assertThat(notice.decorate(ERROR, null)).as("errors carry no note").isSameAs(ERROR);
        CallToolResult first = notice.decorate(OK, null);
        assertThat(first.content()).hasSize(2);
        assertThat(first.content().get(0)).isEqualTo(OK.content().get(0));
        assertThat(((TextContent) first.content().get(1)).text()).isEqualTo("Note:"
            + " inubit-mcp-server 0.8.0 is available (installed 0.7.1), see"
            + " https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.8.0. Update manually;"
            + " see get_health.serverUpdate.");
        assertThat(first.structuredContent()).isEqualTo(OK.structuredContent());
        assertThat(first.isError()).isFalse();
        assertThat(notice.decorate(OK, null)).isSameAs(OK);

        status = update("0.7.1", "0.9.0");
        assertThat(notice.decorate(OK, null).content()).hasSize(2);
        assertThat(notice.decorate(OK, null)).isSameAs(OK);
        status = update("0.7.1", "0.8.0");
        assertThat(notice.decorate(OK, null)).as("not for an older one").isSameAs(OK);
    }

    @Test
    void theInstructionsSentenceNamesTheKnownRelease() {
        status = update("0.7.1", "0.8.0");

        assertThat(notice.instructionsSentence()).contains("A newer inubit-mcp-server 0.8.0 is"
            + " available (installed 0.7.1):"
            + " https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.8.0; update manually.");
    }

    @Test
    void aClientWithElicitationIsAskedOnceInTheBackground() {
        status = update("0.7.1", "0.8.0");
        List<ElicitRequest> requests = new ArrayList<>();
        UpdateNotice.Client client = client(request -> {
            requests.add(request);
            return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("doNotRemindAgain", false));
        });

        notice.decorate(OK, client);
        notice.decorate(OK, client);
        notice.decorate(ERROR, client);

        assertThat(requests).as("not asked inside the tool call").isEmpty();
        assertThat(dialogs).hasSize(1);
        dialogs.get(0).run();
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.message()).isEqualTo("inubit-mcp-server 0.8.0 is available"
                + " (installed 0.7.1): https://github.com/D4lD4r/inubit-mcp-server/releases/tag/"
                + "v0.8.0. The update is manual: download the JAR and its .sha256 file, check the"
                + " checksum, replace the JAR, restart the client.");
            assertThat(request.mode()).isIn(null, "form");
        });
        assertThat(cache.dismissed).isEmpty();
    }

    @Test
    void doNotRemindAgainDismissesTheVersion() {
        status = update("0.7.1", "0.8.0");
        notice.decorate(OK, client(request -> new ElicitResult(ElicitResult.Action.ACCEPT,
            Map.of("doNotRemindAgain", true))));
        dialogs.get(0).run();

        assertThat(cache.dismissed).containsExactly(v("0.8.0"));
    }

    @Test
    void declineCancelOrAFailureDismissNothing() {
        List<Function<ElicitRequest, ElicitResult>> answers = List.of(
            request -> new ElicitResult(ElicitResult.Action.DECLINE, null),
            request -> new ElicitResult(ElicitResult.Action.CANCEL, null),
            request -> new ElicitResult(ElicitResult.Action.ACCEPT, null),
            request -> new ElicitResult(ElicitResult.Action.ACCEPT,
                Map.of("doNotRemindAgain", "true")),
            request -> null,
            request -> {
                throw new IllegalStateException("timeout");
            });
        for (Function<ElicitRequest, ElicitResult> answer : answers) {
            List<Runnable> queue = new ArrayList<>();
            UpdateNotice fresh = new UpdateNotice(() -> update("0.7.1", "0.8.0"), cache,
                queue::add);
            fresh.decorate(OK, client(answer));
            queue.get(0).run();
        }
        assertThat(cache.dismissed).isEmpty();
    }

    @Test
    void aDismissedVersionIsNotAskedAgainButANewerOneIs() {
        cache.dismissed.add(v("0.8.0"));
        status = update("0.7.1", "0.8.0");
        List<ElicitRequest> requests = new ArrayList<>();
        UpdateNotice.Client client = client(request -> {
            requests.add(request);
            return new ElicitResult(ElicitResult.Action.CANCEL, null);
        });

        notice.decorate(OK, client);
        dialogs.forEach(Runnable::run);
        assertThat(requests).isEmpty();

        status = update("0.7.1", "0.9.0");
        notice.decorate(OK, client);
        dialogs.get(1).run();
        assertThat(requests).hasSize(1);
    }

    @Test
    void aClientWithoutElicitationIsNotAsked() {
        status = update("0.7.1", "0.8.0");
        UpdateNotice.Client plain = new UpdateNotice.Client() {
            @Override
            public boolean supportsElicitation() {
                return false;
            }

            @Override
            public ElicitResult elicit(ElicitRequest request) {
                throw new AssertionError("not asked");
            }
        };

        assertThat(notice.decorate(OK, plain).content()).hasSize(2);
        assertThat(dialogs).isEmpty();
        assertThat(UpdateNotice.Client.of(null)).isNull();
    }

    @Test
    void noneAddsNothing() {
        assertThat(UpdateNotice.NONE.decorate(OK, client(r -> null))).isSameAs(OK);
        assertThat(UpdateNotice.NONE.instructionsSentence()).isEmpty();
    }

    @Test
    void theFormHasOneBooleanThatDefaultsToFalse() {
        assertThat(UpdateNotice.FORM).isEqualTo(Map.of("type", "object", "properties",
            Map.of("doNotRemindAgain", Map.of("type", "boolean",
                "title", "Do not remind me of this version again", "default", false))));
    }

    private static UpdateNotice.Client client(Function<ElicitRequest, ElicitResult> answer) {
        return new UpdateNotice.Client() {
            @Override
            public boolean supportsElicitation() {
                return true;
            }

            @Override
            public ElicitResult elicit(ElicitRequest request) {
                return answer.apply(request);
            }
        };
    }

    private static Optional<ServerUpdate> update(String current, String latest) {
        return Optional.of(ServerUpdate.of(v(current), v(latest), T0));
    }

    private static SemVer v(String version) {
        return SemVer.parse(version).orElseThrow();
    }

    private static final class MemoryCache implements UpdateCache {

        final List<SemVer> dismissed = new ArrayList<>();

        @Override
        public Optional<CachedRelease> read() {
            return dismissed.isEmpty() ? Optional.empty() : Optional.of(new CachedRelease(
                dismissed.getLast(), T0, Optional.of(dismissed.getLast())));
        }

        @Override
        public void write(SemVer latestVersion, Instant checkedAt) {
        }

        @Override
        public void dismiss(SemVer version) {
            dismissed.add(version);
        }
    }
}
