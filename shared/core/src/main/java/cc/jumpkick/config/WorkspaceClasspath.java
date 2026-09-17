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
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Resolves workspace-sibling dependencies (and tests kinds / fixtures) for one module's build.
 * Workspace coords are not in the lockfile; matching siblings contribute under the shared {@link
 * BuildLayout} {@code target/}: their {@code classes/main} tree to a compile classpath and their
 * main jar to a runtime one, their test classes when an edge selects {@link DependencyKind#TESTS},
 * and their fixtures directory when {@code fixtures = true}.
 *
 * <p>The two views exist because they are ready at different moments. A sibling's classes tree is
 * whole once its compile (and the classes assembler of a mixed module) has run; its jar only once
 * it has packaged. A consumer's compile reads the tree, so it can be admitted before the sibling
 * packages, tests or builds its native tail; everything that runs the sibling — packaging,
 * tests, a native image — reads the jar, which carries the resources and the plugin-contributed
 * classes the tree alone does not.
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

    /**
     * The direct edges a module's main compile reads: its export, main and provided siblings, as
     * Maven's compile classpath holds a {@code provided} dependency. Packaging and running read
     * {@link #RUNTIME_SCOPES}, where a provided sibling is absent, the platform supplying it.
     */
    public static final Set<Scope> COMPILE_SCOPES = Set.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED);

    /** The direct edges a module's test compile and test run read: {@link #COMPILE_SCOPES} plus the test scopes. */
    public static final Set<Scope> TEST_SCOPES =
            Set.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED, Scope.TEST, Scope.TEST_DEV);

    /** The direct edges whose jars ride into a module's package, run and native image. */
    public static final Set<Scope> RUNTIME_SCOPES = Set.of(Scope.EXPORT, Scope.MAIN);

    private WorkspaceClasspath() {}

    /**
     * The module refs this manifest selects with {@code kind = "tests"} — the direct edges that put
     * a sibling's {@code classes/test} on this module's classpath, and so the only reason anything
     * outside a module can read its test compilation.
     *
     * <p>Direct edges only, because a tests kind never rides transitively: {@code workspaceClosure}
     * seeds {@code testsKinds} from the module's own declarations and never from a sibling's.
     *
     * <p>Refs come back exactly as written — a {@code workspace:<name>} placeholder or a full coord
     * — because the caller is the one holding a sibling index to resolve them against.
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
     * Whether this manifest puts a sibling's test output on its own test classpath: a {@code kind =
     * "tests"} edge selects the sibling's test classes, {@code fixtures = true} its fixtures. Both
     * are outputs of the sibling's test stage rather than of its main compile, so a plan that
     * selects either waits for the sibling's artifacts before compiling its tests; one that selects
     * neither has nothing of a sibling's to wait for there. An external test-jar declared with a
     * tests kind answers true as well — a wait too many, never one too few.
     */
    public static boolean selectsTestOutputs(JkBuild project) {
        for (List<Dependency> deps : project.dependencies().byScope().values()) {
            for (Dependency dep : deps) {
                if (dep.kind() == DependencyKind.TESTS || dep.fixtures()) return true;
            }
        }
        return false;
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
            rootManifest = JkBuildParser.parse(ManifestPaths.manifestIn(root));
            if (!rootManifest.isWorkspaceRoot()) {
                return new Result(List.of(), List.of());
            }
        }

        Siblings sib =
                indexSiblings(root, rootManifest, projectDir.toAbsolutePath().normalize());
        Map<String, Path> siblingDirByModule = sib.dirByModule();
        Map<String, Path> siblingJarByModule = sib.jarByModule();
        Map<String, Path> siblingClassesByModule = sib.classesByModule();
        Map<String, Path> siblingTestClassesByModule = sib.testClassesByModule();
        Map<String, Path> siblingTestResourcesByModule = sib.testResourcesByModule();
        Map<String, Path> siblingFixturesByModule = sib.fixturesByModule();
        Closure closure = workspaceClosure(project, scopes, sib);
        LinkedHashSet<String> visited = closure.visited();
        Set<String> testsKinds = closure.testsKinds();
        Set<String> fixturesKinds = closure.fixturesKinds();

        List<Path> jars = new ArrayList<>();
        List<Path> closureJars = new ArrayList<>();
        List<Path> closureClasses = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> missingClasses = new ArrayList<>();
        List<Path> siblingLockfiles = new ArrayList<>();
        LinkedHashSet<Path> seenPaths = new LinkedHashSet<>();
        for (String module : visited) {
            Path siblingJar = siblingJarByModule.get(module);
            if (siblingJar == null) continue;
            Path siblingClasses = Objects.requireNonNull(siblingClassesByModule.get(module), "sibling classes");
            // The full declared closure, whether or not the sibling is built yet —
            // an IDE module graph depends on declared edges, not on compiled
            // artifacts (IntelliJ compiles the modules itself), and an action key on
            // deterministic paths reads the same after `jk clean`.
            closureJars.add(siblingJar);
            closureClasses.add(siblingClasses);
            Path missingSibDir = siblingDirByModule.get(module);
            boolean sourceless = missingSibDir != null
                    && (!Files.exists(siblingJar) || !Files.isDirectory(siblingClasses))
                    && !hasAnySource(missingSibDir.resolve("src"));
            String missingLabel = module + " (expected at " + siblingJar + ")";
            String missingClassesLabel = module + " (expected classes at " + siblingClasses + ")";
            if (sourceless) {
                // Name the real cause: the sibling was never going to compile anything — its
                // outputs only appear once the module is scheduled and packages empty.
                missingLabel = module + " has no sources — jk packages an empty jar for it once the"
                        + " module is scheduled; expected at " + siblingJar;
                missingClassesLabel = module + " has no sources — jk creates an empty classes tree for"
                        + " it once the module is scheduled; expected at " + siblingClasses;
            }
            addIfPresent(jars, seenPaths, siblingJar, missing, missingLabel);
            if (!Files.isDirectory(siblingClasses)) missingClasses.add(missingClassesLabel);

            if (testsKinds.contains(module)) {
                // Main output is always required for a tests kind (test classes
                // reference main). Test classes dir is the monorepo stand-in for
                // a Maven test-jar; test resources ride along when present.
                Path testClasses = siblingTestClassesByModule.get(module);
                if (testClasses != null) {
                    closureJars.add(testClasses);
                    closureClasses.add(testClasses);
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
                        closureClasses.add(testResources);
                    }
                }
            }
            if (fixturesKinds.contains(module)) {
                Path fixtures = siblingFixturesByModule.get(module);
                if (fixtures != null) {
                    closureJars.add(fixtures);
                    closureClasses.add(fixtures);
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
        return new Result(
                jars, missing, siblingLockfiles, closureJars, List.copyOf(visited), closureClasses, missingClasses);
    }

    /** Every other build unit in the workspace, by {@code group:name} coord, with its layout paths. */
    private record Siblings(
            Map<String, Path> dirByModule,
            Map<String, Path> jarByModule,
            Map<String, Path> classesByModule,
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
        Map<String, Path> siblingClassesByModule = new HashMap<>();
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
            siblingClassesByModule.put(coord, layout.classesDir());
            siblingTestClassesByModule.put(coord, layout.testClassesDir());
            siblingTestResourcesByModule.put(coord, layout.testResourcesDir());
            siblingFixturesByModule.put(coord, layout.testFixturesClassesDir());
            siblingManifestByCoord.put(coord, unit);
            siblingCoordByName.put(unit.project().name(), coord);
        }
        return new Siblings(
                siblingDirByModule,
                siblingJarByModule,
                siblingClassesByModule,
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
            // Tests kind never propagates transitively, and neither does an optional
            // edge: it is the sibling's own, as a POM's optional dependency is.
            for (Scope scope : SIBLING_MODULE_SCOPES) {
                for (Dependency dep : sibBuild.dependencies().of(scope)) {
                    if (dep.optional()) continue;
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

    /** Resolve a {@code workspace:<name>} or group-qualified dep reference to its full {@code group:name} coord. */
    private static String resolveWorkspaceRef(String module, Map<String, String> coordByName) {
        if (!Dependency.isWorkspaceRef(module)) return module;
        String qualified = Dependency.workspaceCoordinate(module);
        if (qualified != null) return qualified;
        String coord = coordByName.get(Dependency.workspaceName(module));
        return coord != null ? coord : module;
    }

    /**
     * @param jars the runtime view as built so far: every sibling main jar, tests-kind test
     *     classes, test resources and fixtures directory that is on disk
     * @param missingSiblingJars the runtime-view entries that are not on disk, each named with its
     *     cause — a package, test or native step's concern
     * @param siblingLockfiles the siblings' lockfiles, for their external transitive deps
     * @param siblingClosureJars the declared runtime view, built or not: main jars, then the test
     *     classes, test resources and fixtures of the direct edges that select them
     * @param siblingCoords full {@code group:name} coords of workspace siblings in this resolve
     * @param siblingClosureClasses the declared compile view: {@link BuildLayout#classesDir} per
     *     sibling in place of its jar, then the same test classes, test resources and fixtures.
     *     A compile classpath, and the key that fingerprints it, read this list
     * @param missingSiblingClasses the siblings whose classes tree is not on disk, each named with
     *     its cause — the compile's concern, and the only sibling absence a compile has to fail on
     */
    public record Result(
            List<Path> jars,
            List<String> missingSiblingJars,
            List<Path> siblingLockfiles,
            List<Path> siblingClosureJars,
            List<String> siblingCoords,
            List<Path> siblingClosureClasses,
            List<String> missingSiblingClasses) {
        public Result {
            jars = List.copyOf(jars);
            missingSiblingJars = List.copyOf(missingSiblingJars);
            siblingLockfiles = List.copyOf(siblingLockfiles);
            siblingClosureJars = List.copyOf(siblingClosureJars);
            siblingCoords = List.copyOf(siblingCoords);
            siblingClosureClasses = List.copyOf(siblingClosureClasses);
            missingSiblingClasses = List.copyOf(missingSiblingClasses);
        }

        /**
         * Callers that do not distinguish the declared closure from the built jars (build/run):
         * the closure, in both views, defaults to {@code jars}.
         */
        public Result(List<Path> jars, List<String> missingSiblingJars, List<Path> siblingLockfiles) {
            this(jars, missingSiblingJars, siblingLockfiles, jars, List.of(), jars, List.of());
        }

        /** No sibling lockfiles; the closure defaults to {@code jars} in both views. */
        public Result(List<Path> jars, List<String> missingSiblingJars) {
            this(jars, missingSiblingJars, List.of(), jars, List.of(), jars, List.of());
        }

        public Result(
                List<Path> jars,
                List<String> missingSiblingJars,
                List<Path> siblingLockfiles,
                List<Path> siblingClosureJars) {
            this(
                    jars,
                    missingSiblingJars,
                    siblingLockfiles,
                    siblingClosureJars,
                    List.of(),
                    siblingClosureJars,
                    List.of());
        }

        public Result(
                List<Path> jars,
                List<String> missingSiblingJars,
                List<Path> siblingLockfiles,
                List<Path> siblingClosureJars,
                List<String> siblingCoords) {
            this(
                    jars,
                    missingSiblingJars,
                    siblingLockfiles,
                    siblingClosureJars,
                    siblingCoords,
                    siblingClosureJars,
                    List.of());
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
