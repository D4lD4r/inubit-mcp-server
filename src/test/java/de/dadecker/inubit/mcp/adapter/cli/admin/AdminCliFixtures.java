package de.dadecker.inubit.mcp.adapter.cli.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * The {@code admin-settings-*} cases of {@code fixtures/v8_1/cli/} (described in its
 * {@code README.md}, a SYNTHETIC case also in its own {@code .README}) as one session block each,
 * and the check of an unknown answer.
 */
final class AdminCliFixtures {

    private static final String DIRECTORY = "/fixtures/v8_1/cli/";
    private static final String PREAMBLE_WARNING = "SECURITY WARNING: Hostname verification is"
        + " disabled. Your connection might not be secured!";

    private AdminCliFixtures() {
    }

    /**
     * The lines of {@code <case>.stdout} as {@code CliSessionParser} hands out a block: without
     * {@code \r} and without blank lines.
     */
    static List<String> block(String fixtureCase) {
        String path = DIRECTORY + fixtureCase + ".stdout";
        try (InputStream in = AdminCliFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(line -> line.replace("\r", ""))
                .filter(line -> !line.isBlank())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The answer of a recorded script-mode case as a session block: the lines of {@link #block}
     * after the client's preamble ({@code JAVA_HOME is set}, {@code Password: }, the hostname
     * warning).
     */
    static List<String> answer(String fixtureCase) {
        List<String> lines = block(fixtureCase);
        assertThat(lines.subList(0, 3)).as("the preamble of " + fixtureCase)
            .containsExactly("JAVA_HOME is set", "Password: ", PREAMBLE_WARNING);
        return lines.subList(3, lines.size());
    }

    /** Asserts {@code UNEXPECTED_RESPONSE} with an excerpt and returns the error. */
    static ToolError assertUnknown(ThrowingCallable parse) {
        ToolError[] error = new ToolError[1];
        assertThatThrownBy(parse).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            assertThat(e.error().excerpt()).isPresent();
            error[0] = e.error();
        });
        return error[0];
    }
}
