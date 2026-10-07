package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeploymentPreview;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult;
import de.dadecker.inubit.mcp.domain.model.DeploymentResult.State;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scripting of a confirmed deployment on {@link DeployHarness} (T021–T023). */
abstract class DeployExecution {

    static final String TAG = "TAG-01";
    static final String MODULE_IMPORT = "--importModule --importUser 'jdoe' --returnProtocol";

    @TempDir
    Path temp;

    DeployHarness harness;

    DeployHarness harness(boolean emptyTargets) {
        try {
            harness = new DeployHarness(temp, emptyTargets);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return harness;
    }

    static DeployGuard.Request request(Optional<String> code) {
        return new DeployGuard.Request("int", TAG, Optional.empty(), code,
            Optional.of("client/1.0"));
    }

    /** The source nodes (changed by {@code change}, tagged once) answer the release exports. */
    void source(Consumer<FakeServer> change, boolean tag) {
        DeployHarness.SOURCES.forEach(node -> change.accept(harness.servers.get(node)));
        if (tag) {
            harness.tagged("GRP-01", TAG);
        }
        DeployHarness.SOURCES.forEach(node -> harness.exportRelease(node, TAG));
        harness.exportGroup(DeployHarness.SOURCE);
    }

    /** One planner/state read of a node of a head target. */
    void nodeExports(NodeId node) {
        harness.exportGroup(node).exportRepository(node, DeployHarness.RELEASE_XSL);
    }

    /** The preview: source and every target node read once. */
    DeploymentPreview preview() {
        source(server -> { }, true);
        DeployHarness.TARGETS.forEach(this::nodeExports);
        return harness.deployService(List.of()).deploy(request(Optional.empty())).preview()
            .orElseThrow();
    }

    /** The start of the execute call: source and every target node read again. */
    void executeStart() {
        source(server -> { }, false);
        DeployHarness.TARGETS.forEach(this::nodeExports);
    }

    /** A node of a head target that gets Module-0005 and the repository file. */
    void deployed(NodeId node) {
        nodeExports(node); // re-check
        harness.importRepositoryApplied(node).importApplied(node, MODULE_IMPORT);
        nodeExports(node); // verification
        harness.tagVerified(node, "GRP-01", TAG);
    }

    DeploymentResult execute(DeploymentPreview preview) {
        return harness.deployService(List.of()).deploy(request(preview.confirmationCode()))
            .result().orElseThrow();
    }

    List<AuditRecord> audit() {
        return harness.audit.stream().filter(r -> r.capability().equals("deploy_release"))
            .toList();
    }

    ToolErrorException failure(DeploymentPreview preview) {
        try {
            execute(preview);
        } catch (ToolErrorException e) {
            return e;
        }
        throw new AssertionError("no failure");
    }
}
