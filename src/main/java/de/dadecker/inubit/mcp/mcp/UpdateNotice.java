package de.dadecker.inubit.mcp.mcp;

import de.dadecker.inubit.mcp.application.UpdateCache;
import de.dadecker.inubit.mcp.application.UpdateCache.CachedRelease;
import de.dadecker.inubit.mcp.domain.model.SemVer;
import de.dadecker.inubit.mcp.domain.model.ServerUpdate;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the MCP client about a newer release of this server (feature 016) beyond
 * {@code get_health}: one note block in the first successful tool result per newer version
 * (FR-017), the closing sentence of the {@code instructions} (FR-018), and a background dialog
 * for clients that declare elicitation (FR-019, research R-13). With the update check off the
 * status stays empty and nothing is added.
 */
public final class UpdateNotice {

    /** Adds nothing: the update check is off. */
    public static final UpdateNotice NONE = new UpdateNotice(Optional::empty, UpdateCache.NONE,
        task -> {
            throw new RejectedExecutionException("no update dialog");
        });

    /** The form of the dialog: one boolean, default false (research R-13). */
    static final Map<String, Object> FORM = Map.of("type", "object", "properties",
        Map.of("doNotRemindAgain", Map.of("type", "boolean",
            "title", "Do not remind me of this version again", "default", false)));

    private static final Logger LOG = LoggerFactory.getLogger(UpdateNotice.class);

    private final Supplier<Optional<ServerUpdate>> status;
    private final UpdateCache cache;
    private final Executor dialogs;
    private final AtomicReference<SemVer> noted = new AtomicReference<>();
    private final Set<SemVer> asked = ConcurrentHashMap.newKeySet();

    /**
     * @param status  the last known update status ({@code UpdateStatus::current})
     * @param cache   where a dismissed version is read and stored
     * @param dialogs runs the dialogs, so that no tool call waits for one
     */
    public UpdateNotice(Supplier<Optional<ServerUpdate>> status, UpdateCache cache,
        Executor dialogs) {
        this.status = Objects.requireNonNull(status, "status");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
    }

    /** A single daemon thread for the dialogs. */
    public static ExecutorService dialogExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "inubit-mcp-update-dialog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** The closing sentence of the {@code instructions} if a newer release is known (FR-018). */
    public Optional<String> instructionsSentence() {
        return available().map(update -> "A newer inubit-mcp-server " + update.latestVersion()
            + " is available (installed " + update.currentVersion() + "): " + update.releaseUrl()
            + "; update manually.");
    }

    /**
     * {@code result} with the note block if it is the first successful one for the newest known
     * release (FR-017); starts the dialog for that release in the background (FR-019).
     */
    CallToolResult decorate(CallToolResult result, Client client) {
        if (Boolean.TRUE.equals(result.isError())) {
            return result;
        }
        Optional<ServerUpdate> update = available();
        if (update.isEmpty()) {
            return result;
        }
        SemVer latest = SemVer.parse(update.get().latestVersion()).orElseThrow();
        offerDialog(update.get(), latest, client);
        if (!markNoted(latest)) {
            return result;
        }
        List<Content> content = new ArrayList<>(result.content());
        content.add(new TextContent(note(update.get())));
        return new CallToolResult(content, result.isError(), result.structuredContent(),
            result.meta());
    }

    static String note(ServerUpdate update) {
        return "Note: inubit-mcp-server " + update.latestVersion() + " is available (installed "
            + update.currentVersion() + "), see " + update.releaseUrl()
            + ". Update manually; see get_health.serverUpdate.";
    }

    static String dialogMessage(ServerUpdate update) {
        return "inubit-mcp-server " + update.latestVersion() + " is available (installed "
            + update.currentVersion() + "): " + update.releaseUrl() + ". The update is manual:"
            + " download the JAR and its .sha256 file, check the checksum, replace the JAR,"
            + " restart the client.";
    }

    private Optional<ServerUpdate> available() {
        return status.get().filter(ServerUpdate::updateAvailable);
    }

    /** True if {@code latest} is newer than every version noted so far, and records it. */
    private boolean markNoted(SemVer latest) {
        while (true) {
            SemVer previous = noted.get();
            if (previous != null && previous.compareTo(latest) >= 0) {
                return false;
            }
            if (noted.compareAndSet(previous, latest)) {
                return true;
            }
        }
    }

    private void offerDialog(ServerUpdate update, SemVer latest, Client client) {
        if (client == null || !client.supportsElicitation() || asked.contains(latest)) {
            return;
        }
        if (!asked.add(latest)) {
            return;
        }
        try {
            dialogs.execute(() -> ask(update, latest, client));
        } catch (RejectedExecutionException e) {
            LOG.debug("Update dialog not started: {}", e.getClass().getSimpleName());
        }
    }

    private void ask(ServerUpdate update, SemVer latest, Client client) {
        try {
            boolean dismissed = cache.read().flatMap(CachedRelease::dismissedVersion)
                .filter(latest::equals).isPresent();
            if (dismissed) {
                return;
            }
            ElicitResult answer = client.elicit(ElicitRequest.builder(dialogMessage(update), FORM)
                .build());
            if (answer != null && answer.action() == ElicitResult.Action.ACCEPT
                && answer.content() != null
                && Boolean.TRUE.equals(answer.content().get("doNotRemindAgain"))) {
                cache.dismiss(latest);
            } else {
                LOG.debug("Update dialog answered: {}", answer == null ? "nothing"
                    : answer.action());
            }
        } catch (RuntimeException e) {
            LOG.debug("Update dialog failed: {}", e.getClass().getSimpleName());
        }
    }

    /** The calling client as far as the dialog needs it; adapts the SDK exchange. */
    interface Client {

        boolean supportsElicitation();

        ElicitResult elicit(ElicitRequest request);

        static Client of(McpSyncServerExchange exchange) {
            if (exchange == null) {
                return null;
            }
            return new Client() {
                @Override
                public boolean supportsElicitation() {
                    ClientCapabilities capabilities = exchange.getClientCapabilities();
                    return capabilities != null && capabilities.elicitation() != null;
                }

                @Override
                public ElicitResult elicit(ElicitRequest request) {
                    return exchange.createElicitation(request);
                }
            };
        }
    }
}
