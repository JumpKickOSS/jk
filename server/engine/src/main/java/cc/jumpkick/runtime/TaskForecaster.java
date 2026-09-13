// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Read-only step forecast for each module in a {@link BuildGraph}, using the same cache keys as the
 * real build. Dirty modules and their consumers are marked will-run in dependency order so
 * downstream keys are not trusted against about-to-change inputs (misses are pessimistic, never
 * false cache hits).
 */
public final class TaskForecaster {

    private TaskForecaster() {}

    /** Forecast every module in {@code graph}, in topological (dependency) order. */
    public static List<TaskForecast.Module> of(BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache) {
        return of(graph, cas, actionCache, cache, false);
    }

    /**
     * Like {@link #of(BuildGraph.Result, Cas, ActionCache, Path)} but omits test steps when
     * {@code skipTests} so a never-tested workspace is not forecast perpetually dirty.
     */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph, Cas cas, ActionCache actionCache, Path cache, boolean skipTests) {
        return of(graph, cas, actionCache, cache, skipTests, WorkspaceTarget.PACKAGE);
    }

    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target) {
        return of(graph, cas, actionCache, cache, skipTests, target, Set.of());
    }

    /**
     * As {@link #of(BuildGraph.Result, Cas, ActionCache, Path, boolean, WorkspaceTarget)} with the
     * resolved terminal module set: the dirs that will receive the target's terminal step
     * (native-image / write-image), mirroring {@code WorkspacePreparePhase.assemblePlan} eligibility.
     * The forecast must consume the same set the plan assembly uses — re-deriving eligibility
     * here (e.g. from {@code [native]} tables) skips fallback modules and prices unselected ones.
     */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target,
            Set<Path> terminalDirs) {
        return of(graph, cas, actionCache, cache, skipTests, target, terminalDirs, null);
    }

    /**
     * As above with the request's {@code --profile}: the build compiles with that profile's javac
     * args appended, so the forecast must key its compile steps the same way or a profile build
     * after a default build forecasts every compile as cached and schedules nothing.
     */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target,
            Set<Path> terminalDirs,
            @Nullable String profile) {
        return of(graph, cas, actionCache, cache, skipTests, target, terminalDirs, profile, null);
    }

    /**
     * As above with the install request's {@code --m2-dir}: the cache-install step judges "already
     * installed" against the Maven local repo it writes to, so the forecast reads the same root or
     * {@code jk explain} reports the install done, or pending, against the machine's {@code ~/.m2}.
     */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target,
            Set<Path> terminalDirs,
            @Nullable String profile,
            @Nullable Path m2Dir) {
        // One resolver for the whole walk: every module resolves its classpath against the same
        // lock and store, so its per-artifact resolve memo is only useful if it outlives a module.
        ClasspathResolver resolver = new ClasspathResolver(cas);
        Path workerJar = null;
        try {
            workerJar = PluginJar.JAVA_COMPILER.locateStored(cas);
        } catch (RuntimeException e) {
            // forecast without a worker still uses action-cache + zinc-file presence
            Log.debug("of: forecast without a worker still uses action-cache + zinc-file presence", e);
        }
        try (JavaCompilerHost.Scope ignored = JavaCompilerHost.open()) {
            return forecastModules(
                    graph,
                    cas,
                    resolver,
                    actionCache,
                    cache,
                    skipTests,
                    target,
                    terminalDirs,
                    workerJar,
                    profile,
                    m2Dir);
        }
    }

    private static List<TaskForecast.Module> forecastModules(
            BuildGraph.Result graph,
            Cas cas,
            ClasspathResolver resolver,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target,
            Set<Path> terminalDirs,
            @Nullable Path workerJar,
            @Nullable String profile,
            @Nullable Path m2Dir) {
        List<TaskForecast.Module> out = new ArrayList<>();
        // --force/--rerun bypasses jk's build caches, so every step runs — the forecast must say
        // so too (otherwise the plan tree renders "Fully Cached" while the ETA, which honors force,
        // predicts a full rebuild — a self-contradiction).
        boolean force = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        // Dirs whose *main output* will change this build — seeds downstream and
        // cross-module dirtiness. Filled as we walk in dependency order.
        Set<Path> dirty = new HashSet<>();
        // Jar CAS shas recovered from each walked module's CURRENT package-jar record —
        // consumers fingerprint wiped sibling jars from here, never from an unvalidated
        // last-record pointer (which may name a different edit of the sibling).
        Map<Path, String> restoredJarShas = new HashMap<>();
        // Sibling lookup for scope-aware dirtiness (coord + bare name → dir).
        Map<String, Path> dirByCoord = new HashMap<>();
        Map<String, Path> dirByName = new HashMap<>();
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            dirByCoord.put(unit.coord(), unit.dir());
            dirByName.put(unit.manifest().project().name(), unit.dir());
        }
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            // Scope-aware: a dirty *test-only* sibling (e.g. cli → engine via test-dependencies)
            // must not force compile/package/native — only tests re-run against the new jar.
            // Treating every graph edge as compile-dirty was pricing full native-image (~35s)
            // on dogfood engine edits while live builds skipped compile+package+native.
            DepDirtiness dep =
                    depDirtiness(u, graph.edges().getOrDefault(u.dir(), Set.of()), dirty, dirByCoord, dirByName);
            long t0 = Perf.start();
            TaskForecast.Module m = forecastModule(
                    u,
                    dep,
                    force,
                    skipTests,
                    cas,
                    resolver,
                    actionCache,
                    cache,
                    restoredJarShas,
                    target,
                    terminalDirs,
                    workerJar,
                    profile,
                    m2Dir);
            Perf.end("forecast " + u.coord(), t0);
            // Seed main-output dirtiness for *compile* consumers only when this module's
            // consumed jar/classes will change — not when only test-scope work is dirty.
            // Package matters on its own: a consumer's compile classpath hashes sibling JAR
            // *content*, so an upstream whose compile is cached but whose jar is stale
            // repackages and invalidates the consumer.

            // Also seed when a compile-scope dep is dirty even if predictors still look cached
            // against pre-rebuild sibling jars (pessimistic; avoids under-reserve).
            if (seedsCompileConsumerCascade(m) || dep.compileDepDirty()) {
                dirty.add(u.dir());
            }
            out.add(m);
        }
        return out;
    }

    /**
     * Whether this module's forecast should force compile-scope dependents dirty.
     *
     * <p>True when compile or package will change the jar/classes consumers hash. False for
     * resource-only drift ({@code copy-resources} RUN + package CACHED): the producer still
     * schedules via {@link TaskForecast.Module#dirty()}, but dependents must not inherit full
     * recompile+test ETA while the packaged jar stays byte-identical.
     */
    static boolean seedsCompileConsumerCascade(TaskForecast.Module m) {
        if (m == null || m.steps() == null) return false;
        return m.steps().stream()
                .anyMatch(p -> !p.cached()
                        && (p.name().startsWith(TaskNames.COMPILE_MAIN)
                                || p.name().startsWith(TaskNames.COMPILE_JAVA)
                                || p.name().startsWith(TaskNames.COMPILE_KOTLIN)
                                || p.name().startsWith(TaskNames.COMPILE_GROOVY)
                                || TaskNames.PACKAGE_JAR.equals(p.name())
                                || TaskNames.PACKAGE_ASSEMBLY.equals(p.name())));
    }

    /**
     * Which dirty prereqs affect this module's main compile vs tests only. A dirty prereq
     * reachable only via {@code order-after} (incl. {@code test-plugin-jars}) forces no
     * compile/test pricing, but still marks {@link #orderDepDirty} — the dependent must
     * <em>schedule</em> so its real action keys re-check the prereq's out-of-band outputs
     * (test-plugin jars ride the run-tests stamp; users add order-after precisely for
     * consumption the classpath cannot express). Pricing nothing keeps ETA honest; skipping
     * the module entirely shipped stale outputs.
     */
    record DepDirtiness(boolean compileDepDirty, boolean testDepDirty, boolean orderDepDirty) {
        static final DepDirtiness NONE = new DepDirtiness(false, false, false);
    }

    static DepDirtiness depDirtiness(
            BuildGraph.BuildUnit u,
            Set<Path> prereqs,
            Set<Path> dirty,
            Map<String, Path> dirByCoord,
            Map<String, Path> dirByName) {
        if (prereqs == null || prereqs.isEmpty() || dirty.isEmpty()) return DepDirtiness.NONE;
        boolean compile = false;
        boolean test = false;
        boolean order = false;
        JkBuild m = u.manifest();
        for (Path dep : prereqs) {
            if (!dirty.contains(dep)) continue;
            boolean viaCompile = false;
            boolean viaTest = false;
            for (Scope scope : Scope.values()) {
                for (Dependency d : m.dependencies().of(scope)) {
                    Path hit = ModuleOrder.resolveSibling(d, dirByCoord, dirByName);
                    if (hit == null || !hit.equals(dep)) continue;
                    if (scope == Scope.TEST || scope == Scope.TEST_DEV) viaTest = true;
                    else viaCompile = true;
                }
            }
            if (viaCompile) compile = true;
            else if (viaTest) test = true;
            else order = true; // order-after-only prereq: schedule, price nothing
        }
        return new DepDirtiness(compile, test, order);
    }

    /**
     * The {@link BuildPlanner.Inputs} a real {@code jk build} constructs for one module — the
     * single factory both {@code jk build} ({@code BuildCommand.prepareModule}) and {@code jk
     * explain}'s ETA use, so the two can't drift in what they feed the effort-weight prediction.
     * The {@code jdksDir} default of {@code null} is load-bearing: it routes {@link
     * cc.jumpkick.runtime.EffortWeights#jdkWeight} through the full JDK probe chain (PATH /
     * JAVA_HOME / GraalVM / SDKMAN / …) instead of the empty {@code the managed JDK root}, so an
     * already-installed JDK predicts a zero-cost {@code ensure-jdk} rather than a phantom download.
     */
    public static BuildPlanner.Inputs inputsFor(
            Path dir,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose) {
        return inputsFor(dir, cache, workers, jdksDir, profile, skipTests, verbose, Set.of());
    }

    /**
     * As {@link #inputsFor(Path, Path, int, Path, String, boolean, boolean)} but carrying the sibling
     * module dirs of the build graph, so the effort-weight prediction can borrow a project-tier learned
     * rate for a not-yet-built module (see {@link cc.jumpkick.runtime.EffortWeights#learned}).
     */
    public static BuildPlanner.Inputs inputsFor(
            Path dir,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            Set<Path> projectModules) {
        return inputsFor(dir, cache, workers, jdksDir, profile, skipTests, verbose, projectModules, false);
    }

    /**
     * As {@link #inputsFor(Path, Path, int, Path, String, boolean, boolean, Set)} with {@code
     * testOnly} — when true, plans stop before packaging ({@code jk test} / MCP {@code jk_test}).
     */
    public static BuildPlanner.Inputs inputsFor(
            Path dir,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            Set<Path> projectModules,
            boolean testOnly) {
        Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
        Path lockFile = LockPaths.lockFile(dir);
        // 0 = auto at run-tests (JUnitLauncher); forecast treats as 1 for cost estimates.
        int workerCount = workers > 0 ? workers : 1;
        // testOnly still runs tests unless --scripts-only (guard scripts, no JUnit).
        boolean skip = SessionContext.current().testSelection().scriptsOnly() || (!testOnly && skipTests);
        boolean compactEst = CompileSupport.isSimpleLayout(dir);
        int estimatedTestCount = skip ? 0 : TestSupport.estimateAllSuiteTestCount(dir, compactEst);
        return new BuildPlanner.Inputs(
                        dir,
                        cache,
                        buildFile,
                        lockFile,
                        dir,
                        workerCount,
                        estimatedTestCount,
                        profile,
                        jdksDir,
                        skip,
                        verbose,
                        testOnly,
                        false,
                        Set.of(),
                        SessionContext.current())
                .withProjectModules(projectModules);
    }

    private static TaskForecast.Module forecastModule(
            BuildGraph.BuildUnit u,
            DepDirtiness dep,
            boolean force,
            boolean skipTests,
            Cas cas,
            ClasspathResolver resolver,
            ActionCache actionCache,
            Path cache,
            Map<Path, String> restoredJarShas,
            WorkspaceTarget target,
            Set<Path> terminalDirs,
            @Nullable Path workerJar,
            @Nullable String profile,
            @Nullable Path m2Dir) {
        return new ModuleForecast(
                        u,
                        dep,
                        force,
                        skipTests,
                        cas,
                        resolver,
                        actionCache,
                        cache,
                        restoredJarShas,
                        target,
                        terminalDirs,
                        workerJar,
                        profile,
                        m2Dir)
                .run();
    }

    /**
     * The JDK compile-main will run on, resolved exactly as {@code PlannerSetup.ensureJdkStep}
     * resolves it and with the same fallback — but with installs refused. {@code jk explain} is
     * read-only, so a pin that is not on disk raises here and the module forecasts a step that
     * will run rather than a key computed against whichever JDK happens to be on PATH.
     */
    static Path forecastJavaHome(Path dir, JkBuild project, Lockfile lock) throws IOException, InterruptedException {
        return JdkEnsure.ensure(dir, null, project, lock, m -> {}, false)
                .jdkOpt()
                .map(InstalledJdk::home)
                .orElseGet(() -> JavaHomes.resolveJavaHome(dir));
    }

    /** Main resource roots (or a module-root {@code jk-plugin.toml}) differ from copies under {@code classesDir}. */
    static boolean mainResourcesOutOfSync(Path dir, boolean compact, Path classesDir) {
        if (flattenedPluginCatalogPresent(classesDir)) return true;
        if (resourcesOutOfSync(ModuleLayout.mainResourcesDir(dir, compact), classesDir)) {
            return true;
        }
        return pluginManifestOutOfSync(dir, classesDir);
    }

    /**
     * True when main classes still hold test-only flattened plugin manifests. Those files are
     * not in {@code src/main/resources}, so {@link #resourcesOutOfSync} cannot see them.
     * Restricted to jk's BUILT_IN names, matching the strip in {@code PlannerResources}: a user
     * resource that merely shares the package must not read as drift, or every build re-runs
     * resources forever.
     */
    static boolean flattenedPluginCatalogPresent(Path classesDir) {
        Path catalog = classesDir.resolve(Path.of("cc", "jumpkick", "plugin", "manifest"));
        if (!Files.isDirectory(catalog)) return false;
        var builtIn = PluginTableRegistry.builtInManifestNames();
        try (var stream = Files.list(catalog)) {
            return stream.anyMatch(p ->
                    Files.isRegularFile(p) && builtIn.contains(p.getFileName().toString()));
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Presence only: any non-stamp file under the tree. Whether the tree is <em>whole</em> is a
     * different question, answered against its compile record by {@link
     * ModuleOutputs#compileOutputsOnDisk}.
     */
    static boolean classesDirHasContent(Path classesDir) throws IOException {
        // Terminates on the first hit with the attrs the walk already read — Files.walk's
        // anyMatch re-stats every entry to ask isRegularFile.
        return PathUtil.anyRegularFile(
                classesDir,
                d -> false,
                p -> !BuildStamps.isStampFile(p.getFileName().toString()));
    }

    /** True when a module-root {@code jk-plugin.toml} differs from its copy at the classes root. */
    static boolean pluginManifestOutOfSync(Path dir, Path outDir) {
        Path src = dir.resolve(ManifestPaths.PLUGIN_MANIFEST);
        Path copy = outDir.resolve(ManifestPaths.PLUGIN_MANIFEST);
        // A deleted (or renamed-away) manifest with a copy still in classes/ is the
        // orphan: the jar stays "self-describing" with an obsolete manifest until a clean build.
        if (!Files.isRegularFile(src)) return Files.isRegularFile(copy);
        try {
            if (!Files.isRegularFile(copy)) return true;
            if (Files.size(copy) != Files.size(src)) return true;
            if (Files.getLastModifiedTime(src).compareTo(Files.getLastModifiedTime(copy)) > 0
                    && Files.mismatch(src, copy) >= 0) {
                return true;
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    static boolean resourcesOutOfSync(Path resDir, Path outDir) {
        // Two readAttributes per resource, not six metadata ops. The source's come from the walk for
        // free; the copy's answer presence, size and mtime together — where isRegularFile + size +
        // size + mtime + mtime each re-resolved a path.
        boolean[] dirty = {false};
        try {
            PathUtil.forEachRegularFile(resDir, (source, attrs) -> {
                if (dirty[0]) return;
                Path copy = outDir.resolve(resDir.relativize(source));
                Optional<BasicFileAttributes> target = PathUtil.stat(copy);
                if (target.isEmpty() || !target.get().isRegularFile()) {
                    dirty[0] = true;
                    return;
                }
                if (target.get().size() != attrs.size()) {
                    dirty[0] = true;
                    return;
                }
                if (attrs.lastModifiedTime().compareTo(target.get().lastModifiedTime()) > 0
                        && Files.mismatch(source, copy) >= 0) {
                    dirty[0] = true;
                }
            });
        } catch (IOException e) {
            return true; // unreadable ⇒ treat as dirty
        }
        return dirty[0];
    }

    /**
     * Map a {@link JavaCompile.Prediction} to a step, honoring upstream dirtiness. A request that
     * invokes javac plugins names them in the step's text, so {@code jk explain --verbose} shows
     * that input beside the outcome.
     */
    static TaskForecast.Task compileStep(
            String name, JavaCompile.Prediction pred, boolean compileDepDirty, CompileRequest request) {
        TaskForecast.Task step = compileStep(name, pred, compileDepDirty);
        List<String> plugins = PlannerCompile.pluginNames(request);
        if (plugins.isEmpty()) return step;
        String text = PlannerCompile.PLUGIN_FLAG + String.join(",", plugins);
        if (!step.text().isEmpty()) text = step.text() + " · " + text;
        return new TaskForecast.Task(name, step.status(), text, step.key());
    }

    private static TaskForecast.Task compileStep(String name, JavaCompile.Prediction pred, boolean compileDepDirty) {
        return switch (pred.outcome()) {
            case CACHE_HIT ->
                // Only force RUN when a *compile-scope* sibling is dirty (action key still sees
                // the pre-rebuild jar). Test-only siblings never reach here as compileDepDirty.
                compileDepDirty
                        ? new TaskForecast.Task(name, TaskForecast.Status.RUN, "recompile · dependency changed", null)
                        : new TaskForecast.Task(name, TaskForecast.Status.CACHED, "", key8(pred.actionKey()));
            case INCREMENTAL -> {
                String detail = pred.reason() != null && !pred.reason().isBlank()
                        ? pred.reason()
                        : count(pred.sourceCount(), "source") + " changed";
                String files = fileHint(pred.sources());
                if (!files.isEmpty()) detail = detail + " (" + files + ")";
                yield new TaskForecast.Task(name, TaskForecast.Status.PARTIAL, "compile · " + detail, null);
            }
            case FULL -> {
                // surface the concrete gate (classpath, options, first compile, …).
                String why = pred.reason() != null && !pred.reason().isBlank()
                        ? pred.reason()
                        : "sources / options / classpath";
                yield new TaskForecast.Task(
                        name,
                        TaskForecast.Status.FULL,
                        "full compile · " + count(pred.sourceCount(), "source") + " · " + why,
                        null);
            }
        };
    }

    // --- the build's test classpaths, mirrored (best-effort; misses fail safe) ---

    /**
     * True when the stamp-language compile ({@code compile-kotlin} / {@code compile-groovy}) has a
     * surviving action-cache pointer whose payloads are still present — the post-{@code jk clean}
     * restore path. Never-built modules have no {@code tasks/} pointer.
     */
    static boolean stampLangActionPresent(ActionCache ac, String taskId) {
        try {
            var rec = ac.lastFor(taskId);
            return rec.isPresent() && present(ac, rec.get().actionKey());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Record exists AND every <em>payload</em> blob is still in the action cache's CAS. LRU
     * eviction removes payloads while their records live on (records die by TTL), and a record
     * whose blobs are gone cannot restore — forecasting it CACHED would over-promise: wrong
     * {@code jk explain}, undercounted dirty set, deflated ETA seed.
     *
     * <p>Only 64-char hex values are payload digests. Marker records (run-tests green stamp)
     * park small scalars such as {@code tests.total=0} in the same map — those are not CAS
     * keys and must not fail the presence check, or a successful empty/green suite is forever
     * forecast as dirty (plan shows {@code run-tests [run]} after every build).
     *
     * <p>Presence check only ({@code pathFor} + {@code isRegularFile}); never hashes bytes.
     */
    static boolean present(ActionCache ac, @Nullable String key) {
        return presentRecord(ac, key).isPresent();
    }

    /** The record behind {@link #present}, for callers that read its markers. */
    static Optional<ActionCache.ActionRecord> presentRecord(ActionCache ac, @Nullable String key) {
        try {
            if (key == null) return Optional.empty();
            var rec = ac.lookup(key);
            if (rec.isEmpty()) return Optional.empty();
            for (String sha : rec.get().outputs().values()) {
                if (!isSha256Hex(sha)) continue; // marker scalar, not a CAS blob
                if (!Files.isRegularFile(ac.cas().pathFor(sha))) return Optional.empty();
            }
            return rec;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Same shape {@link ActionCache} meters by — 64-char hex digests only. */
    static boolean isSha256Hex(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    static String key8(String key) {
        return key != null && key.length() >= 8 ? key.substring(0, 8) : key;
    }

    static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String fileHint(List<Path> sources) {
        if (sources == null || sources.isEmpty()) return "";
        int n = sources.size();
        int show = Math.min(n, 4);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < show; i++) {
            if (i > 0) b.append(", ");
            b.append(sources.get(i).getFileName());
        }
        if (n > show) b.append(", +").append(n - show);
        return b.toString();
    }
}
