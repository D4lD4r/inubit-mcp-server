package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T014 (FR-025): nothing unredacted can reach a writer. A {@link RedactedArchive} needs a
 * {@link SecretRedactor.Seal}, which only the redactor can create, and apart from the reader that
 * produces it and the redactor that consumes it, no class of the archive package accepts an
 * {@link ExportArchive}.
 */
class RedactionGateTest {

    /** The classes allowed to take an {@link ExportArchive} (outer class names). */
    private static final Set<String> MAY_TAKE_UNREDACTED =
        Set.of("ArchiveReader", "SecretRedactor", "ExportArchive", "RedactedArchive");

    @Test
    void onlyTheRedactorCanSealAnArchive() {
        for (Constructor<?> constructor : RedactedArchive.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).as(constructor.toString())
                .contains(SecretRedactor.Seal.class);
            assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse();
        }
        for (Constructor<?> constructor : SecretRedactor.Seal.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(constructor.getModifiers())).as(constructor.toString())
                .isTrue();
        }
        assertThat(Arrays.stream(SecretRedactor.class.getDeclaredMethods())
            .filter(m -> !Modifier.isPrivate(m.getModifiers())))
            .as("no non-private method hands out a seal")
            .noneMatch(m -> m.getReturnType().equals(SecretRedactor.Seal.class));
    }

    @Test
    void theRedactorReturnsARedactedArchive() throws NoSuchMethodException {
        assertThat(SecretRedactor.class.getMethod("redact", ExportArchive.class).getReturnType())
            .isEqualTo(RedactedArchive.class);
    }

    @Test
    void noOtherClassOfThePackageAcceptsAnUnredactedArchive() throws Exception {
        List<String> violations = new ArrayList<>();
        List<Class<?>> classes = packageClasses();
        assertThat(classes).as("the scan sees the package").extracting(Class::getSimpleName)
            .contains("ArchiveReader", "XmlNormalizer", "SecretRedactor");
        for (Class<?> type : classes) {
            String outer = type.getName().substring(type.getPackageName().length() + 1)
                .split("\\$")[0];
            if (MAY_TAKE_UNREDACTED.contains(outer)) {
                continue;
            }
            List<Executable> executables = new ArrayList<>(List.of(type.getDeclaredMethods()));
            executables.addAll(List.of(type.getDeclaredConstructors()));
            for (Executable executable : executables) {
                if (Arrays.asList(executable.getParameterTypes()).contains(ExportArchive.class)) {
                    violations.add(executable.toString());
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void onlyARedactedArchiveBecomesWritableFiles() {
        // review I2: write(Path, Rendered) is public, so Rendered must come from render() only
        for (Constructor<?> constructor : WorkspaceWriter.Rendered.class
            .getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(constructor.getModifiers())).as(constructor.toString())
                .isTrue();
        }
        assertThat(WorkspaceWriter.Rendered.class.isRecord()).as("a record has a public"
            + " canonical constructor").isFalse();
        assertThat(Arrays.stream(WorkspaceWriter.class.getDeclaredMethods())
            .filter(m -> !Modifier.isPrivate(m.getModifiers()))
            .filter(m -> m.getReturnType().equals(WorkspaceWriter.Rendered.class)))
            .as("non-private factories of Rendered and what they take")
            .allSatisfy(m -> assertThat(List.of(m.getParameterTypes()))
                .containsAnyOf(RedactedArchive.class, List.class));
        for (Class<?> nested : WorkspaceWriter.Rendered.class.getDeclaredClasses()) {
            assertThat(Modifier.isPrivate(nested.getModifiers())).as(nested.toString()).isTrue();
        }
    }

    /** The compiled production classes of the archive package. */
    private static List<Class<?>> packageClasses() throws IOException, URISyntaxException,
        ClassNotFoundException {
        Path root = Path.of(ArchiveReader.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        String packagePath = ArchiveReader.class.getPackageName().replace('.', '/');
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.list(root.resolve(packagePath))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = file.getFileName().toString();
                classes.add(Class.forName(ArchiveReader.class.getPackageName() + "."
                    + name.substring(0, name.length() - ".class".length())));
            }
        }
        return classes;
    }
}
