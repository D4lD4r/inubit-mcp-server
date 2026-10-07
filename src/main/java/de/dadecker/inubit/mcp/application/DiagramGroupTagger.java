package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sets a tag on whole diagram groups and verifies it (feature 004, FR-021, research D-16,
 * D-26) — the step {@code tag_artifacts} and a tagged {@code import_artifacts} share. INUBIT tags
 * only whole diagram groups: one {@code tag --tagMove} per group moves the tag to the current
 * versions of the group's technical workflows and their modules and leaves the same tag on every
 * other artifact untouched. The verification reads the history of the requested groups only
 * (never owner-wide): the current version of every technical workflow of those groups and of
 * every module they use must carry the tag. Nothing is ever removed — StartCLI removes a tag only
 * for the whole owner, which could take away a tag marking another group's state.
 */
final class DiagramGroupTagger {

    private static final Logger LOG = LoggerFactory.getLogger(DiagramGroupTagger.class);
    private static final String TECHNICAL = "technical";
    private static final String REPORTS = ".reports";

    /**
     * What the tagging did.
     *
     * @param applied   every tag command ran and the verification found the tag on every
     *                  current version of the requested groups
     * @param workflows the technical workflows whose current version carries the tag (0 if the
     *                  verification did not run)
     * @param modules   the modules whose current version carries the tag
     * @param failure   {@code IMPORT_FAILED} at step {@code tag} (a tag command failed) or
     *                  {@code VERIFY_MISMATCH} at step {@code verify}
     * @param reports   workspace-relative report files ({@code .reports/tag-<auditId>.txt})
     * @param tagged    the groups whose tag command succeeded
     */
    record Result(boolean applied, int workflows, int modules,
        Optional<WriteOutcome.Failure> failure, List<String> reports, List<String> tagged) {

        Result {
            failure = failure == null ? Optional.empty() : failure;
            reports = List.copyOf(reports);
            tagged = List.copyOf(tagged);
        }
    }

    private final Path root;

    /** @param root the workspace root (reports) */
    DiagramGroupTagger(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * Tags {@code groups} of {@code owner} with {@code tag} and verifies them. Every failure of
     * the tag commands or the verification is part of the result, never thrown.
     */
    Result tag(NodeId node, TagPort port, String owner, List<String> groups, String tag,
        UUID auditId) {
        List<String> tagged = new ArrayList<>();
        Verification verified = new Verification();
        String step = "tag";
        try {
            for (String group : groups) {
                port.tag(tag, group, owner);
                tagged.add(group);
            }
            step = "verify";
            for (String group : groups) {
                verified.add(port.history(owner, group), group, tag);
            }
            if (verified.problems.isEmpty()) {
                return new Result(true, verified.workflows(), verified.modules(),
                    Optional.empty(), List.of(), tagged);
            }
            String report = report("tag-" + auditId + ".txt", verified.problems);
            return new Result(false, verified.workflows(), verified.modules(), Optional.of(
                new WriteOutcome.Failure(ErrorCode.VERIFY_MISMATCH, "verify",
                    "The current versions of " + cut(new TreeSet<>(verified.problems.stream()
                        .map(p -> p.substring(0, p.indexOf(':'))).toList())) + " do not carry"
                        + " the tag " + tag + "; see " + report)), List.of(report), tagged);
        } catch (ToolErrorException e) {
            return new Result(false, verified.workflows(), verified.modules(), Optional.of(
                new WriteOutcome.Failure(step.equals("tag") ? ErrorCode.IMPORT_FAILED
                    : ErrorCode.VERIFY_MISMATCH, step, e.error().code() + ": "
                    + e.error().message())), List.of(), tagged);
        } catch (RuntimeException e) {
            LOG.error("Tagging on {} failed unexpectedly at {}", node, step, e);
            return new Result(false, verified.workflows(), verified.modules(), Optional.of(
                new WriteOutcome.Failure(ErrorCode.IMPORT_FAILED, step, "INTERNAL: an"
                    + " unexpected failure (" + e.getClass().getSimpleName() + ") after the tag"
                    + " was sent")), List.of(), tagged);
        }
    }

    /** The warning of a failed tagging: what is tagged already, nothing removed, retry. */
    static String retry(String tag, Result result) {
        String before = result.failure().map(WriteOutcome.Failure::step).filter("tag"::equals)
            .isPresent() && !result.tagged().isEmpty() ? "The tag " + tag + " was set for "
                + String.join(", ", result.tagged()) + " before the failure. " : "";
        return before + "Nothing was removed; fix the cause and call tag_artifacts again with the"
            + " same groups and the tag " + tag + " (the tag name is reused and moves to the"
            + " current versions)";
    }

    /** The technical workflows of {@code group} in {@code history}, by name. */
    static Map<String, TagPort.Diagram> technicalWorkflows(TagPort.History history,
        String group) {
        Map<String, TagPort.Diagram> workflows = new TreeMap<>();
        history.diagrams().forEach((name, diagram) -> {
            if (diagram.type().equals(TECHNICAL) && diagram.diagramGroup().equals(group)) {
                workflows.put(name, diagram);
            }
        });
        return workflows;
    }

    private String report(String name, List<String> lines) {
        Path file = root.resolve(REPORTS).resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return REPORTS + "/" + name;
    }

    private static String cut(Set<String> names) {
        List<String> list = List.copyOf(names);
        return list.size() <= 10 ? String.join(", ", list)
            : String.join(", ", list.subList(0, 10)) + ", …+" + (list.size() - 10);
    }

    /**
     * The verification of research D-26: the current version of every technical workflow of
     * the requested groups and of every module they use carries the tag. Other versions and
     * other groups are not looked at (a tag there is left alone).
     */
    private static final class Verification {

        final Map<String, Boolean> workflows = new TreeMap<>();
        final Map<String, Boolean> modules = new TreeMap<>();
        final List<String> problems = new ArrayList<>();

        void add(TagPort.History history, String group, String tag) {
            technicalWorkflows(history, group).forEach((name, diagram) -> {
                boolean carries = carriesOnHead(diagram.versions(), tag);
                workflows.put(name, carries);
                if (!carries) {
                    problems.add(name + ": the current version of the workflow (diagram group "
                        + group + ") does not carry the tag");
                }
            });
            history.modules().forEach((name, versions) -> {
                boolean carries = carriesOnHead(versions, tag);
                if (modules.put(name, carries) == null && !carries) {
                    problems.add(name + ": the current version of the module (used in diagram"
                        + " group " + group + ") does not carry the tag");
                }
            });
        }

        int workflows() {
            return (int) workflows.values().stream().filter(Boolean::booleanValue).count();
        }

        int modules() {
            return (int) modules.values().stream().filter(Boolean::booleanValue).count();
        }

        private static boolean carriesOnHead(List<VersionEntry> versions, String tag) {
            return !versions.isEmpty() && versions.get(0).tags().contains(tag);
        }
    }
}
