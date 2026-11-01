package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand.Listing;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T016: the allow-listed commands of a 6.1 StartCLI session (research D-5, D-10). */
class CliSessionCommandTest {

    @Test
    void theFixedCommandsHaveTheirDocumentedText() {
        assertThat(CliSessionCommand.UNAME.text()).isEqualTo("uname");
        assertThat(CliSessionCommand.UPTIME.text()).isEqualTo("uptime");
        assertThat(CliSessionCommand.MAINTENANCE.text()).isEqualTo("maintenance");
        assertThat(CliSessionCommand.FREE_MEMORY.text()).isEqualTo("free -m");
        assertThat(CliSessionCommand.EXIT.text()).isEqualTo("exit");
    }

    @Test
    void aListingSelectsTheStateAndLimitsTheRows() {
        assertThat(CliSessionCommand.psList(Listing.ALL, 10_000).text())
            .isEqualTo("ps -a -l 10000");
        assertThat(CliSessionCommand.psList(Listing.ERROR, 10_000).text())
            .isEqualTo("ps -e -l 10000");
        assertThat(CliSessionCommand.psList(Listing.WAITING, 10_000).text())
            .isEqualTo("ps -w -l 10000");
        assertThat(CliSessionCommand.psList(Listing.QUEUED, 1).text()).isEqualTo("ps -q -l 1");
    }

    @Test
    void aCountSelectsTheState() {
        assertThat(CliSessionCommand.psCount(Listing.ERROR).text()).isEqualTo("ps -c -e");
        assertThat(CliSessionCommand.psCount(Listing.WAITING).text()).isEqualTo("ps -c -w");
        assertThat(CliSessionCommand.psCount(Listing.QUEUED).text()).isEqualTo("ps -c -q");
        assertThat(CliSessionCommand.psCount(Listing.ALL).text()).isEqualTo("ps -c -a");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 10_001, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void aRowLimitOutsideOneTo10000IsRefused(int rows) {
        assertThatThrownBy(() -> CliSessionCommand.psList(Listing.ALL, rows))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingListingIsRefused() {
        assertThatThrownBy(() -> CliSessionCommand.psList(null, 10))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CliSessionCommand.psCount(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void noFreeTextIsAccepted() {
        // no public constructor and no public method that takes text: every command comes from
        // a constant or from a Listing plus a bounded number
        List<Constructor<?>> constructors = Arrays.stream(
            CliSessionCommand.class.getDeclaredConstructors())
            .filter(constructor -> !Modifier.isPrivate(constructor.getModifiers()))
            .toList();
        assertThat(constructors).isEmpty();

        List<Method> methods = Arrays.stream(CliSessionCommand.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .toList();
        assertThat(methods).isNotEmpty().allSatisfy(method ->
            assertThat(method.getParameterTypes())
                .doesNotContain(String.class, CharSequence.class, Object.class, char[].class));
    }

    @Test
    void theTextIsOneLine() {
        List<CliSessionCommand> all = List.of(CliSessionCommand.UNAME, CliSessionCommand.UPTIME,
            CliSessionCommand.MAINTENANCE, CliSessionCommand.FREE_MEMORY, CliSessionCommand.EXIT,
            CliSessionCommand.psList(Listing.ALL, 10_000),
            CliSessionCommand.psCount(Listing.ERROR));

        assertThat(all).allSatisfy(command -> {
            assertThat(command.text()).doesNotContain("\n", "\r").isNotBlank();
            assertThat(command).hasToString(command.text());
        });
    }
}
