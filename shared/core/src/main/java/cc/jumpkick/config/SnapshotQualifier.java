// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code -SNAPSHOT} a {@code jk snapshot} run puts on every module's version, in the model the
 * build plans from. The declared version stays {@code 1.0.0} in {@code jk.toml} and the lock; the
 * effective one is {@code 1.0.0-SNAPSHOT} wherever the build reads a module's coordinate, and so is
 * the version of every edge onto a workspace sibling.
 */
public final class SnapshotQualifier {

    public static final String SUFFIX = "-SNAPSHOT";

    private SnapshotQualifier() {}

    /** True when the ambient session asked for snapshot versions. */
    public static boolean active() {
        return SessionContext.current().snapshot();
    }

    /** {@code version} as the ambient session sees it: with {@link #SUFFIX} under a snapshot run, else as declared. */
    public static String version(String version) {
        return active() && !version.endsWith(SUFFIX) ? version + SUFFIX : version;
    }

    /**
     * {@code module} with its version and its edges onto the workspace members in {@code siblings}
     * ({@code group:artifact}) qualified. Unchanged when the session did not ask for snapshots.
     */
    static JkBuild qualify(JkBuild module, Set<String> siblings) {
        if (!active()) return module;
        var p = module.project();
        String version = version(p.version());
        JkBuild out = version.equals(p.version()) ? module : module.withProject(p.withVersion(version));
        Map<Scope, List<Dependency>> by = new EnumMap<>(Scope.class);
        boolean changed = false;
        for (Scope scope : Scope.values()) {
            List<Dependency> deps = out.dependencies().of(scope);
            if (deps.isEmpty()) continue;
            List<Dependency> next = deps.stream()
                    .map(d -> siblings.contains(d.module()) ? qualified(d) : d)
                    .toList();
            changed |= !next.equals(deps);
            by.put(scope, next);
        }
        return changed ? out.withDependencies(new JkBuild.Dependencies(by)) : out;
    }

    private static Dependency qualified(Dependency d) {
        if (d.version() instanceof VersionSelector.Exact ex && !ex.version().endsWith(SUFFIX)) {
            return d.withVersion(VersionSelector.parse("=" + ex.version() + SUFFIX));
        }
        return d;
    }
}
