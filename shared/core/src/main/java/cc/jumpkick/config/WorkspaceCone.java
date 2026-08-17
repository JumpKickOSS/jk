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
     * {@code modulesByDir} when they match. Identity is the <em>canonical</em> (real) path —
     * same rule as the build graph — so symlinked checkouts do not silently drop prereqs: an
     * absolute-normalized-only compare missed a seed reached through a symlink, prereq
     * expansion stopped, and the target built without its dirty prereqs (JK-2101).
     */
    public static Set<Path> expand(Map<Path, JkBuild> modulesByDir, Collection<Path> seeds, Collection<Scope> scopes) {
        Map<Path, Path> byIdentity = new LinkedHashMap<>();
        Map<String, Path> dirByCoord = new LinkedHashMap<>();
        Map<String, Path> dirByName = new LinkedHashMap<>();
        for (var e : modulesByDir.entrySet()) {
            // Register both identities; canonical wins on lookup below.
            byIdentity.put(e.getKey().toAbsolutePath().normalize(), e.getKey());
            byIdentity.put(canon(e.getKey()), e.getKey());
            JkBuild b = e.getValue();
            dirByCoord.put(b.project().group() + ":" + b.project().name(), e.getKey());
            dirByName.put(b.project().name(), e.getKey());
        }
        Set<Path> want = new LinkedHashSet<>();
        ArrayDeque<Path> q = new ArrayDeque<>();
        for (Path s : seeds) {
            Path key = byIdentity.get(canon(s));
            if (key == null) key = byIdentity.getOrDefault(s.toAbsolutePath().normalize(), s);
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

    /** Canonical (real) path, falling back to absolute-normalized for paths that don't exist. */
    private static Path canon(Path p) {
        try {
            return p.toRealPath();
        } catch (Exception e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
