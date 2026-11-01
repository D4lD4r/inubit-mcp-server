package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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
 * @param cliAvailable       CLI home configured and {@code startcli} script found
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
    Optional<Version> version) {

    /**
     * What is known about the node's INUBIT version, its StartCLI client and the capabilities
     * usable on it (data-model.md → NodeSummary, contracts/mcp-tools-delta.md).
     *
     * @param setting      the configured {@code versionLine} text
     * @param access       feature 011 (research D-13): {@code rest_and_cli}, or {@code cli_only}
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
        Objects.requireNonNull(version, "version");
        if (!id.group().equals(group) || !id.name().equals(node)) {
            throw new IllegalArgumentException("id " + id + " does not match " + group + "/"
                + node);
        }
    }

    /** This summary with {@code value} as its version block. */
    public NodeSummary withVersion(Version value) {
        return new NodeSummary(id, group, node, production, writeEnabled, developmentEnabled,
            confirmationMode, repositoryWrite, versionLine, cliAvailable, Optional.of(value));
    }
}
