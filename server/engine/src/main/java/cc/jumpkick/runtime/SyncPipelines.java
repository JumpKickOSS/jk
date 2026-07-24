// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.CacheSync;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepKind;
import cc.jumpkick.run.StepNames;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * {@code jk sync} pipeline: align the local JDK and dependency CAS with {@code jk.lock}. Engine
 * builds pass {@code allowJdkInstall=false} so JDK downloads stay client-side; {@code coordLabel}
 * is null in the engine (plain {@code name:version}, no themed text on the wire).
 */
public final class SyncPipelines {

    private SyncPipelines() {}

    /** Cross-step pipeline keys. */
    public static final PipelineKey<Lockfile> LOCKFILE = PipelineKey.of("lockfile", Lockfile.class);

    public static final PipelineKey<JkBuild> BUILD = PipelineKey.of("build", JkBuild.class);
    public static final PipelineKey<JdkEnsure.Outcome> JDK_OUTCOME =
            PipelineKey.of("jdk-outcome", JdkEnsure.Outcome.class);
    public static final PipelineKey<CacheSync.Report> CAS_REPORT = PipelineKey.of("cas-report", CacheSync.Report.class);
    public static final PipelineKey<JkPluginSync.Result> WORKER_REPORT =
            PipelineKey.of("worker-report", JkPluginSync.Result.class);
    public static final PipelineKey<Integer> WORKSPACE_MODULES = PipelineKey.of("workspace-modules", Integer.class);
    public static final PipelineKey<Boolean> LOCKFILE_CREATED = PipelineKey.of("lockfile-created", Boolean.class);

    /**
     * Sync pipeline for {@code dir}. {@code refresh} comes from ambient {@link SessionContext}.
     * {@code allowJdkInstall} is true only for in-process paths.
     */
    public static Pipeline syncPipeline(
            Path dir,
            Path cache,
            Path jdksDir,
            URI repoUrl,
            boolean sources,
            AtomicInteger totalFetched,
            AtomicInteger totalUpToDate,
            BiFunction<String, String, String> coordLabel,
            boolean allowJdkInstall) {
        Path lockFile = dir.resolve("jk.lock");
        BiFunction<String, String, String> label = coordLabel != null ? coordLabel : (n, v) -> n + ":" + v;

        // Pre-scan: count artifacts across root + all workspace module lockfiles so
        // sync-cas has an accurate denominator from the first bar frame. Falls back
        // to 0 (dynamic ticks) when jk.lock doesn't exist yet.
        int preScannedTotal = 0;
        if (Files.isRegularFile(lockFile)) {
            try {
                Lockfile rootLock = LockfileReader.read(lockFile);
                preScannedTotal += CacheSync.countArtifacts(rootLock);
                JkBuild rootBuild = parseBuildIfPresent(dir);
                if (rootBuild != null && rootBuild.isWorkspaceRoot()) {
                    try {
                        Map<Path, JkBuild> mods = WorkspaceLoader.loadModules(dir, rootBuild);
                        for (Path modDir : mods.keySet()) {
                            Path modLock = modDir.resolve("jk.lock");
                            if (Files.isRegularFile(modLock)) {
                                preScannedTotal += CacheSync.countArtifacts(LockfileReader.read(modLock));
                            }
                        }
                    } catch (Exception ignored) {
                        /* best-effort */
                    }
                }
            } catch (Exception ignored) {
                /* lock unreadable — fall through to dynamic ticks */
            }
        }
        final int preScanDenominator = preScannedTotal;

        Step parseLock = Step.builder(StepNames.PARSE_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk.lock");
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
                                cc.jumpkick.model.JkVersion.VERSION,
                                List.of(),
                                true,
                                cc.jumpkick.resolver.ResolveObserver.NOOP,
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

        Step ensureJdk = Step.builder(StepNames.ENSURE_JDK)
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_LOCK)
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

        Step syncCas = Step.builder(StepNames.SYNC_CAS)
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_LOCK)
                .ticks(preScanDenominator) // pre-scanned; 0 → updateTicks() fallback
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    int packages = CacheSync.countArtifacts(lock);
                    if (preScanDenominator == 0 && packages > 0) ctx.updateTicks(packages);
                    ctx.label("fetch deps");

                    Cas cas = new Cas(cache);
                    Http http = new Http();
                    JkBuild build = ctx.get(BUILD).orElse(null);
                    boolean mirrorToM2 = build != null && build.project().m2install();
                    var observer = new CacheSync.ProgressObserver() {
                        @Override
                        public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched " + label.apply(pkg.name(), pkg.version()));
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
                            ctx.error("dep", label.apply(pkg.name(), pkg.version()) + " — " + error);
                            ctx.progress(1);
                        }
                    };
                    boolean refresh = SessionContext.current().config().forceOr(false);
                    var report = new CacheSync(cas, http, mirrorToM2).sync(lock, observer, refresh);
                    ctx.put(CAS_REPORT, report);
                    if (report.hasErrors()) {
                        throw new RuntimeException("dep fetch had errors");
                    }
                })
                .build();

        // jk's own plugin jars (test-runner, kotlin-compiler) — pulled from the
        // local Maven repo into the CAS so `jk test` / Kotlin builds find them by
        // SHA. Best-effort: absent plugins warn but don't fail the sync.
        Step syncWorkers = Step.builder(StepNames.SYNC_WORKERS)
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("sync jk workers");
                    Cas cas = new Cas(cache);
                    try {
                        var report = JkPluginSync.ensureInCas(cas, new JkPluginSync.Observer() {
                            @Override
                            public void fetched(String artifact) {
                                ctx.label("fetched " + artifact);
                            }

                            @Override
                            public void missing(String artifact, String detail) {
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

        Step writeManifest = Step.builder(StepNames.WRITE_SYNC_MANIFEST)
                .requires(StepNames.SYNC_CAS)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("stamp reachability manifest");
                    try {
                        Lockfile lock = ctx.require(LOCKFILE);
                        cc.jumpkick.task.SyncManifest.write(cache.resolve("actions"), lockFile, lock);
                    } catch (IOException e) {
                        ctx.warn("manifest", "could not stamp reachability manifest: " + e.getMessage());
                    }
                    ctx.progress(1);
                })
                .build();

        // Sync declared third-party plugin jars from Maven to CAS.
        Step syncPlugins = Step.builder(StepNames.SYNC_PLUGINS)
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_LOCK)
                .ticks(0)
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    var pluginEntries = lock.plugins();
                    if (pluginEntries.isEmpty()) return;
                    ctx.updateTicks(pluginEntries.size());
                    ctx.label("sync plugins");
                    Cas cas = new Cas(cache);
                    JkBuild build = ctx.get(BUILD).orElse(null);
                    cc.jumpkick.repo.RepoGroup repos = build != null
                            ? RepoGroupBuilder.buildFor(build, repoUrl, cas)
                            : RepoGroupBuilder.buildFor(JkBuildParser.parse(dir.resolve("jk.toml")), repoUrl, cas);
                    for (var pe : pluginEntries) {
                        ctx.label("sync " + pe.coordinate());
                        String hex = pe.sha256Hex();
                        if (cas.contains(hex)) {
                            ctx.progress(1);
                            continue;
                        }
                        if (pe.coordinate().indexOf(':') < 0) {
                            ctx.error("plugin", "malformed coordinate: " + pe.coordinate());
                            ctx.progress(1);
                            continue;
                        }
                        var coord = Coordinate.ofModule(pe.coordinate(), pe.version());
                        try {
                            var r = repos.tryFetchArtifact(coord);
                            if (r.isPresent()) {
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
        Step syncSources = Step.builder(StepNames.SYNC_SOURCES)
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_LOCK)
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
                    Cas cas = new Cas(cache);
                    var observer = new CacheSync.ProgressObserver() {
                        @Override
                        public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched sources " + pkg.name() + ":" + pkg.version());
                            ctx.progress(1);
                        }

                        @Override
                        public void upToDate(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void failed(Lockfile.Artifact pkg, String error) {
                            ctx.warn("sources", pkg.name() + ":" + pkg.version() + " — " + error);
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

        Step syncModules = Step.builder(StepNames.SYNC_MODULES)
                .kind(StepKind.IO)
                .requires(StepNames.WRITE_SYNC_MANIFEST)
                .ticks(0) // grown as modules are discovered
                .execute(ctx -> {
                    JkBuild root;
                    try {
                        root = JkBuildParser.parse(dir.resolve("jk.toml"));
                    } catch (Exception ignored) {
                        return;
                    }
                    if (!root.isWorkspaceRoot()) return;

                    Map<Path, JkBuild> modules;
                    try {
                        modules = WorkspaceLoader.loadModules(dir, root);
                    } catch (Exception e) {
                        ctx.warn("modules", "skipping module sync — " + e.getMessage());
                        return;
                    }
                    if (modules.isEmpty()) return;

                    Cas cas = new Cas(cache);
                    Http http = new Http();
                    boolean refresh = SessionContext.current().config().forceOr(false);

                    for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                        Path moduleDir = entry.getKey();
                        Path moduleLock = moduleDir.resolve("jk.lock");
                        if (!Files.isRegularFile(moduleLock)) continue;
                        boolean moduleMirrorToM2 = entry.getValue().project().m2install();
                        try {
                            Lockfile lock = LockfileReader.read(moduleLock);
                            int modArtifacts = CacheSync.countArtifacts(lock);
                            if (preScanDenominator == 0 && modArtifacts > 0) ctx.updateTicks(modArtifacts);
                            String modLabel = dir.relativize(moduleDir).toString();
                            var observer = new CacheSync.ProgressObserver() {
                                @Override
                                public void fetched(Lockfile.Artifact pkg) {
                                    ctx.label(modLabel + ": fetched " + label.apply(pkg.name(), pkg.version()));
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
                                    ctx.warn(
                                            "dep",
                                            modLabel + ": " + label.apply(pkg.name(), pkg.version()) + " — " + error);
                                    ctx.progress(1);
                                }
                            };
                            new CacheSync(cas, http, moduleMirrorToM2).sync(lock, observer, refresh);
                        } catch (Exception e) {
                            ctx.warn("modules", dir.relativize(moduleDir) + ": " + e.getMessage());
                        }
                    }
                })
                .build();

        return Pipeline.builder("sync")
                .addStep(parseLock)
                .addStep(ensureJdk)
                .addStep(syncCas)
                .addStep(syncSources)
                .addStep(syncPlugins)
                .addStep(syncWorkers)
                .addStep(writeManifest)
                .addStep(syncModules)
                .build();
    }

    /** Parse {@code dir/jk.toml} if it exists and is valid; {@code null} otherwise. */
    public static JkBuild parseBuildIfPresent(Path dir) {
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) return null;
        try {
            return JkBuildParser.parse(buildFile);
        } catch (Exception e) {
            return null;
        }
    }
}
