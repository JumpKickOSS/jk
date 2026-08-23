// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.ModuleRuntimeClasspath;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ActionCache;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Classpath, packaging cache, nested-engine test isolation, and contribution staging.
 */
public final class PlannerSupport {

    private PlannerSupport() {}

    /**
     * The resolved paths of {@code [[contribute.provided-classpath]]} entries — declared
     * step-dependency artifacts (an SDK platform jar) that join the COMPILE classpaths only.
     */
    static List<Path> contributedProvidedClasspath(
            cc.jumpkick.model.JkBuild project, BuildPlanner.Inputs in, cc.jumpkick.cache.Cas cas) {
        List<String> names = cc.jumpkick.plugin.manifest.PluginContributions.providedClasspath(project, in.dir());
        if (names.isEmpty()) return List.of();
        try {
            Map<String, Path> fetched =
                    PluginBuild.fetchStepDependencies(project, in.dir(), cas, PluginBuild.sdkPins(in.lockFile()));
            List<Path> out = new ArrayList<>();
            for (String name : names) {
                Path path = fetched.get(name);
                if (path == null) {
                    throw new RuntimeException("[[contribute.provided-classpath]] names `" + name
                            + "` but no step-dependency resolved under that artifact name");
                }
                out.add(path);
            }
            return out;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("cannot resolve the plugin-contributed compile classpath: " + e.getMessage(), e);
        }
    }

    /**
     * The {@code -processorpath} / KSP processor classpath: the lock's PROCESSOR scope plus any
     * workspace siblings declared in {@code [processor-dependencies]} and their own external
     * closures.
     *
     * <p>A processor runs as a program, so it needs its own dependencies (a KSP processor needs
     * {@code symbol-processing-api}, an emitter library, …) — hence the sibling-lockfile loop,
     * mirroring {@link #mainCompileClasspath}. Sibling jars come from the declared closure rather
     * than the built set so {@code jk explain} reproduces the same action key after a clean.
     */
    public static List<Path> processorClasspath(
            Lockfile lock, ClasspathResolver resolver, WorkspaceClasspath.Result siblings) throws IOException {
        return processorClasspath(lock, resolver, siblings, false);
    }

    public static List<Path> processorClasspath(
            Lockfile lock, ClasspathResolver resolver, WorkspaceClasspath.Result siblings, boolean requirePresent)
            throws IOException {
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, Set.of(Scope.PROCESSOR), requirePresent));
        for (Path jar : siblings.siblingClosureJars()) {
            if (!cp.contains(jar)) cp.add(jar);
        }
        for (Path sibLock : siblings.siblingLockfiles()) {
            try {
                Lockfile sl = LockfileReader.read(sibLock);
                for (Path p : resolver.classpathFor(sl, ClasspathResolver.COMPILE_MAIN, requirePresent)) {
                    if (!cp.contains(p)) cp.add(p);
                }
            } catch (Exception ignored) {
                /* best-effort: a sibling's lock may be absent */
            }
        }
        return cp;
    }

    /**
     * Declared {@code [processor-dependencies]} entries that resolve to nothing — not a workspace
     * sibling and absent from the lock. Silently skipping code generation is the worst failure mode
     * for an annotation-driven project, so callers turn this into a build error.
     *
     * <p>After {@link cc.jumpkick.model.WorkspaceMerge#resolveSiblingCoordinates}, workspace
     * processors are real {@code group:name} coords (so {@link
     * cc.jumpkick.model.Dependency#isWorkspace()} is false) and still never appear in the lock —
     * pass the {@link WorkspaceClasspath} result so rewritten siblings stay covered.
     */
    public static List<String> unresolvedProcessorDeps(JkBuild project, Lockfile lock) {
        return unresolvedProcessorDeps(project, lock, null);
    }

    public static List<String> unresolvedProcessorDeps(
            JkBuild project, Lockfile lock, WorkspaceClasspath.Result processorSiblings) {
        Set<String> locked = lockModules(lock);
        Set<String> siblings = new HashSet<>();
        if (processorSiblings != null) {
            siblings.addAll(processorSiblings.siblingCoords());
        }
        List<String> missing = new ArrayList<>();
        for (cc.jumpkick.model.Dependency dep : project.dependencies().of(Scope.PROCESSOR)) {
            if (dep.isWorkspace()) continue; // covered by the missing-sibling guard
            if (siblings.contains(dep.module())) continue;
            if (!locked.contains(dep.module())) missing.add(dep.module());
        }
        return missing;
    }

    public static List<Path> mainCompileClasspath(
            Lockfile lock, ClasspathResolver resolver, WorkspaceClasspath.Result siblings) throws IOException {
        return mainCompileClasspath(lock, resolver, siblings, false);
    }

    public static List<Path> mainCompileClasspath(
            Lockfile lock, ClasspathResolver resolver, WorkspaceClasspath.Result siblings, boolean requirePresent)
            throws IOException {
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_MAIN, requirePresent));
        // The declared closure (deterministic jar paths) — not just the built ones
        // so the action key is stable whether or not target/ is currently populated.
        // In a valid build the siblings are all built (the missing-sibling check
        // upstream guarantees it), so these are the same paths javac compiles against;
        // after `jk clean` they still let `jk explain` reproduce the build's key.
        cp.addAll(siblings.siblingClosureJars());
        for (Path sibLock : siblings.siblingLockfiles()) {
            try {
                Lockfile sl = LockfileReader.read(sibLock);
                for (Path p : resolver.classpathFor(sl, ClasspathResolver.COMPILE_MAIN, requirePresent)) {
                    if (!cp.contains(p)) cp.add(p);
                }
            } catch (Exception ignored) {
                /* best-effort: a sibling's lock may be absent */
            }
        }
        return cp;
    }

    /**
     * Jars to embed in an assembly / native-image classpath for one module — delegates to {@link
     * ModuleRuntimeClasspath}.
     */
    static List<Path> assemblyDependencyJars(Path moduleDir, JkBuild project, Path lockFile, Path cache)
            throws IOException {
        return ModuleRuntimeClasspath.jars(moduleDir, project, lockFile, JkStores.cas(cache));
    }

    /**
     * Compile Kotlin {@code sources} into {@code outputDir} via the plugin (action-cached: restores
     * from the CAS on an exact-input hit without launching the plugin, else compiles incrementally).
     * Shared by the main {@code compile-kotlin} and {@code compile-test} steps. The caller owns
     * freshness stamps, output assembly, and outcome reporting.
     *
     * @param javaSourceRoots when non-empty, passed as {@code -Xjava-source-roots} so a mixed module's
     * Kotlin can read Java declarations from source
     */
    static List<Path> mainStampClasspath(
            List<Path> baseClasspath,
            List<Path> processorCp,
            boolean mixedKotlin,
            boolean mixedGroovy,
            BuildLayout layout,
            Path groovyCompileJar) {
        List<Path> inputs = new ArrayList<>(baseClasspath);
        if (mixedKotlin) inputs.add(layout.kotlinClassesDir());
        if (mixedGroovy) {
            inputs.add(layout.groovyClassesDir());
            if (groovyCompileJar != null) inputs.add(groovyCompileJar);
        }
        if (processorCp != null) inputs.addAll(processorCp);
        return inputs;
    }

    /**
     * Scala stdlib jars to fold into the freshness stamp for a module with {@code .scala} sources, so
     * a scala-version bump (which swaps the stdlib jar's content identity) invalidates the stat-only
     * fast path instead of silently skipping the compile against the old compiler (JK-2295). Empty for
     * a non-Scala module. Cheap on a warm closure cache (a directory listing, no network).
     */
    static List<Path> scalaStampLibs(TaskContext ctx, Path moduleDir, boolean compact, Cas cas) {
        List<Path> scalaSrcs;
        try {
            scalaSrcs = CompileSupport.collectScalaSources(moduleDir, compact);
        } catch (Exception e) {
            return List.of();
        }
        if (scalaSrcs.isEmpty()) return List.of();
        try {
            return ScalaCompile.prepare(ctx.require(PROJECT), ctx.require(LOCKFILE), cas)
                    .libraryJars();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The version-matched {@code groovy} jar for javac's classpath in a mixed module: every Groovy
     * class implements {@code groovy.lang.GroovyObject}, so Java code referencing a Groovy type
     * needs the jar to resolve the supertype. Warm after compile-groovy's setup (CAS-memoized).
     */
    static Path groovyCompileJar(TaskContext ctx, Cas cas) throws IOException {
        String groovyVersion = CompileToolchain.groovyVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        try {
            cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            return GroovyPluginSetup.prepare(repos, cas, groovyVersion).groovyJar();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Groovy compile jar", e);
        }
    }

    /**
     * The version-matched Groovy runtime closure (already in the CAS from the worker setup).
     * Groovy output needs it on the <em>runtime</em> classpath — compilation pairs the groovy jar
     * onto the compile classpath, but the JVM still needs the full runtime closure when the code
     * runs (mirrors {@link #kotlinStdlib}).
     */
    static List<Path> groovyRuntime(TaskContext ctx, Cas cas) throws IOException {
        String groovyVersion = CompileToolchain.groovyVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        if (groovyVersion == null || groovyVersion.isBlank()) {
            groovyVersion = cc.jumpkick.groovy.GroovyResolver.DEFAULT_VERSION;
        }
        try {
            cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            return GroovyToolResolver.resolveRuntime(repos, cas, groovyVersion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Groovy runtime", e);
        }
    }

    /** The resolved lock's {@code group:artifact} names — the classpath-has condition's universe. */
    static Set<String> lockModules(Lockfile lock) {
        Set<String> out = new HashSet<>();
        for (var a : lock.artifacts()) {
            out.add(a.name());
            // Rows are keyed by full package id (g:a:type:classifier) since package identity
            // gained type/classifier; consumers (classpath-has conditions, processor-dependency
            // checks) still speak plain group:artifact — expose that form too.
            out.add(a.moduleGroup() + ":" + a.moduleArtifact());
        }
        return out;
    }

    /**
     * The version-matched {@code kotlin-stdlib} path (already in the CAS from the plugin closure).
     * Kotlin output needs it on the <em>runtime</em> classpath too — compilation pairs the stdlib
     * with {@code -no-stdlib}, but the JVM still needs {@code kotlin.jvm.internal.*} etc. when the
     * code runs.
     */
    static Path kotlinStdlib(TaskContext ctx, Cas cas) throws IOException {
        String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        try {
            cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            return KotlinPluginSetup.prepare(repos, cas, kotlinVersion).stdlib();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Kotlin stdlib", e);
        }
    }

    static void copyResources(Path resourceDir, Path classesDir) throws IOException {
        if (!Files.exists(resourceDir)) return;
        try (Stream<Path> stream = Files.walk(resourceDir)) {
            for (Path source : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(source)) continue;
                Path relative = resourceDir.relativize(source);
                Path target = classesDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Map each workspace sibling to its main output jar, keyed by both project name and {@code
     * group:artifact} coord. Used by {@link #workerJarProps} to locate {@code test-plugin-jars}
     * entries. Empty when this module isn't in a workspace.
     */
    static Map<String, Path> siblingMainJars(Path moduleDir) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        var rootOpt = cc.jumpkick.config.WorkspaceLocator.findRoot(moduleDir);
        if (rootOpt.isEmpty()) return out;
        Path root = rootOpt.get();
        JkBuild rootManifest = JkBuildParser.parse(root.resolve("jk.toml"));
        if (!rootManifest.isWorkspaceRoot()) return out;
        for (String module : rootManifest.workspace().modules()) {
            Path dir = root.resolve(module);
            Path manifest = dir.resolve("jk.toml");
            if (!Files.exists(manifest)) continue;
            JkBuild sib;
            try {
                sib = JkBuildParser.parse(manifest);
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
            BuildLayout layout = BuildLayout.of(dir, sib);
            // An assembly plugin runs from its -all.jar — that's the artifact
            // that bundles plugin-api/PluginMain and the plugin's deps; a
            // plain module ships only its main jar.
            Path jar = sib.assembly() ? layout.assemblyJar() : layout.mainJar();
            String name = sib.project().name();
            out.put(name, jar);
            out.put(sib.project().group() + ":" + name, jar);
            // test-plugin-jars uses short names ("test-runner"); first-party
            // workers publish as jk-<short> — alias so sibling lookup works.
            if (name.startsWith("jk-") && name.length() > 3) {
                out.put(name.substring(3), jar);
            }
            // Also key by the module directory basename (plugins/test-runner → test-runner).
            Path base = dir.getFileName();
            if (base != null) {
                out.putIfAbsent(base.toString(), jar);
            }
        }
        return out;
    }

    /**
     * Packaging cache (mirrors the compile {@link ActionCache} path, for artifacts). Returns {@code
     * true} when a cached artifact for {@code key} was hard-linked back into {@code baseDir} — the
     * caller then skips the (re)packaging work.
     *
     * <p>Invariant: every packaging step that writes a jar under {@code target/} also
     * {@link #storePackaged stores} an action record. A jar without a record only happens when the
     * action cache was deleted out of band while {@code target/} was kept — recovery is to
     * re-package (this miss path), not to trust the on-disk jar as authoritative.
     *
     * <p>{@code --redo}/{@code --force} skip <em>restore</em> (always re-package) but still
     * {@link #storePackaged store} — same contract as {@link cc.jumpkick.task.JavaCompile}: the next
     * {@code jk explain} / incremental build must see a CACHE_HIT, not a phantom repackage.
     */
    static boolean restorePackaged(Path cacheRoot, String key, Path baseDir) throws IOException {
        // rebuildOr already subsumes force (JkConfig: force implies rebuild).
        if (cc.jumpkick.config.SessionContext.current().config().rebuildOr(false)) {
            return false;
        }
        ActionCache ac = packagingActionCache(cacheRoot);
        var hit = ac.lookup(key);
        return hit.isPresent() && ac.restoreArtifacts(hit.get(), baseDir);
    }

    /**
     * Record a freshly-produced packaging artifact so a later build / explain can skip it. Writes
     * even under {@code --redo} — redo only means "do not restore/skip work", not "do not
     * teach the cache" (parity with compile). {@code persist=false} is {@code jk verify}'s scratch
     * rebuild: packaging DOES run there (the artifact is what verify diffs), its keys embed the
     * unique scratch path so they can never recur, and a store would be a permanent orphan record
     * plus CAS copies on every verify run.
     */
    static void storePackaged(
            Path cacheRoot,
            String taskId,
            String key,
            List<String> tokens,
            Path baseDir,
            List<Path> artifacts,
            boolean persist)
            throws IOException {
        if (!persist) return;
        packagingActionCache(cacheRoot)
                .storeArtifacts(taskId, key, Map.of("inputs", String.join(";", tokens)), baseDir, artifacts);
    }

    /** Action cache with store-CAS fallback for Class-C blobs promoted by release. */
    static ActionCache packagingActionCache(Path cacheRoot) {
        return new ActionCache(JkStores.cacheCas(cacheRoot), cacheRoot.resolve("actions"), JkStores.storeCas());
    }

    /** Test hook: {@link #storePackaged} under a rebuild session must still persist. */
    static void storePackagedForTest(
            Path cacheRoot,
            String taskId,
            String key,
            List<String> tokens,
            Path baseDir,
            List<Path> artifacts,
            boolean persist)
            throws IOException {
        storePackaged(cacheRoot, taskId, key, tokens, baseDir, artifacts, persist);
    }

    static Map<String, String> workerJarProps(Path moduleDir, List<String> modules) throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        if (modules.isEmpty()) return props;
        Map<String, Path> jarByModule = siblingMainJars(moduleDir);
        for (String module : modules) {
            // Accept short names (test-runner), artifact ids (jk-test-runner), or either already.
            var wj = cc.jumpkick.engine.plugin.PluginJar.byArtifactId(module);
            if (wj.isEmpty()) wj = cc.jumpkick.engine.plugin.PluginJar.byArtifactId("jk-" + module);
            if (wj.isEmpty()) continue;
            Path jar = jarByModule.get(module);
            if (jar == null) jar = jarByModule.get(wj.get().artifactId());
            if (jar == null && module.startsWith("jk-")) jar = jarByModule.get(module.substring(3));
            if (jar != null && Files.exists(jar)) {
                props.put(wj.get().jarProperty(), jar.toAbsolutePath().toString());
            } else {
                // Not a built sibling — self-host by reusing the running jk's plugin
                // jar (located via its sha resource + CAS, or a -D override).
                Path located = wj.get().locateStored(cc.jumpkick.cache.JkStores.cas(cc.jumpkick.util.JkDirs.cache()));
                if (located != null) props.put(wj.get().jarProperty(), located.toString());
            }
        }
        return props;
    }

    /**
     * CLI integration suite: tests spawn a real engine via the wire and register {@code
     * EngineTestExtension}, which force-stops the engine after each class. Under pure-jk {@code jk
     * test} that must not share the host engine's {@code JK_STATE_DIR} (host would die mid-suite).
     */
    static boolean needsNestedEngineIsolation(JkBuild project) {
        if (project == null || project.project() == null) return false;
        String name = project.project().name();
        if ("jk-cli".equals(name)) return true;
        return "cc.jumpkick.cli.Jk".equals(project.mainClass());
    }

    /**
     * Resolve engine assembly + every first-party worker jar so CLI tests match Gradle's {@code
     * -Djk.engine.jar} / {@code -Djk.*.plugin.jar} wiring.
     *
     * <p>{@code jk test} (testOnly) does not package the engine assembly, so the workspace
     * {@code *-all.jar} is often missing. Fall back to the host engine jar (the process serving
     * this build) or the product-lib install under {@code EngineInstall} — same fat jar Gradle
     * hands CLI tests via {@code :engine:shadowJar}.
     */
    static void enrichCliTestProps(Path moduleDir, Map<String, String> props) throws IOException {
        Map<String, Path> siblings = siblingMainJars(moduleDir);
        Path engine = resolveEngineJarForNestedTests(siblings);
        if (engine != null) {
            props.put("jk.engine.jar", engine.toAbsolutePath().toString());
        }
        for (PluginJar w : PluginJar.values()) {
            if (props.containsKey(w.jarProperty())) continue;
            Path jar = siblings.get(w.artifactId());
            if (jar == null && w.artifactId().startsWith("jk-")) {
                jar = siblings.get(w.artifactId().substring(3));
            }
            if (jar != null && Files.isRegularFile(jar)) {
                props.put(w.jarProperty(), jar.toAbsolutePath().toString());
            } else {
                Path located = w.locateStored(JkStores.cas(cc.jumpkick.util.JkDirs.cache()));
                if (located != null) props.put(w.jarProperty(), located.toString());
            }
        }
    }

    /**
     * Engine jar for nested CLI suites: workspace assembly when present, else the host process's
     * fat jar / installed EngineInstall materialization.
     */
    static Path resolveEngineJarForNestedTests(Map<String, Path> siblings) {
        Path engine = siblings != null ? siblings.get("jk-engine") : null;
        if (engine == null && siblings != null) engine = siblings.get("engine");
        if (engine != null && Files.isRegularFile(engine)) return engine.normalize();
        return locateHostEngineJar();
    }

    /**
     * Fat engine jar this process was launched from, the same version under {@link
     * cc.jumpkick.cache.EngineInstall}, or a monorepo product path ({@code build/dist/lib},
     * Gradle {@code build/libs}, pure-jk {@code target/server/engine}). Null only when none
     * of those exist (cold checkout with no install and no prior package).
     */
    /**
     * Test hook: when set, host-engine-jar discovery searches only this root's monorepo product
     * paths. Keeps tests from depending on — or worse, seeding — the real checkout's build
     * outputs, and makes fallback assertions deterministic on warm developer trees
     * where the process/EngineInstall probes would otherwise win.
     */
    static Path locateHostEngineJar() {
        Path override = BuildPlanner.hostEngineSearchOverride;
        if (override != null) return findMonorepoEngineJar(override);
        try {
            var cs = cc.jumpkick.engine.EngineMain.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path p = Path.of(cs.getLocation().toURI());
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
                    return p.normalize();
                }
            }
        } catch (Exception ignored) {
            // fall through — exploded test classpath is common under Gradle
        }
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry == null || entry.isBlank()) continue;
            Path p = Path.of(entry);
            String name = p.getFileName() != null ? p.getFileName().toString() : "";
            if (Files.isRegularFile(p)
                    && name.endsWith(".jar")
                    && (name.startsWith("jk-engine") || name.equals("jk-engine.jar"))) {
                return p.toAbsolutePath().normalize();
            }
        }
        try {
            var mat = cc.jumpkick.cache.EngineInstall.current().resolve(cc.jumpkick.model.JkVersion.VERSION);
            if (mat.isPresent() && Files.isRegularFile(mat.get().engineJar())) {
                return mat.get().engineJar().toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            // Isolated JK_HOME (Gradle :engine:test / nested CLI suite) has no engine jar.
        }
        // Last resort: monorepo product outputs relative to user.dir (and parents). Pure-jk
        // nested isolation runs with user.dir = clients/cli; host run-tests has monorepo root
        // or server/engine as cwd under Gradle.
        return findMonorepoEngineJar(
                Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
    }

    /** Prefer fat assembly, then dist/shadow, then thin main jar under known layout roots. */
    static Path findMonorepoEngineJar(Path start) {
        String ver = cc.jumpkick.model.JkVersion.VERSION;
        Path walk = start;
        for (int up = 0; up < 5 && walk != null; up++, walk = walk.getParent()) {
            for (String rel : List.of(
                    "target/server/engine/jk-engine-" + ver + "-all.jar",
                    "build/dist/lib/jk-engine-" + ver + ".jar",
                    "server/engine/build/libs/jk-engine-" + ver + ".jar",
                    "build/libs/jk-engine-" + ver + ".jar",
                    "target/server/engine/jk-engine-" + ver + ".jar",
                    "server/engine/target/jk-engine-" + ver + "-all.jar",
                    "server/engine/target/jk-engine-" + ver + ".jar")) {
                Path p = walk.resolve(rel);
                if (Files.isRegularFile(p)) return p.normalize();
            }
        }
        return null;
    }

    /**
     * Isolated {@code JK_HOME} + short {@code JK_STATE_DIR} under {@code /tmp} (UDS path length) for
     * nested-engine CLI tests. Keeps the host engine's socket alone.
     *
     * <p><strong>Fully sandboxed product layout</strong> — {@code JK_HOME} mirrors XDG, so cache
     * lands in {@code $JK_HOME/cache} and the store in {@code $JK_HOME/data/store}. Never point {@code JK_CACHE_DIR} or {@code JK_STORE_DIR} at the host: a
     * prior bug set them to the developer's real trees so {@code SelfNukeCommandTest} /
     * {@code jk cache nuke} / {@code jk self nuke --data} wiped action-cache and install-local
     * workers mid-{@code jk build}. After that, post-green {@code jk explain} reported a full
     * rebuild and subsequent tests could not find {@code jk-test-runner}.
     *
     * <p>Plugin/worker jars for nested suites still arrive via {@code -Djk.*.plugin.jar} props
     * ({@link #enrichCliTestProps}), not by sharing the host store.
     */
    static Map<String, String> nestedEngineTestEnv(Path moduleDir) throws IOException {
        Path jkHome = moduleDir.resolve("target").resolve("test-jk-home");
        Files.createDirectories(jkHome);
        String runId = Long.toString(System.currentTimeMillis(), 36) + "-"
                + Integer.toHexString(System.identityHashCode(moduleDir) & 0xffff);
        Path stateDir = Path.of("/tmp", "jk-cli-" + runId);
        Files.createDirectories(stateDir);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JK_HOME", jkHome.toAbsolutePath().toString());
        env.put("JK_JDKS_DIR", jkHome.resolve("jdks").toAbsolutePath().toString());
        env.put("JK_STATE_DIR", stateDir.toAbsolutePath().toString());
        // Intentionally no JK_CACHE_DIR / JK_STORE_DIR — both resolve under JK_HOME.
        env.put("JK_HTTP_ENABLED", "false");
        env.put("JK_HTTP_PORT", "0");
        env.put("JK_STREAM_IDLE_MS", "45000");
        env.put("TERM", "xterm-256color");
        env.put("CI", "false");
        // Clear NO_COLOR so TUI ANSI assertions match Gradle's deterministic setup.
        env.put("NO_COLOR", "");
        // Nested engines + workers: train-on-miss is pure overhead under the suite.
        env.put("JK_AOT_TRAIN", "off");
        return env;
    }

    /**
     * The selection the runner actually executes: the session's, with this module's {@code [test]
     * include-tags} / {@code exclude-tags} folded in when the session carries no tags at all (jk
     * build / BSP without data). {@code jk test} resolves tags CLI-side and its selection already
     * carries them.
     */
    static cc.jumpkick.config.TestSelection effectiveSelection(cc.jumpkick.config.TestSelection sel, Path moduleDir) {
        // tagsResolved: the CLI already applied baseline/profile/flag layers — an empty list may
        // be an explicit clear ([profiles.x] exclude-tags = []) and must stay empty.
        if (sel.tagsResolved()) return sel;
        if (!sel.includeTags().isEmpty() || !sel.excludeTags().isEmpty()) return sel;
        var fromToml = cc.jumpkick.config.JkBuildParser.parseTestTags(moduleDir.resolve("jk.toml"));
        if (fromToml.isEmpty()) return sel;
        return cc.jumpkick.config.TestSelection.of(
                sel.suites(), sel.allSuites(), fromToml.includeTags(), fromToml.excludeTags());
    }

    /**
     * Worker / engine jar props that feed both the forked test JVM and the {@link
     * cc.jumpkick.task.TestStamp} extras. Includes declared {@code [build] test-plugin-jars} and,
     * for nested-engine CLI modules, every first-party {@link PluginJar} plus the engine assembly
     * forecast and live run-tests must hash the same set).
     */
    public static Map<String, String> testStampWorkerJars(Path dir, JkBuild project) throws IOException {
        Map<String, String> props =
                new LinkedHashMap<>(workerJarProps(dir, project.build().testPluginJars()));
        if (needsNestedEngineIsolation(project)) {
            enrichCliTestProps(dir, props);
        }
        return props;
    }

    /**
     * The run-tests stamp's identity tokens for {@code project} at {@code dir} — the same set the
     * build folds into its {@link cc.jumpkick.task.TestStamp} key, so {@code jk explain}'s forecast
     * predicts test-skip without drifting.
     */
    public static List<String> testStampExtras(Path dir, JkBuild project) throws IOException {
        // The SESSION selection, not DEFAULT: the forecast must key run-tests exactly like the
        // live run (PlannerTest feeds in.session().testSelection()), or a widened build
        // (`jk build --all`) forecasts "tests cached" off the unit-tier marker and the whole
        // workspace short-circuits to "up to date" without running the widened tier (JK-2203).
        return testStampExtras(
                testStampWorkerJars(dir, project),
                effectiveSelection(cc.jumpkick.config.SessionContext.current().testSelection(), dir),
                project.build().testEnv(),
                dir);
    }

    /**
     * Full {@link cc.jumpkick.task.TestStamp} key for the default {@code jk build} selection
     * single factory for forecast and any offline checker. {@code testRuntimeCp} must match the
     * build's runtime classpath for the stamp (lock deps + workspace sibling jars; plugin
     * contributions optional for non-plugin modules).
     */
    public static String runTestsStampKey(
            Path dir, JkBuild project, boolean compact, Path mainClasses, Path lockFile, List<Path> testRuntimeCp)
            throws IOException {
        return runTestsStampKey(dir, project, compact, mainClasses, null, lockFile, testRuntimeCp);
    }

    /**
     * {@code mainClassesFingerprint} overrides the on-disk main-classes tree when non-null (post-
     * {@code jk clean} projection from the compile action record).
     */
    public static String runTestsStampKey(
            Path dir,
            JkBuild project,
            boolean compact,
            Path mainClasses,
            String mainClassesFingerprint,
            Path lockFile,
            List<Path> testRuntimeCp)
            throws IOException {
        List<String> discovered = cc.jumpkick.layout.TestSuites.discover(dir, compact);
        // Session selection for suite resolution too — --all widens the suite set, and the
        // forecast's source list must cover the same files the live run stamps (JK-2203).
        var resolved =
                cc.jumpkick.config.SessionContext.current().testSelection().resolve(discovered);
        List<String> suites = resolved.ok() ? resolved.suites() : List.of(cc.jumpkick.layout.TestSuites.DEFAULT);
        List<Path> stampSrcs = new ArrayList<>();
        stampSrcs.addAll(cc.jumpkick.layout.TestSuites.collectJavaSources(dir, compact, suites));
        stampSrcs.addAll(cc.jumpkick.layout.TestSuites.collectKotlinSources(dir, compact, suites));
        stampSrcs.addAll(cc.jumpkick.layout.TestSuites.collectGroovySources(dir, compact, suites));
        return cc.jumpkick.task.TestStamp.computeKey(
                stampSrcs,
                mainClasses,
                mainClassesFingerprint,
                cc.jumpkick.layout.ModuleLayout.suiteResourceDirs(dir, compact, suites),
                lockFile,
                testRuntimeCp,
                testStampExtras(dir, project));
    }

    /**
     * Stamp extras with env expansion and secret hashing for a module at {@code moduleDir}
     * . There is deliberately no lookup-free overload: it would silently skip
     * both expansion and hashing and produce a key that disagrees with this one.
     */
    static List<String> testStampExtras(
            Map<String, String> workerJars,
            cc.jumpkick.config.TestSelection selection,
            Map<String, String> testEnv,
            Path moduleDir) {
        cc.jumpkick.config.EnvLookup lookup =
                moduleDir == null ? null : cc.jumpkick.config.BuildEnv.lookupFor(moduleDir);
        cc.jumpkick.config.SecretRedactor redactor = lookup == null
                ? cc.jumpkick.config.SecretRedactor.none()
                : cc.jumpkick.config.SecretRedactor.from(lookup);
        return testStampExtras(workerJars, selection, testEnv, redactor, lookup);
    }

    /**
     * Stamp extras. Declared {@code [test] env} values only (sandbox defaults stay out — they are
     * absolute paths that would defeat cache sharing). Environment references expand through
     * {@code lookup}; a {@code.env}-sourced value is hashed into the key, never written verbatim
     *
     */
    static List<String> testStampExtras(
            Map<String, String> workerJars,
            cc.jumpkick.config.TestSelection selection,
            Map<String, String> testEnv,
            cc.jumpkick.config.SecretRedactor redactor,
            cc.jumpkick.config.EnvLookup lookup) {
        List<String> extras = new ArrayList<>();
        extras.add("jk:" + cc.jumpkick.model.BuildIdentity.cacheKeyVersion());
        // Suite + tag filters are part of the outcome.
        if (selection != null) extras.add("sel:" + selection.identityToken());
        // [test] env changes what the suite sees, so it must retest.
        cc.jumpkick.config.SecretRedactor secrets =
                redactor == null ? cc.jumpkick.config.SecretRedactor.none() : redactor;
        for (Map.Entry<String, String> e : new TreeMap<>(testEnv).entrySet()) {
            String raw = e.getValue() == null ? "" : e.getValue();
            String expanded = raw;
            boolean envResolved = false;
            if (lookup != null && raw.indexOf('$') >= 0) {
                // Expand ${VAR} for cache identity, but leave ${target}/${module} as tokens so
                // the key stays portable across checkouts (same instinct as the sandbox defaults).
                boolean[] resolved = {false};
                try {
                    expanded = cc.jumpkick.config.Interpolation.expand(raw, "[test].env." + e.getKey(), var -> {
                        if ("target".equals(var) || "module".equals(var)) return "${" + var + "}";
                        String v = lookup.get(var);
                        if (v != null) resolved[0] = true;
                        return v;
                    });
                } catch (cc.jumpkick.config.JkBuildParseException ex) {
                    // Unset var — keep the raw text so a broken reference still changes the key.
                    expanded = raw;
                }
                envResolved = resolved[0];
            }
            String keyed = secrets.forCacheKey(expanded);
            if (envResolved && keyed.equals(expanded)) {
                // Non-secret env reference (${HOME}, a CI id): the VALUE still keys the stamp
                // a changed environment retestsbut the literal must not land in a
                // (potentially shared) key: no absolute paths or ids on disk.
                keyed = cc.jumpkick.config.SecretRedactor.KEY_PREFIX + cc.jumpkick.util.Hashing.sha256Hex(expanded);
            }
            extras.add("test-env:" + e.getKey() + "=" + keyed);
        }
        // Plugin jars by content — a plugin change retests the module that forks it.
        for (Map.Entry<String, String> e : workerJars.entrySet()) {
            String fp;
            try {
                fp = cc.jumpkick.task.ClasspathFingerprint.entry(Path.of(e.getValue()));
            } catch (IOException ex) {
                fp = "err";
            }
            extras.add("worker:" + e.getKey() + "=" + fp);
        }
        return extras;
    }

    /** Package-private for {@link TaskForecaster} package-jar key parity with the live step. */
    static PluginBuild.Declarations pluginDeclarationsFor(JkBuild project, BuildLayout layout, Path cache)
            throws IOException, InterruptedException {
        var active = PluginBuild.activeCodePlugin(project, layout.moduleRoot());
        if (active.isEmpty()) return null;
        return PluginBuild.declarations(active.get(), project, layout.moduleRoot(), cache, layout.moduleTargetDir());
    }

    /**
     * Main classes plus any plugin {@code contributesClasses}/{@code contributesResources} dirs
     * (Micronaut AOT, etc.). When nothing is contributed, returns {@code classes} unchanged.
     */
    /**
     * The plugin {@code contributesClasses}/{@code contributesResources} dirs that exist, in
     * declaration order. Listing is cheap; {@link #stageClassesWithContributions} is the copy.
     */
    // Package-private for BuildPlannerStagedClassesTest.
    static List<Path> existingContributedDirs(PluginBuild.Declarations decls, BuildLayout layout) {
        if (decls == null) return List.of();
        List<Path> out = new ArrayList<>();
        for (Path pth : PluginBuild.contributedDirs(decls, layout)) {
            if (pth != null && Files.isDirectory(pth)) out.add(pth);
        }
        return out;
    }

    /**
     * Class dirs of workspace MAIN dependencies to vendor into a plugin-worker jar (Gradle
     * {@code bundledCodec}: plugin-sdk + jsonl). External deps stay on the sidecar POM.
     *
     * <p>{@link cc.jumpkick.config.JkBuildParser#parse(Path)} rewrites {@code workspace:}
     * placeholders to real {@code group:artifact} coordinates before packaging runs, so sibling
     * lookup must accept both forms.
     */
    static List<Path> workerCodecClassDirs(Path moduleDir, JkBuild project) {
        if (moduleDir == null || project == null || !cc.jumpkick.plugin.PluginModule.isWorker(moduleDir)) {
            return List.of();
        }
        Path root;
        JkBuild rootManifest;
        try {
            var rootOpt = WorkspaceLocator.findRoot(moduleDir);
            if (rootOpt.isEmpty()) return List.of();
            root = rootOpt.get();
            rootManifest = JkBuildParser.parse(root.resolve("jk.toml"));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        if (!rootManifest.isWorkspaceRoot()) return List.of();
        Map<String, Path> dirByName = new LinkedHashMap<>();
        Map<String, Path> dirByCoord = new LinkedHashMap<>();
        Map<Path, JkBuild> byDir = new LinkedHashMap<>();
        for (String module : rootManifest.workspace().modules()) {
            Path dir = root.resolve(module);
            Path manifest = dir.resolve("jk.toml");
            if (!Files.isRegularFile(manifest)) continue;
            JkBuild sib;
            try {
                sib = JkBuildParser.parse(manifest);
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
            byDir.put(dir, sib);
            indexWorkerSibling(dirByName, dirByCoord, dir, sib);
        }
        // Members may depend on the workspace root unit itself.
        byDir.put(root, rootManifest);
        indexWorkerSibling(dirByName, dirByCoord, root, rootManifest);
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        ArrayDeque<JkBuild> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(project);
        while (!q.isEmpty()) {
            JkBuild cur = q.removeFirst();
            for (Dependency d : cur.dependencies().of(Scope.MAIN)) {
                Path dir = workerSiblingDir(d, dirByName, dirByCoord);
                if (dir == null) continue;
                String seenKey = dir.toAbsolutePath().normalize().toString();
                if (!seen.add(seenKey)) continue;
                JkBuild sib = byDir.get(dir);
                if (sib == null) continue;
                Path classes = BuildLayout.of(dir, sib).classesDir();
                if (Files.isDirectory(classes)) out.add(classes);
                q.addLast(sib);
            }
        }
        return List.copyOf(out);
    }

    private static void indexWorkerSibling(
            Map<String, Path> dirByName, Map<String, Path> dirByCoord, Path dir, JkBuild sib) {
        String name = sib.project().name();
        dirByName.putIfAbsent(name, dir);
        if (name.startsWith("jk-") && name.length() > 3) {
            dirByName.putIfAbsent(name.substring(3), dir);
        }
        Path base = dir.getFileName();
        if (base != null) dirByName.putIfAbsent(base.toString(), dir);
        dirByCoord.putIfAbsent(sib.project().group() + ":" + name, dir);
    }

    /** Resolve a MAIN dep to a workspace sibling dir (placeholder or rewritten coordinate). */
    private static Path workerSiblingDir(Dependency d, Map<String, Path> dirByName, Map<String, Path> dirByCoord) {
        String ws = d.workspaceName();
        if (ws != null) {
            Path dir = dirByName.get(ws);
            if (dir != null) return dir;
            if (ws.startsWith("jk-") && ws.length() > 3) return dirByName.get(ws.substring(3));
            return dirByName.get("jk-" + ws);
        }
        Path byCoord = dirByCoord.get(d.module());
        if (byCoord != null) return byCoord;
        Path byLib = dirByName.get(d.library());
        if (byLib != null) return byLib;
        return dirByName.get(d.name());
    }

    /**
     * Cache-key token covering the contributed dirs. Order-sensitive on purpose: contributions
     * merge first-wins, so declaration order is part of what the packaged output depends on.
     */
    /** Package-private for {@link TaskForecaster} package-jar key parity. */
    static String contributionsToken(List<Path> contributed) throws IOException {
        if (contributed == null || contributed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Path dir : contributed) {
            sb.append(cc.jumpkick.task.ClasspathFingerprint.entry(dir)).append('\n');
        }
        return cc.jumpkick.util.Hashing.sha256Hex(sb.toString());
    }

    /**
     * Main classes plus the plugin-contributed dirs, merged into one tree for the packagers.
     * Returns {@code classes} unchanged when nothing is contributed.
     *
     * <p>Both packaging tasks call this, and package-jar always runs first, so it publishes the
     * staged dir under {@link #STAGED_CLASSES} and assembly reuses it. Reuse is keyed on the
     * inputs, not merely on the dir existing: package-jar may have restored from cache without
     * staging at all, in which case assembly stages for itself.
     */
    // Package-private for BuildPlannerStagedClassesTest.
    static Path stageClassesWithContributions(
            cc.jumpkick.run.TaskContext ctx, Path classes, List<Path> extra, BuildLayout layout) throws IOException {
        if (extra.isEmpty()) return classes;
        Path stage = layout.moduleTargetDir().resolve("package-classes");
        String inputs = cc.jumpkick.task.ClasspathFingerprint.entry(classes) + "|" + contributionsToken(extra);
        if (ctx != null && inputs.equals(ctx.get(STAGED_CLASSES_INPUTS).orElse(null)) && Files.isDirectory(stage)) {
            return stage;
        }
        // A wipe that cannot finish is a build error, not something to paper over: the copy below
        // only overwrites paths it reproduces, so a survivor from a previous build (a renamed or
        // no-longer-emitted class) would be packaged, and the packaging key is taken over this
        // dir — so the wrong content is what gets cached and restored.
        cc.jumpkick.util.PathUtil.deleteRecursivelyOrThrow(stage);
        Files.createDirectories(stage);
        copyTreeInto(classes, stage);
        for (Path contrib : extra) copyTreeInto(contrib, stage);
        if (ctx != null) ctx.put(STAGED_CLASSES_INPUTS, inputs);
        return stage;
    }

    static void copyTreeInto(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (var walk = Files.walk(from)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path rel = from.relativize(src);
                Path dst = to.resolve(rel.toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
    /**
     * Mirror the javac diagnostic loop for worker compilers: one {@code ctx.error}/{@code warn}
     * per diagnostic so each keeps its own {@code path:line[:col]:} header for journal locus
     * parsing and CLI snippets, instead of one joined blob whose first header wins.
     */
    static void forwardWorkerDiagnostics(
            cc.jumpkick.run.TaskContext ctx,
            String code,
            List<cc.jumpkick.compile.CompileResult.Diagnostic> diagnostics,
            String emptyFallback) {
        boolean errored = false;
        for (cc.jumpkick.compile.CompileResult.Diagnostic d : diagnostics) {
            if (d.severity() == cc.jumpkick.compile.CompileResult.Severity.ERROR) {
                ctx.error(code, d.describe());
                errored = true;
            } else {
                ctx.warn(code, d.describe());
            }
        }
        // Never fail silently: a crash that produced no ERROR diagnostic still explains itself.
        if (!errored) ctx.error(code, emptyFallback);
    }
}
