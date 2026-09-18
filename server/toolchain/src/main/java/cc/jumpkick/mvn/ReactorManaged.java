// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the reactor's own POMs manage — the root, every pom-packaged module and every BOM leaf, by
 * {@code group:artifact} — for the workspace pass that decides which of a member's versionless
 * declarations are written without a version. A reactor POM a member imports leaves {@code
 * [platform]} ({@link SiblingEdges}), so a version it supplied under Maven has nothing the lock
 * reads to supply it and is written as the effective POM resolved it; a version a published BOM or
 * parent chain supplies stays the platform's.
 */
final class ReactorManaged {

    private final Map<String, Map<String, String>> tables = new HashMap<>();

    /** Keep what the reactor POM {@code ga} manages, as a member importing it receives it. */
    void add(String ga, EffectiveModel model) {
        tables.put(ga, model.managedTable());
    }

    /**
     * The modules of {@code supplied} — what {@code member} declares without a version and a BOM
     * import, not an ancestor's own entry, versioned — less every module a reactor POM the member
     * imports manages at the version the member resolved: that version was the reactor POM's say,
     * and Maven's first import wins, so a published BOM behind it would say the same or not apply.
     * Empty when the member imports a reactor POM the walk kept no table for (an aggregator, a
     * module of an inactive profile): what it supplied cannot be told apart, so nothing is left to
     * the platform.
     */
    Set<String> keep(JkBuild member, Set<String> supplied, Map<String, ReactorModules.Unbuilt> unbuilt) {
        if (supplied.isEmpty()) return supplied;
        Map<String, String> reactor = new HashMap<>();
        for (Dependency bom : member.dependencies().of(Scope.PLATFORM)) {
            Map<String, String> table = tables.get(bom.module());
            if (table != null) {
                table.forEach(reactor::putIfAbsent);
            } else if (unbuilt.containsKey(bom.module())) {
                return Set.of();
            }
        }
        if (reactor.isEmpty()) return supplied;
        Set<String> keep = new HashSet<>(supplied);
        for (Map.Entry<Scope, List<Dependency>> e :
                member.dependencies().byScope().entrySet()) {
            if (e.getKey() == Scope.PLATFORM || e.getKey() == Scope.MANAGED) continue;
            for (Dependency d : e.getValue()) {
                if (d.version() instanceof VersionSelector.Exact exact
                        && exact.version().equals(reactor.get(d.module()))) {
                    keep.remove(d.module());
                }
            }
        }
        return keep;
    }
}
