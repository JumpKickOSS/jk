// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.CacheSync;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Parse / sync-deps / ensure-jdk steps for {@link BuildPlanner#coreBuilder}.
 */
public final class PlannerSetup {

    private PlannerSetup() {}

    static Task parseBuildStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        AtomicReference<List<Path>> groovyMainSrcRef = cx.groovyMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.PARSE_BUILD)
                .stage(BuildStage.RESOLVE)
                .label("Parsing")
                .weight(() -> plan.get().fullyCached() ? W_CACHED_TOUCH : W_PARSE)
                .ticks(() -> {
                    if (Files.exists(in.lockFile())) {
                        try {
                            return LockfileReader.read(in.lockFile())
                                            .artifacts()
                                            .size()
                                    + 5;
                        } catch (Exception ignored) {
                        }
                    }
                    return 10;
                })
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    JkBuild project;
                    try {
                        // Same override the plan was built from (jk assemble --fat/--minified).
                        // Plan construction and step bodies must read one effective config, or a
                        // task gets scheduled against a config its body cannot see.
                        project = cc.jumpkick.plugin.manifest.VariantApply.apply(
                                        applyAssemblyOverride(JkBuildParser.parse(in.buildFile()), in.session()),
                                        in.dir(),
                                        cc.jumpkick.model.Variants.Selection.parse(in.variant()),
                                        in.clientEnv())
                                .build();
                    } catch (RuntimeException e) {
                        ctx.error("toml", e.getMessage());
                        throw e;
                    }
                    ctx.put(PROJECT, project);
                    BuildLayout layout = BuildLayout.of(in.dir(), project);
                    ctx.put(LAYOUT, layout);

                    if (!Files.exists(in.lockFile())) {
                        ctx.label("resolve deps (first run)");
                        LockFlow.Result result;
                        try {
                            // noDefaultFeatures=false — same feature selection as `jk lock` (JK-1358).
                            result = LockFlow.run(in.lockDir(), in.cache(), List.of(), false, null);
                        } catch (UnsatisfiableException e) {
                            ctx.error("verbatim", e.getMessage());
                            throw new RuntimeException("dependency resolution failed");
                        }
                        if (result.status() != 0) {
                            ctx.error(
                                    "verbatim",
                                    result.error() != null ? result.error() : "dependency resolution failed");
                            throw new RuntimeException("lock failed");
                        }
                        ctx.put(LOCKFILE, result.lockfile());
                    } else if (AutoLock.isStale(in.dir(), in.lockFile())) {
                        ctx.label("jk.toml changed — updating lock");
                        Lockfile existing = LockfileReader.read(in.lockFile());
                        Lockfile updated = AutoLock.maybeReLock(
                                in.dir(),
                                existing,
                                in.lockFile(),
                                in.cache(),
                                null,
                                cc.jumpkick.model.BuildIdentity.cacheKeyVersion(),
                                List.of(),
                                true,
                                cc.jumpkick.resolver.ResolveObserver.NOOP,
                                ctx::output);
                        ctx.put(LOCKFILE, updated != null ? updated : existing);
                    } else {
                        ctx.put(LOCKFILE, LockfileReader.read(in.lockFile()));
                    }

                    Lockfile lock = ctx.require(LOCKFILE);
                    // Reading the lock keeps its deps fresh against the 90-day cache GC.
                    cc.jumpkick.task.AccessLedger.atDefaultPath().touchLock(lock);
                    ctx.label("resolve classpath");
                    ClasspathResolver resolver = new ClasspathResolver(cas);

                    WorkspaceClasspath.Result mainSiblings =
                            WorkspaceClasspath.resolve(in.dir(), project, Set.of(Scope.EXPORT, Scope.MAIN));
                    if (!mainSiblings.missingSiblingJars().isEmpty()) {
                        for (String missing : mainSiblings.missingSiblingJars())
                            ctx.error("workspace", "sibling not built — " + missing);
                        throw new RuntimeException("missing workspace siblings");
                    }
                    // Lockfile + sibling jars + siblings' transitive lockfile deps — the
                    // exact classpath `jk explain` re-derives, so the action keys match.
                    List<Path> mainCp = mainCompileClasspath(lock, resolver, mainSiblings);
                    // Plugin-contributed PROVIDED classpath (an Android platform jar): javac
                    // sees it, runtime/packaging never do. Resolved through the same engine
                    // fetch the steps use, so the compile action key fingerprints it.
                    List<Path> contributedProvided = contributedProvidedClasspath(project, in, cas);
                    mainCp.addAll(contributedProvided);

                    Profile profile = CompileSupport.resolveProfile(project.profiles(), in.profileName());
                    // Default lint (deprecation/unchecked) unless [build] lint = false;
                    // the profile's own javac args win (appended after). Shared by the
                    // main- and test-compile steps (both read JAVAC_ARGS).
                    ctx.put(
                            JAVAC_ARGS,
                            cc.jumpkick.compile.JavacLint.effectiveArgs(
                                    project.build().lint(),
                                    cc.jumpkick.plugin.manifest.PluginContributions.javacArgs(
                                            project, in.dir(), lockModules(lock)),
                                    profile == null ? List.of() : profile.javacArgs()));
                    ctx.put(CLASSPATH, mainCp);

                    // Annotation processors live in their own scope (kept off the
                    // compile classpath); javac discovers them via -processorpath and
                    // KspProcessors.split routes the KSP ones to the forked KSP2 round.
                    // Workspace siblings must merge in exactly as they do for main/test
                    // a processor declared `{ workspace = true }` is never in the
                    // lock, so a lock-only path silently yields no processors at all.
                    WorkspaceClasspath.Result processorSiblings =
                            WorkspaceClasspath.resolve(in.dir(), project, Set.of(Scope.PROCESSOR));
                    // A declared processor that cannot be found generates nothing, and a build
                    // that silently skips code generation is worse than one that fails
                    // . Mirror the main-classpath missing-sibling guard above.
                    if (!processorSiblings.missingSiblingJars().isEmpty()) {
                        for (String missing : processorSiblings.missingSiblingJars())
                            ctx.error("workspace", "processor sibling not built — " + missing);
                        throw new RuntimeException("missing workspace siblings");
                    }
                    List<String> unresolvedProcessors = unresolvedProcessorDeps(project, lock, processorSiblings);
                    if (!unresolvedProcessors.isEmpty()) {
                        for (String unresolved : unresolvedProcessors)
                            ctx.error(
                                    "processor",
                                    "processor dependency '" + unresolved + "' is declared in"
                                            + " [processor-dependencies] but is not in jk-lock.toml —"
                                            + " run `jk lock`");
                        throw new RuntimeException("unresolved processor dependencies");
                    }
                    ctx.put(PROCESSOR_CP, processorClasspath(lock, resolver, processorSiblings));

                    WorkspaceClasspath.Result testSiblings = WorkspaceClasspath.resolve(
                            in.dir(), project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST, Scope.TEST_DEV));
                    List<Path> compileTestCp =
                            new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST));
                    compileTestCp.addAll(testSiblings.jars());
                    List<Path> testRuntimeCp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.TEST));
                    testRuntimeCp.addAll(testSiblings.jars());
                    // A sibling's own external deps (e.g. resolver's maven-artifact) must
                    // also reach the test classpath, or tests exercising sibling code hit
                    // NoClassDefFoundError. Mirrors the main-cp sibling-lockfile loop above.
                    for (Path sibLock : testSiblings.siblingLockfiles()) {
                        try {
                            cc.jumpkick.lock.Lockfile sl = cc.jumpkick.lock.LockfileReader.read(sibLock);
                            for (Path p : resolver.classpathFor(sl, ClasspathResolver.COMPILE_MAIN)) {
                                if (!compileTestCp.contains(p)) compileTestCp.add(p);
                            }
                            for (Path p : resolver.classpathFor(sl, ClasspathResolver.RUNTIME)) {
                                if (!testRuntimeCp.contains(p)) testRuntimeCp.add(p);
                            }
                        } catch (Exception ignored) {
                            /* best-effort */
                        }
                    }
                    compileTestCp.addAll(contributedProvided);
                    ctx.put(PROVIDED_CP, contributedProvided);
                    ctx.put(COMPILE_TEST_CP, compileTestCp);
                    ctx.put(TEST_RUNTIME_CP, testRuntimeCp);
                    // Reuse source lists that the tick suppliers may have already walked.
                    // If the ticks haven't fired yet (unusual ordering), populate and cache now.
                    List<Path> javaMainSrcs = javaMainSrcRef.get();
                    if (javaMainSrcs == null) {
                        javaMainSrcs = CompileSupport.collectJavaSources(javaMainSrcDir);
                        javaMainSrcRef.compareAndSet(null, javaMainSrcs);
                        javaMainSrcs = javaMainSrcRef.get();
                    }
                    List<Path> kotlinMainSrcs = kotlinMainSrcRef.get();
                    if (kotlinMainSrcs == null) {
                        kotlinMainSrcs = CompileSupport.collectKotlinSources(in.dir(), compact);
                        kotlinMainSrcRef.compareAndSet(null, kotlinMainSrcs);
                        kotlinMainSrcs = kotlinMainSrcRef.get();
                    }
                    List<Path> groovyMainSrcs = groovyMainSrcRef.get();
                    if (groovyMainSrcs == null) {
                        groovyMainSrcs = CompileSupport.collectGroovySources(in.dir(), compact);
                        groovyMainSrcRef.compareAndSet(null, groovyMainSrcs);
                        groovyMainSrcs = groovyMainSrcRef.get();
                    }
                    // [build] extra-src roots (variant overlays folded in by VariantApply) and
                    // plugin-contributed source roots ([[contribute.source-roots]] — grails-app/…)
                    // join the source set here — the tick suppliers' pre-walk never saw them.
                    List<Path> extraSrcDirs = new ArrayList<>(CompileSupport.extraSrcDirs(project, in.dir()));
                    for (var root : cc.jumpkick.plugin.manifest.PluginContributions.sourceRoots(project, in.dir())) {
                        if (!root.resource()) extraSrcDirs.add(in.dir().resolve(root.dir()));
                    }
                    if (!extraSrcDirs.isEmpty()) {
                        javaMainSrcs = CompileSupport.withExtraSources(javaMainSrcs, extraSrcDirs, ".java");
                        kotlinMainSrcs = CompileSupport.withExtraSources(kotlinMainSrcs, extraSrcDirs, ".kt");
                        groovyMainSrcs = CompileSupport.withExtraSources(groovyMainSrcs, extraSrcDirs, ".groovy");
                        javaMainSrcRef.set(javaMainSrcs);
                        kotlinMainSrcRef.set(kotlinMainSrcs);
                        groovyMainSrcRef.set(groovyMainSrcs);
                    }
                    ctx.put(JAVA_SOURCES, javaMainSrcs);
                    ctx.put(KOTLIN_SOURCES, kotlinMainSrcs);
                    ctx.put(GROOVY_SOURCES, groovyMainSrcs);
                    ctx.put(RELEASE, project.project().javaRelease());
                    ctx.put(MAIN_CLASSES, layout.classesDir());
                    ctx.put(TEST_CLASSES, layout.testClassesDir());
                    ctx.progress(1);
                })
                .build();
    }

    static Task syncDepsStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.RESOLVE_DEPS)
                .stage(BuildStage.RESOLVE)
                .label("Syncing")
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_BUILD)
                .weight(() -> plan.get().sync())
                .ticks(() -> {
                    try {
                        return LockfileReader.read(in.lockFile()).artifacts().size();
                    } catch (Exception ignored) {
                        return 10;
                    }
                })
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    JkBuild project = ctx.require(PROJECT);
                    boolean mirrorToM2 = project.project().m2install();
                    // Ticks are already counted up front by estimateTicks (artifact
                    // count); progress(1)-per-artifact below fills it.
                    var observer = new CacheSync.ProgressObserver() {
                        @Override
                        public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched " + pkg.name());
                            ctx.progress(1);
                        }

                        @Override
                        public void upToDate(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void skipped(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void failed(Lockfile.Artifact pkg, String err) {
                            ctx.error("dep", pkg.name() + " — " + err);
                            ctx.progress(1);
                        }
                    };
                    boolean refresh = in.session().config().forceOr(false);
                    var report = new CacheSync(cas, new Http(), mirrorToM2).sync(lock, observer, refresh);
                    if (report.hasErrors()) throw new RuntimeException("dep sync had errors");
                })
                .build();
    }

    static Task ensureJdkStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.ENSURE_JDK)
                .stage(BuildStage.RESOLVE)
                .label("JDK")
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_BUILD)
                .weight(() -> EffortWeights.jdkWeight(in.dir(), in.jdksDir()))
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve JDK");
                    Lockfile lock = ctx.require(LOCKFILE);
                    JkBuild project = ctx.require(PROJECT);
                    try {
                        JdkEnsure.Outcome outcome =
                                JdkEnsure.ensure(in.dir(), in.jdksDir(), project, lock, m -> ctx.warn("jdk", m));
                        // JAVA_HOME is published HERE, not in parse-build: resolving before the
                        // ensure meant the FIRST build against a never-installed pin snapshotted
                        // the running JVM and compiled/tested on the wrong JDK (self-healing on
                        // the next build — but wrong once is wrong). The ensure's own outcome is
                        // authoritative; the walk is only the no-pin fallback.
                        ctx.put(
                                JAVA_HOME,
                                outcome.jdk()
                                        .map(cc.jumpkick.jdk.InstalledJdk::home)
                                        .orElseGet(() -> JavaHomes.resolveJavaHome(in.dir())));
                        // Already on disk / locked — no download work this run.
                        if (outcome.source() != JdkEnsure.Source.INSTALLED) ctx.cached();
                    } catch (Exception e) {
                        ctx.error("jdk", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();
    }
}
