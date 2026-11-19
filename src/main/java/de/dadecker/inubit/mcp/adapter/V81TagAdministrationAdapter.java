package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.admin.CliTagAdminRunner;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort;
import java.util.Objects;
import java.util.Optional;

/**
 * The 8.1 {@link TagAdministrationPort} (feature 022, US4, research D-18); 9.0 uses it too. The
 * active tag is read, set and removed on the node's {@link CliTagAdminRunner}.
 */
final class V81TagAdministrationAdapter implements TagAdministrationPort {

    private final CliTagAdminRunner tags;

    V81TagAdministrationAdapter(CliTagAdminRunner tags) {
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    @Override
    public Optional<String> activeTag(String user) {
        return tags.activeTag(user);
    }

    @Override
    public void setActiveTag(String user, String tag, WritePermit permit) {
        tags.setActiveTag(user, tag, permit);
    }

    @Override
    public void removeActiveTag(String user, WritePermit permit) {
        tags.removeActiveTag(user, permit);
    }

    @Override
    public String toString() {
        return "V81TagAdministrationAdapter[" + tags + "]";
    }
}
