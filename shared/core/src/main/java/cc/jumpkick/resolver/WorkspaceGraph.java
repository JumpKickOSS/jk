// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.WorkspaceMerge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The workspace siblings a {@code jk tree} render can see, and the disk reads that found them.
 *
 * <p>This is the "who are my neighbours" question, which {@link DependencyTree} asks but does not
 * answer: parsing each module's {@code jk.toml}, memoising the one shared {@code jk-lock.toml},
 * and deciding whether a given dependency string names a sibling. It is a separate owner from the
 * render because it is the only part that touches the filesystem — the render is a pure function of
 * this graph plus a {@link LockGraph}.
 *
 * <p>{@code byGa} being non-empty is what "the tree is rooted at a <em>member</em>" means, and it is
 * the whole of the expand-vs-collapse decision: only {@link #forMember} fills it, so only a
 * member-rooted tree can resolve a sibling to a real module node and walk into it. A root-rooted
 * tree gets {@link #collapse}, which fills the name map alone, and every sibling edge renders as a
 * {@code [workspace]} reference. There was a third component, {@code expandSiblings}, asserting the
 * same thing as a boolean; nothing ever read it, and a field that restates a structural invariant
 * without checking it is one more thing that can disagree with the structure.
 */
record WorkspaceGraph(Map<String, String> byName, Map<String, LoadedModule> byGa) {

    /** A workspace module loaded for the scope-first view (build is required; lock may be absent). */
    record LoadedModule(JkBuild build, @Nullable Lockfile lock) {}

    static WorkspaceGraph none() {
        return new WorkspaceGraph(Map.of(), Map.of());
    }

    static WorkspaceGraph collapse(Map<String, String> byName) {
        return new WorkspaceGraph(byName == null ? Map.of() : byName, Map.of());
    }

    /** Member-scoped tree: map sibling GAV → loaded module so workspace deps expand. */
    static WorkspaceGraph forMember(Path projectDir, Lockfile lock) {
        if (projectDir == null) return none();
        try {
            var rootDir = WorkspaceLocator.findRoot(projectDir);
            if (rootDir.isEmpty()) return none();
            Path root = rootDir.get();
            JkBuild rootBuild = JkBuildParser.parseLocal(root.resolve(ManifestPaths.MANIFEST));
            if (!rootBuild.isWorkspaceRoot()) return none();
            List<LoadedModule> loaded = loadModules(rootBuild.workspaceModules(), root, lock);
            List<JkBuild> siblingBuilds = new ArrayList<>(loaded.size());
            for (LoadedModule m : loaded) siblingBuilds.add(m.build());
            Map<String, String> byName = new HashMap<>();
            Map<String, LoadedModule> byGa = new HashMap<>();
            for (LoadedModule m : loaded) {
                JkBuild rewritten = WorkspaceMerge.resolveSiblingCoordinates(rootBuild, m.build(), siblingBuilds);
                // Members share the workspace lock GraphOps already loaded.
                Lockfile moduleLock = lock != null ? lock : m.lock();
                String ga = moduleGa(rewritten);
                byName.put(rewritten.project().name(), ga);
                byGa.put(ga, new LoadedModule(rewritten, moduleLock));
            }
            String rootGa = moduleGa(rootBuild);
            byName.putIfAbsent(rootBuild.project().name(), rootGa);
            byGa.putIfAbsent(rootGa, new LoadedModule(rootBuild, lock));
            return new WorkspaceGraph(byName, byGa);
        } catch (Exception e) {
            return none();
        }
    }

    /**
     * Map of each workspace module's short name → its full {@code group:artifact} coord, used to
     * resolve a sibling's {@code workspace:<name>} dep reference back to a readable coordinate when
     * collapsing it in a module's subtree.
     */
    static Map<String, String> modulesByName(List<String> modules, Path rootDir) {
        Map<String, String> byName = new HashMap<>();
        if (rootDir == null) return byName;
        for (String m : modules) {
            try {
                JkBuild b = JkBuildParser.parse(rootDir.resolve(m).normalize().resolve(ManifestPaths.MANIFEST));
                byName.put(
                        b.project().name(),
                        b.project().group() + ":" + b.project().name());
            } catch (Exception e) {
                // unreadable module jk.toml — sibling refs to it fall back to the raw module
                Log.debug("modulesByName: unreadable module jk.toml", e);
            }
        }
        return byName;
    }

    /**
     * Load each workspace module (declaration order); a module whose jk.toml can't be parsed is
     * dropped.
     */
    static List<LoadedModule> loadModules(List<String> moduleRels, Path rootDir) {
        return loadModules(moduleRels, rootDir, null);
    }

    /**
     * As {@link #loadModules(List, Path)}, but when {@code sharedLock} is non-null every member
     * uses it directly — modules never own a lockfile ({@code LockPaths} resolves each to the same
     * root {@code jk-lock.toml}), and re-parsing that ~2,300-line file once per member threw away
     * ~15 identical parses per render. With no shared lock, each distinct lock path is
     * still parsed at most once.
     */
    static List<LoadedModule> loadModules(List<String> moduleRels, Path rootDir, @Nullable Lockfile sharedLock) {
        JkBuild rootBuild = null;
        if (rootDir != null) {
            try {
                Path rootToml = rootDir.resolve(ManifestPaths.MANIFEST);
                if (Files.isRegularFile(rootToml)) rootBuild = JkBuildParser.parse(rootToml);
            } catch (Exception e) {
                // inheritance best-effort
                Log.debug("loadModules: inheritance best-effort", e);
            }
        }
        List<LoadedModule> modules = new ArrayList<>();
        Map<Path, Lockfile> lockMemo = new HashMap<>();
        for (String rel : moduleRels) {
            Path dir = rootDir == null ? null : rootDir.resolve(rel).normalize();
            JkBuild build = null;
            Lockfile lock = sharedLock;
            try {
                Path toml = dir == null ? null : dir.resolve(ManifestPaths.MANIFEST);
                if (toml != null && Files.isRegularFile(toml)) build = JkBuildParser.parseLocal(toml);
                if (lock == null && dir != null) {
                    Path lf = LockPaths.lockFile(dir);
                    if (lf != null && Files.isRegularFile(lf)) {
                        Path key = lf.toAbsolutePath().normalize();
                        if (!lockMemo.containsKey(key)) lockMemo.put(key, readLockOrNull(key));
                        lock = lockMemo.get(key);
                    }
                }
            } catch (Exception e) {
                // unreadable module — dropped (can't read its scopes)
                Log.debug("loadModules: unreadable module", e);
            }
            if (build != null) {
                if (rootBuild != null) {
                    build = WorkspaceLoader.inheritFromRoot(build, rootBuild);
                }
                modules.add(new LoadedModule(build, lock));
            }
        }
        return modules;
    }

    private static @Nullable Lockfile readLockOrNull(Path lockFile) {
        try {
            return LockfileReader.read(lockFile);
        } catch (Exception e) {
            return null;
        }
    }

    static String moduleGa(JkBuild build) {
        return build.project().group() + ":" + build.project().name();
    }

    private static String toGa(String module) {
        if (module == null || module.isEmpty()) return module;
        if (PackageId.isMavenPackageKey(module)) {
            try {
                return PackageId.parse(module).ga();
            } catch (RuntimeException e) {
                // fall through
                Log.debug("toGa: fall through", e);
            }
        }
        String[] p = module.split(":", 3);
        return p.length >= 2 ? p[0] + ":" + p[1] : module;
    }

    /** The loaded sibling {@code module} names, or {@code null} when it names no sibling here. */
    @Nullable
    LoadedModule sibling(String module) {
        if (byGa.isEmpty() && byName.isEmpty()) return null;
        if (Dependency.isWorkspaceRef(module)) {
            String name = Dependency.workspaceName(module);
            String ga = byName.get(name);
            return ga == null ? null : byGa.get(ga);
        }
        return byGa.get(toGa(module));
    }

    /** True when {@code module} names a workspace sibling (either form) in this graph. */
    boolean isSiblingDep(String module) {
        return Dependency.isWorkspaceRef(module) || sibling(module) != null;
    }

    /** The readable coordinate for a collapsed {@code workspace:<name>} reference. */
    String collapsedCoord(String module) {
        return byName.getOrDefault(Dependency.workspaceName(module), module);
    }

    /**
     * The scope surface a consumed workspace sibling contributes to its consumer: export, main,
     * runtime — matching {@code ModuleRuntimeClasspath}/{@code WorkspaceClasspath}. A sibling's
     * test/dev/processor deps never ride the member's classpath, and its export/runtime deps
     * always do — regardless of which section of the member declared the sibling.
     */
    static List<Scope> siblingContributedScopes() {
        return List.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);
    }

    /**
     * True when a sibling's dependencies in {@code s} chain onward as module (workspace) edges.
     * {@code WorkspaceClasspath} never adds a sibling's RUNTIME module deps to the consumer's
     * classpath, so the tree must not draw them either.
     */
    static boolean chainsModuleEdges(Scope s) {
        return WorkspaceClasspath.SIBLING_MODULE_SCOPES.contains(s);
    }
}
