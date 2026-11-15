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
import java.util.concurrent.Callable;

/**
 * Whether a build is a snapshot, and the {@code -SNAPSHOT} it puts on every module's version. Every
 * build but {@code jk release} and {@code jk publish} is a snapshot of the next release: the declared
 * version stays {@code 1.0.0} in {@code jk.toml} and the lock, and the effective one is {@code
 * 1.0.0-SNAPSHOT} wherever the build reads a module's coordinate (its jar names, the version it stamps
 * into filtered resources, every edge onto a workspace sibling). Release and publish ship the declared
 * version.
 */
public final class SnapshotQualifier {

    public static final String SUFFIX = "-SNAPSHOT";

    private SnapshotQualifier() {}

    /** True unless the ambient session ships a release ({@code jk release}, {@code jk publish}). */
    public static boolean active() {
        return !SessionContext.current().release();
    }

    /** {@code version} as the ambient session sees it: with {@link #SUFFIX} in a snapshot build, else as declared. */
    public static String version(String version) {
        return active() && !version.endsWith(SUFFIX) ? version + SUFFIX : version;
    }

    /** {@code body}'s answer with every manifest read as declared, as the lock records a version. */
    public static <T> T asDeclared(Callable<T> body) throws Exception {
        return SessionContext.where(SessionContext.current().withRelease(true), body);
    }

    /**
     * {@code module} with its version and its edges onto the workspace members in {@code siblings}
     * ({@code group:artifact}) qualified. Unchanged in a release build.
     */
    public static JkBuild qualify(JkBuild module, Set<String> siblings) {
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
        return changed ? out.withDependencyScopes(by) : out;
    }

    /** True when {@code module} pins some edge to a release version, the only kind {@link #qualify} rewrites. */
    static boolean hasPinnedEdge(JkBuild module) {
        for (Scope scope : Scope.values()) {
            for (Dependency d : module.dependencies().of(scope)) {
                if (d.version() instanceof VersionSelector.Exact ex
                        && !ex.version().endsWith(SUFFIX)) return true;
            }
        }
        return false;
    }

    private static Dependency qualified(Dependency d) {
        if (d.version() instanceof VersionSelector.Exact ex && !ex.version().endsWith(SUFFIX)) {
            return d.withVersion(VersionSelector.parse("=" + ex.version() + SUFFIX));
        }
        return d;
    }
}
