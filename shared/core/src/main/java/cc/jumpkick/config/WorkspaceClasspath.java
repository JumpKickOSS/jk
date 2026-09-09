// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Resolves workspace-sibling dependency jars (and tests kinds / fixtures) for one module's build.
 * Workspace coords are not in the lockfile; matching siblings contribute their main jar under the
 * shared {@link BuildLayout} {@code target/}, their test classes when an edge selects
 * {@link DependencyKind#TESTS}, and their fixtures directory when {@code fixtures = true}.
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
     * The module refs this manifest selects with {@code kind = "tests"} — the direct edges that put
     * a sibling's {@code classes/test} on this module's classpath, and so the only reason anything
     * outside a module can read its test compilation.
     *
     * <p>Direct edges only, because a tests kind never rides transitively: {@code workspaceClosure}
     * seeds {@code testsKinds} from the module's own declarations and never from a sibling's. Refs come back exactly as written — a {@code workspace:<name>} placeholder or a
     * full coord — because the caller is the one holding a sibling index to resolve them against.
     *
     * <p>Every scope is scanned even though {@code ManifestDeps} rejects a tests kind outside
     * {@code [test-dependencies]} and {@code [test-dev-dependencies]}. That rule is why the narrow
     * answer would be the same one; it is not a reason for a second place to depend on it.
     */
    public static Set<String> directTestsKindRefs(JkBuild project) {
        Set<String> refs = new LinkedHashSet<>();
        for (List<Dependency> deps : project.dependencies().byScope().values()) {
            for (Dependency dep : deps) {
                if (dep.kind() == DependencyKind.TESTS && dep.module() != null) refs.add(dep.module());
            }
        }
        return refs;
    }

    /**
     * @param projectDir the module being built
     * @param project the parsed manifest of {@code projectDir}
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
            rootManifest = JkBuildParser.parse(root.resolve(ManifestPaths.MANIFEST));
            if (!rootManifest.isWorkspaceRoot()) {
                return new Result(List.of(), List.of());
            }
        }

        Siblings sib =
                indexSiblings(root, rootManifest, projectDir.toAbsolutePath().normalize());
        Map<String, Path> siblingDirByModule = sib.dirByModule();
        Map<String, Path> siblingJarByModule = sib.jarByModule();
        Map<String, Path> siblingTestClassesByModule = sib.testClassesByModule();
        Map<String, Path> siblingTestResourcesByModule = sib.testResourcesByModule();
        Map<String, Path> siblingFixturesByModule = sib.fixturesByModule();
        Closure closure = workspaceClosure(project, scopes, sib);
        LinkedHashSet<String> visited = closure.visited();
        Set<String> testsKinds = closure.testsKinds();
        Set<String> fixturesKinds = closure.fixturesKinds();

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
            if (missingSibDir != null && !hasAnySource(missingSibDir.resolve("src"))) {
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
            if (fixturesKinds.contains(module)) {
                Path fixtures = siblingFixturesByModule.get(module);
                if (fixtures != null) {
                    closureJars.add(fixtures);
                    addIfPresent(
                            jars, seenPaths, fixtures, missing, module + " fixtures (expected at " + fixtures + ")");
                }
            }

            // Collect the sibling's lockfile so the caller can include its
            // external transitive deps on the compile classpath (e.g. tomlj
            // declared in jk-core is needed by jk-io via the transitive chain).
            Path sibDir = siblingDirByModule.get(module);
            if (sibDir != null) {
                Path lockFile = LockPaths.lockFile(sibDir);
                if (Files.exists(lockFile)) siblingLockfiles.add(lockFile);
            }
        }
        return new Result(jars, missing, siblingLockfiles, closureJars, List.copyOf(visited));
    }

    /** Every other build unit in the workspace, by {@code group:name} coord, with its layout paths. */
    private record Siblings(
            Map<String, Path> dirByModule,
            Map<String, Path> jarByModule,
            Map<String, Path> testClassesByModule,
            Map<String, Path> testResourcesByModule,
            Map<String, Path> fixturesByModule,
            Map<String, JkBuild> manifestByCoord,
            Map<String, String> coordByName) {}

    /**
     * Every build unit in the workspace — the members AND the buildable root — is a resolvable
     * sibling. Inter-unit dependencies are explicit (`x = { workspace = true }`) and may point in
     * any acyclic direction: member→member, member→root, or root→member (Cargo/uv style; no
     * implicit "root depends on all members"). The unit doing the resolving ({@code self}) is
     * excluded from its own sibling set.
     */
    private static Siblings indexSiblings(Path root, JkBuild rootManifest, Path self) throws IOException {
        Map<String, Path> siblingDirByModule = new HashMap<>();
        Map<String, Path> siblingJarByModule = new HashMap<>();
        Map<String, Path> siblingTestClassesByModule = new HashMap<>();
        Map<String, Path> siblingTestResourcesByModule = new HashMap<>();
        Map<String, Path> siblingFixturesByModule = new HashMap<>();
        Map<String, JkBuild> siblingManifestByCoord = new HashMap<>();
        Map<String, String> siblingCoordByName = new HashMap<>(); // name → full coord
        // One load for the whole workspace, not one parse per sibling.
        // loadModules returns every member with root inheritance applied — the index below. It
        // throws for a member with no manifest; applyWorkspace already rethrows for any module
        // carrying workspace deps, so a caller never reaches here with a broken workspace.
        // WorkspaceClasspathTest pins that from both sides.
        Map<Path, JkBuild> members = WorkspaceLoader.loadModules(root, rootManifest);
        Map<Path, JkBuild> units = new LinkedHashMap<>(members);
        units.put(root, rootManifest); // the root is a unit too
        for (Map.Entry<Path, JkBuild> unit0 : units.entrySet()) {
            Path unitDir = unit0.getKey();
            if (unitDir.toAbsolutePath().normalize().equals(self)) continue; // exclude self
            JkBuild unit = unit0.getValue();
            String coord = unit.project().group() + ":" + unit.project().name();
            BuildLayout layout = BuildLayout.of(unitDir, unit);
            siblingDirByModule.put(coord, unitDir);
            siblingJarByModule.put(coord, layout.mainJar());
            siblingTestClassesByModule.put(coord, layout.testClassesDir());
            siblingTestResourcesByModule.put(coord, layout.testResourcesDir());
            siblingFixturesByModule.put(coord, layout.testFixturesClassesDir());
            siblingManifestByCoord.put(coord, unit);
            siblingCoordByName.put(unit.project().name(), coord);
        }
        return new Siblings(
                siblingDirByModule,
                siblingJarByModule,
                siblingTestClassesByModule,
                siblingTestResourcesByModule,
                siblingFixturesByModule,
                siblingManifestByCoord,
                siblingCoordByName);
    }

    /** The transitive workspace closure in discovery order, plus the direct tests/fixtures asks. */
    private record Closure(LinkedHashSet<String> visited, Set<String> testsKinds, Set<String> fixturesKinds) {}

    /**
     * Collect the full transitive workspace closure via BFS. Direct deps seed the queue; each
     * discovered sibling's own workspace deps are then enqueued so that e.g. io→core→model are all
     * on the classpath even though the module's jk.toml only declares the direct dep (core).
     * Modules requested with kind=tests also contribute their test classes (direct edges only —
     * tests kind does not ride transitively). fixtures = true is the same shape for the fixtures
     * directory.
     */
    private static Closure workspaceClosure(JkBuild project, Set<Scope> scopes, Siblings sib) {
        Map<String, Path> siblingJarByModule = sib.jarByModule();
        Map<String, String> siblingCoordByName = sib.coordByName();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        Set<String> testsKinds = new HashSet<>();
        Set<String> fixturesKinds = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        for (Scope scope : scopes) {
            for (Dependency dep : project.dependencies().of(scope)) {
                String module = resolveWorkspaceRef(dep.module(), siblingCoordByName);
                if (!siblingJarByModule.containsKey(module)) continue;
                if (dep.kind() == DependencyKind.TESTS) {
                    testsKinds.add(module);
                }
                if (dep.fixtures()) {
                    fixturesKinds.add(module);
                }
                if (visited.add(module)) {
                    queue.add(module);
                }
            }
        }
        while (!queue.isEmpty()) {
            String coord = queue.poll();
            JkBuild sibBuild = sib.manifestByCoord().get(coord);
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
        return new Closure(visited, testsKinds, fixturesKinds);
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
         * Callers that do not distinguish the declared closure from the built jars (build/run):
         * the closure defaults to {@code jars}.
         */
        public Result(List<Path> jars, List<String> missingSiblingJars, List<Path> siblingLockfiles) {
            this(jars, missingSiblingJars, siblingLockfiles, jars, List.of());
        }

        /** No sibling lockfiles; the closure defaults to {@code jars}. */
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

    /** Existence probe — does not retain a listing. */
    private static boolean hasAnySource(Path src) {
        try {
            return PathUtil.anyRegularFile(src, d -> false, p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".groovy") || n.endsWith(".scala");
            });
        } catch (IOException e) {
            return false;
        }
    }
}
