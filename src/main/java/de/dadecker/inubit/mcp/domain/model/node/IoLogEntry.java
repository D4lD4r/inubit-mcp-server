package de.dadecker.inubit.mcp.domain.model.node;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One entry of the I/O log listing of a process run (research D-8):
 * {@code <processId>@@@<moduleName>(<moduleId>)@@@Input.<n>} or {@code …@@@Output.<n>}.
 *
 * @param entry      the listed entry, unchanged
 * @param moduleName the module part before its last {@code (}, or the whole module part when it
 *                   does not have the form {@code <name>(<id>)}
 * @param moduleId   the text in the last parentheses; absent when the module part did not parse
 * @param index      1–999,999,999
 * @param ref        {@link IoMessageRef#of} the entry
 */
public record IoLogEntry(
    String entry,
    String moduleName,
    Optional<String> moduleId,
    IoDirection direction,
    int index,
    IoMessageRef ref) {

    private static final Pattern ENTRY = Pattern.compile(
        "(?<process>[0-9A-Za-z_-]{1,64})@@@(?<module>.+)@@@(?<dir>Input|Output)"
            + "\\.(?<index>[1-9][0-9]{0,8})");

    public IoLogEntry {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(ref, "ref");
        if (moduleName.isEmpty()) {
            throw new IllegalArgumentException("Invalid module name: empty");
        }
        if (index < 1) {
            throw new IllegalArgumentException("Invalid index: below 1");
        }
    }

    /**
     * Parses a listed entry of the run {@code processId}. A module part that is not
     * {@code <name>(<id>)} with both parts non-empty is kept raw as {@code moduleName}; the
     * messages never quote the entry.
     *
     * @throws IllegalArgumentException if the entry is not an I/O log entry or belongs to another
     *                                  process
     */
    public static IoLogEntry parse(String processId, String entry) {
        Objects.requireNonNull(processId, "processId");
        Matcher matcher = ENTRY.matcher(Objects.requireNonNull(entry, "entry"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not an I/O log entry: expected "
                + "<processId>@@@<module>@@@Input.<n> or …@@@Output.<n> with n from 1");
        }
        if (!matcher.group("process").equals(processId)) {
            throw new IllegalArgumentException("The I/O log entry belongs to another process");
        }
        String module = matcher.group("module");
        String moduleName = module;
        Optional<String> moduleId = Optional.empty();
        int open = module.lastIndexOf('(');
        if (module.endsWith(")") && open > 0 && open < module.length() - 2) {
            moduleName = module.substring(0, open);
            moduleId = Optional.of(module.substring(open + 1, module.length() - 1));
        }
        IoDirection direction = matcher.group("dir").equals(IoDirection.INPUT.wireName())
            ? IoDirection.INPUT : IoDirection.OUTPUT;
        return new IoLogEntry(entry, moduleName, moduleId, direction,
            Integer.parseInt(matcher.group("index")), IoMessageRef.of(entry));
    }

    /** False when the module part did not have the form {@code <name>(<id>)}. */
    public boolean moduleParsed() {
        return moduleId.isPresent();
    }
}
