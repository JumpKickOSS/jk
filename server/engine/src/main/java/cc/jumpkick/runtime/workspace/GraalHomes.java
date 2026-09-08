// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.runtime.BuildGraph;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Resolve a client-supplied Graal home keyed by module dir (exact / absolute / canonical). */
public final class GraalHomes {

    private GraalHomes() {}

    public static @Nullable Path lookup(Path moduleDir, Map<Path, Path> graalByDir) {
        if (moduleDir == null || graalByDir == null || graalByDir.isEmpty()) return null;
        Path hit = graalByDir.get(moduleDir);
        if (hit != null) return hit;
        Path abs = moduleDir.toAbsolutePath().normalize();
        hit = graalByDir.get(abs);
        if (hit != null) return hit;
        Path canon = BuildGraph.canonicalPath(moduleDir);
        hit = graalByDir.get(canon);
        if (hit != null) return hit;
        for (var e : graalByDir.entrySet()) {
            Path k = e.getKey();
            if (k == null) continue;
            if (abs.equals(k.toAbsolutePath().normalize()) || canon.equals(BuildGraph.canonicalPath(k))) {
                return e.getValue();
            }
        }
        return null;
    }
}
