// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Resolves workspace-sibling dependency jars (and tests kinds) for one module's build. Workspace
 * coords are not in the lockfile; matching siblings contribute their main jar under the shared
 * {@link BuildLayout} {@code target/}, and optionally their test classes when an edge selects
 * {@link DependencyKind#TESTS} (Mill {@code testModuleDeps} / Maven test-jar).
 */
public final class WorkspaceClasspath {

    /**
     * Scopes through which a consumed sibling's MODULE (workspace) deps propagate to its
     * consumer's classpath. A sibling's {@code runtime-dependencies} that are themselves
     * workspace modules do NOT ride — only export/main module edges chain. This is the single
     * source of truth: {@code jk tree}'s sibling walk mirrors it, so display and the
     * real classpath cannot drift apart again.
     */
    public static final List<Scope> SIBLING_MODULE_SCOPES = List.of(Scope.EXPORT, Scope.MAIN);

    private WorkspaceClasspath() {}

    /**
     * @param moduleDir the module being built
     * @param module the parsed manifest of {@code moduleDir}
     * @param scopes the scopes whose deps should contribute (typically {@code MAIN} for compile,
     *     {@code MAIN}+{@code TEST} for tests)
     */
    public static Result resolve(Path projectDir, JkBuild project, Set<Scope> scopes) throws IOException {
        // Locate the workspace root — this project is either the root itself or a member.
        Path root;
        JkBuild rootManifest;
        if (project.isWorkspaceRoot()) {
            root = projectDir;
            rootManifest = project;
        } else {
            var rootOpt = WorkspaceLocator.findRoot(projectDir);
            if (rootOpt.isEmpty()) {
                return new Result(List.of(), List.of());
            }
            root = rootOpt.get();
            rootManifest = JkBuildParser.parse(root.resolve("jk.toml"));
            if (!rootManifest.isWorkspaceRoot()) {
                return new Result(List.of(), List.of());
            }
        }

        // Every build unit in the workspace — the members AND the buildable root — is a
        // resolvable sibling. Inter-unit dependencies are explicit (`x = { workspace = true }`)
        // and may point in any acyclic direction: member→member, member→root, or root→member
        // (Cargo/uv style; no implicit "root depends on all members"). The unit doing the
        // resolving is excluded from its own sibling set.
        Path self = projectDir.toAbsolutePath().normalize();
        Map<String, Path> siblingDirByModule = new HashMap<>();
        Map<String, Path> siblingJarByModule = new HashMap<>();
        Map<String, Path> siblingTestClassesByModule = new HashMap<>();
        Map<String, Path> siblingTestResourcesByModule = new HashMap<>();
        Map<String, JkBuild> siblingManifestByCoord = new HashMap<>();
        Map<String, String> siblingCoordByName = new HashMap<>(); // name → full coord
        List<Path> unitDirs = new ArrayList<>();
        for (String moduleName : rootManifest.workspace().modules()) {
            unitDirs.add(root.resolve(moduleName));
        }
        unitDirs.add(root); // the root is a unit too
        for (Path unitDir : unitDirs) {
            if (unitDir.toAbsolutePath().normalize().equals(self)) continue; // exclude self
            Path manifest = unitDir.resolve("jk.toml");
            if (!Files.exists(manifest)) continue;
            JkBuild unit;
            try {
                unit = unitDir.equals(root) ? rootManifest : JkBuildParser.parse(manifest);
            } catch (RuntimeException ignored) {
                continue;
            }
            String coord = unit.project().group() + ":" + unit.project().name();
            BuildLayout layout = BuildLayout.of(unitDir, unit);
            siblingDirByModule.put(coord, unitDir);
            siblingJarByModule.put(coord, layout.mainJar());
            siblingTestClassesByModule.put(coord, layout.testClassesDir());
            siblingTestResourcesByModule.put(coord, layout.testResourcesDir());
            siblingManifestByCoord.put(coord, unit);
            siblingCoordByName.put(unit.project().name(), coord);
        }

        // Collect the full transitive workspace closure via BFS.  Direct deps
        // seed the queue; each discovered sibling's own workspace deps are then
        // enqueued so that e.g. io→core→model are all on the classpath even
        // though the module's jk.toml only declares the direct dep (core).
        // Modules requested with kind=tests also contribute their test
        // classes (direct edges only — tests kind does not ride transitively).
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        Set<String> testsKinds = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        for (Scope scope : scopes) {
            for (Dependency dep : project.dependencies().of(scope)) {
                String module = resolveWorkspaceRef(dep.module(), siblingCoordByName);
                if (!siblingJarByModule.containsKey(module)) continue;
                if (dep.kind() == DependencyKind.TESTS) {
                    testsKinds.add(module);
                }
                if (visited.add(module)) {
                    queue.add(module);
                }
            }
        }
        while (!queue.isEmpty()) {
            String coord = queue.poll();
            JkBuild sibBuild = siblingManifestByCoord.get(coord);
            if (sibBuild == null) continue;
            // MAIN and EXPORT propagate transitively: a sibling's exported deps
            // (api semantics) ride along to anything that depends on it, and MAIN
            // deps stay visible down the workspace chain (io→core→model).
            // Tests kind never propagates transitively.
            for (Scope scope : SIBLING_MODULE_SCOPES) {
                for (Dependency dep : sibBuild.dependencies().of(scope)) {
                    String depModule = resolveWorkspaceRef(dep.module(), siblingCoordByName);
                    if (siblingJarByModule.containsKey(depModule) && visited.add(depModule)) {
                        queue.add(depModule);
                    }
                }
            }
        }

        List<Path> jars = new ArrayList<>();
        List<Path> closureJars = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<Path> siblingLockfiles = new ArrayList<>();
        LinkedHashSet<Path> seenPaths = new LinkedHashSet<>();
        for (String module : visited) {
            Path siblingJar = siblingJarByModule.get(module);
            if (siblingJar == null) continue;
            // The full declared closure, whether or not the jar is built yet —
            // an IDE module graph depends on declared edges, not on compiled
            // artifacts (IntelliJ compiles the modules itself).
            closureJars.add(siblingJar);
            String missingLabel = module + " (expected at " + siblingJar + ")";
            Path missingSibDir = siblingDirByModule.get(module);
            if (missingSibDir != null
                    && !cc.jumpkick.layout.Languages.anySourceUnder(missingSibDir.resolve("src"), ".java")
                    && !cc.jumpkick.layout.Languages.anySourceUnder(missingSibDir.resolve("src"), ".kt")
                    && !cc.jumpkick.layout.Languages.anySourceUnder(missingSibDir.resolve("src"), ".groovy")
                    && !cc.jumpkick.layout.Languages.anySourceUnder(missingSibDir.resolve("src"), ".scala")) {
                // Name the real cause: the sibling was never going to compile anything — its jar
                // only appears once the module is scheduled and packages empty.
                missingLabel = module + " has no sources — jk packages an empty jar for it once the"
                        + " module is scheduled; expected at " + siblingJar;
            }
            addIfPresent(jars, seenPaths, siblingJar, missing, missingLabel);

            if (testsKinds.contains(module)) {
                // Main jar is always required for a tests kind (test classes
                // reference main). Test classes dir is the monorepo stand-in for
                // a Maven test-jar; test resources ride along when present.
                Path testClasses = siblingTestClassesByModule.get(module);
                if (testClasses != null) {
                    closureJars.add(testClasses);
                    addIfPresent(
                            jars,
                            seenPaths,
                            testClasses,
                            missing,
                            module + " tests kind (expected test classes at " + testClasses + ")");
                }
                Path testResources = siblingTestResourcesByModule.get(module);
                if (testResources != null && Files.isDirectory(testResources)) {
                    if (seenPaths.add(testResources)) {
                        jars.add(testResources);
                        closureJars.add(testResources);
                    }
                }
            }

            // Collect the sibling's lockfile so the caller can include its
            // external transitive deps on the compile classpath (e.g. tomlj
            // declared in jk-core is needed by jk-io via the transitive chain).
            Path sibDir = siblingDirByModule.get(module);
            if (sibDir != null) {
                Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(sibDir);
                if (Files.exists(lockFile)) siblingLockfiles.add(lockFile);
            }
        }
        return new Result(jars, missing, siblingLockfiles, closureJars, List.copyOf(visited));
    }

    private static void addIfPresent(
            List<Path> jars, Set<Path> seen, Path path, List<String> missing, String missingLabel) {
        if (path == null) return;
        if (!seen.add(path)) return;
        if (Files.exists(path)) {
            jars.add(path);
        } else {
            missing.add(missingLabel);
        }
    }

    /** Resolve a {@code workspace:<name>} dep reference to its full {@code group:name} coord. */
    private static String resolveWorkspaceRef(String module, Map<String, String> coordByName) {
        if (!Dependency.isWorkspaceRef(module)) return module;
        String name = Dependency.workspaceName(module);
        String coord = coordByName.get(name);
        return coord != null ? coord : module;
    }

    public record Result(
            List<Path> jars,
            List<String> missingSiblingJars,
            List<Path> siblingLockfiles,
            List<Path> siblingClosureJars,
            /** Full {@code group:name} coords of workspace siblings in this resolve (built or not). */
            List<String> siblingCoords) {
        public Result {
            jars = List.copyOf(jars);
            missingSiblingJars = List.copyOf(missingSiblingJars);
            siblingLockfiles = List.copyOf(siblingLockfiles);
            siblingClosureJars = List.copyOf(siblingClosureJars);
            siblingCoords = List.copyOf(siblingCoords);
        }

        /**
         * Back-compat constructor for callers that don't distinguish the declared closure from the
         * built jars (build/run): the closure defaults to {@code jars}.
         */
        public Result(List<Path> jars, List<String> missingSiblingJars, List<Path> siblingLockfiles) {
            this(jars, missingSiblingJars, siblingLockfiles, jars, List.of());
        }

        /** Back-compat constructor for callers that don't use sibling lockfiles. */
        public Result(List<Path> jars, List<String> missingSiblingJars) {
            this(jars, missingSiblingJars, List.of(), jars, List.of());
        }

        public Result(
                List<Path> jars,
                List<String> missingSiblingJars,
                List<Path> siblingLockfiles,
                List<Path> siblingClosureJars) {
            this(jars, missingSiblingJars, siblingLockfiles, siblingClosureJars, List.of());
        }
    }
}
