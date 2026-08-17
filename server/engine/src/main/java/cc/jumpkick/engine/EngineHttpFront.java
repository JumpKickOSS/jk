// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.HttpEvents;
import cc.jumpkick.engine.http.HttpJobSelect;
import cc.jumpkick.engine.http.HttpJobSpec;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobRequest;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildMetrics;
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.runtime.CachePlans;
import cc.jumpkick.runtime.CompilePlans;
import cc.jumpkick.runtime.FormatPlans;
import cc.jumpkick.runtime.ImagePlans;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import cc.jumpkick.runtime.WorkspaceSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Embedded HTTP bind + dashboard/MCP job triggers (FireAndForget JobEnvelope). */
@RequiredArgsConstructor
public final class EngineHttpFront {

    private final @Nullable JkHttpConfig config;
    private final EnginePaths.Paths paths;
    private final String version;
    private final @Nullable HttpEvents events;
    private final BuildJournal journal;
    private final Supplier<Path> metricsFile;
    private final Consumer<String> log;
    private final Supplier<StatusSnapshot> status;
    private final LiveRuns liveRuns;
    private final AtomicInteger peakActiveConnections;
    private final IntSupplier liveConnections;
    private final JobEnvelope jobs;
    private final JobSessions sessions;
    private final SsePublisher sse;
    private final JournalWriter journalWriter;
    private final LongSupplier eventRequestId;
    private final EngineListeners listeners;
    private final LongPredicate cancelJob;
    private final @Nullable ReentrantReadWriteLock cacheGate;

    private volatile @Nullable HttpEngineServer server;
    private volatile @Nullable String error;

    public @Nullable HttpEngineServer server() {
        return server;
    }

    public @Nullable String error() {
        return error;
    }

    public int liveEventStreams() {
        HttpEngineServer h = server;
        return h == null ? 0 : h.liveEventStreams();
    }

    /** Start when {@code [http]} is present; bind failure is advisory only. */
    public void start() {
        if (config == null) return;
        HttpEngineServer candidate = new HttpEngineServer(
                config,
                config.webRootPath(),
                paths.httpToken(),
                paths.log(),
                version,
                status,
                events,
                httpJobs(),
                journal,
                () -> BuildMetrics.load(metricsFile.get()).entries(),
                // Single-flight + 30s TTL: dashboard SSE reconnect + GET /api/cache must not each
                // exclusive-walk multi-GiB stores (SerialGC balloons committed heap ~90 MiB).
                cc.jumpkick.engine.http.CacheSnapshot.memoizing(cc.jumpkick.util.JkDirs.cache()),
                log);
        // Hard-refresh mid-build: history rows carry live requestId/progress/phases; SSE connect
        // delivers one compact run-snapshot per job to the new subscription only.
        candidate.setLiveRunSupport(liveRuns::snapshot, liveRuns::rehydrate);
        // Combined-connection peak observed at every admission point (UDS accept bumps it too) —
        // not only when a status snapshot happens to run.
        candidate.setOnSseAdmitted(() -> peakActiveConnections.accumulateAndGet(liveConnections.getAsInt(), Math::max));
        if (cacheGate != null) candidate.setCacheGate(cacheGate);
        try {
            candidate.start();
            Files.writeString(paths.http(), candidate.url());
            server = candidate;
            log.accept("jk engine: http listening on " + candidate.url());
        } catch (IOException | RuntimeException e) {
            candidate.close();
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.accept("jk engine: http failed to start (" + error + ") — continuing without http");
        }
    }

    /** Hand the Web UI port to a successor immediately (idempotent). */
    public void stopNow() {
        HttpEngineServer h = server;
        if (h != null) h.stopNow();
    }

    public void close() {
        HttpEngineServer h = server;
        if (h != null) h.close();
        server = null;
    }

    private EngineHttpJobs httpJobs() {
        return new EngineHttpJobs() {
            @Override
            public long triggerBuild(String dir) {
                return trigger(HttpJobSpec.of("build", dir));
            }

            @Override
            public long triggerTest(String dir) {
                return trigger(HttpJobSpec.of("test", dir));
            }

            @Override
            public long triggerLock(String dir) {
                return trigger(HttpJobSpec.of("lock", dir));
            }

            @Override
            public long trigger(HttpJobSpec spec) {
                return EngineHttpFront.this.trigger(spec);
            }

            @Override
            public boolean cancel(long requestId) {
                return cancelJob.test(requestId);
            }
        };
    }

    /** {@code POST /api/build} / MCP {@code jk_run}: same JobEnvelope as CLI, FireAndForget. */
    private long trigger(HttpJobSpec spec) {
        Path entryDir = requireProject(spec.dir());
        JkBuild entry = parseEntry(entryDir);
        Set<Path> dirty = HttpJobSelect.dirtyHint(entryDir, entry, spec.modules());
        TestSelection tags = HttpJobSelect.testSelection(spec.includeTags(), spec.excludeTags(), spec.suites());
        return switch (spec.kind()) {
            case "build" -> triggerWorkspace(entryDir, "build", spec.skipTests(), false, dirty, tags);
            case "assemble" -> triggerWorkspace(entryDir, "build", true, false, dirty, tags);
            case "test" -> triggerWorkspace(entryDir, "test", false, true, dirty, tags);
            case "lock" -> triggerPlan(entryDir, "lock", (l, tok, w) -> runLock(entryDir, tok));
            case "update" -> triggerPlan(entryDir, "update", (l, tok, w) -> runUpdate(entryDir, tok));
            case "format" -> triggerPlan(entryDir, "format", (l, tok, w) -> runFormat(entryDir, tok));
            case "compile" ->
                triggerExclusive(entryDir, "compile", (l, tok, w) -> runCompile(entryDir, dirty, tok), true, false);
            case "image" ->
                triggerExclusive(
                        entryDir, "image", (l, tok, w) -> runImage(entryDir, dirty, tok), spec.skipTests(), false);
            case "native" ->
                triggerExclusive(
                        entryDir,
                        "native",
                        (l, tok, w) -> runNative(entryDir, entry, dirty, spec.modules(), tok),
                        spec.skipTests(),
                        false);
            case "clean" ->
                jobs.submitAsync(
                        requestLine("cache-clear-request", entryDir),
                        JobRequest.maintenance(
                                "clean", "jk-engine-http-clean-", (l, tok, w) -> runClean(entryDir, tok)),
                        "");
            default -> throw new IllegalArgumentException("kind not hosted: " + spec.kind());
        };
    }

    private long triggerWorkspace(
            Path entryDir, String kind, boolean skipTests, boolean testOnly, Set<Path> dirty, TestSelection tags) {
        JobRequest req = JobRequest.workspace(
                kind,
                "jk-engine-http-" + kind + "-",
                (l, tok, w) -> runWorkspace(entryDir, skipTests, testOnly, dirty, tags, tok));
        return jobs.submitAsync(
                requestLine("build-request", entryDir),
                req,
                BuildJobFingerprint.ofHttp(kind, entryDir, skipTests, testOnly));
    }

    private long triggerPlan(Path entryDir, String kind, cc.jumpkick.engine.jobs.JobBody body) {
        return jobs.submitAsync(
                requestLine(kind + "-request", entryDir),
                JobRequest.plan(kind, "jk-engine-http-" + kind + "-", body),
                "");
    }

    private long triggerExclusive(
            Path entryDir, String kind, cc.jumpkick.engine.jobs.JobBody body, boolean skipTests, boolean testOnly) {
        return jobs.submitAsync(
                requestLine(kind + "-request", entryDir),
                JobRequest.plan(kind, "jk-engine-http-" + kind + "-", body),
                BuildJobFingerprint.ofHttp(kind, entryDir, skipTests, testOnly));
    }

    private static String requestLine(String type, Path entryDir) {
        return "{\"type\":"
                + cc.jumpkick.plugin.protocol.Jsonl.quote(type)
                + ",\"dir\":"
                + cc.jumpkick.plugin.protocol.Jsonl.quote(entryDir.toString())
                + ",\"trigger\":\"web\"}";
    }

    private static Path requireProject(String dirStr) {
        Path entryDir = cc.jumpkick.util.PathUtil.resolveUserPath(dirStr);
        if (!Files.isRegularFile(entryDir.resolve("jk.toml"))) {
            throw new IllegalArgumentException("no jk.toml in " + entryDir);
        }
        return entryDir;
    }

    private static JkBuild parseEntry(Path entryDir) {
        try {
            return JkBuildParser.parse(entryDir.resolve("jk.toml"));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("cannot parse jk.toml in " + entryDir + ": " + e.getMessage());
        }
    }

    private boolean runWorkspace(
            Path entryDir,
            boolean skipTests,
            boolean testOnly,
            Set<Path> dirtyHint,
            TestSelection tags,
            Session.CancelToken cancelToken) {
        try {
            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            Path cache = cc.jumpkick.util.JkDirs.cache();
            Path jdksDir = cc.jumpkick.util.JkDirs.jdks();
            WorkspaceRequest req = new WorkspaceRequest(
                            entryDir,
                            entryBuild,
                            cache,
                            jdksDir,
                            Runtime.getRuntime().availableProcessors(),
                            null,
                            skipTests,
                            false,
                            0,
                            dirtyHint,
                            false,
                            true)
                    .withTestOnly(testOnly);
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withTestSelection(tags == null ? TestSelection.DEFAULT : tags)
                    .withCancel(cancelToken);
            long rid = eventRequestId.getAsLong();
            if (rid > 0) sessions.progressRoot(rid, entryDir.toString());
            WorkspaceResult result = SessionContext.where(
                    session, () -> BuildService.buildWorkspace(req, listeners.hub(entryDir.toString())));
            journalWriter.accOutcome(rid, result.success(), result.exitCode());
            if (rid > 0) {
                if (result.success()) sessions.tracker(rid).finish();
                sse.emitWorkspaceProgress(rid, null, true);
            }
            if (!result.success()) {
                for (String err : result.errors().stream().limit(5).toList()) {
                    sse.publishRequestError(rid, entryDir.toString(), err);
                }
            }
            return result.success();
        } catch (Exception e) {
            return fail(entryDir, "job", e);
        }
    }

    private boolean runLock(Path entryDir, Session.CancelToken cancelToken) {
        try {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            var scope = LockPlans.lockScope(entryDir);
            Path lockDir = scope.lockDir();
            Session session = Session.defaults()
                    .withWorkingDir(lockDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            BuildPlan plan = LockPlans.lockBuildPlan(
                    lockDir, scope.effective(), cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
            return runLocked(entryDir, lockDir, session, plan);
        } catch (Exception e) {
            return fail(entryDir, "lock", e);
        }
    }

    private boolean runUpdate(Path entryDir, Session.CancelToken cancelToken) {
        try {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            var scope = LockPlans.lockScope(entryDir);
            Path lockDir = scope.lockDir();
            Session session = Session.defaults()
                    .withWorkingDir(lockDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            BuildPlan plan = LockPlans.updateBuildPlan(lockDir, scope.effective(), cache, null, List.of(), true);
            return runLocked(entryDir, lockDir, session, plan);
        } catch (Exception e) {
            return fail(entryDir, "update", e);
        }
    }

    private boolean runLocked(Path entryDir, Path lockDir, Session session, BuildPlan plan) {
        plan.addListener(listeners.hubPlan(lockDir.toString()));
        BuildPlanResult result;
        synchronized (cc.jumpkick.runtime.LockGate.monitorFor(lockDir)) {
            try {
                result = SessionContext.where(session, plan::run);
            } catch (Exception e) {
                return fail(entryDir, "lock", e);
            }
        }
        return finishPlan(entryDir, result);
    }

    private boolean runFormat(Path entryDir, Session.CancelToken cancelToken) {
        try {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            // Same style/hygiene precedence as `jk format` (no CLI flags here): [format] in the
            // entry jk.toml, then the built-in defaults — one verb, one result across entry points.
            cc.jumpkick.config.FormatStyles.Resolved styles = cc.jumpkick.config.FormatStyles.resolve(
                    null, null, null, null, null, null, parseEntry(entryDir).format());
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            BuildPlan plan = SessionContext.where(
                    session,
                    () -> FormatPlans.formatBuildPlan(
                            entryDir,
                            cache,
                            false,
                            styles.java(),
                            styles.kotlin(),
                            styles.optimizeImports(),
                            styles.importOrder(),
                            styles.removeUnusedImports(),
                            null,
                            (p, s, m, i, t) -> {}));
            plan.addListener(listeners.hubPlan(entryDir.toString()));
            return finishPlan(entryDir, SessionContext.where(session, plan::run));
        } catch (Exception e) {
            return fail(entryDir, "format", e);
        }
    }

    private boolean runCompile(Path entryDir, Set<Path> dirty, Session.CancelToken cancelToken) {
        try {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(cc.jumpkick.util.JkDirs.jdks())
                    .withCancel(cancelToken);
            List<Path> dirs = compileDirs(entryDir, dirty);
            return SessionContext.where(session, () -> {
                for (Path dir : dirs) {
                    BuildPlan plan = CompilePlans.compileBuildPlan(dir, cache, null, false);
                    plan.addListener(listeners.hubPlan(dir.toString()));
                    BuildPlanResult result = plan.run();
                    if (!result.success()) return finishPlan(entryDir, result);
                }
                journalWriter.accOutcome(eventRequestId.getAsLong(), true, 0);
                return true;
            });
        } catch (Exception e) {
            return fail(entryDir, "compile", e);
        }
    }

    private static List<Path> compileDirs(Path entryDir, Set<Path> dirty) {
        if (dirty != null) return List.copyOf(dirty);
        return List.of(entryDir);
    }

    private boolean runImage(Path entryDir, Set<Path> dirty, Session.CancelToken cancelToken) {
        try {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            Path jdksDir = cc.jumpkick.util.JkDirs.jdks();
            Path target = dirty == null || dirty.isEmpty()
                    ? entryDir
                    : dirty.iterator().next();
            Session session = Session.defaults()
                    .withWorkingDir(target)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken);
            BuildPlan plan = SessionContext.where(
                    session,
                    () -> ImagePlans.imageBuildPlan(target, cache, jdksDir, true, false, null, null, null, null, null));
            plan.addListener(listeners.hubPlan(target.toString()));
            return finishPlan(entryDir, SessionContext.where(session, plan::run));
        } catch (Exception e) {
            return fail(entryDir, "image", e);
        }
    }

    private boolean runNative(
            Path entryDir, JkBuild entry, Set<Path> dirty, List<String> modules, Session.CancelToken cancelToken) {
        try {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            Path jdksDir = cc.jumpkick.util.JkDirs.jdks();
            Path graal = graalHome();
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken);
            return SessionContext.where(session, () -> {
                if (graal == null) {
                    // Without a Graal home the old path built empty target sets and reported a
                    // plain package build as native success — fail loudly instead.
                    throw new IllegalStateException(
                            "native job: no GraalVM home available (install one with `jk jdk graal`)");
                }
                // Eligibility over the FULL workspace, never the dirty-filtered subset: a dirty
                // non-native module must not flip the no-table fallback on (JK-2087).
                Map<Path, JkBuild> allModules = nativeScopes(entryDir, entry);
                Set<Path> selected = HttpJobSelect.selected(entryDir, entry, modules);
                Set<Path> nativeTargets = nativeEligibleTargets(allModules, selected);
                Map<Path, Path> graalByDir = new LinkedHashMap<>();
                for (Path d : nativeTargets) graalByDir.put(d, graal);
                if (nativeTargets.isEmpty()) {
                    // Never "image everything" as a fallback — an unrequested multi-minute
                    // native-image of unrelated modules is worse than a clear failure.
                    throw new IllegalStateException("native job: no native-eligible module in the selection "
                            + "(needs a [native] table or a unique main class)");
                }
                // Terminal targets must schedule even when the client's dirty hint missed them —
                // the binary is the job's deliverable (same principle as the forecast side,
                // JK-2084/JK-2088).
                Set<Path> hint = dirty == null ? null : new LinkedHashSet<>(dirty);
                if (hint != null) {
                    for (Path d : nativeTargets) hint.add(BuildGraph.canonicalPath(d));
                }
                WorkspaceRequest req = new WorkspaceRequest(
                                entryDir, entry, cache, jdksDir, 0, null, true, false, 0, hint, false, true)
                        .withSpec(WorkspaceSpec.nativeImage(nativeTargets, graalByDir, null, List.of()));
                WorkspaceResult wr = BuildService.buildWorkspace(req, listeners.hub(entryDir.toString()));
                journalWriter.accOutcome(eventRequestId.getAsLong(), wr.success(), wr.exitCode());
                return wr.success();
            });
        } catch (Exception e) {
            return fail(entryDir, "native", e);
        }
    }

    private static Map<Path, JkBuild> nativeScopes(Path entryDir, JkBuild entry) throws IOException {
        if (!entry.isWorkspaceRoot()) {
            return Map.of(entryDir, entry);
        }
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(entryDir, entry);
        Map<Path, JkBuild> ordered = new LinkedHashMap<>();
        for (Path dir : BuildGraph.orderModules(modules)) ordered.put(dir, modules.get(dir));
        return ordered;
    }

    /**
     * Selection ∩ native eligibility, mirroring {@code NativeCommand.graalHomesForModules}:
     * modules with a {@code [native]} table are preferred; when NO module declares one, modules
     * with a unique main are eligible. Never falls back to "every module" — an empty result is
     * the caller's cue to fail the job (JK-2087).
     */
    static Set<Path> nativeEligibleTargets(Map<Path, JkBuild> allModules, Set<Path> selected) {
        boolean anyNativeTable = false;
        for (JkBuild b : allModules.values()) {
            if (b.nativeImage()) {
                anyNativeTable = true;
                break;
            }
        }
        Set<Path> targets = new LinkedHashSet<>();
        for (var e : allModules.entrySet()) {
            Path dir = e.getKey();
            boolean inSelection = selected == null || selected.contains(BuildGraph.canonicalPath(dir));
            if (!inSelection) continue;
            // enabled = false is an explicit opt-out — never re-enters via the fallback (JK-2089).
            if (e.getValue().nativeExplicitlyDisabled()) continue;
            boolean eligible = e.getValue().nativeImage();
            if (!eligible && !anyNativeTable) {
                eligible = cc.jumpkick.layout.NativePreflight.resolveMain(dir, null)
                        instanceof cc.jumpkick.layout.NativePreflight.Main.Unique;
            }
            if (eligible) targets.add(dir);
        }
        return targets;
    }

    private boolean runClean(Path entryDir, Session.CancelToken cancelToken) {
        try {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
            BuildPlan plan = CachePlans.clearBuildPlan(cache, entryDir, false);
            plan.addListener(listeners.hubPlan(entryDir.toString()));
            return finishPlan(entryDir, SessionContext.where(session, plan::run));
        } catch (Exception e) {
            return fail(entryDir, "clean", e);
        }
    }

    private static Path graalHome() {
        String g = System.getenv("GRAALVM_HOME");
        if (g == null || g.isBlank()) return null;
        Path p = Path.of(g);
        return Files.isDirectory(p) ? p : null;
    }

    private boolean finishPlan(Path entryDir, BuildPlanResult result) {
        journalWriter.accOutcome(eventRequestId.getAsLong(), result.success(), result.success() ? 0 : 1);
        if (!result.success()) {
            for (var d : result.errors().stream().limit(5).toList()) {
                sse.publishRequestError(eventRequestId.getAsLong(), entryDir.toString(), d.message());
            }
        }
        return result.success();
    }

    private boolean fail(Path entryDir, String kind, Exception e) {
        journalWriter.accOutcome(eventRequestId.getAsLong(), false, 1);
        log.accept("jk engine: http-triggered " + kind + " of " + entryDir + " failed: "
                + EventRedaction.redactEnv(entryDir.toString(), String.valueOf(e.getMessage())));
        sse.publishRequestError(eventRequestId.getAsLong(), entryDir.toString(), String.valueOf(e.getMessage()));
        return false;
    }
}
