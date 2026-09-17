// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.repo.Pom;
import java.util.ArrayList;
import java.util.List;

/**
 * A Maven dependency's {@code <exclusions>} become the jk entry's {@code exclude} list: one
 * {@code group:artifact} per exclusion, with {@code *} where the POM wrote {@code *} or left the
 * side out — {@code group:*}, {@code *:artifact} or {@code *:*}, the spellings the lock prunes by.
 * An exclusion the grammar refuses is a report row naming it.
 */
final class ExclusionMapping {

    private ExclusionMapping() {}

    static Dependency apply(Dependency dep, Pom.Dep source, ImportReport.Builder report) {
        if (source.exclusions().isEmpty()) return dep;
        List<String> exclude = new ArrayList<>(source.exclusions().size());
        for (Pom.Dep.Exclusion exclusion : source.exclusions()) {
            String group = PluginFacts.usable(exclusion.groupId());
            String artifact = PluginFacts.usable(exclusion.artifactId());
            String spelling = (group == null ? "*" : group) + ":" + (artifact == null ? "*" : artifact);
            try {
                exclude.add(Dependency.exclusion(spelling));
            } catch (IllegalArgumentException e) {
                report.warning("`<exclusion>` " + spelling + " on " + source.module()
                        + " is not a coordinate jk prunes by (" + e.getMessage() + "); it was not written.");
            }
        }
        return exclude.isEmpty() ? dep : dep.withExclusions(exclude);
    }
}
