package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** Shared helpers of the knowledge service tests (feature 019). */
final class KnowledgeFixture {

    static final Duration BOUND = Duration.ofSeconds(10);
    static final Instant OLDER = Instant.parse("2026-01-01T10:00:00Z");
    static final Instant NEWER = Instant.parse("2026-02-01T10:00:00Z");

    final Path root;
    final NoteRefs refs = new NoteRefs(new SecureRandom());

    KnowledgeFixture(Path temp) throws IOException {
        this.root = Files.createDirectories(temp.resolve("knowledge"));
    }

    KnowledgeService service(ConfiguredSecrets secrets) {
        return new KnowledgeService(new KnowledgeDirectory(root), secrets, refs, BOUND);
    }

    Path note(String relative, String text, Instant modified) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    static ToolError error(ErrorCode code, ThrowingCallable call) {
        AtomicReference<ToolError> error = new AtomicReference<>();
        assertThatThrownBy(call).isInstanceOfSatisfying(ToolErrorException.class,
            e -> error.set(e.error()));
        if (error.get().code() != code) {
            throw new AssertionError("expected " + code + " but was " + error.get());
        }
        return error.get();
    }

    static String all(ToolError error) {
        return error.message() + " | " + error.likelyCause() + " | " + error.nextStep();
    }
}
