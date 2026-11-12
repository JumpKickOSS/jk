// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
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
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.ContextPropagator;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.FilteredResources;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.ProcessedResources;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.ProjectedFiles;
import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Read-only step forecast for each module in a {@link BuildGraph}, using the same cache keys as the
 * real build. Dirty modules and their consumers are marked will-run in dependency order so
 * downstream keys are not trusted against about-to-change inputs (misses are pessimistic, never
 * false cache hits).
 */
public final class TaskForecaster {

    private TaskForecaster() {}

    /** A module's plan for the forecast's goal, as the one planner builds it; the workspace layer supplies it. */
    @FunctionalInterface
    public interface Planner {
        BuildPlan plan(BuildGraph.BuildUnit unit) throws Exception;
    }

    /**
     * Forecast every module in {@code graph}, in topological (dependency) order, by pricing each
     * step of the plan {@code planner} builds for it. {@code only} walks just those modules and
     * reports every other one with no work: for a caller that already knows the rest are clean and
     * their outputs present — a preflight memo hit, whose dirty set is build logic or a stale row
     * and never moves a consumer's inputs. Null walks all. {@code profile} keys the compile steps
     * with that profile's javac args, as the build does.
     */
    public static List<TaskForecast.Module> of(
            BuildGraph.Result graph,
            Cas cas,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target,
            @Nullable String profile,
            @Nullable Set<Path> only,
            Planner planner) {
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
        Path worker = workerJar;
        try {
            // The walk reads the same classpath entry once per module that names it, and a
            // directory entry is a whole tree walk. It writes nothing, so one reading each is the
            // same answer — see ClasspathFingerprint.withEntryMemo, which only a reader may bind.
            return JavaCompilerHost.scoped(() -> ClasspathFingerprint.withEntryMemo(() -> forecastModules(
                    graph, cas, resolver, actionCache, cache, skipTests, target, worker, profile, only, planner)));
        } catch (RuntimeException | Error direct) {
            throw direct;
        } catch (Exception impossible) {
            // forecastModules declares nothing checked; the memo scope's signature does.
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Told each module the walk finishes, as (done, total); bound by the preflight's cache check and
     * carried onto the lanes that price the modules.
     */
    public static final ScopedValue<ModuleProgress> PROGRESS = ScopedValue.newInstance();

    static {
        ContextPropagator.carry(PROGRESS);
    }

    /** A forecast walk's progress: {@code done} of {@code total} modules priced. */
    @FunctionalInterface
    public interface ModuleProgress {
        void moduleDone(int done, int total);
    }

    private static List<TaskForecast.Module> forecastModules(
            BuildGraph.Result graph,
            Cas cas,
            ClasspathResolver resolver,
            ActionCache actionCache,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target,
            @Nullable Path workerJar,
            @Nullable String profile,
            @Nullable Set<Path> only,
            Planner planner) {
        // --force/--rerun bypasses jk's build caches, so every step runs — the forecast must say
        // so too (otherwise the plan tree renders "Fully Cached" while the ETA, which honors force,
        // predicts a full rebuild — a self-contradiction).
        boolean force = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        // Dirs whose *main output* will change this build — seeds downstream and
        // cross-module dirtiness. Filled wave by wave, so a wave reads only earlier waves.
        Set<Path> dirty = ConcurrentHashMap.newKeySet();
        // What each walked module's CURRENT records say the build restores before its consumers
        // key on it — wiped trees by the token and identity of the tree that comes back, wiped
        // jars by their payload sha — never an unvalidated last-record pointer (which may name a
        // different edit of the sibling). Consumers read every sibling through this ledger, so a
        // wiped workspace forecasts the keys the build will compute.
        RestoredOutputs restored = new RestoredOutputs(actionCache);
        // Sibling lookup for scope-aware dirtiness (coord + bare name → dir). Filled before the
        // walk and read-only inside it.
        Map<String, Path> dirByCoord = new HashMap<>();
        Map<String, Path> dirByName = new HashMap<>();
        // Each walked module's reading of its own API for the consumers that follow it.
        Map<Path, ModuleHint> hints = new ConcurrentHashMap<>();
        Map<Path, BuildGraph.BuildUnit> byDir = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            dirByCoord.put(unit.coord(), unit.dir());
            dirByName.put(unit.manifest().project().name(), unit.dir());
            byDir.put(unit.dir(), unit);
        }
        Map<Path, TaskForecast.Module> forecast = new ConcurrentHashMap<>();
        Set<Path> seeds = ConcurrentHashMap.newKeySet();
        Map<Path, Set<Path>> reads = graph.reads();
        Consumer<BuildGraph.BuildUnit> one = u -> {
            // A cancelled job stops at the module boundary: a walk over a thousand-module reactor
            // otherwise runs on for minutes after the client has gone, and the abandoned forecast
            // holds its request's scope the whole time.
            if (SessionContext.current().cancelled()) {
                throw new CancellationException("forecast cancelled after " + forecast.size() + " of "
                        + graph.topoOrder().size() + " modules");
            }
            if (only != null && !only.contains(u.dir())) {
                forecast.put(u.dir(), new TaskForecast.Module(u.dir(), u.coord(), List.of(), 0, 0, false, false));
                return;
            }
            // Scope-aware: a dirty *test-only* sibling (e.g. cli → engine via test-dependencies)
            // must not force compile/package/native — only tests re-run against the new jar.
            // Treating every graph edge as compile-dirty was pricing full native-image (~35s)
            // on dogfood engine edits while live builds skipped compile+package+native.
            DepDirtiness dep = depDirtiness(u, reads.getOrDefault(u.dir(), Set.of()), dirty, dirByCoord, dirByName);
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
                    restored,
                    hints,
                    target,
                    workerJar,
                    profile,
                    planner);
            Perf.end("forecast " + u.coord(), t0);
            forecast.put(u.dir(), m);
            // Seed main-output dirtiness for *compile* consumers only when this module's
            // consumed classes/jar will change — not when only test-scope work is dirty.
            // Package matters on its own: a consumer's tests and packaging read the sibling
            // JAR, so an upstream whose compile is cached but whose jar is stale repackages
            // and the consumer's test and package steps re-run against the new bytes.

            // Also seed when a compile-scope dep is dirty even if predictors still look cached
            // against pre-rebuild sibling jars (pessimistic; avoids under-reserve).
            if (seedsCompileConsumerCascade(m) || dep.compileDepDirty()) {
                seeds.add(u.dir());
            }
        };
        // A test edge orders the walk too: the consumer's suite re-runs when the sibling's output
        // moves. A module's verdict is published before any consumer starts, so a consumer reads
        // its prereqs' dirtiness complete.
        List<BuildGraph.BuildUnit> walked = new ArrayList<>(byDir.values());
        AtomicInteger priced = new AtomicInteger();
        ForecastLanes.walk(walked, graph.readOrder(), lanes(walked.size()), u -> {
            one.accept(u);
            if (seeds.contains(u.dir())) dirty.add(u.dir());
            ModuleProgress progress = ContextPropagator.get(PROGRESS);
            if (progress != null) progress.moduleDone(priced.incrementAndGet(), walked.size());
        });
        // Anything the walk could not reach — a cycle the graph resolve should already have
        // refused — is still forecast, in dependency order, rather than silently missing.
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            if (forecast.containsKey(u.dir())) continue;
            one.accept(u);
            if (seeds.contains(u.dir())) dirty.add(u.dir());
        }
        List<TaskForecast.Module> out = new ArrayList<>(graph.topoOrder().size());
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            TaskForecast.Module m = forecast.get(u.dir());
            if (m != null) out.add(m);
        }
        return out;
    }

    /**
     * Ceiling on forecast lanes. The arms wait on the filesystem far more than on the CPU, so the
     * useful width is what the device absorbs rather than the core count; past this the lanes queue
     * on the device and only add contention. At most half of {@link JkThreads#CPU_THREADS}: an idle
     * lane holds its pool thread while it waits for a module to become ready, and the rest of the
     * pool stays free for what a visit itself hands to it.
     */
    private static final int MAX_LANES = 16;

    /** Modules a lane must be worth before one is opened; below this the walk runs on the caller. */
    private static final int MIN_MODULES_PER_LANE = 2;

    static int lanes(int modules) {
        return Math.clamp(modules / MIN_MODULES_PER_LANE, 1, Math.min(MAX_LANES, JkThreads.CPU_THREADS / 2));
    }

    /** Wait for every lane of a wave, re-raising the first failure as the serial walk would have. */
    static void joinWave(List<Future<?>> pending) {
        RuntimeException failure = null;
        for (Future<?> f : pending) {
            try {
                f.get();
            } catch (InterruptedException e) {
                cancelWave(pending);
                Thread.currentThread().interrupt();
                throw new CancellationException("forecast interrupted");
            } catch (CancellationException e) {
                // A future completed by cancel throws this directly, not wrapped.
                cancelWave(pending);
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof Error error) throw error;
                if (cause instanceof CancellationException cancelled) {
                    cancelWave(pending);
                    throw cancelled;
                }
                if (failure != null) continue;
                failure = cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
            }
        }
        if (failure instanceof CancellationException cancelled) {
            cancelWave(pending);
            throw cancelled;
        }
        if (failure != null) throw failure;
    }

    private static void cancelWave(List<Future<?>> pending) {
        for (Future<?> rest : pending) rest.cancel(true);
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
                        && (p.name().startsWith(TaskNames.COMPILE_JAVA)
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
    record DepDirtiness(boolean compileDepDirty, boolean testDepDirty, boolean orderDepDirty, List<Path> compileDeps) {
        static final DepDirtiness NONE = new DepDirtiness(false, false, false, List.of());

        DepDirtiness {
            compileDeps = List.copyOf(compileDeps);
        }
    }

    /**
     * What a module's forecast concluded about its own API for its consumers: the {@link
     * SourceApiIndex.Hint} its sources yield (body-only, API changed, or unknown), under the
     * module's name so a consumer's text can say whose edit it reads.
     */
    record ModuleHint(String module, SourceApiIndex.Hint hint) {}

    /**
     * A consumer's reading of its dirty compile-scope dependencies, before any of them compiled:
     * every dependency body-only ⇒ its compile key is likely to hold; any dependency whose
     * declarations moved ⇒ likely to miss; a dependency the index cannot classify ⇒ no hint. The
     * text is labelled as a hint because it is one — the key decides at build time, and the step
     * stays scheduled either way.
     */
    record DepHint(SourceApiIndex.Kind kind, String text) {
        static final DepHint NONE = new DepHint(SourceApiIndex.Kind.UNKNOWN, "");
    }

    static DepHint depHint(List<Path> compileDeps, Map<Path, ModuleHint> hints) {
        if (compileDeps.isEmpty()) return DepHint.NONE;
        List<String> bodyOnly = new ArrayList<>();
        List<String> apiChanged = new ArrayList<>();
        boolean unknown = false;
        for (Path dep : compileDeps) {
            ModuleHint h = hints.get(dep.toAbsolutePath().normalize());
            if (h == null) {
                unknown = true;
                continue;
            }
            switch (h.hint().kind()) {
                case BODY_ONLY -> bodyOnly.add(h.module());
                case API_CHANGED -> {
                    List<String> files = new ArrayList<>();
                    for (String f : h.hint().files()) files.add(ForecastSteps.fileName(f));
                    apiChanged.add(files.isEmpty() ? h.module() : h.module() + ": " + String.join(", ", files));
                }
                case UNKNOWN -> unknown = true;
            }
        }
        if (!apiChanged.isEmpty()) {
            return new DepHint(
                    SourceApiIndex.Kind.API_CHANGED,
                    TaskForecast.LIKELY_RECOMPILE + " · API changed in " + String.join("; ", apiChanged) + " (hint)");
        }
        if (unknown) return DepHint.NONE;
        return new DepHint(
                SourceApiIndex.Kind.BODY_ONLY,
                TaskForecast.LIKELY_UP_TO_DATE + " · body-only edit in " + String.join(", ", bodyOnly) + " (hint)");
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
        List<Path> compileDeps = new ArrayList<>();
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
            if (viaCompile) {
                compile = true;
                compileDeps.add(dep);
            } else if (viaTest) {
                test = true;
            } else {
                order = true; // order-after-only prereq: schedule, price nothing
            }
        }
        return new DepDirtiness(compile, test, order, compileDeps);
    }

    /**
     * The {@link BuildPlanner.Inputs} a real {@code jk build} constructs for one module — the
     * single factory both {@code jk build} and {@code jk
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
     * testOnly} — when true, plans stop before packaging ({@code jk test} / MCP {@code test}).
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
        return inputsFor(dir, cache, workers, jdksDir, profile, skipTests, verbose, projectModules, testOnly, false);
    }

    /** As above with {@code compileOnly}: the plan stops after compiling ({@code jk compile}). */
    public static BuildPlanner.Inputs inputsFor(
            Path dir,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            Set<Path> projectModules,
            boolean testOnly,
            boolean compileOnly) {
        Path buildFile = ManifestPaths.manifestIn(dir);
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
                        compileOnly,
                        Set.of(),
                        SessionContext.current())
                .withProjectModules(projectModules);
    }

    /**
     * One step a goal's tail appended to {@code unit}'s plan, priced the way it declares: an
     * always-run step runs, a priced one asks its predictor, alone, as no walk precedes it. Null for
     * a step that rides with its module. A step that declares nothing fails: an unpriced tail step
     * would leave its module unscheduled on a clean tree.
     */
    public static TaskForecast.@Nullable Task priceTailStep(
            Task step, BuildGraph.BuildUnit unit, Path cache, ActionCache actionCache) throws Exception {
        return switch (step.forecast()) {
            case null -> throw new IllegalStateException("tail step `" + step.name() + "` declares no forecast");
            case StepForecast.Rides r -> null;
            case StepForecast.Always a -> new TaskForecast.Task(step.name(), TaskForecast.Status.RUN, a.why(), null);
            case StepForecast.Priced p -> {
                ForecastScope s = ForecastScope.alone(unit, cache, actionCache);
                s.price(step.name(), p.predictor());
                yield s.verdict(step.name());
            }
        };
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
            RestoredOutputs restored,
            Map<Path, ModuleHint> hints,
            WorkspaceTarget target,
            @Nullable Path workerJar,
            @Nullable String profile,
            Planner planner) {
        ForecastScope s = new ForecastScope(
                u,
                dep,
                force,
                skipTests,
                cas,
                resolver,
                actionCache,
                cache,
                restored,
                hints,
                target,
                workerJar,
                profile);
        try {
            List<Task> steps = forecastOrder(planner.plan(u).steps());
            String unlocked = s.unlocked();
            // The build locks before it plans, against nothing cached for the new lock: every step
            // the plan prices runs, for that reason.
            if (unlocked != null) return s.unlocked(steps, unlocked);
            for (Task step : steps) {
                switch (step.forecast()) {
                    case StepForecast.Priced p -> s.price(step.name(), p.predictor());
                    case StepForecast.Always a -> s.always(step.name(), a.why());
                    case null, default -> {
                        /* rides with its module */
                    }
                }
            }
            return s.finish();
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            // Degrade gracefully — never crash explain over one unparseable module.
            Log.debug("forecast " + u.coord() + ": could not predict", e);
            return s.failed("could not predict (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The plan's steps in the order the forecast prices them: every step after the steps it
     * requires, and among the steps ready at once the main compiles first, then the resource and
     * fixture steps, so a predictor reads the compile dirtiness and resource drift it keys on.
     */
    static List<Task> forecastOrder(List<Task> steps) {
        Map<String, Task> byName = new LinkedHashMap<>();
        for (Task t : steps) byName.put(t.name(), t);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) index.put(steps.get(i).name(), i);
        Map<String, Integer> waiting = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Task t : steps) {
            int n = 0;
            for (String r : t.requires()) {
                if (!byName.containsKey(r)) continue;
                n++;
                dependents.computeIfAbsent(r, k -> new ArrayList<>()).add(t.name());
            }
            waiting.put(t.name(), n);
        }
        PriorityQueue<String> ready = new PriorityQueue<>(
                Comparator.comparingInt((String n) -> forecastRank(n)).thenComparingInt(index::get));
        for (var e : waiting.entrySet()) if (e.getValue() == 0) ready.add(e.getKey());
        List<Task> out = new ArrayList<>(steps.size());
        while (!ready.isEmpty()) {
            String n = ready.poll();
            out.add(byName.get(n));
            for (String d : dependents.getOrDefault(n, List.of())) {
                if (waiting.merge(d, -1, Integer::sum) == 0) ready.add(d);
            }
        }
        // A cycle the plan builder should have refused still prices every step, in plan order.
        for (Task t : steps) if (!out.contains(t)) out.add(t);
        return out;
    }

    private static int forecastRank(String step) {
        return switch (step) {
            case TaskNames.COMPILE_KOTLIN,
                    TaskNames.COMPILE_JAVA,
                    TaskNames.COMPILE_GROOVY,
                    TaskNames.ASSEMBLE_CLASSES,
                    TaskNames.COMPILE_VERSIONS -> 0;
            case TaskNames.COPY_RESOURCES,
                    TaskNames.BUILD_INFO,
                    TaskNames.MODULE_SERVICES,
                    TaskNames.COMPILE_TEST_FIXTURES -> 1;
            default -> 2;
        };
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

    /**
     * Main resource roots (or a module-root {@code jk-plugin.toml}) differ from copies under {@code
     * classesDir}. A copy a processor rewrote is the compile's while its resource is unchanged.
     */
    static boolean mainResourcesOutOfSync(Path dir, JkBuild project, boolean compact, Path classesDir) {
        if (flattenedPluginCatalogPresent(classesDir)) return true;
        Set<String> held;
        try {
            held = ProcessedResources.unchanged(classesDir);
        } catch (IOException e) {
            return true;
        }
        for (Path ksp : PlannerKsp.liveOutputRoots(project, dir)) {
            if (resourcesOutOfSync(ksp, classesDir, held)) return true;
        }
        if (!FilteredResources.layoutRootFiltered(dir, project, compact)
                && resourcesOutOfSync(ModuleLayout.mainResourcesDir(dir, compact), classesDir, held)) {
            return true;
        }
        for (Path extra : FilteredResources.extraMainDirs(dir, project)) {
            if (resourcesOutOfSync(extra, classesDir, held)) return true;
        }
        if (FilteredResources.mainOutOfSync(dir, project, classesDir, held)) return true;
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
            // Any mtime difference, older included: a restored manifest carries an older one.
            return !Files.getLastModifiedTime(src).equals(Files.getLastModifiedTime(copy))
                    && Files.mismatch(src, copy) >= 0;
        } catch (IOException e) {
            return true;
        }
    }

    static boolean resourcesOutOfSync(Path resDir, Path outDir) {
        return resourcesOutOfSync(resDir, outDir, Set.of());
    }

    /** As {@link #resourcesOutOfSync(Path, Path)}, passing over the tree-relative paths in {@code skip}. */
    static boolean resourcesOutOfSync(Path resDir, Path outDir, Set<String> skip) {
        Map<String, String> projected = ProjectedFiles.at(resDir);
        if (projected != null) return projectedOutOfSync(projected, outDir);
        // Two readAttributes per resource, not six metadata ops. The source's come from the walk for
        // free; the copy's answer presence, size and mtime together — where isRegularFile + size +
        // size + mtime + mtime each re-resolved a path.
        boolean[] dirty = {false};
        try {
            PathUtil.forEachRegularFile(resDir, (source, attrs) -> {
                if (dirty[0]
                        || skip.contains(resDir.relativize(source).toString().replace('\\', '/'))) return;
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
                // Any mtime difference, older included, is settled by content. Through the hash
                // memo, so a copy whose mtime never matches its source's is read once, not per check.
                if (!attrs.lastModifiedTime().equals(target.get().lastModifiedTime())
                        && !FileHashMemo.contentHash(source.toAbsolutePath().normalize(), attrs)
                                .equals(FileHashMemo.contentHash(
                                        copy.toAbsolutePath().normalize(), target.get()))) {
                    dirty[0] = true;
                }
            });
        } catch (IOException e) {
            return true; // unreadable ⇒ treat as dirty
        }
        return dirty[0];
    }

    /** Whether a file the build lays into a projected root differs from its copy under {@code outDir}. */
    private static boolean projectedOutOfSync(Map<String, String> projected, Path outDir) {
        try {
            for (Map.Entry<String, String> file : projected.entrySet()) {
                Path copy = outDir.resolve(file.getKey()).toAbsolutePath().normalize();
                if (!Files.isRegularFile(copy)
                        || !FileHashMemo.contentHash(copy).equals(file.getValue())) return true;
            }
        } catch (IOException e) {
            return true; // unreadable ⇒ treat as dirty
        }
        return false;
    }
}
