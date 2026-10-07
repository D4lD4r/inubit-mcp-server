package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.OwnerKind;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.UserDirectoryPort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * T009 (feature 004, research D-21, D-25): the owner kind from positive evidence only — the
 * profile's {@code owners} override or INUBIT's user list — and the single guard that refuses
 * writes for user-group owners until that is verified live.
 */
class OwnerKindResolverTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    private final List<NodeId> lookups = new CopyOnWriteArrayList<>();
    private Set<String> users = Set.of("jdoe", "User01");
    private ToolErrorException failure;

    private OwnerKindResolver resolver(Map<String, OwnerKind> overrides) {
        UserDirectoryPort directory = () -> {
            if (failure != null) {
                throw failure;
            }
            return users;
        };
        return new OwnerKindResolver(overrides, node -> {
            lookups.add(node);
            return directory;
        });
    }

    private static ToolError errorOf(Runnable call) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class, call::run);
        assertThat(exception).as("expected a refusal").isNotNull();
        return exception.error();
    }

    @Test
    void theProfileOverrideWinsWithoutAskingInubit() {
        OwnerKindResolver resolver = resolver(Map.of("OWNERS", OwnerKind.USER_GROUP,
            "jdoe", OwnerKind.USER_GROUP, "svc", OwnerKind.USER));

        assertThat(resolver.resolve(DEV, "OWNERS")).isEqualTo(OwnerKind.USER_GROUP);
        assertThat(resolver.resolve(DEV, "jdoe")).as("even if listed as a user")
            .isEqualTo(OwnerKind.USER_GROUP);
        assertThat(resolver.resolve(DEV, "svc")).isEqualTo(OwnerKind.USER);
        assertThat(lookups).isEmpty();
    }

    @Test
    void aListedUserIsAUser() {
        assertThat(resolver(Map.of()).resolve(DEV, "jdoe")).isEqualTo(OwnerKind.USER);
        assertThat(lookups).containsExactly(DEV);
    }

    @Test
    void anOwnerThatIsNotListedCannotBeDeterminedAndNamesTheProfileSetting() {
        ToolError error = errorOf(() -> resolver(Map.of()).resolve(DEV, "OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).contains("OWNERS", "cannot be determined");
        assertThat(error.nextStep()).contains("owners.OWNERS");
    }

    @Test
    void aFailingLookupIsAPreconditionFailureThatSaysWhy() {
        failure = new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE,
            "dev/node1 is not reachable", "down", "retry").withNode(DEV));

        ToolError error = errorOf(() -> resolver(Map.of()).resolve(DEV, "jdoe"));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).contains("jdoe", "user list");
        assertThat(error.likelyCause()).contains("UNREACHABLE", "dev/node1 is not reachable");
        assertThat(error.nextStep()).contains("owners.jdoe");
    }

    @Test
    void writesAreAdmittedForUsers() {
        assertThat(resolver(Map.of()).admitForWrite(DEV, "jdoe")).isEqualTo(OwnerKind.USER);
    }

    @Test
    void writesForUserGroupOwnersAreRefusedByOneGuardAsNotYetVerified() {
        OwnerKindResolver resolver = resolver(Map.of("OWNERS", OwnerKind.USER_GROUP));

        ToolError error = errorOf(() -> resolver.admitForWrite(DEV, "OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).contains("OWNERS", "user-group owners",
            "not yet verified");
        assertThat(errorOf(() -> OwnerKindResolver.requireVerified(DEV, "OWNERS",
            OwnerKind.USER_GROUP)).message()).isEqualTo(error.message());
        assertThat(OwnerKindResolver.requireVerified(DEV, "jdoe", OwnerKind.USER))
            .isEqualTo(OwnerKind.USER);
    }
}
