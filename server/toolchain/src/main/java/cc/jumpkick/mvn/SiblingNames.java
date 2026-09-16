// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two reactor leaves may share an artifactId under different groups; a Maven dependency tells them
 * apart by group, and so does a group-qualified workspace edge ({@code edqs = { workspace = true,
 * group = "…" }}), which the import writes for every edge to a shared name. The import reports
 * every shared name: what each carrier builds into, and which dependents got the qualified edge.
 */
final class SiblingNames {

    private SiblingNames() {}

    /** One member: its root-relative path and its {@code group:artifact}. */
    private record Carrier(String path, String ga) {}

    /** The module names two or more of {@code modules} (keyed by root-relative path) carry. */
    static Set<String> shared(Map<String, JkBuild> modules) {
        Set<String> seen = new HashSet<>();
        Set<String> shared = new LinkedHashSet<>();
        for (JkBuild module : modules.values()) {
            if (!seen.add(module.project().name())) shared.add(module.project().name());
        }
        return shared;
    }

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
                if (dependsOnAnyOther(e.getValue(), coords)) dependents.add(e.getKey());
            }
            String all = "`" + String.join("` and `", paths) + "` all carry the name `" + shared.getKey() + "` ("
                    + String.join(", ", coords) + "); each builds into its own `target/<path>/`";
            if (dependents.isEmpty()) {
                report.warning(all + ", and no member depends on the name.");
                continue;
            }
            report.warning(all + ". `" + String.join("`, `", dependents)
                    + (dependents.size() == 1 ? "` depends" : "` depend")
                    + " on it, so each edge names the member's group: `" + shared.getKey()
                    + " = { workspace = true, group = \"…\" }`.");
        }
    }

    /** True when {@code module} declares any of {@code coords} but its own outside {@code [platform]}. */
    private static boolean dependsOnAnyOther(JkBuild module, List<String> coords) {
        String own = ga(module);
        for (Scope scope : Scope.values()) {
            if (scope == Scope.PLATFORM) continue;
            for (Dependency d : module.dependencies().of(scope)) {
                if (!d.module().equals(own) && coords.contains(d.module())) return true;
            }
        }
        return false;
    }

    private static String ga(JkBuild build) {
        return build.project().group() + ":" + build.project().name();
    }
}
