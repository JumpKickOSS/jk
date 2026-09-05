// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.CacheSync;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.SyncManifest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk sync} plan: align the local JDK and dependency CAS with {@code jk-lock.toml}. Engine
 * builds pass {@code allowJdkInstall=false} so JDK downloads stay client-side; {@code coordLabel}
 * is null in the engine (plain {@code name:version}, no themed text on the wire).
 */
public final class SyncPlans {

    private SyncPlans() {}

    /** Cross-step plan keys. */
    public static final BuildPlanKey<Lockfile> LOCKFILE = BuildPlanKey.scalar("lockfile", Lockfile.class);

    public static final BuildPlanKey<JkBuild> BUILD = BuildPlanKey.scalar("build", JkBuild.class);
    public static final BuildPlanKey<JdkEnsure.Outcome> JDK_OUTCOME =
            BuildPlanKey.scalar("jdk-outcome", JdkEnsure.Outcome.class);
    public static final BuildPlanKey<CacheSync.Report> CAS_REPORT =
            BuildPlanKey.scalar("cas-report", CacheSync.Report.class);
    public static final BuildPlanKey<JkPluginSync.Result> WORKER_REPORT =
            BuildPlanKey.scalar("worker-report", JkPluginSync.Result.class);
    public static final BuildPlanKey<Integer> WORKSPACE_MODULES =
            BuildPlanKey.scalar("workspace-modules", Integer.class);
    public static final BuildPlanKey<Boolean> LOCKFILE_CREATED = BuildPlanKey.scalar("lockfile-created", Boolean.class);

    /**
     * Sync plan for {@code dir}. {@code refresh} comes from ambient {@link SessionContext}.
     * {@code allowJdkInstall} is true only for in-process paths.
     */
    public static BuildPlan syncBuildPlan(
            Path dir,
            Path cache,
            @Nullable Path jdksDir,
            @Nullable URI repoUrl,
            boolean sources,
            AtomicInteger totalFetched,
            AtomicInteger totalUpToDate,
            @Nullable BiFunction<String, String, String> coordLabel,
            boolean allowJdkInstall) {
        Path lockFile = LockPaths.lockFile(dir);
        // Plain engine path uses Artifact.displayCoord() (g:a:v, or g:a:v!aar / :classifier when
        // non-default). An explicit coordLabel overrides for themed in-process clients.
        BiFunction<String, String, String> label = coordLabel;

        // Pre-scan: count artifacts in the canonical lock (workspace root or standalone) so
        // sync-cas has an accurate denominator from the first bar frame. Falls back to 0
        // (dynamic ticks) when jk-lock.toml doesn't exist yet.
        int preScannedTotal = 0;
        if (Files.isRegularFile(lockFile)) {
            try {
                preScannedTotal += CacheSync.countArtifacts(LockfileReader.read(lockFile));
            } catch (Exception ignored) {
                /* lock unreadable — fall through to dynamic ticks */
            }
        }
        final int preScanDenominator = preScannedTotal;

        Task parseLock = Task.builder(TaskNames.PARSE_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk-lock.toml");
                    if (!Files.exists(lockFile)) {
                        ctx.label("resolve deps");
                        var result = LockFlow.run(dir, cache, List.of(), false, repoUrl);
                        if (result.workspaceModuleCount() > 0) {
                            ctx.put(WORKSPACE_MODULES, result.workspaceModuleCount());
                        }
                        if (result.status() != 0) {
                            ctx.error(
                                    "lock",
                                    result.error() != null
                                            ? result.error()
                                            : "lockfile resolution failed (exit " + result.status() + ")");
                            throw new RuntimeException("lock-flow failed");
                        }
                        ctx.put(LOCKFILE, result.lockfile());
                        if (result.build() != null) ctx.put(BUILD, result.build());
                        ctx.put(LOCKFILE_CREATED, true);
                    } else if (AutoLock.isStale(dir, lockFile)) {
                        ctx.label("jk.toml changed — updating lock");
                        Lockfile existing = LockfileReader.read(lockFile);
                        Lockfile updated = AutoLock.maybeReLock(
                                dir,
                                existing,
                                lockFile,
                                cache,
                                repoUrl,
                                List.of(),
                                true,
                                ResolveObserver.NOOP,
                                ctx::output);
                        ctx.put(LOCKFILE, updated != null ? updated : existing);
                        var build = parseBuildIfPresent(dir);
                        if (build != null) ctx.put(BUILD, build);
                    } else {
                        ctx.put(LOCKFILE, LockfileReader.read(lockFile));
                        var build = parseBuildIfPresent(dir);
                        if (build != null) ctx.put(BUILD, build);
                    }
                    ctx.progress(1);
                })
                .build();

        Task ensureJdk = Task.builder(TaskNames.ENSURE_JDK)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve JDK");
                    Lockfile lock = ctx.require(LOCKFILE);
                    JkBuild build = ctx.get(BUILD).orElse(null);
                    try {
                        var outcome =
                                JdkEnsure.ensure(dir, jdksDir, build, lock, m -> ctx.warn("jdk", m), allowJdkInstall);
                        ctx.put(JDK_OUTCOME, outcome);
                    } catch (Exception e) {
                        ctx.error("jdk", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Task syncCas = Task.builder(TaskNames.SYNC_CAS)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_LOCK)
                .ticks(preScanDenominator) // pre-scanned; 0 → updateTicks() fallback
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    int packages = CacheSync.countArtifacts(lock);
                    if (preScanDenominator == 0 && packages > 0) ctx.updateTicks(packages);
                    ctx.label("fetch deps");

                    Cas cas = JkStores.storeCas();
                    Http http = new Http();
                    JkBuild build = ctx.get(BUILD).orElse(null);
                    boolean mirrorToM2 = build != null && build.project().m2integration();
                    var observer = new CacheSync.ProgressObserver() {
                        @Override
                        public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched " + formatCoord(label, pkg));
                            totalFetched.incrementAndGet();
                            ctx.progress(1);
                        }

                        @Override
                        public void upToDate(Lockfile.Artifact pkg) {
                            totalUpToDate.incrementAndGet();
                            ctx.progress(1);
                        }

                        @Override
                        public void skipped(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void failed(Lockfile.Artifact pkg, String error) {
                            ctx.error("dep", formatCoord(label, pkg) + " — " + error);
                            ctx.progress(1);
                        }
                    };
                    boolean refresh = SessionContext.current().config().forceOr(false);
                    var report = new CacheSync(cas, http, mirrorToM2).sync(lock, observer, refresh);
                    ctx.put(CAS_REPORT, report);
                    if (report.hasErrors()) {
                        throw new RuntimeException("dep fetch had errors");
                    }
                    materializeNativeMetadata(ctx, lock, build, cache, repoUrl, dir, cas);
                })
                .build();

        // jk's own plugin jars (test-runner, kotlin-compiler) — pulled from the
        // local Maven repo into the CAS so `jk test` / Kotlin builds find them by
        // SHA. Best-effort: absent plugins warn but don't fail the sync.
        Task syncWorkers = Task.builder(TaskNames.SYNC_WORKERS)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("sync jk workers");
                    Cas cas = JkStores.storeCas();
                    try {
                        var report = JkPluginSync.ensureInCas(cas, new JkPluginSync.Observer() {
                            @Override
                            public void fetched(String artifact) {
                                ctx.label("fetched " + artifact);
                            }

                            @Override
                            public void missing(String artifact, @Nullable String detail) {
                                // Empty code → diagnostic render omits [step/code] brackets.
                                ctx.warn("", artifact + " " + detail + ".");
                            }
                        });
                        ctx.put(WORKER_REPORT, report);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted syncing jk workers", e);
                    }
                    ctx.progress(1);
                })
                .build();

        Task writeManifest = Task.builder(TaskNames.WRITE_SYNC_MANIFEST)
                .requires(TaskNames.SYNC_CAS)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("stamp reachability manifest");
                    try {
                        Lockfile lock = ctx.require(LOCKFILE);
                        SyncManifest.write(CacheTree.ACTIONS.under(cache), lockFile, lock);
                    } catch (IOException e) {
                        ctx.warn("manifest", "could not stamp reachability manifest: " + e.getMessage());
                    }
                    ctx.progress(1);
                })
                .build();

        // Sync declared third-party plugin jars from Maven to CAS.
        Task syncPlugins = Task.builder(TaskNames.SYNC_PLUGINS)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_LOCK)
                .ticks(0)
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    var pluginEntries = lock.plugins();
                    if (pluginEntries.isEmpty()) return;
                    ctx.updateTicks(pluginEntries.size());
                    ctx.label("sync plugins");
                    Cas cas = JkStores.storeCas();
                    JkBuild build = ctx.get(BUILD).orElse(null);
                    RepoGroup repos = build != null
                            ? RepoGroupBuilder.buildFor(build, repoUrl, cas)
                            : RepoGroupBuilder.buildFor(
                                    JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST)), repoUrl, cas);
                    for (var pe : pluginEntries) {
                        ctx.label("sync " + pe.coordinate());
                        String hex = pe.sha256Hex();
                        if (pe.coordinate().indexOf(':') < 0) {
                            ctx.error("plugin", "malformed coordinate: " + pe.coordinate());
                            ctx.progress(1);
                            continue;
                        }
                        var coord = Coordinate.ofModule(pe.coordinate(), pe.version());
                        if (cas.contains(hex)) {
                            // The jar blob alone isn't enough: worker launch needs the sibling
                            // POM (PomRuntimeClasspath), and a store warmed via the CAS-blob
                            // fallback has jar-without-POM forever unless sync repairs it. Warm
                            // mirrors make this a cheap local probe.
                            ensureSiblingPom(ctx, repos, coord);
                            ctx.progress(1);
                            continue;
                        }
                        try {
                            var r = repos.tryFetchArtifact(coord, hex);
                            if (r.isPresent()) {
                                // Pin is law: these bytes become worker code under the pinned hash,
                                // and the memo fast-path trusts the hash without re-hashing — so a
                                // mismatch must never reach the CAS. The pinned fetch already
                                // skipped stale local copies, so a mismatch here means the remote
                                // itself serves different bytes than the lock pins.
                                String got = r.get().fetched().sha256();
                                if (got == null || !got.equalsIgnoreCase(hex)) {
                                    ctx.error(
                                            "plugin",
                                            pe.coordinate() + ":" + pe.version()
                                                    + " — repository serves different bytes than the lock pins"
                                                    + " (sha256 " + shortSha(got) + " vs locked " + shortSha(hex)
                                                    + "); if the upstream republished, re-lock with"
                                                    + " `jk lock --force`");
                                    ctx.progress(1);
                                    continue;
                                }
                                cas.putFile(r.get().fetched().cachePath(), hex);
                                // Worker classpath needs the sibling POM next to the jar.
                                ensureSiblingPom(ctx, repos, coord);
                                ctx.label("fetched " + pe.coordinate() + ":" + pe.version());
                            } else {
                                ctx.error("plugin", pe.coordinate() + " not found in any repo");
                            }
                        } catch (Exception e) {
                            ctx.error("plugin", pe.coordinate() + " — " + e.getMessage());
                        }
                        ctx.progress(1);
                    }
                    // Extract the fetched jars' manifests so the very next parse validates
                    // the plugins' tables (and applies their contributions).
                    PluginDescriptorOps.ensureMaterialized(dir, cache);
                })
                .build();

        // Sync sources JARs for packages that have sourcesChecksum pinned in lock.
        Task syncSources = Task.builder(TaskNames.SYNC_SOURCES)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_LOCK)
                .ticks(0)
                .execute(ctx -> {
                    if (!sources) return; // opt-in only
                    Lockfile lock = ctx.require(LOCKFILE);
                    long withSrc = lock.artifacts().stream()
                            .filter(p -> p.sourcesChecksum() != null)
                            .count();
                    if (withSrc == 0) return;
                    ctx.updateTicks((int) withSrc);
                    ctx.label("sync sources");
                    Cas cas = JkStores.storeCas();
                    var observer = new CacheSync.ProgressObserver() {
                        @Override
                        public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched sources " + pkg.displayCoord());
                            ctx.progress(1);
                        }

                        @Override
                        public void upToDate(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void failed(Lockfile.Artifact pkg, String error) {
                            ctx.warn("sources", pkg.displayCoord() + " — " + error);
                            ctx.progress(1);
                        }
                    };
                    try {
                        new CacheSync(cas, new Http()).syncSources(lock, observer);
                    } catch (Exception e) {
                        ctx.warn("sources", "sources sync failed: " + e.getMessage());
                    }
                })
                .build();

        // Workspace modules no longer own lockfiles — the root jk-lock.toml is the only pin set
        // (synced above via SYNC_CAS). SYNC_MODULES remains a no-op step for wire/plan stability.
        Task syncModules = Task.builder(TaskNames.SYNC_MODULES)
                .kind(TaskKind.IO)
                .requires(TaskNames.WRITE_SYNC_MANIFEST)
                .ticks(0)
                .execute(ctx -> {
                    /* intentionally empty — single workspace lock covers all modules */
                })
                .build();

        return BuildPlan.builder("sync")
                .stateKeys(LOCKFILE, BUILD, JDK_OUTCOME, CAS_REPORT, WORKER_REPORT, WORKSPACE_MODULES, LOCKFILE_CREATED)
                .addTask(parseLock)
                .addTask(ensureJdk)
                .addTask(syncCas)
                .addTask(syncSources)
                .addTask(syncPlugins)
                .addTask(syncWorkers)
                .addTask(writeManifest)
                .addTask(syncModules)
                .build();
    }

    /** Progress/diagnostic coordinate: themed label when provided, else {@link Lockfile.Artifact#displayCoord()}. */
    private static String formatCoord(@Nullable BiFunction<String, String, String> coordLabel, Lockfile.Artifact pkg) {
        return coordLabel != null ? coordLabel.apply(pkg.displayIdentity(), pkg.version()) : pkg.displayCoord();
    }

    /**
     * Fetch the plugin's sibling POM into the mirror (warm hit = local probe only) and say so when
     * it can't be had — a silent POM 404 used to surface only at worker launch as "has no Maven
     * POM; run `jk install`".
     */
    /**
     * Unpack the lock's {@code [native]} reachability-metadata pin into the artifact store.
     *
     * <p>{@code jk sync}'s promise is that a materialized store plus a lock is enough to build with
     * the network off, and the pin is a locked download like any other — leaving it out would make
     * an offline {@code jk native} silently drop every third-party reflection config the image
     * needs. It rides {@code sync-cas} rather than a step of its own because the extracted tree,
     * not a CAS blob, is what a build reads: extraction is the materialization.
     *
     * <p>{@code build} and {@code repoUrl} may be null — the manifest is re-read and the default
     * repositories used, exactly as {@code sync-plugins} does.
     *
     * <p>Best-effort. A native build already degrades without the repository, so a sync that could
     * not reach it warns rather than failing the whole materialization.
     */
    private static void materializeNativeMetadata(
            TaskContext ctx,
            Lockfile lock,
            @Nullable JkBuild build,
            Path cache,
            @Nullable URI repoUrl,
            Path dir,
            Cas cas) {
        Lockfile.NativeMetadata pin = lock.nativeMetadata();
        if (pin == null) return;
        ctx.label("sync reachability metadata " + pin.version());
        try {
            JkBuild project = build != null ? build : JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
            ReachabilityMetadata.ensureExtracted(
                    JkStores.store(), RepoGroupBuilder.buildFor(project, repoUrl, cas), pin);
        } catch (IOException | RuntimeException e) {
            ctx.warn("native", "reachability metadata not materialized: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void ensureSiblingPom(TaskContext ctx, RepoGroup repos, Coordinate coord) {
        try {
            var pom = repos.tryFetchArtifact(
                    new Coordinate(coord.group(), coord.artifact(), coord.version(), null, "pom"));
            if (pom.isEmpty()) {
                ctx.warn("plugin", coord.toGav() + ": no POM in any repo — worker launch will refuse this plugin");
            }
        } catch (Exception e) {
            ctx.warn("plugin", coord.toGav() + ": POM fetch failed — " + e.getMessage());
        }
    }

    private static String shortSha(String hex) {
        return hex == null || hex.length() <= 12 ? String.valueOf(hex) : hex.substring(0, 12);
    }

    /** Parse {@code dir/jk.toml} if it exists and is valid; {@code null} otherwise. */
    public static @Nullable JkBuild parseBuildIfPresent(Path dir) {
        Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(buildFile)) return null;
        try {
            return JkBuildParser.parse(buildFile);
        } catch (Exception e) {
            return null;
        }
    }
}
