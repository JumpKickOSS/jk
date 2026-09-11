// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.ModuleRuntimeClasspath;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.groovy.GroovyResolver;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.GroovyPluginSetup;
import cc.jumpkick.runtime.base.GroovyToolResolver;
import cc.jumpkick.runtime.base.KotlinPluginSetup;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Classpath, packaging cache, nested-engine test isolation, and contribution staging.
 */
public final class PlannerSupport {

    private PlannerSupport() {}

    /**
     * The resolved paths of {@code [[contribute.provided-classpath]]} entries — declared
     * step-dependency artifacts (an SDK platform jar) that join the COMPILE classpaths only. The
     * entry names its tool itself, so only the named tools are fetched, whatever their scope.
     */
    static List<Path> contributedProvidedClasspath(
            JkBuild project, BuildPlanner.Inputs in, Cas cas, PluginBuild.StepTools tools) {
        List<String> names = PluginContributions.providedClasspath(project, in.dir());
        if (names.isEmpty()) return List.of();
        try {
            Map<String, Path> fetched = tools.fetch(
                    tools.named(project, in.dir(), names), project, cas, PluginBuild.sdkPins(in.lockFile()));
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
            JkBuild project, Lockfile lock, WorkspaceClasspath.@Nullable Result processorSiblings) {
        Set<String> locked = lockModules(lock);
        Set<String> siblings = new HashSet<>();
        if (processorSiblings != null) {
            siblings.addAll(processorSiblings.siblingCoords());
        }
        List<String> missing = new ArrayList<>();
        for (Dependency dep : project.dependencies().of(Scope.PROCESSOR)) {
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
        return ModuleRuntimeClasspath.jars(moduleDir, project, lockFile, JkStores.storeCas());
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
            @Nullable Path groovyCompileJar) {
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
     * fast path instead of silently skipping the compile against the old compiler. Empty for
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
            RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
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
            groovyVersion = GroovyResolver.DEFAULT_VERSION;
        }
        try {
            RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
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
            RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            return KotlinPluginSetup.prepare(repos, cas, kotlinVersion).stdlib();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Kotlin stdlib", e);
        }
    }

    /**
     * Merge {@code resourceDir} into {@code classesDir}, leaving whatever is already correct alone.
     *
     * <p>{@link PathUtil#copyTree} skips byte-identical files so mtimes stay put: {@code
     * mainStampClasspath} feeds those dirs to {@code FreshnessStamp}, which compares by mtime.
     */
    /** Merge one compiled-output tree ({@code kotlin}, {@code groovy}) into the main classes tree. */
    static void copyResources(Path resourceDir, Path classesDir) throws IOException {
        PathUtil.copyTree(resourceDir, classesDir);
    }

    /**
     * Map each workspace sibling to its main output jar, keyed by both project name and {@code
     * group:artifact} coord. Used by {@link #workerJarProps} to locate {@code test-plugin-jars}
     * entries. Empty when this module isn't in a workspace.
     */
    static Map<String, Path> siblingMainJars(Path moduleDir) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        var rootOpt = WorkspaceLocator.findRoot(moduleDir);
        if (rootOpt.isEmpty()) return out;
        Path root = rootOpt.get();
        JkBuild rootManifest = JkBuildParser.parse(root.resolve(ManifestPaths.MANIFEST));
        if (!rootManifest.isWorkspaceRoot()) return out;
        for (String module : rootManifest.workspaceModules()) {
            Path dir = root.resolve(module);
            Path manifest = dir.resolve(ManifestPaths.MANIFEST);
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
    static boolean restorePackaged(Path cacheRoot, String key, @Nullable Path baseDir) throws IOException {
        // rebuildOr already subsumes force (JkConfig: force implies rebuild).
        if (SessionContext.current().config().rebuildOr(false)) {
            return false;
        }
        ActionCache ac = packagingActionCache(cacheRoot);
        var hit = ac.lookup(key);
        return baseDir != null && hit.isPresent() && ac.restoreArtifacts(hit.get(), baseDir);
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
            @Nullable Path baseDir,
            List<Path> artifacts,
            boolean persist)
            throws IOException {
        if (!persist) return;
        packagingActionCache(cacheRoot)
                .storeArtifacts(taskId, key, Map.of("inputs", String.join(";", tokens)), baseDir, artifacts);
    }

    /** Action cache with store-CAS fallback for Class-C blobs promoted by release. */
    static ActionCache packagingActionCache(Path cacheRoot) {
        return new ActionCache(JkStores.cacheCas(cacheRoot), CacheTree.ACTIONS.under(cacheRoot), JkStores.storeCas());
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
            // Accept short names (test-runner), artifact ids (jk-test-runner), or a group:artifact
            // coordinate. A rule pack listed here is not a worker; it is staged by stageSiblingRulePacks.
            var wj = workerFor(module);
            if (wj.isEmpty()) continue;
            // The worker's own artifact id first: a sibling that merely shares a short name (a rule
            // pack called quarkus beside the jk-quarkus worker) must not answer for it.
            Path jar = jarByModule.get(wj.get().artifactId());
            if (jar == null) jar = jarByModule.get(module);
            if (jar != null && Files.exists(jar)) {
                props.put(wj.get().jarProperty(), jar.toAbsolutePath().toString());
            } else {
                // Not a built sibling — self-host by reusing the running jk's plugin
                // jar (located via its sha resource + CAS, or a -D override).
                Path located = wj.get().locateStored(JkStores.storeCas());
                if (located != null) props.put(wj.get().jarProperty(), located.toString());
            }
        }
        return props;
    }

    /**
     * Stage into the module's sandbox store every {@code test-plugin-jars} entry that is a workspace
     * sibling but not a worker — a first-party rule pack the module's tests lock scaffolds against.
     * A project's lock finds a first-party pack in the store's {@code jk-local} shelf before it asks
     * any repository, so this is what makes an unpublished pack resolvable under the sandbox home
     * {@code testEnv} points at. Nothing to do for a module outside a workspace.
     */
    static void stageSiblingRulePacks(Path moduleDir, JkBuild project, Map<String, String> testEnv) throws IOException {
        List<String> entries = project.build().testPluginJars();
        if (entries.isEmpty() || !testEnv.containsKey(TestEnv.JK_HOME)) return;
        Map<String, JkBuild> siblings = siblingManifests(moduleDir);
        if (siblings.isEmpty()) return;
        Path store = JkDirs.of(testEnv::get, System.getProperty("user.home")).storeDir();
        for (String entry : entries) {
            if (workerFor(entry).isPresent()) continue;
            JkBuild sibling = siblings.get(entry);
            if (sibling == null) continue;
            var proj = sibling.project();
            Path jar = BuildLayout.of(siblingDir(moduleDir, sibling), sibling).mainJar();
            if (!Files.isRegularFile(jar)) continue;
            Path shelf = store.resolve("repos")
                    .resolve(RepoArtifactResolver.JK_LOCAL)
                    .resolve(proj.group().replace('.', '/'))
                    .resolve(proj.name())
                    .resolve(proj.version());
            Path staged = shelf.resolve(proj.name() + "-" + proj.version() + ".jar");
            if (Files.isRegularFile(staged)
                    && Files.size(staged) == Files.size(jar)
                    && Files.getLastModifiedTime(staged).compareTo(Files.getLastModifiedTime(jar)) >= 0) {
                continue;
            }
            Files.createDirectories(shelf);
            Files.copy(jar, staged, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The first-party worker a {@code test-plugin-jars} entry names: a short name ({@code
     * test-runner}), an artifact id ({@code jk-test-runner}) or a coordinate ({@code
     * cc.jumpkick:jk-test-runner}). Only an unqualified short name is widened with {@code jk-}: a
     * coordinate in another group ({@code cc.jumpkick.guards:quarkus}) is not the worker that
     * happens to share its short name.
     */
    private static Optional<PluginJar> workerFor(String entry) {
        int colon = entry.indexOf(':');
        if (colon >= 0) {
            String group = entry.substring(0, colon);
            String artifact = entry.substring(colon + 1);
            if (!PluginJar.GROUP.equals(group)) return Optional.empty();
            return PluginJar.byArtifactId(artifact);
        }
        var wj = PluginJar.byArtifactId(entry);
        return wj.isPresent() ? wj : PluginJar.byArtifactId("jk-" + entry);
    }

    /** Workspace siblings' manifests, keyed by project name and by {@code group:name}. */
    private static Map<String, JkBuild> siblingManifests(Path moduleDir) throws IOException {
        Map<String, JkBuild> out = new LinkedHashMap<>();
        var rootOpt = WorkspaceLocator.findRoot(moduleDir);
        if (rootOpt.isEmpty()) return out;
        Path root = rootOpt.get();
        JkBuild rootManifest = JkBuildParser.parse(root.resolve(ManifestPaths.MANIFEST));
        if (!rootManifest.isWorkspaceRoot()) return out;
        for (String module : rootManifest.workspaceModules()) {
            Path manifest = root.resolve(module).resolve(ManifestPaths.MANIFEST);
            if (!Files.exists(manifest)) continue;
            JkBuild sib;
            try {
                sib = JkBuildParser.parse(manifest);
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
            out.put(sib.project().name(), sib);
            out.put(sib.project().group() + ":" + sib.project().name(), sib);
        }
        return out;
    }

    /** The directory a sibling manifest was parsed from, found again by its declared module path. */
    private static Path siblingDir(Path moduleDir, JkBuild sibling) throws IOException {
        Path root = WorkspaceLocator.findRoot(moduleDir).orElseThrow();
        JkBuild rootManifest = JkBuildParser.parse(root.resolve(ManifestPaths.MANIFEST));
        for (String module : rootManifest.workspaceModules()) {
            Path dir = root.resolve(module);
            Path manifest = dir.resolve(ManifestPaths.MANIFEST);
            if (!Files.exists(manifest)) continue;
            try {
                JkBuild sib = JkBuildParser.parse(manifest);
                if (sib.project().group().equals(sibling.project().group())
                        && sib.project().name().equals(sibling.project().name())) {
                    return dir;
                }
            } catch (IOException | RuntimeException ignored) {
                // an unparsable sibling cannot be the one already parsed
            }
        }
        throw new IOException("workspace module for " + sibling.project().group() + ":"
                + sibling.project().name() + " is no longer listed at " + root);
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
                Path located = w.locateStored(JkStores.storeCas());
                if (located != null) props.put(w.jarProperty(), located.toString());
            }
        }
    }

    /**
     * Engine jar for nested CLI suites: workspace assembly when present, else the host process's
     * fat jar / installed EngineInstall materialization.
     */
    static @Nullable Path resolveEngineJarForNestedTests(Map<String, Path> siblings) {
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
    static @Nullable Path locateHostEngineJar() {
        Path override = BuildPlanner.hostEngineSearchOverride;
        if (override != null) return findMonorepoEngineJar(override);
        try {
            var cs = PlannerSupport.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path p = Path.of(cs.getLocation().toURI());
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
                    return p.normalize();
                }
            }
        } catch (Exception ignored) {
            // fall through — exploded test classpath is common under Gradle
        }
        for (Path p : Classpaths.split(System.getProperty("java.class.path", ""))) {
            String name = p.getFileName() != null ? p.getFileName().toString() : "";
            if (Files.isRegularFile(p)
                    && name.endsWith(".jar")
                    && (name.startsWith("jk-engine") || name.equals("jk-engine.jar"))) {
                return p.toAbsolutePath().normalize();
            }
        }
        try {
            var mat = EngineInstall.current().resolve(JkVersion.VERSION);
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
    static @Nullable Path findMonorepoEngineJar(Path start) {
        String ver = JkVersion.VERSION;
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
     * Isolated {@code JK_HOME} + {@code JK_STATE_DIR} in this module's sandbox slot ({@link TestHomes})
     * for nested-engine CLI tests. Keeps the host engine's socket, cache, and store alone.
     *
     * <p>{@code JK_HOME} relocates cache and store together. Nested destructive tests must never
     * receive the host's {@code JK_CACHE_DIR} or {@code JK_STORE_DIR}.
     *
     * <p>Plugin/worker jars for nested suites still arrive via {@code -Djk.*.plugin.jar} props
     * ({@link #enrichCliTestProps}), not by sharing the host store.
     */
    static Map<String, String> nestedEngineTestEnv(Path moduleDir) throws IOException {
        Path jkHome = TestHomes.prepare(moduleDir);
        String runId = Long.toString(System.currentTimeMillis(), 36) + "-"
                + Integer.toHexString(System.identityHashCode(moduleDir) & 0xffff);
        // Under this module's sandbox home, which is outside the project (see TestHomes) and which
        // `jk clean` removes for this module. Nested engines here use TCP
        // (JK_ENGINE_TRANSPORT=tcp), so UDS path length does not constrain depth.
        Path stateDir = jkHome.resolve("engine-state").resolve(runId);
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
    static TestSelection effectiveSelection(TestSelection sel, Path moduleDir) {
        // tagsResolved: the CLI already applied baseline/profile/flag layers — an empty list may
        // be an explicit clear ([profiles.x] exclude-tags = []) and must stay empty.
        if (sel.tagsResolved()) return sel;
        if (!sel.includeTags().isEmpty() || !sel.excludeTags().isEmpty()) return sel;
        var fromToml = JkBuildParser.parseTestTags(moduleDir.resolve(ManifestPaths.MANIFEST));
        if (fromToml.isEmpty()) return sel;
        return TestSelection.of(
                sel.suites(),
                sel.allSuites(),
                fromToml.includeTags(),
                fromToml.excludeTags(),
                false,
                sel.guard(),
                sel.scriptsOnly(),
                sel.noScripts(),
                sel.classes());
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
        // workspace short-circuits to "up to date" without running the widened tier.
        return testStampExtras(
                testStampWorkerJars(dir, project),
                effectiveSelection(SessionContext.current().testSelection(), dir),
                project.build(),
                dir);
    }

    /** Lock + workspace sibling classpath the forecast uses for compile-test. */
    static List<Path> testCompileClasspath(Path dir, JkBuild project, Lockfile lock, ClasspathResolver resolver)
            throws IOException {
        WorkspaceClasspath.Result sib =
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST, Scope.TEST_DEV));
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST));
        cp.addAll(sib.jars());
        for (Path sl : sib.siblingLockfiles()) {
            try {
                Lockfile s = LockfileReader.read(sl);
                for (Path p : resolver.classpathFor(s, ClasspathResolver.COMPILE_MAIN)) if (!cp.contains(p)) cp.add(p);
            } catch (Exception ignored) {
                /* best-effort */
            }
        }
        return cp;
    }

    /** Lock + workspace sibling classpath the forecast uses for run-tests. */
    static List<Path> testRuntimeClasspath(Path dir, JkBuild project, Lockfile lock, ClasspathResolver resolver)
            throws IOException {
        WorkspaceClasspath.Result sib =
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST, Scope.TEST_DEV));
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.TEST));
        cp.addAll(sib.jars());
        for (Path sl : sib.siblingLockfiles()) {
            try {
                Lockfile s = LockfileReader.read(sl);
                for (Path p : resolver.classpathFor(s, ClasspathResolver.RUNTIME)) if (!cp.contains(p)) cp.add(p);
            } catch (Exception ignored) {
                /* best-effort */
            }
        }
        return cp;
    }

    /**
     * Full {@link cc.jumpkick.task.TestStamp} key for the default {@code jk build} selection
     * single factory for forecast and any offline checker. {@code testRuntimeCp} must match the
     * build's runtime classpath for the stamp (lock deps + workspace sibling jars; plugin
     * contributions optional for non-plugin modules).
     */
    public static @Nullable String runTestsStampKey(
            Path dir, JkBuild project, boolean compact, Path mainClasses, Path lockFile, List<Path> testRuntimeCp)
            throws IOException {
        return runTestsStampKey(dir, project, compact, mainClasses, null, lockFile, testRuntimeCp);
    }

    /**
     * {@code mainClassesFingerprint} overrides the on-disk main-classes tree when non-null (post-
     * {@code jk clean} projection from the compile action record).
     */
    public static @Nullable String runTestsStampKey(
            Path dir,
            JkBuild project,
            boolean compact,
            Path mainClasses,
            @Nullable String mainClassesFingerprint,
            Path lockFile,
            List<Path> testRuntimeCp)
            throws IOException {
        List<String> discovered = TestSuites.discover(dir, compact);
        // Session selection for suite resolution too — --all widens the suite set, and the
        // forecast's source list must cover the same files the live run stamps.
        var resolved = SessionContext.current().testSelection().resolve(discovered);
        List<String> suites = resolved.ok() ? resolved.suites() : List.of(TestSuites.DEFAULT);
        // The same factory the live plan fills TEST_SOURCES from, so the forecast stamps exactly
        // the files the run stamps: every selected suite's sources in every language, plus the
        // `[test] extra-src` files that belong to the tier and to no suite.
        List<Path> stampSrcs =
                PlannerTest.TestSources.collect(project, dir, compact, suites).all();
        BuildLayout layout = BuildLayout.of(dir, project);
        List<Path> stampRt = PlannerFixtures.withOwnFixtures(project, layout, testRuntimeCp);
        List<String> stampExtras = testStampExtras(dir, project);
        List<Path> stampRes = ModuleLayout.suiteResourceDirs(dir, compact, suites);
        String key = TestStamp.computeKey(
                stampSrcs, mainClasses, mainClassesFingerprint, stampRes, lockFile, stampRt, stampExtras);
        if (Perf.ENABLED) {
            // The key itself, not just its inputs: when this disagrees with `live-test-stamp` for
            // the same module, the forecast is predicting a suite re-run the build will skip. That
            // is how `jk explain`'s missing test selection was found — every input printed here
            // matched and only the key differed, which narrowed it to the one input not printed.
            System.err.println("[jk-perf] fstamp " + dir
                    + " key=" + key
                    + " src=" + stampSrcs.size() + " res=" + stampRes.size()
                    + " rt=" + stampRt.size() + " extras=" + stampExtras.size()
                    + " X=" + stampExtras + " suites=" + suites
                    + " cpFp=" + ClasspathFingerprint.of(stampRt)
                    + " mainFp="
                    + (mainClassesFingerprint != null
                            ? mainClassesFingerprint
                            : ClasspathFingerprint.entry(mainClasses)));
        }
        return key;
    }

    /**
     * Stamp extras with env expansion and secret hashing for a module at {@code moduleDir}. There is
     * deliberately no lookup-free overload: it would silently skip both expansion and hashing and
     * produce a key that disagrees with this one.
     */
    static List<String> testStampExtras(
            Map<String, String> workerJars, TestSelection selection, JkBuild.Build build, Path moduleDir) {
        EnvLookup lookup = BuildEnv.lookupFor(Objects.requireNonNull(moduleDir, "moduleDir"));
        return testStampExtras(workerJars, selection, build, SecretRedactor.from(lookup), lookup);
    }

    /**
     * Stamp extras. Declared {@code [env] vars} and {@code [test] env} values only (sandbox defaults
     * stay out — they are absolute paths that would defeat cache sharing).
     */
    static List<String> testStampExtras(
            Map<String, String> workerJars,
            TestSelection selection,
            JkBuild.Build build,
            SecretRedactor redactor,
            EnvLookup lookup) {
        List<String> extras = new ArrayList<>();
        extras.add("jk:" + BuildIdentity.cacheKeyVersion());
        // Suite + tag filters are part of the outcome.
        if (selection != null) extras.add("sel:" + selection.identityToken());
        // [test] env changes what the suite sees, so it must retest. Resolved by the same owner the
        // fork uses, in its cache-key mode: ${target}/${module} stay tokens so the key is portable,
        // a .env-sourced value is hashed, and an unset ${VAR} fails here exactly as it fails at
        // launch — a manifest jk cannot fork must never forecast as "tests cached".
        // The launch-side directories are not passed: the mode, not the caller, decides what the
        // two path tokens mean, and this mode keeps them literal.
        var mode = new TestEnvValues.Mode.CacheKey(lookup, redactor);
        var resolved = new LinkedHashMap<>(
                TestEnvValues.resolve("[env].vars", build.env().vars(), null, null, mode));
        resolved.putAll(TestEnvValues.resolve("[test].env", build.testEnv(), null, null, mode));
        for (Map.Entry<String, String> e : resolved.entrySet()) {
            extras.add("test-env:" + e.getKey() + "=" + e.getValue());
        }
        // Plugin jars by content — a plugin change retests the module that forks it.
        for (Map.Entry<String, String> e : workerJars.entrySet()) {
            String fp;
            try {
                fp = ClasspathFingerprint.entry(Path.of(e.getValue()));
            } catch (IOException ex) {
                fp = "err";
            }
            extras.add("worker:" + e.getKey() + "=" + fp);
        }
        return extras;
    }

    /** Package-private for {@link TaskForecaster} package-jar key parity with the live step. */
    static PluginBuild.@Nullable Declarations pluginDeclarationsFor(JkBuild project, BuildLayout layout, Path cache)
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
    static List<Path> existingContributedDirs(PluginBuild.@Nullable Declarations decls, BuildLayout layout) {
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
        if (moduleDir == null || project == null || !PluginModule.isWorker(moduleDir)) {
            return List.of();
        }
        Path root;
        JkBuild rootManifest;
        try {
            var rootOpt = WorkspaceLocator.findRoot(moduleDir);
            if (rootOpt.isEmpty()) return List.of();
            root = rootOpt.get();
            rootManifest = JkBuildParser.parse(root.resolve(ManifestPaths.MANIFEST));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        if (!rootManifest.isWorkspaceRoot()) return List.of();
        Map<String, Path> dirByName = new LinkedHashMap<>();
        Map<String, Path> dirByCoord = new LinkedHashMap<>();
        Map<Path, JkBuild> byDir = new LinkedHashMap<>();
        for (String module : rootManifest.workspaceModules()) {
            Path dir = root.resolve(module);
            Path manifest = dir.resolve(ManifestPaths.MANIFEST);
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
    private static @Nullable Path workerSiblingDir(
            Dependency d, Map<String, Path> dirByName, Map<String, Path> dirByCoord) {
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
            sb.append(ClasspathFingerprint.entry(dir)).append('\n');
        }
        return Hashing.sha256Hex(sb.toString());
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
    static Path stageClassesWithContributions(TaskContext ctx, Path classes, List<Path> extra, BuildLayout layout)
            throws IOException {
        if (extra.isEmpty()) return classes;
        Path stage = layout.moduleTargetDir().resolve("package-classes");
        String inputs = ClasspathFingerprint.entry(classes) + "|" + contributionsToken(extra);
        if (ctx != null && inputs.equals(ctx.get(STAGED_CLASSES_INPUTS).orElse(null)) && Files.isDirectory(stage)) {
            return stage;
        }
        // A wipe that cannot finish is a build error, not something to paper over: the copy below
        // only overwrites paths it reproduces, so a survivor from a previous build (a renamed or
        // no-longer-emitted class) would be packaged, and the packaging key is taken over this
        // dir — so the wrong content is what gets cached and restored.
        PathUtil.deleteRecursivelyOrThrow(stage);
        Files.createDirectories(stage);
        copyTreeInto(classes, stage);
        for (Path contrib : extra) copyTreeInto(contrib, stage);
        mergeServiceRegistrations(stage, classes, extra);
        if (ctx != null) ctx.put(STAGED_CLASSES_INPUTS, inputs);
        return stage;
    }

    /**
     * {@code META-INF/services/*} is the one path a contribution must add to rather than replace.
     * The tree copies above let the last source win, and for a plugin worker whose contribution is
     * a workspace sibling that is itself a plugin — grails over spring-boot — that left the jar
     * registering the sibling's plugin and not its own; the worker then reported "no plugin with
     * protocol prefix" for the very jar it was launched from. Every source's registrations are kept,
     * the module's own first, each provider once.
     */
    static void mergeServiceRegistrations(Path stage, Path classes, List<Path> extra) throws IOException {
        Path servicesRel = Path.of("META-INF", "services");
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        List<Path> sources = new ArrayList<>();
        sources.add(classes);
        sources.addAll(extra);
        for (Path source : sources) {
            Path services = source.resolve(servicesRel);
            List<Path> files = new ArrayList<>();
            PathUtil.forEachRegularFile(services, (file, attrs) -> files.add(file));
            files.sort(Comparator.comparing(f -> f.getFileName().toString()));
            for (Path file : files) {
                var providers = merged.computeIfAbsent(file.getFileName().toString(), k -> new LinkedHashSet<>());
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String provider = line.strip();
                    if (!provider.isEmpty() && !provider.startsWith("#")) providers.add(provider);
                }
            }
        }
        for (var e : merged.entrySet()) {
            if (e.getValue().size() < 2) continue; // one source, or one provider: the copy is right
            Path out = Files.createDirectories(stage.resolve(servicesRel)).resolve(e.getKey());
            Files.writeString(out, String.join("\n", e.getValue()) + "\n", StandardCharsets.UTF_8);
        }
    }

    static void copyTreeInto(Path from, Path to) throws IOException {
        PathUtil.copyTree(from, to);
    }
    /**
     * Mirror the javac diagnostic loop for worker compilers: one {@code ctx.error}/{@code warn}
     * per diagnostic so each keeps its own {@code path:line[:col]:} header for journal locus
     * parsing and CLI snippets, instead of one joined blob whose first header wins.
     */
    static void forwardWorkerDiagnostics(
            TaskContext ctx, String code, List<CompileResult.Diagnostic> diagnostics, String emptyFallback) {
        boolean errored = false;
        for (CompileResult.Diagnostic d : diagnostics) {
            if (d.severity() == CompileResult.Severity.ERROR) {
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
