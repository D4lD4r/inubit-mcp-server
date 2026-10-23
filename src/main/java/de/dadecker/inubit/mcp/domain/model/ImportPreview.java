package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The preview of a writing call under server-side confirmation (feature 004, research D-2,
 * contracts/mcp-tools-delta.md): nothing was sent; the call runs only when it is repeated with
 * {@code confirmationCode} before {@code expiresAt}.
 *
 * @param scope         the scope as text ({@code diagram group G} / {@code modules a, b})
 * @param modify        the artifacts the import changes, {@code existing} included
 * @param existing      new in the workspace, but on the node already: the import updates them
 *                      as a new version (0.4.2, e.g. left by a rolled-back import)
 * @param identical     new in the workspace and identical on the node: not sent (0.4.2)
 * @param checkWarnings the number of WARNING findings of the checks
 * @param tag           the tag set on the diagram group after the verified import (research
 *                      D-26)
 * @param repositoryReferences feature 008 (FR-012), with repository files to send: per file the
 *                      workspace artifacts outside the scope that use it (only files with any)
 * @param repositoryDiff feature 008 (FR-012): the report of the line differences between the
 *                      node and the workspace of every repository file in {@code modify}
 * @param warnings      feature 008, if the change set had repository files: references that
 *                      were not followed, shared use
 */
public record ImportPreview(NodeId node, String scope, String baseCommit, List<String> create,
    List<String> modify, List<String> existing, List<String> identical,
    List<String> notImported, int checkWarnings, Optional<String> tag,
    Optional<List<RepositoryReferences>> repositoryReferences, Optional<String> repositoryDiff,
    Optional<List<String>> warnings, String confirmationCode, Instant expiresAt,
    String message) {

    /**
     * The workspace artifacts outside the scope, of every owner of the group, that use one
     * repository file (research D-5): workflows as {@code <workflow> (<diagram group>)}, modules
     * as {@code <module> (<plugin type> module)}, repository files by their full path; another
     * owner's artifact with {@code owner <name>} (e.g. {@code Mapping-0007 (GRP-02, owner bob)},
     * {@code /Root/bob/y.xsd (owner bob)}).
     *
     * @param path      the full repository path of the file
     * @param artifacts at most 20 of them, sorted
     * @param truncated true if there are more (all are in the report)
     */
    public record RepositoryReferences(String path, List<String> artifacts, boolean truncated) {
        public RepositoryReferences {
            Objects.requireNonNull(path, "path");
            artifacts = List.copyOf(artifacts);
        }
    }

    public ImportPreview {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(baseCommit, "baseCommit");
        create = List.copyOf(create);
        modify = List.copyOf(modify);
        existing = List.copyOf(existing);
        identical = List.copyOf(identical);
        notImported = List.copyOf(notImported);
        tag = tag == null ? Optional.empty() : tag;
        repositoryReferences = repositoryReferences == null ? Optional.empty()
            : repositoryReferences.map(List::copyOf);
        repositoryDiff = repositoryDiff == null ? Optional.empty() : repositoryDiff;
        warnings = warnings == null ? Optional.empty() : warnings.map(List::copyOf);
        Objects.requireNonNull(confirmationCode, "confirmationCode");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(message, "message");
    }
}
