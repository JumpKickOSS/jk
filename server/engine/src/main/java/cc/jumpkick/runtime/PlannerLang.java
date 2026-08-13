// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Shared Kotlin / Groovy compiler invocation used by main and test compile steps.
 */
public final class PlannerLang {

    private PlannerLang() {}

    static cc.jumpkick.task.KotlinCompile.Result compileKotlinSources(
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
        java.util.Set<String> lockModules = lockModules(ctx.require(LOCKFILE));
        List<KotlincRequest.Plugin> ktPlugins = new ArrayList<>();
        try {
            cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            kt = KotlinPluginSetup.prepare(repos, cas, kotlinVersion);
            // Same null-defaulting as KotlinPluginSetup.prepare — a contributed plugin must
            // match the compiler actually used.
            String pluginVersion = (kotlinVersion == null || kotlinVersion.isBlank())
                    ? cc.jumpkick.kotlin.KotlinResolver.DEFAULT_VERSION
                    : kotlinVersion;
            for (var use : cc.jumpkick.plugin.manifest.PluginContributions.kotlinPlugins(
                    ctx.require(PROJECT), workingDir, pluginVersion, lockModules)) {
                Path jar = repos.tryFetchArtifact(
                                cc.jumpkick.model.Coordinate.of(use.group(), use.artifact(), use.version()))
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
                Path jar = repos.tryFetchArtifact(cc.jumpkick.model.Coordinate.of(parts[0], parts[1], version))
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
        for (String arg : cc.jumpkick.plugin.manifest.PluginContributions.kotlinArgs(
                ctx.require(PROJECT), workingDir, lockModules)) {
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
        String configToken = cc.jumpkick.util.Hashing.sha256Hex((CompileSupport.kotlinJvmTarget(ctx.require(RELEASE))
                                + "|" + moduleName + "|" + String.join(",", ktArgs) + "|"
                                + ktPlugins.stream()
                                        .map(p -> p.id() + "=" + p.options())
                                        .collect(java.util.stream.Collectors.joining(",")))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
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
                .snapshotDir(in.cache().resolve("kotlin-cp-snapshots"))
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
                        .lookup(ActionKey.forKotlinc(taskId, req, cc.jumpkick.model.BuildIdentity.cacheKeyVersion()))
                        .isPresent();
                ctx.reweight(restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
            } catch (Exception ignored) {
                /* keep the up-front estimate */
            }
        }
        return cc.jumpkick.task.KotlinCompile.run(
                taskId,
                req,
                cc.jumpkick.model.BuildIdentity.cacheKeyVersion(),
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
    static cc.jumpkick.task.GroovyCompile.Result compileGroovySources(
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
            cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
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
        for (String arg : cc.jumpkick.plugin.manifest.PluginContributions.groovyArgs(
                ctx.require(PROJECT), in.dir(), lockModules(ctx.require(LOCKFILE)))) {
            if (!gvArgs.contains(arg)) gvArgs.add(arg);
        }
        // Joint mode sweeps.java sources through a real javac pass — annotation processors
        // must run there or generated members fail resolution.
        @SuppressWarnings("unchecked")
        List<Path> processorCp = javaSourceRoots == null
                ? List.of()
                : (List<Path>) ctx.get(PROCESSOR_CP).orElse(java.util.List.of());
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
                        .lookup(ActionKey.forGroovyc(taskId, req, cc.jumpkick.model.BuildIdentity.cacheKeyVersion()))
                        .isPresent();
                ctx.reweight(restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
            } catch (Exception ignored) {
                /* keep the up-front estimate */
            }
        }
        return cc.jumpkick.task.GroovyCompile.run(
                taskId,
                req,
                cc.jumpkick.model.BuildIdentity.cacheKeyVersion(),
                !rerun,
                !in.ephemeralActions(), // verify-scratch: no persistent residue
                actionCache.cas(),
                actionCache);
    }

    /**
     * The compile-main freshness-stamp classpath side — ONE recipe shared by the live check,
     * {@code write-stamp}, and the forecastthe base compile classpath, then the
     * mixed-language sibling outputs javac sees (kotlin/groovy classes dirs + the version-matched
     * groovy jar), then the annotation-processor path (not on the compile classpath, but a
     * processor bump must bust the stamp). Hand-maintained copies of this recipe drifted twice:
     * the forecast missed the mixed-language entries and write-stamp missed {@code processorCp},
     * so mixed and processor modules never stamp-matched.
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
    static java.util.Set<String> lockModules(Lockfile lock) {
        java.util.Set<String> out = new java.util.HashSet<>();
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
}
