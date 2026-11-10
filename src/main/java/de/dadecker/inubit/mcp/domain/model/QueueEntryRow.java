package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One Queue Manager row with what reading its content needs (feature 020, D-9; source in 8.1:
 * the REST {@code queueLog}). Unlike {@link ProcessInstance} it keeps the uncut workflow name,
 * the file name and size and the error texts. The adapter throws {@code UNEXPECTED_RESPONSE} for
 * a row that breaks the rules below; the constructor rejects such a row, too, without quoting it.
 *
 * @param processId  {@code workflowId}
 * @param workflow   uncut, without {@code @@@} or control characters (it becomes part of an
 *                   object type {@code queue_manager@@@<workflow>@@@error})
 * @param fileName   1–255 characters, without {@code /}, {@code \} or control characters
 *                   (including NUL)
 * @param fileSize   bytes, not negative
 * @param errorTexts the non-empty fields {@code userDefined1..5}, each at most
 *                   {@value ItemBounds#MAX_MESSAGE_CHARS} characters
 */
public record QueueEntryRow(
    String processId,
    Optional<String> globalProcessId,
    ProcessState state,
    String rawState,
    Instant since,
    String workflow,
    Optional<String> module,
    Optional<String> moduleType,
    String fileName,
    long fileSize,
    SortedMap<String, String> errorTexts) {

    public static final int MAX_FILE_NAME_CHARS = 255;

    private static final Set<String> ERROR_TEXT_KEYS = Set.of("userDefined1", "userDefined2",
        "userDefined3", "userDefined4", "userDefined5");

    public QueueEntryRow {
        Objects.requireNonNull(processId, "processId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rawState, "rawState");
        Objects.requireNonNull(since, "since");
        globalProcessId = orEmpty(globalProcessId);
        module = orEmpty(module);
        moduleType = orEmpty(moduleType);
        checkEntry(workflow, fileName, fileSize);
        errorTexts = Collections.unmodifiableSortedMap(new TreeMap<>(
            Objects.requireNonNull(errorTexts, "errorTexts")));
        errorTexts.forEach((key, text) -> {
            if (!ERROR_TEXT_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                    "Error texts are only userDefined1 to userDefined5");
            }
            if (text == null || text.isEmpty() || text.length() > ItemBounds.MAX_MESSAGE_CHARS) {
                throw new IllegalArgumentException("Error text " + key + " must have 1 to "
                    + ItemBounds.MAX_MESSAGE_CHARS + " characters");
            }
        });
    }

    /**
     * True if {@code fileName} has 1–255 characters and no {@code /}, {@code \} or control
     * character.
     */
    public static boolean isValidFileName(String fileName) {
        return fileName != null && !fileName.isEmpty()
            && fileName.length() <= MAX_FILE_NAME_CHARS
            && fileName.indexOf('/') < 0 && fileName.indexOf('\\') < 0
            && noControlCharacter(fileName);
    }

    /** True if {@code workflow} is not empty and has no {@code @@@} and no control character. */
    public static boolean isValidWorkflow(String workflow) {
        return workflow != null && !workflow.isEmpty() && !workflow.contains("@@@")
            && noControlCharacter(workflow);
    }

    /** The rules shared with {@link QueueEntryRef}; the messages never quote the values. */
    static void checkEntry(String workflow, String fileName, long fileSize) {
        if (!isValidWorkflow(workflow)) {
            throw new IllegalArgumentException(
                "Invalid workflow name: empty, with '@@@' or with control characters");
        }
        if (!isValidFileName(fileName)) {
            throw new IllegalArgumentException("Invalid file name: expected 1 to "
                + MAX_FILE_NAME_CHARS + " characters without '/', '\\' or control characters");
        }
        if (fileSize < 0) {
            throw new IllegalArgumentException("Invalid file size: negative");
        }
    }

    private static boolean noControlCharacter(String text) {
        return text.chars().noneMatch(Character::isISOControl);
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
