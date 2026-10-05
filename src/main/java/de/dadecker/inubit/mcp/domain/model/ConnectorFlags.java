package de.dadecker.inubit.mcp.domain.model;

/**
 * The connector role of a module (data-model.md → InventoryDetail, modules only), from the CLI
 * module index ({@code IsInputConnector}, {@code IsOutputConnector}, {@code IsScheduled}).
 */
public record ConnectorFlags(boolean input, boolean output, boolean scheduled) {
}
