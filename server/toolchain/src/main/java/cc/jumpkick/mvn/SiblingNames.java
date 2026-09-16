// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two reactor leaves may share an artifactId under different groups; a Maven dependency tells them
 * apart by group, a jk workspace edge is spelled by module name alone. The import reports every
 * shared name: a Tier-2 row when no member depends on it, a Tier-3 row naming both paths and the
 * dependents when an edge would be ambiguous, since that edge cannot pick one member silently.
 */
final class SiblingNames {

    private SiblingNames() {}

    /** One member: its root-relative path and its {@code group:artifact}. */
    private record Carrier(String path, String ga) {}

    /** {@code modules} keyed by root-relative path, in workspace order. */
    static void report(Map<String, JkBuild> modules, ImportReport.Builder report) {
        Map<String, List<Carrier>> byName = new LinkedHashMap<>();
        for (Map.Entry<String, JkBuild> e : modules.entrySet()) {
            byName.computeIfAbsent(e.getValue().project().name(), k -> new ArrayList<>())
                    .add(new Carrier(e.getKey(), ga(e.getValue())));
        }
        for (Map.Entry<String, List<Carrier>> shared : byName.entrySet()) {
            List<Carrier> carriers = shared.getValue();
            if (carriers.size() < 2) continue;
            List<String> paths = carriers.stream().map(Carrier::path).toList();
            List<String> coords = carriers.stream().map(Carrier::ga).toList();
            List<String> dependents = new ArrayList<>();
            for (Map.Entry<String, JkBuild> e : modules.entrySet()) {
                if (!coords.contains(ga(e.getValue())) && dependsOnAny(e.getValue(), coords)) {
                    dependents.add(e.getKey());
                }
            }
            String both = "`" + String.join("` and `", paths) + "` both carry the name `" + shared.getKey() + "` ("
                    + String.join(", ", coords) + ")";
            if (dependents.isEmpty()) {
                report.warning(both + "; each builds into its own `target/<path>/`, and no member depends on the"
                        + " name, so nothing is ambiguous.");
                continue;
            }
            report.error(both + ", and `" + String.join("`, `", dependents)
                    + "` depend on it. A workspace edge is spelled by module name alone, so the edge is written as `"
                    + shared.getKey() + ".workspace = true` and jk refuses the workspace as ambiguous until one of"
                    + " the two modules is renamed.");
        }
    }

    /** True when {@code module} declares any of {@code coords} outside {@code [platform]}. */
    private static boolean dependsOnAny(JkBuild module, List<String> coords) {
        for (Scope scope : Scope.values()) {
            if (scope == Scope.PLATFORM) continue;
            for (Dependency d : module.dependencies().of(scope)) {
                if (coords.contains(d.module())) return true;
            }
        }
        return false;
    }

    private static String ga(JkBuild build) {
        return build.project().group() + ":" + build.project().name();
    }
}
