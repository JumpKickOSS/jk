// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Dependency cone from seed modules. Used by workspace execute so native/image selection is a
 * filter on the same graph, not a second orchestrator.
 */
public final class WorkspaceCone {

    private WorkspaceCone() {}

    /**
     * Seeds plus transitive prereqs under {@code scopes}. Paths are returned as keys from
     * {@code modulesByDir} when they match (absolute-normalized compare).
     */
    public static Set<Path> expand(Map<Path, JkBuild> modulesByDir, Collection<Path> seeds, Collection<Scope> scopes) {
        Map<Path, Path> byNorm = new LinkedHashMap<>();
        Map<String, Path> dirByCoord = new LinkedHashMap<>();
        Map<String, Path> dirByName = new LinkedHashMap<>();
        for (var e : modulesByDir.entrySet()) {
            Path n = e.getKey().toAbsolutePath().normalize();
            byNorm.put(n, e.getKey());
            JkBuild b = e.getValue();
            dirByCoord.put(b.project().group() + ":" + b.project().name(), e.getKey());
            dirByName.put(b.project().name(), e.getKey());
        }
        Set<Path> want = new LinkedHashSet<>();
        ArrayDeque<Path> q = new ArrayDeque<>();
        for (Path s : seeds) {
            Path key = byNorm.getOrDefault(s.toAbsolutePath().normalize(), s);
            if (want.add(key)) q.add(key);
        }
        while (!q.isEmpty()) {
            Path d = q.poll();
            JkBuild m = modulesByDir.get(d);
            if (m == null) continue;
            for (Path pre : ModuleOrder.modulePrereqs(d, m, dirByCoord, dirByName, scopes)) {
                if (want.add(pre)) q.add(pre);
            }
        }
        return want;
    }
}
