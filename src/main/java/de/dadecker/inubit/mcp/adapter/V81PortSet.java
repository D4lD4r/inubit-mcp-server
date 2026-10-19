package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliImportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliTagRunner;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ArtifactAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ImportAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ProcessControlAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81TagAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81LogAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81ProcessQueryAdapter;

/** The 8.1 port set: the REST adapters on the server's client and the StartCLI adapters. */
final class V81PortSet {

    private V81PortSet() {
    }

    static PortSet create(PortSetRegistry.NodeResources node) {
        V81InventoryAdapter inventory = new V81InventoryAdapter(node.server().id(), node.client(),
            new CliExportRunner(node.server(), node.credentials(), node.guard(), node.cliRunner(),
                classifier(node)));
        return new PortSet(
            new V81ProcessQueryAdapter(node.server().id(), node.client()),
            new V81LogAdapter(node.server().id(), node.client()),
            inventory,
            new V81ProcessControlAdapter(node.server(), node.credentials(), node.guard(),
                node.cliRunner(), classifier(node)),
            new V81ArtifactAdapter(new CliExportRunner(node.server(), node.credentials(),
                node.guard(), node.cliRunner(), classifier(node)), inventory::confirmCredentials),
            new V81ImportAdapter(new CliImportRunner(node.server(), node.credentials(),
                node.guard(), node.cliRunner(), classifier(node)), inventory::confirmCredentials),
            new V81TagAdapter(node.server().id(), new CliExportRunner(node.server(),
                node.credentials(), node.guard(), node.cliRunner(), classifier(node)),
                new CliTagRunner(node.server(), node.credentials(), node.guard(),
                    node.cliRunner(), classifier(node)), inventory::confirmCredentials));
    }

    private static CliOutputClassifier classifier(PortSetRegistry.NodeResources node) {
        return new CliOutputClassifier(node.scrubber(), node.server().credentialVariables());
    }
}
