package de.dadecker.inubit.mcp;

import de.dadecker.inubit.mcp.application.DeployLock;
import de.dadecker.inubit.mcp.application.DeploymentLedger;
import de.dadecker.inubit.mcp.config.KnowledgeConfig;
import de.dadecker.inubit.mcp.config.KnowledgeStatus;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.AuditLog;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The warnings about existing private objects of the server that grant access to other
 * principals (feature 013, FR-007, US3 AS4): the default audit directory of the profile and the
 * audit logs in it, and the deployment ledgers and locks of the profile (T025). The server does
 * not change an access list it did not set; the warning names
 * the object, the principals and the {@code icacls} command that restricts it to the current
 * user and {@code SYSTEM}. An {@code auditDirectory} the user configured is used as it is.
 *
 * <p>On POSIX {@link PrivateFileSystem#check} reports nothing (existing objects are tightened
 * there), so this gives no warnings.
 */
final class PrivateObjectCheck {

    private static final String SYSTEM_SID = "S-1-5-18";
    private static final String NO_ACCESS_LIST = "Everyone (no access list)";
    private static final Pattern SID = Pattern.compile("S-1-[0-9]+(-[0-9]+)+");

    private final PrivateFileSystem files;
    private final String user;

    PrivateObjectCheck(PrivateFileSystem files, String user) {
        this.files = Objects.requireNonNull(files, "files");
        this.user = Objects.requireNonNull(user, "user");
    }

    /**
     * The warnings for {@code auditDirectory} and its {@code audit-*.jsonl} files, if it is the
     * existing default audit directory of {@code profile} below {@code home}, then for the
     * deployment ledgers ({@code <group>.ledger.json}) and locks ({@code <group>.lock}) in the
     * existing {@link DeploymentLedger#defaultDirectory deployments directory} of
     * {@code profile}; none for objects that do not exist or an invalid profile name.
     */
    List<String> warnings(Path auditDirectory, Path home, String profile) {
        if (!ProfileInfo.isValidName(profile)) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        if (auditDirectory.equals(AuditLog.defaultDirectory(home, profile))
            && Files.isDirectory(auditDirectory)) {
            warn(warnings, "audit directory", auditDirectory, true);
            files(warnings, auditDirectory, "audit-*.jsonl", "audit logs").forEach(log ->
                warn(warnings, "audit log", log, false));
        }
        Path deployments = DeploymentLedger.defaultDirectory(home, profile);
        if (Files.isDirectory(deployments)) {
            files(warnings, deployments, "*{" + DeploymentLedger.SUFFIX + "," + DeployLock.SUFFIX
                + "}", "deployment ledgers and locks").forEach(file -> warn(warnings,
                    file.getFileName().toString().endsWith(DeployLock.SUFFIX)
                        ? "deployment lock" : "deployment ledger", file, false));
        }
        return warnings;
    }

    /**
     * The start-time warnings for the knowledge directory (feature 019, FR-003,
     * contracts/configuration-delta.md): not usable (not a directory, not readable), else who
     * else may access it — on POSIX the group and other bits with the {@code chmod} remedy, on
     * Windows the access list in the wording above. None for a missing directory or a relative
     * path (the validator reports that); nothing is changed.
     */
    List<String> knowledgeWarnings(Path knowledge, boolean windows) {
        KnowledgeStatus status = KnowledgeStatus.probe(new KnowledgeConfig(knowledge, true), 0);
        if (status.state() != KnowledgeStatus.State.OK) {
            return knowledge.isAbsolute() ? status.problem().map(problem ->
                List.of("The knowledge directory " + knowledge + " is not usable: " + problem
                    + "; the knowledge tools report it until it is fixed")).orElse(List.of())
                : List.of();
        }
        List<String> warnings = new ArrayList<>();
        if (windows) {
            warn(warnings, "knowledge directory", knowledge, true);
            return warnings;
        }
        try {
            files.othersMayAccess(knowledge).ifPresent(finding -> warnings.add(
                "The knowledge directory " + knowledge + " is " + finding + "; run chmod 700 "
                    + knowledge + " (the server does not change permissions)"));
        } catch (IOException e) {
            warnings.add("the access of the knowledge directory " + knowledge
                + " could not be checked (" + describe(e) + ")");
        }
        return warnings;
    }

    /** The files of {@code directory} that match {@code glob}, sorted; none if unreadable. */
    private static List<Path> files(List<String> warnings, Path directory, String glob,
        String what) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
            stream.forEach(found::add);
        } catch (IOException e) {
            warnings.add("the access of the " + what + " in " + directory
                + " could not be checked (" + describe(e) + ")");
            return List.of();
        }
        return found.stream().sorted().toList();
    }

    private void warn(List<String> warnings, String what, Path path, boolean directory) {
        List<String> principals;
        try {
            principals = files.check(path);
        } catch (IOException e) {
            warnings.add("the access of the " + what + " " + path + " could not be checked ("
                + describe(e) + ")");
            return;
        }
        if (!principals.isEmpty()) {
            warnings.add("the " + what + " " + path + " grants access to "
                + String.join(", ", principals) + "; the server does not change it. Restrict it"
                + " to " + user + " and SYSTEM with: " + remedy(path, principals, directory));
        }
    }

    /** One {@code icacls} call: no inherited entries, full control for the two, the rest out. */
    private String remedy(Path path, List<String> principals, boolean directory) {
        String rights = directory ? ":(OI)(CI)F" : ":F";
        String removed = principals.stream()
            .filter(principal -> !principal.equals(NO_ACCESS_LIST))
            .map(principal -> quote(SID.matcher(principal).matches() ? "*" + principal
                : principal))
            .collect(Collectors.joining(" "));
        return "icacls " + quote(path.toString()) + " /inheritance:r /grant:r "
            + quote(files.currentUserSid().map(sid -> "*" + sid).orElse(user) + rights) + " "
            + quote("*" + SYSTEM_SID + rights)
            + (removed.isEmpty() ? "" : " /remove " + removed);
    }

    private static String quote(String text) {
        return "\"" + text + "\"";
    }

    private static String describe(IOException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
