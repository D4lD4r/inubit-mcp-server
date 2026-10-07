package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.DeployMode;
import java.util.List;

/**
 * {@code deploy} record of a group as written in the YAML (feature 005, research D-2): the group
 * receives releases from group {@code from}, as a whole and never per node.
 *
 * @param from    the source group (required in the record; {@link ConfigValidator} checks that it
 *                is another existing group and that the chain is acyclic)
 * @param mode    {@link DeployMode#EXECUTE} (default) or {@link DeployMode#PACKAGE_ONLY}
 * @param exclude what is never deployed into this group, in addition to system diagrams
 */
public record DeployConfig(String from, DeployMode mode, List<ExcludeRule> exclude) {

    public DeployConfig {
        from = Optionals.orDefault(from, "");
        mode = Optionals.orDefault(mode, DeployMode.EXECUTE);
        exclude = Optionals.copyOrEmpty(exclude);
    }
}
