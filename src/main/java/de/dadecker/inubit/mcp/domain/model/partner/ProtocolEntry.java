package de.dadecker.inubit.mcp.domain.model.partner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One {@code ReferencedTWF} entry of a partner (data-model.md → ProtocolEntry).
 *
 * @param protocol             the {@code protocol} attribute ({@code FTP}, {@code WebService},
 *                             …), as found
 * @param workflow             the workflow, resolved through {@code referencableTWFId}
 * @param format               the {@code format} attribute, as found, not interpreted
 * @param protocolSettings     in export order
 * @param additionalParameters in export order
 * @param position             index among the partner's entries with the same
 *                             {@code (protocol, workflow)} (FR-013)
 */
public record ProtocolEntry(
    String protocol,
    WorkflowRef workflow,
    Optional<String> format,
    List<Setting> protocolSettings,
    List<Setting> additionalParameters,
    int position) {

    public ProtocolEntry {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(workflow, "workflow");
        format = format == null ? Optional.empty() : format;
        protocolSettings = List.copyOf(protocolSettings);
        additionalParameters = List.copyOf(additionalParameters);
        if (position < 0) {
            throw new IllegalArgumentException("position must be >= 0: " + position);
        }
    }
}
