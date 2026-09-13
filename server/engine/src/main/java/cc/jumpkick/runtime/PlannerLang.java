// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerSupport.lockModules;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.compile.KotlincSnapshots;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.GroovyPluginSetup;
import cc.jumpkick.runtime.base.KotlinPluginSetup;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.KotlinClasspathAbi;
import cc.jumpkick.task.LangCompile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Shared Kotlin / Groovy compiler invocation used by main and test compile steps.
 */
public final class PlannerLang {

    private PlannerLang() {}

    /**
     * One Kotlin compiler plugin the module compiles with, by coordinate. The jar is fetched when
     * the compile actually runs; naming the coordinate is enough to decide whether it has to.
     */
    record KotlinPluginUse(String id, String group, String artifact, String version, List<String> options) {
        KotlinPluginUse {
            options = List.copyOf(options);
        }
    }

    /**
     * The option-bearing inputs of a module's kotlinc invocation, resolved from the manifest and
     * the installed plugins' contributions without fetching anything: the free args, the compiler
     * plugins by coordinate, the JVM target and the module name. {@link #compileKotlinSources}
     * builds its request from exactly this, so what is resolved here is what the compiler sees.
     */
    record KotlinConfig(
            @Nullable String kotlinVersion,
            List<String> args,
            List<KotlinPluginUse> plugins,
            int jvmTarget,
            String moduleName,
            Path javaHome,
            /** The mixed module's Java roots {@code -Xjava-source-roots} in {@code args} names; empty otherwise. */
            List<Path> javaSourceRoots) {
        KotlinConfig {
            args = List.copyOf(args);
            plugins = List.copyOf(plugins);
            javaSourceRoots = List.copyOf(javaSourceRoots);
        }

        /**
         * Digest of every option-bearing input for the freshness stamp: the compiler version, the
         * JVM target, the module name, the JDK's identity, the args and the plugins with their
         * options. A change here moves no source or classpath mtime; only this does.
         */
        String digest() throws IOException {
            return FreshnessStamp.optionsDigest(digestParts());
        }

        /** The facts {@link #digest} folds, one per part. */
        List<String> digestParts() throws IOException {
            List<String> parts = new ArrayList<>();
            parts.add("kotlin:" + (kotlinVersion == null ? "" : kotlinVersion));
            parts.add("jvmTarget:" + jvmTarget);
            parts.add("moduleName:" + moduleName);
            parts.add("jdk:" + ActionKey.jdkToken(javaHome));
            for (String arg : args) parts.add("arg:" + arg);
            for (KotlinPluginUse p : plugins) {
                parts.add("plugin:" + p.id() + ":" + p.group() + ":" + p.artifact() + ":" + p.version() + ":"
                        + String.join(",", p.options()));
            }
            return parts;
        }
    }

    /**
     * The Kotlin compile's freshness-stamp digest: the {@link KotlinConfig#digest option-bearing
     * facts} plus the {@link KotlinClasspathAbi ABI token} of every compile-classpath entry. The
     * stamp then describes the classpath the way the action key does — by ABI — so a sibling
     * rewritten with the same ABI leaves the digest alone and one whose ABI moved changes it even
     * before any mtime is consulted. Sorted: the stamp does not care about classpath order any
     * more than the key does.
     */
    static String kotlinStampDigest(
            KotlinConfig config, List<Path> classpath, KotlinClasspathAbi.Snapshotter snapshotter) throws IOException {
        List<String> parts = new ArrayList<>(config.digestParts());
        List<String> tokens = new ArrayList<>(KotlinClasspathAbi.tokens(classpath, snapshotter));
        tokens.sort(Comparator.naturalOrder());
        for (String token : tokens) parts.add("cp:" + token);
        return FreshnessStamp.optionsDigest(parts);
    }

    /**
     * The Kotlin worker of one compile — the version-matched Build Tools API closure, the stdlib,
     * the compiler plugins' jars and the request built from them — resolved on first use and then
     * shared. Two callers need it and neither should pay for it before it is needed: the classpath
     * snapshotter behind the freshness stamp and the action key (which forks the worker only when
     * a classpath entry's ABI token is not yet memoized), and the compile itself. A stamp that is
     * fresh with every token memoized never resolves the toolchain at all.
     */
    static final class KotlinWorker {
        private final JkBuild project;
        private final Cas cas;
        private final KotlinConfig config;
        private final List<Path> sources;
        private final List<Path> classpath;
        private final Path outputDir;
        private final @Nullable Path workingDir;
        private final Path snapshotDir;
        private final WorkerEnv env;
        private @Nullable KotlincRequest request;

        private KotlinWorker(
                JkBuild project,
                Path moduleDir,
                Cas cas,
                KotlinConfig config,
                List<Path> sources,
                List<Path> classpath,
                Path outputDir,
                @Nullable Path workingDir,
                Path snapshotDir) {
            this.project = project;
            this.cas = cas;
            this.config = config;
            this.sources = List.copyOf(sources);
            this.classpath = List.copyOf(classpath);
            this.outputDir = outputDir;
            this.workingDir = workingDir;
            this.snapshotDir = snapshotDir;
            this.env = WorkerEnv.forModule(project.build().env(), moduleDir, null);
        }

        WorkerEnv env() {
            return env;
        }

        /** Snapshots classpath entries through this compile's worker; resolves it on first use. */
        KotlinClasspathAbi.Snapshotter snapshotter() {
            return entries -> KotlincSnapshots.snapshot(request(), entries, env);
        }

        /** The compile request, built once: what the worker is told and what the key hashes. */
        synchronized KotlincRequest request() throws IOException {
            KotlincRequest built = request;
            if (built != null) return built;
            KotlinPluginSetup.Prepared kt;
            List<KotlincRequest.Plugin> ktPlugins = new ArrayList<>();
            try {
                RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
                kt = KotlinPluginSetup.prepare(repos, cas, config.kotlinVersion());
                for (KotlinPluginUse use : config.plugins()) {
                    Path jar = repos.tryFetchArtifact(Coordinate.of(use.group(), use.artifact(), use.version()))
                            .map(hit -> hit.fetched().cachePath())
                            .orElseThrow(() -> new RuntimeException("cannot fetch the " + use.id()
                                    + " Kotlin compiler plugin (" + use.group() + ":" + use.artifact() + ":"
                                    + use.version() + ") — the module compiles with it"));
                    ktPlugins.add(new KotlincRequest.Plugin(use.id(), jar, use.options()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted resolving the Kotlin compiler", e);
            }
            // Compilation classpath: project deps + the version-matched stdlib (the
            // in-process plugin has no kotlin-home to auto-supply it; -no-stdlib).
            List<Path> compileCp = new ArrayList<>(classpath);
            compileCp.add(kt.stdlib());
            // Compiler plugins ride the typed BTA COMPILER_PLUGINS argument — raw -Xplugin/-P
            // strings in extraArgs are silently ignored by the BTA execution path.
            List<String> ktArgs = config.args();
            Files.createDirectories(outputDir);
            String moduleName = config.moduleName();
            // The incremental state is only valid for the exact compile CONFIG that produced it:
            // BTA's IC sees "no source changes" after an args/plugins/module-name change and would
            // emit nothing into a clean output dir. Key the working dir by a config hash so any
            // config change starts fresh IC state (stale dirs age out with the cache).
            int jvmTarget = config.jvmTarget();
            String configToken = Hashing.sha256Hex((jvmTarget
                                    + "|" + moduleName + "|" + String.join(",", ktArgs) + "|"
                                    + ktPlugins.stream()
                                            .map(p -> p.id() + "=" + p.options())
                                            .collect(Collectors.joining(",")))
                            .getBytes(StandardCharsets.UTF_8))
                    .substring(0, 12);
            Path icWorkingDir =
                    workingDir == null ? null : workingDir.resolveSibling(workingDir.getFileName() + "-" + configToken);
            built = KotlincRequest.builder()
                    .sources(sources)
                    .javaSourceRoots(config.javaSourceRoots())
                    .classpath(compileCp)
                    .outputDir(outputDir)
                    .jvmTarget(jvmTarget)
                    .workerClasspath(kt.workerClasspath())
                    .javaHome(config.javaHome())
                    .workingDir(icWorkingDir)
                    .snapshotDir(snapshotDir)
                    .extraArgs(ktArgs)
                    .plugins(ktPlugins)
                    // Lockstep with the KSP round's -module-name: internal-member mangling
                    // (member$module_name) is baked into call sites KSP-generated Java emits
                    // (Hilt factories calling internal providers).
                    .moduleName(moduleName)
                    .build();
            request = built;
            return built;
        }
    }

    /**
     * The worker for compiling {@code sources} against {@code classpath} into {@code outputDir};
     * {@code workingDir} (null for a full compile) is the incremental state's home.
     */
    static KotlinWorker kotlinWorker(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            @Nullable Path workingDir,
            KotlinConfig config) {
        return new KotlinWorker(
                ctx.require(PROJECT),
                in.dir(),
                cas,
                config,
                sources,
                classpath,
                outputDir,
                workingDir,
                CacheTree.KOTLIN_CP_SNAPSHOTS.under(in.cache()));
    }

    /** The groovyc free args: the installed plugins' contributions (grails' {@code --parameters}), deduped. */
    static List<String> groovyArgs(JkBuild project, Lockfile lock, Path moduleDir) {
        List<String> args = new ArrayList<>();
        for (String arg : PluginContributions.groovyArgs(project, moduleDir, lockModules(lock))) {
            if (!args.contains(arg)) args.add(arg);
        }
        return args;
    }

    /**
     * Digest of a Groovy compile's option-bearing inputs for its freshness stamp: the Groovy
     * version, the JVM target and the args — the facts {@link #compileGroovySources} builds its
     * request from that no source or classpath mtime reflects.
     */
    static String groovyStampDigest(JkBuild project, Lockfile lock, Path moduleDir, int release, Path javaHome) {
        List<String> parts = new ArrayList<>();
        String groovyVersion = CompileToolchain.groovyVersionFor(lock, project);
        parts.add("groovy:" + (groovyVersion == null ? "" : groovyVersion));
        parts.add("jvmTarget:" + CompileSupport.effectiveRelease(release, JvmOptions.hostFeature(javaHome)));
        for (String arg : groovyArgs(project, lock, moduleDir)) parts.add("arg:" + arg);
        return FreshnessStamp.optionsDigest(parts);
    }

    /** {@link #groovyStampDigest} from the running step's published state. */
    static String groovyStampDigest(TaskContext ctx, Path moduleDir) {
        return groovyStampDigest(
                ctx.require(PROJECT), ctx.require(LOCKFILE), moduleDir, ctx.require(RELEASE), ctx.require(JAVA_HOME));
    }

    /**
     * Resolve {@link KotlinConfig} for the module at {@code moduleDir}. Plugin contributions are
     * looked up against the module dir — that is where the lock that pins the installed plugins
     * and their materialized manifests live, and a lookup keyed anywhere else finds no
     * third-party plugin at all.
     *
     * @param javaSourceRoots mixed module: the Java roots kotlinc reads declarations from, or null
     */
    static KotlinConfig kotlinConfig(
            JkBuild project,
            Lockfile lock,
            Path moduleDir,
            int release,
            Path javaHome,
            @Nullable List<Path> javaSourceRoots) {
        String kotlinVersion = CompileToolchain.kotlinVersionFor(lock, project);
        // A plugin without a version of its own resolves to the compiler's: the only version
        // that can load into this kotlinc anyway. Same null-defaulting as KotlinPluginSetup.
        String pluginVersion =
                (kotlinVersion == null || kotlinVersion.isBlank()) ? KotlinResolver.DEFAULT_VERSION : kotlinVersion;
        Set<String> lockModules = lockModules(lock);
        List<KotlinPluginUse> plugins = new ArrayList<>();
        // The installed plugins' [[contribute.kotlin-plugin]] entries (e.g. spring-boot's
        // all-open, and no-arg gated on jakarta.persistence via classpath-has) — evaluated
        // from the manifest, version-locked to the compiler actually used. The embeddable
        // variants match the BTA plugin's embeddable compiler.
        for (var use : PluginContributions.kotlinPlugins(project, moduleDir, pluginVersion, lockModules)) {
            plugins.add(new KotlinPluginUse(use.id(), use.group(), use.artifact(), use.version(), use.options()));
        }
        // Project-declared [[kotlin-plugins]] (serialization et al.) ride the same lane; an
        // omitted coordinate version means "match the compiler" — the org.jetbrains.kotlin
        // plugin convention.
        for (var decl : project.build().kotlinPlugins()) {
            String[] parts = decl.coordinate().split(":");
            String version = parts.length == 3 ? parts[2] : pluginVersion;
            plugins.add(new KotlinPluginUse(decl.id(), parts[0], parts[1], version, decl.options()));
        }
        List<String> args = new ArrayList<>();
        // The in-process plugin has no kotlin-home to auto-supply the stdlib; it rides the
        // classpath instead.
        args.add("-no-stdlib");
        // Contributed kotlinc args (e.g. spring-boot's -java-parameters, mirroring its javac
        // -parameters — Boot reflects on parameter names), deduped.
        for (String arg : PluginContributions.kotlinArgs(project, moduleDir, lockModules)) {
            if (!args.contains(arg)) args.add(arg);
        }
        if (javaSourceRoots != null && !javaSourceRoots.isEmpty()) {
            StringBuilder roots = new StringBuilder();
            for (Path root : javaSourceRoots) {
                if (roots.length() > 0) roots.append(',');
                roots.append(root.toAbsolutePath());
            }
            args.add("-Xjava-source-roots=" + roots);
        }
        int jvmTarget = CompileSupport.kotlinJvmTarget(release, JvmOptions.hostFeature(javaHome));
        return new KotlinConfig(
                kotlinVersion,
                args,
                plugins,
                jvmTarget,
                project.project().name(),
                javaHome,
                javaSourceRoots == null ? List.of() : javaSourceRoots);
    }

    /** {@link #kotlinConfig} from the running step's published state, for the module being built. */
    static KotlinConfig kotlinConfig(TaskContext ctx, Path moduleDir, @Nullable List<Path> javaSourceRoots) {
        return kotlinConfig(
                ctx.require(PROJECT),
                ctx.require(LOCKFILE),
                moduleDir,
                ctx.require(RELEASE),
                ctx.require(JAVA_HOME),
                javaSourceRoots);
    }

    /**
     * Compile the Kotlin sources of {@code worker} (action-cached: an exact-input hit restores from
     * the CAS without forking; the key looks at the classpath by ABI, so a sibling rewritten with
     * the same ABI hits). Shared by the main {@code compile-kotlin} and {@code compile-test} steps;
     * the caller owns freshness stamps, output assembly and outcome reporting.
     */
    static LangCompile.Result compileKotlinSources(
            TaskContext ctx, BuildPlanner.Inputs in, ActionCache actionCache, String taskId, KotlinWorker worker)
            throws IOException {
        KotlincRequest req = worker.request();
        KotlinClasspathAbi.Snapshotter snapshotter = worker.snapshotter();
        boolean rerun = in.session().config().rebuildOr(false);
        // Reweight from the real request: a CAS hit is a cheap restore (3), else a
        // full kotlinc. Same forKotlinc key LangCompile.run looks up.
        if (!rerun) {
            try {
                boolean restores = actionCache
                        .lookup(ActionKey.forKotlinc(taskId, req, BuildIdentity.cacheKeyVersion(), snapshotter))
                        .isPresent();
                ctx.reweight(
                        restores
                                ? EffortWeights.RESTORE
                                : EffortWeights.compileWeight(req.sources().size()));
            } catch (Exception e) {
                /* keep the up-front estimate */
                Log.debug("compileKotlinSources: keep the up-front estimate", e);
            }
        }
        return LangCompile.run(
                taskId,
                req,
                BuildIdentity.cacheKeyVersion(),
                !rerun,
                !in.ephemeralActions(), // verify-scratch: no persistent residue
                actionCache.cas(),
                actionCache,
                worker.env(),
                snapshotter);
    }

    /**
     * The Groovy worker's request for {@code sources} — the compile-time facts both the action key
     * and the freshness stamp are derived from: project deps plus the version-matched groovy jar
     * on the classpath, the worker's Groovy closure, contributed groovyc args, and in joint mode
     * the Java roots and processor path. Built once per step, before the stamp check, so the stamp
     * compares the very token lines the key hashes. Shared by the main {@code compile-groovy} and
     * {@code compile-test} steps.
     *
     * @param javaSourceRoots when non-empty, joint mode: the worker sweeps {@code .java} under them
     * for resolution only (jk's javac worker owns the real Java outputs)
     * @param stubsOut when non-null, Java-visible stubs are retained there for javac's sourcepath
     */
    static GroovycRequest groovyRequest(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            @Nullable List<Path> javaSourceRoots,
            @Nullable Path stubsOut)
            throws IOException {
        String groovyVersion = CompileToolchain.groovyVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        GroovyPluginSetup.Prepared gv;
        try {
            RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            gv = GroovyPluginSetup.prepare(repos, cas, groovyVersion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Groovy compiler", e);
        }
        // Compilation classpath: project deps + the version-matched groovy jar (user Groovy
        // code compiles against the Groovy runtime types).
        List<Path> compileCp = new ArrayList<>(classpath);
        compileCp.add(gv.groovyJar());
        Files.createDirectories(outputDir);
        if (stubsOut != null) Files.createDirectories(stubsOut);
        // Contributed groovyc args (e.g. grails' --parameters — data binding reflects on
        // parameter names), deduped; mirrors the javac/kotlinc lanes.
        List<String> gvArgs = groovyArgs(ctx.require(PROJECT), ctx.require(LOCKFILE), in.dir());
        // Joint mode sweeps.java sources through a real javac pass — annotation processors
        // must run there or generated members fail resolution.
        List<Path> processorCp =
                javaSourceRoots == null ? List.of() : ctx.get(PROCESSOR_CP).orElse(List.of());
        return GroovycRequest.builder()
                .sources(sources)
                .javaSourceRoots(javaSourceRoots == null ? List.of() : javaSourceRoots)
                .classpath(compileCp)
                .processorPath(processorCp)
                .outputDir(outputDir)
                .stubsOut(stubsOut)
                .jvmTarget(CompileSupport.effectiveRelease(
                        ctx.require(RELEASE), JvmOptions.hostFeature(ctx.require(JAVA_HOME))))
                .workerClasspath(gv.workerClasspath())
                .extraArgs(gvArgs)
                .build();
    }

    /**
     * Run {@code req} through the action cache and the Groovy worker (restores from the CAS on an
     * exact-input hit without launching the plugin, else forks a full compile — Groovy has no
     * incremental state). The caller owns freshness stamps, output assembly, and outcome reporting.
     */
    static LangCompile.Result compileGroovySources(
            TaskContext ctx, BuildPlanner.Inputs in, ActionCache actionCache, GroovycRequest req, String taskId)
            throws IOException {
        boolean rerun = in.session().config().rebuildOr(false);
        // Reweight from the real request: a CAS hit is a cheap restore (3), else a
        // full groovyc. Same forGroovyc key LangCompile.run looks up.
        if (!rerun) {
            try {
                boolean restores = actionCache
                        .lookup(ActionKey.forGroovyc(taskId, req, BuildIdentity.cacheKeyVersion()))
                        .isPresent();
                ctx.reweight(
                        restores
                                ? EffortWeights.RESTORE
                                : EffortWeights.compileWeight(req.sources().size()));
            } catch (Exception e) {
                /* keep the up-front estimate */
                Log.debug("compileGroovySources: keep the up-front estimate", e);
            }
        }
        return LangCompile.run(
                taskId,
                req,
                BuildIdentity.cacheKeyVersion(),
                !rerun,
                !in.ephemeralActions(), // verify-scratch: no persistent residue
                actionCache.cas(),
                actionCache,
                WorkerEnv.forModule(ctx.require(PROJECT).build().env(), in.dir(), null));
    }
}
