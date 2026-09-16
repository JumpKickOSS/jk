// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.repo.Pom;
import java.util.ArrayList;
import java.util.List;

/**
 * A Maven dependency's {@code <exclusions>} become the jk entry's {@code exclude} list: one
 * {@code group:artifact} per exclusion, {@code group:*} when the artifactId is {@code *}. A
 * {@code <groupId>*</groupId>} exclusion has no jk spelling and is a report row naming the limit.
 */
final class ExclusionMapping {

    private ExclusionMapping() {}

    static Dependency apply(Dependency dep, Pom.Dep source, ImportReport.Builder report) {
        if (source.exclusions().isEmpty()) return dep;
        List<String> exclude = new ArrayList<>(source.exclusions().size());
        for (Pom.Dep.Exclusion exclusion : source.exclusions()) {
            String group = PluginFacts.usable(exclusion.groupId());
            String artifact = PluginFacts.usable(exclusion.artifactId());
            if (group == null || group.contains("*")) {
                report.warning(
                        "`<exclusion>` " + (group == null ? "*" : group) + ":" + (artifact == null ? "*" : artifact)
                                + " on " + source.module()
                                + " names every group; jk's `exclude` prunes `group:artifact` or `group:*`, so this exclusion"
                                + " was not written. List the coordinates it should keep out on the entry's `exclude`.");
                continue;
            }
            exclude.add(group + ":" + (artifact == null ? "*" : artifact));
        }
        return exclude.isEmpty() ? dep : dep.withExclusions(exclude);
    }
}
