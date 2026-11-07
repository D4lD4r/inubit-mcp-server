package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One configured server as listed by {@code list_nodes} (data-model.md → NodeSummary, FR-003).
 * Deliberately without URLs, usernames or credentials.
 *
 * @param writeEnabled       effective: {@code write.enabled && (!production || productionOptIn)};
 *                           governs restart and kill only
 * @param developmentEnabled a development stage: {@code development.enabled && !production};
 *                           governs the development tools (import, restore, set_active, tag)
 *                           (0.4.2)
 * @param confirmationMode   {@code SERVER} or {@code CLIENT} (of {@code write})
 * @param repositoryWrite    the effective {@code development.repository} is {@code WRITE} on a
 *                           development stage: the repository file tools may write (008, D-13)
 * @param versionLine        as configured: {@code AUTO}, {@code V6_1}, {@code V8_1},
 *                           {@code V9_X} or a full version such as {@code 8.1.17}
 *                           ({@code VersionSetting#text()})
 * @param cliAvailable       CLI home configured and {@code startcli} script found; in
 *                           {@code list_nodes} of the client chosen for the node's version as
 *                           of the call (feature 017, research D-7)
 * @param startCli           feature 017 (FR-015, research D-18): the StartCLI choice as of the
 *                           call, added by {@code list_nodes}; empty in the startup summary
 * @param version            feature 009 (FR-004, research D-9): the version block, added at call
 *                           time by {@code list_nodes}; empty in the startup summary
 */
public record NodeSummary(
    NodeId id,
    GroupId group,
    String node,
    boolean production,
    boolean writeEnabled,
    boolean developmentEnabled,
    String confirmationMode,
    boolean repositoryWrite,
    String versionLine,
    boolean cliAvailable,
    Optional<StartCli> startCli,
    Optional<Version> version) {

    /**
     * Which StartCLI client, JDK source and address the node uses at its version as of the call
     * (feature 017, FR-015, research D-18): a local directory and kinds only, never a URL or a
     * credential.
     *
     * @param clientDirectory the chosen client directory; empty when {@code source} is
     *                        {@code none}
     * @param source          {@code node}, {@code group}, {@code version_entry},
     *                        {@code defaults} or {@code none}
     * @param versionEntry    the {@code defaults.cliClients} key; only with
     *                        {@code version_entry}
     * @param address         {@code servlet_path}, {@code base_url} or {@code configured}
     *                        (an explicit {@code cli.url}, which is never shown)
     * @param finalChoice     {@code false} while the version of an {@code AUTO} node is unknown:
     *                        the choice may change at runtime; JSON {@code final}
     */
    public record StartCli(Optional<String> clientDirectory, String source,
        Optional<String> versionEntry, String address, boolean finalChoice) {

        private static final Set<String> SOURCES =
            Set.of("node", "group", "version_entry", "defaults", "none");
        private static final Set<String> ADDRESSES =
            Set.of("servlet_path", "base_url", "configured");

        public StartCli {
            Objects.requireNonNull(clientDirectory, "clientDirectory");
            Objects.requireNonNull(versionEntry, "versionEntry");
            if (!SOURCES.contains(source) || !ADDRESSES.contains(address)) {
                throw new IllegalArgumentException("Unknown source " + source + " or address "
                    + address);
            }
            if (clientDirectory.isPresent() == source.equals("none")
                || versionEntry.isPresent() != source.equals("version_entry")) {
                throw new IllegalArgumentException("clientDirectory and versionEntry do not"
                    + " match the source " + source);
            }
        }

        /** No client directory at the servlet address, as without any client home. */
        public static StartCli none(boolean finalChoice) {
            return new StartCli(Optional.empty(), "none", Optional.empty(), "servlet_path",
                finalChoice);
        }
    }

    /**
     * What is known about the node's INUBIT version, its StartCLI client and the capabilities
     * usable on it (data-model.md → NodeSummary, contracts/mcp-tools-delta.md).
     *
     * @param setting      the configured {@code versionLine} text
     * @param access       feature 012 (research D-13): {@code rest_and_cli}, or {@code cli_only}
     *                     for a node pinned to a CLI-only version ({@link NodeAccess})
     * @param status       {@code supported}, {@code partially_supported}, {@code unsupported} or
     *                     {@code unknown}
     * @param warnings     e.g. a pin contradicted by {@code get_health}; empty (omitted) if none
     */
    public record Version(String setting, String access, Server server, Client client,
        String status, Capabilities capabilities, Optional<List<String>> warnings) {

        public Version {
            Objects.requireNonNull(setting, "setting");
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(capabilities, "capabilities");
            warnings = warnings == null ? Optional.empty()
                : warnings.filter(list -> !list.isEmpty()).map(List::copyOf);
        }
    }

    /**
     * The server version: {@code version} with {@code source} ({@code detected},
     * {@code observed}, {@code pinned}) and {@code at} (absent for a pin), or only {@code state}
     * ({@code not_yet_detected}, or {@code unknown} with the error code and time of the failed
     * detection in {@code reason} and {@code at}).
     */
    public record Server(Optional<String> version, Optional<String> source,
        Optional<String> state, Optional<String> reason, Optional<Instant> at) {

        public static final Server NOT_YET_DETECTED = new Server(Optional.empty(),
            Optional.empty(), Optional.of("not_yet_detected"), Optional.empty(), Optional.empty());

        public Server {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(at, "at");
        }

        public static Server known(String version, String source, Optional<Instant> at) {
            return new Server(Optional.of(version), Optional.of(source), Optional.empty(),
                Optional.empty(), at);
        }

        public static Server unknown(String reason, Instant at) {
            return new Server(Optional.empty(), Optional.empty(), Optional.of("unknown"),
                Optional.of(reason), Optional.of(at));
        }
    }

    /**
     * The StartCLI client as in {@code get_health}: {@code version}, the declared range and the
     * {@code comparison} ({@code match}, {@code mismatch}, {@code not_comparable}) with the
     * server version, or only {@code state}: {@code not_available} (no StartCLI installation),
     * {@code not_yet_detected} (not probed yet, or the probe timed out: {@code reason}
     * {@code TIMEOUT} and {@code at}) or {@code unknown} (the probe failed: error code and time).
     */
    public record Client(Optional<String> version, Optional<String> supportedFrom,
        Optional<String> supportedTo, Optional<String> comparison, Optional<String> state,
        Optional<String> reason, Optional<Instant> at) {

        public Client {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(supportedFrom, "supportedFrom");
            Objects.requireNonNull(supportedTo, "supportedTo");
            Objects.requireNonNull(comparison, "comparison");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(at, "at");
        }

        /** Only a state, e.g. {@code not_available}, with the failure if there was one. */
        public static Client state(String state, Optional<String> reason, Optional<Instant> at) {
            return new Client(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(state), reason, at);
        }

        /** A probed client compared with the server version. */
        public static Client of(ClientVersion client, ClientVersion.Comparison comparison) {
            return new Client(Optional.of(client.version().raw()),
                client.supportedFrom().map(InubitVersion::raw),
                client.supportedTo().map(InubitVersion::raw),
                Optional.of(comparison.name().toLowerCase(Locale.ROOT)),
                Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** The capability names whose tools run on the node, and those that do not with why. */
    public record Capabilities(List<String> usable, List<NotUsable> notUsable) {
        public Capabilities {
            usable = List.copyOf(usable);
            notUsable = List.copyOf(notUsable);
        }
    }

    public record NotUsable(String capability, String reason) {
        public NotUsable {
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public NodeSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(confirmationMode, "confirmationMode");
        Objects.requireNonNull(versionLine, "versionLine");
        Objects.requireNonNull(startCli, "startCli");
        Objects.requireNonNull(version, "version");
        if (!id.group().equals(group) || !id.name().equals(node)) {
            throw new IllegalArgumentException("id " + id + " does not match " + group + "/"
                + node);
        }
    }

    /** This summary with {@code cliAvailable} as of now (feature 017, research D-7). */
    public NodeSummary withCliAvailable(boolean value) {
        return new NodeSummary(id, group, node, production, writeEnabled, developmentEnabled,
            confirmationMode, repositoryWrite, versionLine, value, startCli, version);
    }

    /** This summary with {@code value} as its StartCLI choice (feature 017, research D-18). */
    public NodeSummary withStartCli(StartCli value) {
        return new NodeSummary(id, group, node, production, writeEnabled, developmentEnabled,
            confirmationMode, repositoryWrite, versionLine, cliAvailable, Optional.of(value),
            version);
    }

    /** This summary with {@code value} as its version block. */
    public NodeSummary withVersion(Version value) {
        return new NodeSummary(id, group, node, production, writeEnabled, developmentEnabled,
            confirmationMode, repositoryWrite, versionLine, cliAvailable, startCli,
            Optional.of(value));
    }
}
