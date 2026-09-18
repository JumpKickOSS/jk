// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositorySpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one {@code [repositories]} list a reactor's root carries, which every member's lock resolves
 * against: member ids made unique first, then the root's entries followed by every member's.
 */
final class WorkspaceRepositories {

    private WorkspaceRepositories() {}

    /**
     * Members whose {@code <repository>} reuses an id the root or an earlier member gave another
     * URL get that repository renamed — {@code nexus} at a second URL is {@code nexus-2} — since
     * a workspace resolves against one repository set keyed by id, where Maven keys each module's
     * own; the rename is a row naming the module, the id and both URLs. Ids at one URL, however
     * spelled, are one repository and stay.
     */
    static Map<String, JkBuild> disambiguateRepositories(
            List<RepositorySpec> root, Map<String, JkBuild> members, ImportReport.Builder report) {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        for (RepositorySpec spec : root) byName.putIfAbsent(spec.name(), spec);
        Map<String, JkBuild> out = new LinkedHashMap<>();
        for (Map.Entry<String, JkBuild> e : members.entrySet()) {
            JkBuild member = e.getValue();
            List<RepositorySpec> renamed = new ArrayList<>();
            boolean changed = false;
            for (RepositorySpec spec : member.repositories()) {
                RepositorySpec first = byName.get(spec.name());
                if (first != null && !first.sameRepository(spec)) {
                    String name = spec.name();
                    for (int n = 2; byName.containsKey(name); n++) name = spec.name() + "-" + n;
                    report.warning("`<repository>` `" + spec.name() + "` is " + spec.url() + " in `" + e.getKey()
                            + "` and " + first.url() + " elsewhere in the reactor; a workspace resolves against one"
                            + " repository set keyed by id, so this module's is written `" + name + "`.");
                    spec = new RepositorySpec(
                            name,
                            spec.url(),
                            spec.credential(),
                            spec.objectStore(),
                            spec.groups(),
                            spec.allowInsecure(),
                            spec.allowUnverified(),
                            spec.releases(),
                            spec.snapshots(),
                            spec.blocked());
                    changed = true;
                }
                byName.putIfAbsent(spec.name(), spec);
                renamed.add(spec);
            }
            out.put(e.getKey(), changed ? member.withRepositories(renamed) : member);
        }
        return out;
    }

    /**
     * The root's repositories followed by every member's, one entry per name, first declaration
     * wins — the list the workspace lock resolves every member against.
     */
    static List<RepositorySpec> hoistRepositories(List<RepositorySpec> root, Collection<JkBuild> members) {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        for (RepositorySpec spec : root) byName.putIfAbsent(spec.name(), spec);
        for (JkBuild member : members) {
            for (RepositorySpec spec : member.repositories()) byName.putIfAbsent(spec.name(), spec);
        }
        return List.copyOf(byName.values());
    }
}
