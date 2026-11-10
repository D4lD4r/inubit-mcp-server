package de.dadecker.inubit.mcp.application.knowledge;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One call of {@code record_knowledge} as the tool received it (feature 019, research D-10). The
 * values are not checked here: every rule is checked by {@link KnowledgeService#record} so that a
 * refusal is audited.
 *
 * @param name         the note's name, instead of {@code ref}
 * @param ref          the ref of a hit, instead of {@code name} ({@code append}, {@code replace})
 * @param title        the title; required for {@code create}, kept if absent otherwise
 * @param topics       the topics; kept if absent ({@code append}, {@code replace})
 * @param content      the body ({@code create}, {@code replace}) or the section to add
 * @param versionToken the token read last; required for {@code replace} only
 * @param client       the MCP client's {@code name} for the note's {@code source} and
 *                     {@code lastWriter} (research D-5), if sent
 * @param mcpClient    the MCP client's {@code name/version} for the audit, if sent
 */
public record Record(Optional<String> name, Optional<String> ref, Mode mode,
    Optional<String> title, Optional<List<String>> topics, String content,
    Optional<String> versionToken, Optional<String> client, Optional<String> mcpClient) {

    /** What the call does with the note. */
    public enum Mode {
        CREATE, APPEND, REPLACE;

        /** The mode as the tool names it. */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Record {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(title, "title");
        topics = Objects.requireNonNull(topics, "topics").map(List::copyOf);
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(versionToken, "versionToken");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(mcpClient, "mcpClient");
    }
}
