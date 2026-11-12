package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.CliSessionCommand.Listing;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T016: the allow-listed commands of a 6.1 StartCLI session (research D-5, D-10); feature 022
 * T012: the version-neutral builder with the value rules of {@link CliCommand} (research D-7);
 * T019: the settings reads of US1 in the production allowlist.
 */
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
        // no public constructor; the only public method that takes text is command(name), which
        // admits allow-listed names only (feature 022, research D-7): every other command comes
        // from a constant or from a Listing plus a bounded number
        List<Constructor<?>> constructors = Arrays.stream(
            CliSessionCommand.class.getDeclaredConstructors())
            .filter(constructor -> !Modifier.isPrivate(constructor.getModifiers()))
            .toList();
        assertThat(constructors).isEmpty();

        List<Method> methods = Arrays.stream(CliSessionCommand.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> !method.getName().equals("command"))
            .toList();
        assertThat(methods).isNotEmpty().allSatisfy(method ->
            assertThat(method.getParameterTypes())
                .doesNotContain(String.class, CharSequence.class, Object.class, char[].class));
    }

    // --- feature 022 (T012, research D-7): commands built from the session allowlist ----------

    /** An allowlist for the builder's value rules; the production one has no valued option yet. */
    private static final Map<String, Set<String>> ALLOWLIST = Map.of(
        "show", Set.of("--group", "--path", "--all"));

    private static void assertInvalid(ThrowingCallable call, String value) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
            if (!value.isEmpty()) {
                assertThat(e.getMessage()).doesNotContain(value);
            }
        });
    }

    /** T019 (US1): the settings reads of {@code get_server_settings}, and nothing else. */
    @Test
    void theSettingsReadsAreAdmitted() {
        assertThat(CliSessionCommand.SESSION_OPTIONS).isEqualTo(Map.of(
            "threads", Set.of("showNumberOfWorkflowThreads"),
            "privilegedWorkflows", Set.of("showThreadsPercentage", "showWorkflowList"),
            "dbconnectiontolerance", Set.of("show"),
            "date", Set.of()));
        assertThat(CliSessionCommand.command("threads").flag("showNumberOfWorkflowThreads")
            .build().text()).isEqualTo("threads showNumberOfWorkflowThreads");
        assertThat(CliSessionCommand.command("privilegedWorkflows").flag("showThreadsPercentage")
            .build().text()).isEqualTo("privilegedWorkflows showThreadsPercentage");
        assertThat(CliSessionCommand.command("privilegedWorkflows").flag("showWorkflowList")
            .build().text()).isEqualTo("privilegedWorkflows showWorkflowList");
        assertThat(CliSessionCommand.command("dbconnectiontolerance").flag("show").build().text())
            .isEqualTo("dbconnectiontolerance show");
        assertThat(CliSessionCommand.command("date").build().text()).isEqualTo("date");
    }

    /** T019: the settings changes (US6) and every other command stay outside the allowlist. */
    @Test
    void aSettingsChangeOrAnotherCommandIsRejected() {
        assertInvalid(() -> CliSessionCommand.command("threads")
            .flag("setNumberOfWorkflowThreads"), "setNumberOfWorkflowThreads");
        assertInvalid(() -> CliSessionCommand.command("privilegedWorkflows")
            .flag("setThreadsPercentage"), "setThreadsPercentage");
        assertInvalid(() -> CliSessionCommand.command("dbconnectiontolerance").flag("set"), "");
        assertInvalid(() -> CliSessionCommand.command("date").flag("-s"), "");
        assertInvalid(() -> CliSessionCommand.command("passwd"), "");
        assertInvalid(() -> CliSessionCommand.command("license"), "");
        assertInvalid(() -> CliSessionCommand.command(null), "");
    }

    @Test
    void aCommandOrOptionOutsideTheAllowlistIsRejected() {
        assertInvalid(() -> CliSessionCommand.command("delete", ALLOWLIST), "");
        assertInvalid(() -> CliSessionCommand.command("show", ALLOWLIST).flag("--force"),
            "--force");
        assertInvalid(() -> CliSessionCommand.command("show", ALLOWLIST)
            .quoted("--password", "Dev"), "--password");
        assertInvalid(() -> CliSessionCommand.command("show", ALLOWLIST)
            .repositoryPath("--target", "/Root/Dev"), "--target");
    }

    @Test
    void aValueIsQuotedAsInCliCommand() {
        CliSessionCommand command = CliSessionCommand.command("show", ALLOWLIST)
            .quoted("--group", "Dev Team_1.0")
            .repositoryPath("--path", "/Root/Dev/Orders")
            .flag("--all")
            .build();

        assertThat(command.text())
            .isEqualTo("show --group 'Dev Team_1.0' --path '/Root/Dev/Orders' --all");
        assertThat(CliSessionCommand.command("show", ALLOWLIST).build().text()).isEqualTo("show");
    }

    @ParameterizedTest
    @ValueSource(strings = {"it's", "-x", "a\nb", "a;b", "", "a$b", "x\u00e4"})
    void aValueOutsideTheValueRuleIsRejected(String value) {
        assertInvalid(() -> CliSessionCommand.command("show", ALLOWLIST)
            .quoted("--group", value), value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/Root", "/Other/Dev", "/Root/../Dev", "/Root/./Dev", "Root/Dev",
        "/Root/Dev'"})
    void aRepositoryPathOutsideItsRuleIsRejected(String path) {
        assertInvalid(() -> CliSessionCommand.command("show", ALLOWLIST)
            .repositoryPath("--path", path), path);
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
