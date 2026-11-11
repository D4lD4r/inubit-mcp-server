package de.dadecker.inubit.mcp.adapter.cli.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one-time trust store of the queue-entry helper (feature 020, research D-7): a PKCS12 file
 * with the verified leaf as its only entry, owner-only, protected by a random one-time password
 * that never appears in a text.
 */
class HelperTrustStoreTest {

    private static final TestCertificates CERTS = TestCertificates.get();
    private static final NodeId NODE = NodeId.parse("int/a");

    @TempDir
    Path tmp;

    private final PrivateFileSystem files = PrivateFiles.forHost();

    @Test
    void holdsOnlyTheLeafOfTheVerifiedChainAndLoadsWithItsPassword() throws Exception {
        Path file = tmp.resolve(HelperTrustStore.FILE_NAME);

        HelperRequest.TrustStore store = HelperTrustStore.write(NODE, file,
            List.of(CERTS.localhostCertificate(), CERTS.selfsignedCertificate()), files);

        assertThat(store.file()).isEqualTo(file);
        KeyStore loaded = load(file, store.password().reveal());
        assertThat(Collections.list(loaded.aliases())).singleElement()
            .satisfies(alias -> {
                assertThat(loaded.isCertificateEntry(alias)).as("a trusted certificate").isTrue();
                assertThat(loaded.getCertificate(alias)).isEqualTo(CERTS.localhostCertificate());
            });
    }

    @Test
    void theFileIsOwnerOnly() throws Exception {
        Path file = tmp.resolve(HelperTrustStore.FILE_NAME);

        HelperTrustStore.write(NODE, file, List.of(CERTS.localhostCertificate()), files);

        if (Platform.windows()) {
            assertThat(files.check(file)).as("no principal besides this account and SYSTEM")
                .isEmpty();
        } else {
            assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void thePasswordIsARandomOneTime32BytePasswordInBase64() throws Exception {
        HelperRequest.TrustStore first = HelperTrustStore.write(NODE, tmp.resolve("first.p12"),
            List.of(CERTS.localhostCertificate()), files);
        HelperRequest.TrustStore second = HelperTrustStore.write(NODE, tmp.resolve("second.p12"),
            List.of(CERTS.localhostCertificate()), files);

        assertThat(Base64.getDecoder().decode(first.password().reveal())).hasSize(32);
        assertThat(first.password().reveal()).isNotEqualTo(second.password().reveal());
        assertThatThrownBy(() -> load(first.file(), second.password().reveal()))
            .as("another password does not open the store").isInstanceOf(IOException.class);
    }

    @Test
    void thePasswordAppearsInNoText() throws Exception {
        Path file = tmp.resolve(HelperTrustStore.FILE_NAME);

        HelperRequest.TrustStore store = HelperTrustStore.write(NODE, file,
            List.of(CERTS.localhostCertificate()), files);

        String password = store.password().reveal();
        assertThat(store.toString()).doesNotContain(password);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1))
            .as("the store does not hold its password").doesNotContain(password);
    }

    @Test
    void aFileThatCannotBeCreatedPrivatelyIsAnInternalErrorWithoutAFile() {
        Path file = tmp.resolve(HelperTrustStore.FILE_NAME);

        ToolError error = catchThrowableOfType(ToolErrorException.class,
            () -> HelperTrustStore.write(NODE, file, List.of(CERTS.localhostCertificate()),
                PrivateFiles.failing())).error();

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.node()).contains(NODE);
        assertThat(error.message()).isEqualTo("The trust store for the queue-entry helper could"
            + " not be written on int/a (FileSystemException); the queue-entry helper was not"
            + " started");
        assertThat(file).doesNotExist();
        leakCheck(error);
    }

    @Test
    void anExistingFileIsNotOverwritten() throws Exception {
        Path file = Files.writeString(tmp.resolve(HelperTrustStore.FILE_NAME), "planted");

        ToolError error = catchThrowableOfType(ToolErrorException.class,
            () -> HelperTrustStore.write(NODE, file, List.of(CERTS.localhostCertificate()),
                files)).error();

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(file).hasContent("planted");
        leakCheck(error);
    }

    @Test
    void anEmptyChainIsAProgrammingError() {
        assertThatThrownBy(() -> HelperTrustStore.write(NODE, tmp.resolve("x.p12"), List.of(),
            files)).isInstanceOf(IllegalArgumentException.class);
    }

    private static KeyStore load(Path file, String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, password.toCharArray());
        }
        return store;
    }

    private static void leakCheck(ToolError error) {
        for (String text : Set.of(error.message(), error.likelyCause(), error.nextStep())) {
            TestSecrets.assertNoLeak(text);
        }
    }
}
