// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerSupport.lockModules;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.GroovyCompile;
import cc.jumpkick.task.KotlinCompile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared Kotlin / Groovy compiler invocation used by main and test compile steps.
 */
public final class PlannerLang {

    private PlannerLang() {}

    static KotlinCompile.Result compileKotlinSources(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            ActionCache actionCache,
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            String taskId,
            Path workingDir,
            List<Path> javaSourceRoots)
            throws IOException {
        String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        KotlinPluginSetup.Prepared kt;
        // The installed plugins' [[contribute.kotlin-plugin]] entries (e.g. spring-boot's
        // all-open, and no-arg gated on jakarta.persistence via classpath-has) — evaluated
        // from the manifest, fetched version-locked to the compiler actually used. The
        // embeddable variants match the BTA plugin's embeddable compiler.
        Set<String> lockModules = lockModules(ctx.require(LOCKFILE));
        List<KotlincRequest.Plugin> ktPlugins = new ArrayList<>();
        try {
            RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            kt = KotlinPluginSetup.prepare(repos, cas, kotlinVersion);
            // Same null-defaulting as KotlinPluginSetup.prepare — a contributed plugin must
            // match the compiler actually used.
            String pluginVersion =
                    (kotlinVersion == null || kotlinVersion.isBlank()) ? KotlinResolver.DEFAULT_VERSION : kotlinVersion;
            for (var use :
                    PluginContributions.kotlinPlugins(ctx.require(PROJECT), workingDir, pluginVersion, lockModules)) {
                Path jar = repos.tryFetchArtifact(Coordinate.of(use.group(), use.artifact(), use.version()))
                        .map(hit -> hit.fetched().cachePath())
                        .orElseThrow(() -> new RuntimeException("cannot fetch the " + use.id()
                                + " Kotlin compiler plugin (" + use.group() + ":" + use.artifact() + ":"
                                + use.version() + ") — a plugin contribution requires it"));
                ktPlugins.add(new KotlincRequest.Plugin(use.id(), jar, use.options()));
            }
            // Project-declared [[kotlin-plugins]] (serialization et al.) ride the same lane;
            // an omitted coordinate version means "match the compiler" — the org.jetbrains.kotlin
            // plugin convention, and the only version that can load into this kotlinc anyway.
            for (var decl : ctx.require(PROJECT).build().kotlinPlugins()) {
                String[] parts = decl.coordinate().split(":");
                String version = parts.length == 3 ? parts[2] : pluginVersion;
                Path jar = repos.tryFetchArtifact(Coordinate.of(parts[0], parts[1], version))
                        .map(hit -> hit.fetched().cachePath())
                        .orElseThrow(() -> new RuntimeException("cannot fetch the " + decl.id()
                                + " Kotlin compiler plugin (" + parts[0] + ":" + parts[1] + ":" + version
                                + ") — declared under [[kotlin-plugins]]"));
                ktPlugins.add(new KotlincRequest.Plugin(decl.id(), jar, decl.options()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Kotlin compiler", e);
        }
        // Compilation classpath: project deps + the version-matched stdlib (the
        // in-process plugin has no kotlin-home to auto-supply it; -no-stdlib).
        List<Path> compileCp = new ArrayList<>(classpath);
        compileCp.add(kt.stdlib());
        List<String> ktArgs = new ArrayList<>();
        ktArgs.add("-no-stdlib");
        // Contributed kotlinc args (e.g. spring-boot's -java-parameters, mirroring its javac
        // -parameters — Boot reflects on parameter names). User-position args still win: these
        // sit before extraArgs additions exactly where the hard-coded flag used to.
        for (String arg : PluginContributions.kotlinArgs(ctx.require(PROJECT), workingDir, lockModules)) {
            if (!ktArgs.contains(arg)) ktArgs.add(arg);
        }
        // Compiler plugins ride the typed BTA COMPILER_PLUGINS argument — raw -Xplugin/-P
        // strings in extraArgs are silently ignored by the BTA execution path.
        if (javaSourceRoots != null && !javaSourceRoots.isEmpty()) {
            StringBuilder roots = new StringBuilder();
            for (Path root : javaSourceRoots) {
                if (roots.length() > 0) roots.append(',');
                roots.append(root.toAbsolutePath());
            }
            ktArgs.add("-Xjava-source-roots=" + roots);
        }
        Files.createDirectories(outputDir);
        String moduleName = ctx.require(PROJECT).project().name();
        // The incremental state is only valid for the exact compile CONFIG that produced it:
        // BTA's IC sees "no source changes" after an args/plugins/module-name change and would
        // emit nothing into a clean output dir. Key the working dir by a config hash so any
        // config change starts fresh IC state (stale dirs age out with the cache).
        String configToken = Hashing.sha256Hex((CompileSupport.kotlinJvmTarget(ctx.require(RELEASE))
                                + "|" + moduleName + "|" + String.join(",", ktArgs) + "|"
                                + ktPlugins.stream()
                                        .map(p -> p.id() + "=" + p.options())
                                        .collect(Collectors.joining(",")))
                        .getBytes(StandardCharsets.UTF_8))
                .substring(0, 12);
        Path icWorkingDir =
                workingDir == null ? null : workingDir.resolveSibling(workingDir.getFileName() + "-" + configToken);
        KotlincRequest req = KotlincRequest.builder()
                .sources(sources)
                .classpath(compileCp)
                .outputDir(outputDir)
                .jvmTarget(CompileSupport.kotlinJvmTarget(ctx.require(RELEASE)))
                .workerClasspath(kt.workerClasspath())
                .javaHome(ctx.require(JAVA_HOME))
                .workingDir(icWorkingDir)
                .snapshotDir(CacheTree.KOTLIN_CP_SNAPSHOTS.under(in.cache()))
                .extraArgs(ktArgs)
                .plugins(ktPlugins)
                // Lockstep with the KSP round's -module-name: internal-member mangling
                // (member$module_name) is baked into call sites KSP-generated Java emits
                // (Hilt factories calling internal providers).
                .moduleName(moduleName)
                .build();
        boolean rerun = in.session().config().rebuildOr(false);
        // Reweight from the real request: a CAS hit is a cheap restore (3), else a
        // full kotlinc. Same forKotlinc key KotlinCompile.run looks up.
        if (!rerun) {
            try {
                boolean restores = actionCache
                        .lookup(ActionKey.forKotlinc(taskId, req, BuildIdentity.cacheKeyVersion()))
                        .isPresent();
                ctx.reweight(restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
            } catch (Exception ignored) {
                /* keep the up-front estimate */
            }
        }
        return KotlinCompile.run(
                taskId,
                req,
                BuildIdentity.cacheKeyVersion(),
                !rerun,
                !in.ephemeralActions(), // verify-scratch: no persistent residue
                actionCache.cas(),
                actionCache);
    }

    /**
     * Compile Groovy {@code sources} into {@code outputDir} via the plugin (action-cached: restores
     * from the CAS on an exact-input hit without launching the plugin, else forks a full compile
     * Groovy has no incremental state). Shared by the main {@code compile-groovy} and {@code
     * compile-test} steps. The caller owns freshness stamps, output assembly, and outcome reporting.
     *
     * @param javaSourceRoots when non-empty, joint mode: the worker sweeps {@code.java} under them
     * for resolution only (jk's javac worker owns the real Java outputs)
     * @param stubsOut when non-null, Java-visible stubs are retained there for javac's sourcepath
     */
    static GroovyCompile.Result compileGroovySources(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            ActionCache actionCache,
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            String taskId,
            List<Path> javaSourceRoots,
            Path stubsOut)
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
        List<String> gvArgs = new ArrayList<>();
        for (String arg :
                PluginContributions.groovyArgs(ctx.require(PROJECT), in.dir(), lockModules(ctx.require(LOCKFILE)))) {
            if (!gvArgs.contains(arg)) gvArgs.add(arg);
        }
        // Joint mode sweeps.java sources through a real javac pass — annotation processors
        // must run there or generated members fail resolution.
        @SuppressWarnings("unchecked")
        List<Path> processorCp = javaSourceRoots == null
                ? List.of()
                : (List<Path>) ctx.get(PROCESSOR_CP).orElse(List.of());
        GroovycRequest req = GroovycRequest.builder()
                .sources(sources)
                .javaSourceRoots(javaSourceRoots == null ? List.of() : javaSourceRoots)
                .classpath(compileCp)
                .processorPath(processorCp)
                .outputDir(outputDir)
                .stubsOut(stubsOut)
                .jvmTarget(ctx.require(RELEASE))
                .workerClasspath(gv.workerClasspath())
                .extraArgs(gvArgs)
                .build();
        boolean rerun = in.session().config().rebuildOr(false);
        // Reweight from the real request: a CAS hit is a cheap restore (3), else a
        // full groovyc. Same forGroovyc key GroovyCompile.run looks up.
        if (!rerun) {
            try {
                boolean restores = actionCache
                        .lookup(ActionKey.forGroovyc(taskId, req, BuildIdentity.cacheKeyVersion()))
                        .isPresent();
                ctx.reweight(restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
            } catch (Exception ignored) {
                /* keep the up-front estimate */
            }
        }
        return GroovyCompile.run(
                taskId,
                req,
                BuildIdentity.cacheKeyVersion(),
                !rerun,
                !in.ephemeralActions(), // verify-scratch: no persistent residue
                actionCache.cas(),
                actionCache);
    }
}
