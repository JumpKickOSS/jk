// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerNative.groovySources;
import static cc.jumpkick.runtime.PlannerNative.javaSources;
import static cc.jumpkick.runtime.PlannerNative.kotlinSources;
import static cc.jumpkick.runtime.PlannerPlugin.applicationSbom;
import static cc.jumpkick.runtime.PlannerPlugin.packagePlugin;
import static cc.jumpkick.runtime.PlannerSupport.contributionsToken;
import static cc.jumpkick.runtime.PlannerSupport.packagedClasses;
import static cc.jumpkick.runtime.PlannerSupport.restorePackaged;
import static cc.jumpkick.runtime.PlannerSupport.storePackaged;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.JarPackager;
import cc.jumpkick.compile.PackagedTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * package-jar, freshness stamps, and mixed-module class assembly.
 */
public final class PlannerPackage {

    private PlannerPackage() {}

    static Task packageJarStep(
            BuildPlanner.Ctx cx,
            @Nullable ActivePlugin pluginActive,
            @Nullable PluginDeclarations pluginDecls,
            Map<String, String> variantSecrets,
            boolean multiRelease,
            boolean nodeStage) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean groovyModule = cx.groovyModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        boolean javaStamp =
                mixedWithJava || TaskNames.COMPILE_JAVA.equals(mainCompile) || (!kotlinModule && !groovyModule);
        return Task.builder(TaskNames.PACKAGE_JAR)
                .forecast(StepForecast.priced(s -> forecastJar(ForecastScope.of(s))))
                .stage(BuildStage.PACKAGE)
                .label("Packaging")
                .kind(TaskKind.CPU)
                .requires(packageRequires(
                        in, pluginDecls, javaStamp, kotlinModule, groovyModule, multiRelease, nodeStage))
                .weight(() -> plan.get().pkg())
                .ticks(1)
                .execute(ctx -> {
                    // The first step of the plan that reads a sibling's jar: a packager with a
                    // runtime view (an APK's dex, a boot jar, the assembly and the tails behind
                    // this step) needs the siblings' jars written, so the wait for them is here.
                    PlannerSetup.awaitSiblingArtifacts(ctx, in);
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path jarPath = layout.mainJar();
                    if (pluginDecls != null && pluginDecls.packager() != null) {
                        // The packager's declared artifact extension replaces .jar (an APK, …).
                        ActivePlugin owner = Objects.requireNonNull(pluginActive, "packager plugin");
                        jarPath = PluginBuild.mainArtifactPath(layout, owner);
                        Files.createDirectories(jarPath.getParent());
                        packagePlugin(
                                ctx,
                                in,
                                cas,
                                cx.tools(),
                                project,
                                classes,
                                jarPath,
                                pluginActive,
                                pluginDecls,
                                variantSecrets);
                        return;
                    }
                    // Plain/assembly packaging merges plugin contributesClasses (Micronaut AOT, …);
                    // custom packagers (boot-jar) merge step outputs themselves. The dirs are only
                    // *listed* here — staging them is a copy, and it must not happen before the
                    // cache check below.
                    List<Path> contributed =
                            PackagingKeys.packagedContributions(pluginDecls, layout, in.dir(), project);
                    Files.createDirectories(jarPath.getParent());
                    String mainClass = PluginModule.mainClass(in.dir(), project);
                    // Application jars embed the lockfile-derived SBOM (libraries don't:
                    // their consumers' lockfiles are the truth for the final classpath).
                    byte[] sbom = null;
                    if (project.isApplication()) {
                        Lockfile sbomLock = ctx.get(LOCKFILE).orElse(null);
                        if (sbomLock != null) sbom = applicationSbom(project, sbomLock);
                    }
                    List<Path> guarded = new ArrayList<>(List.of(classes));
                    guarded.addAll(contributed);
                    InputGuard guard = InputGuard.of(guarded);
                    PackagingKeys.Keyed keyed = PackagingKeys.packageJar(
                            jarPath, in.dir(), project, guard.token(classes), contributionsToken(contributed), sbom);
                    String pkgKey = keyed.key();
                    long t0 = Perf.start();
                    if (restorePackaged(in.cache(), pkgKey, jarPath.getParent())) {
                        writeSidecarPom(project, layout, jarPath);
                        ctx.label(jarPath.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + jarPath.getFileName());
                    long t1 = Perf.start();
                    PackagedTree tree = packagedClasses(classes, contributed);
                    long t2 = Perf.start();
                    JarPackager.JarRequest jarRequest = JarPackager.JarRequest.of(tree, jarPath);
                    if (mainClass != null && !mainClass.isBlank()) jarRequest = jarRequest.withMainClass(mainClass);
                    Map<String, String> jarAttrs = new LinkedHashMap<>(project.manifest());
                    if (PlannerVersions.declared(project)) jarAttrs.putIfAbsent(PlannerVersions.MULTI_RELEASE, "true");
                    if (sbom != null) {
                        jarAttrs.put("Sbom-Format", "CycloneDX");
                        jarAttrs.put("Sbom-Location", SBOM_JAR_ENTRY);
                        jarRequest = jarRequest.withExtraEntries(Map.of(SBOM_JAR_ENTRY, sbom));
                    }
                    if (!jarAttrs.isEmpty()) jarRequest = jarRequest.withAttributes(jarAttrs);
                    new JarPackager().packageJar(jarRequest);
                    long t3 = Perf.start();
                    // A jar written anew is a build, whatever the compile step found cached: the
                    // client's tail says "built" for it, not "up to date".
                    ctx.put(BUILD_OUTCOME, "built");
                    storePackaged(
                            ctx,
                            guard,
                            in.cache(),
                            keyed.taskId(),
                            pkgKey,
                            keyed.tokens(),
                            jarPath.getParent(),
                            List.of(jarPath),
                            !in.ephemeralActions());
                    writeSidecarPom(project, layout, jarPath);
                    if (Perf.enabled()) {
                        long ms = 1_000_000;
                        Perf.end(
                                "package-jar " + in.dir().getFileName(),
                                t0,
                                "restoreCheck",
                                (t1 - t0) / ms,
                                "list",
                                (t2 - t1) / ms,
                                "jar",
                                (t3 - t2) / ms,
                                "store",
                                (Perf.start() - t3) / ms);
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /** Sibling {@code .pom} so {@code WorkerLaunchClasspath} can resolve a workspace-built worker. */
    static void writeSidecarPom(JkBuild project, BuildLayout layout, Path jarPath) {
        if (project == null || jarPath == null) return;
        try {
            String name = jarPath.getFileName().toString();
            Path pom = name.endsWith(".jar")
                    ? jarPath.resolveSibling(name.substring(0, name.length() - 4) + ".pom")
                    : jarPath.resolveSibling(name + ".pom");
            Files.createDirectories(pom.getParent());
            AtomicWrites.replaceIfChanged(pom, InstallPlans.renderedPomBytes(project, layout));
        } catch (IOException ignored) {
            // Launch still fails clearly if the POM is missing; packaging must not.
        }
    }

    /**
     * package-jar's requires: BEFORE_PACKAGE build-logic (which itself waits on resources), every
     * before-PACKAGE plugin step, and the module's own node output when it has one.
     */
    static String[] packageRequires(
            BuildPlanner.Inputs in,
            @Nullable PluginDeclarations decls,
            boolean useJava,
            boolean useKotlin,
            boolean useGroovy,
            boolean multiRelease,
            boolean nodeStage) {
        List<String> requires = new ArrayList<>();
        requires.add(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE);
        if (multiRelease) requires.add(TaskNames.COMPILE_VERSIONS);
        if (nodeStage) requires.add(TaskNames.NODE_STAGE);
        // Freshness stamps must stay on the package path so target-closure prune retains them.
        if (useJava) requires.add(TaskNames.WRITE_STAMP);
        if (useKotlin) requires.add(TaskNames.WRITE_STAMP_KOTLIN);
        if (useGroovy) requires.add(TaskNames.WRITE_STAMP_GROOVY);
        if (decls != null) {
            for (TaskDecl step : decls.steps()) {
                if (step.packageTime()) requires.add("plugin-" + step.name());
            }
        }
        return requires.toArray(new String[0]);
    }

    /**
     * True when a declared task must run before the compilers: source generation, or other
     * resolve-window work that does not consume classes (e.g. android-manifest feeds aapt2).
     */
    static Task writeStampStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.WRITE_STAMP)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.COMPILE)
                .requires(TaskNames.COMPILE_JAVA)
                .weight(() -> plan.get().fullyCached() ? 0 : W_STAMP)
                .ticks(1)
                .execute(ctx -> {
                    String outcome = ctx.get(BUILD_OUTCOME).orElse("");
                    if ("up-to-date".equals(outcome) || "no-sources".equals(outcome)) {
                        ctx.label("stamp unchanged");
                        ctx.cached(); // SKIPPED — compile already stamp-skipped / no sources
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("write freshness stamp");
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path javaOut = classes; // javac always writes to java/main/
                    List<Path> sources = ctx.require(JAVA_SOURCES);
                    // The classpath lines and option digest compile-java checked — the ones its
                    // action key hashed — so the next check compares like with like; the stamp's
                    // instant is the clock before that compile read its first source.
                    String actionKey = ctx.get(ACTION_KEY).orElse("");
                    FreshnessStamp.write(
                            javaOut,
                            BuildStamps.JAVA,
                            TaskNames.COMPILE_MAIN,
                            actionKey,
                            sources,
                            FreshnessStamp.ClasspathTokens.of(ctx.require(JAVA_STAMP_TOKENS)),
                            ctx.require(RELEASE),
                            ctx.require(JAVA_STAMP_DIGEST),
                            ctx.require(JAVA_STAMP_CLOCK));
                    ctx.progress(1);
                })
                .build();
    }

    static Task writeStampKotlinStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.WRITE_STAMP_KOTLIN)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.COMPILE)
                .requires(TaskNames.COMPILE_KOTLIN)
                .weight(() -> plan.get().fullyCached() ? 0 : W_STAMP)
                .ticks(1)
                .execute(ctx -> {
                    String outcome = ctx.get(KOTLIN_OUTCOME).orElse("");
                    if ("up-to-date".equals(outcome) || "no-sources".equals(outcome)) {
                        ctx.label("stamp unchanged");
                        ctx.cached(); // SKIPPED — compile already stamp-skipped / no sources
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("write freshness stamp");
                    Path classes = ctx.require(MAIN_CLASSES);
                    List<Path> freshInputs = new ArrayList<>(kotlinSources(ctx));
                    if (mixedWithJava) freshInputs.addAll(javaSources(ctx));
                    // The classpath as the compile checked it: ABI token lines, never paths. The
                    // key names the record the merged tree is held against on the next build.
                    FreshnessStamp.write(
                            classes,
                            BuildStamps.KOTLIN,
                            TaskNames.COMPILE_KOTLIN,
                            ctx.get(KOTLIN_ACTION_KEY).orElse(""),
                            freshInputs,
                            FreshnessStamp.ClasspathTokens.of(ctx.require(KOTLIN_STAMP_TOKENS)),
                            ctx.require(RELEASE),
                            ctx.require(KOTLIN_STAMP_DIGEST),
                            ctx.require(KOTLIN_STAMP_CLOCK));
                    ctx.progress(1);
                })
                .build();
    }

    static Task writeStampGroovyStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        boolean mixedGroovy = cx.mixedGroovy();
        return Task.builder(TaskNames.WRITE_STAMP_GROOVY)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.COMPILE)
                .requires(TaskNames.COMPILE_GROOVY)
                .weight(() -> plan.get().fullyCached() ? 0 : W_STAMP)
                .ticks(1)
                .execute(ctx -> {
                    String outcome = ctx.get(GROOVY_OUTCOME).orElse("");
                    if ("up-to-date".equals(outcome) || "no-sources".equals(outcome)) {
                        ctx.label("stamp unchanged");
                        ctx.cached(); // SKIPPED — compile already stamp-skipped / no sources
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("write freshness stamp");
                    Path classes = ctx.require(MAIN_CLASSES);
                    List<Path> freshInputs = new ArrayList<>(groovySources(ctx));
                    if (mixedGroovy) freshInputs.addAll(javaSources(ctx));
                    // The token lines compile-groovy checked — the ones its action key hashed —
                    // and that key, for the next build's wholeness check of the merged tree.
                    FreshnessStamp.write(
                            classes,
                            BuildStamps.GROOVY,
                            TaskNames.COMPILE_GROOVY,
                            ctx.get(GROOVY_ACTION_KEY).orElse(""),
                            freshInputs,
                            FreshnessStamp.ClasspathTokens.of(ctx.require(GROOVY_STAMP_TOKENS)),
                            ctx.require(RELEASE),
                            ctx.require(GROOVY_STAMP_DIGEST),
                            ctx.require(GROOVY_STAMP_CLOCK));
                    ctx.progress(1);
                })
                .build();
    }

    static Task assembleClassesStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        boolean mixedGroovy = cx.mixedGroovy();
        List<String> requires = new ArrayList<>();
        requires.add(TaskNames.COMPILE_JAVA);
        if (mixed) requires.add(TaskNames.COMPILE_KOTLIN);
        if (mixedGroovy) requires.add(TaskNames.COMPILE_GROOVY);
        return Task.builder(TaskNames.ASSEMBLE_CLASSES)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.COMPILE)
                .label("Assembling")
                .kind(TaskKind.CPU)
                .requires(requires.toArray(new String[0]))
                .weight(() -> plan.get().fullyCached() ? 0 : W_ASSEMBLE)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    String jOutcome = ctx.get(BUILD_OUTCOME).orElse("");
                    String kOutcome = ctx.get(KOTLIN_OUTCOME).orElse("");
                    String gOutcome = ctx.get(GROOVY_OUTCOME).orElse("");
                    boolean settled = settledOutcome(jOutcome)
                            && (!mixed || settledOutcome(kOutcome))
                            && (!mixedGroovy || settledOutcome(gOutcome));
                    if (settled) { // all unchanged → classes already holds every language's output
                        ctx.label("up to date");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("assemble classes");
                    Files.createDirectories(classes);
                    // Java output already lives in classes (java/main/); mirror the other
                    // language dirs in. The Groovy merge runs after javac, so real Groovy
                    // classes overwrite any stub-compiled duplicates.
                    Path buildDir = ctx.require(LAYOUT).buildDir();
                    if (mixed) {
                        PlannerSupport.mergeLanguageOutput(
                                ctx.require(LAYOUT).kotlinClassesDir(), classes, buildDir, "kotlin");
                    }
                    if (mixedGroovy) {
                        PlannerSupport.mergeLanguageOutput(
                                ctx.require(LAYOUT).groovyClassesDir(), classes, buildDir, "groovy");
                    }
                    ctx.progress(1);
                })
                .build();
    }

    static boolean settledOutcome(String outcome) {
        return outcome.equals("up-to-date") || outcome.equals("no-sources");
    }

    // ---- forecast ---------------------------------------------------------------------------

    /**
     * package-jar's price, under the key {@link PackagingKeys#packageJar} derives for the live step.
     * After {@code jk clean} the classes tree is gone: its token is projected from the compile
     * record and the resource roots (the same merge the live build produces), so a wiped module
     * still hits the packaging cache. A plugin that owns packaging is priced by its packager's key.
     */
    static TaskForecast.@Nullable Task forecastJar(ForecastScope s) throws Exception {
        s.projectOwnOutputs();
        ForecastScope.Prepared prepared = s.prepared();
        BuildLayout layout = prepared.layout();
        boolean noSources = prepared.noSources();
        if (noSources && (Files.isRegularFile(layout.mainJar()) || s.project.isWorkspaceRoot())) {
            // A source-less member packages an empty jar (its copied resources) that sibling
            // classpaths demand; with the jar in place there is nothing to forecast. A source-less
            // workspace root runs its scripts and packages nothing.
            return null;
        }
        if (s.compileDirty) {
            // Dependency-only dirtiness: the jar is re-packaged only if the compile really runs.
            return new TaskForecast.Task(
                    TaskNames.PACKAGE_JAR,
                    TaskForecast.Status.RUN,
                    s.depOnlyDirty ? "repackage · " + TaskForecast.ONLY_IF_COMPILE_RUNS : "repackage · compile changed",
                    null);
        }
        ActivePlugins.@Nullable Declared plugin = prepared.plugin();
        if (PackagingKeys.ownsPackaging(plugin)) {
            // Packaging owned by a plugin (spring-boot, grails, quarkus, minified, android): the
            // build runs the packager under its own token bag, so the key comes from the same body
            // the build calls; anything that stops us reproducing it forecasts RUN, never a hit.
            return PackagingKeys.pluginPackagerStep(
                    s.project, s.dir, layout, s.cache, s.lockFile, s.cas, prepared.javaHome(), plugin, s.actionCache);
        }
        Path jar = layout.mainJar();
        long tp = Perf.start();
        byte[] sbom = null;
        if (s.project.isApplication()) {
            try {
                sbom = PlannerPlugin.applicationSbom(s.project, prepared.lock());
            } catch (Exception e) {
                // best-effort: a missing SBOM keys an empty sbom: token, like a null one
                Log.debug("forecastJar: best-effort", e);
            }
        }
        // classesTokenForPackage projects post-copy content when resources drifted, so package
        // CACHED/RUN matches the live step after copy-resources. A wiped tree takes the walk's own
        // projection, which merges every compiler's record.
        String classesTok = s.restored.projectedIdentity(layout.classesDir());
        if (classesTok == null) {
            classesTok = PackagingKeys.classesTokenForPackage(
                    s.dir,
                    prepared.compact(),
                    layout,
                    s.project,
                    s.actionCache,
                    s.compileMainKey,
                    s.knownResourceDrift);
        }
        // A worker's vendored sibling class dirs are the declared set the build lists, each read
        // as the tree the build restores.
        String contribTok = PlannerSupport.contributionsToken(
                PackagingKeys.packagedContributions(prepared.pkgDecls(), layout, s.dir, s.project),
                s.restored.identity());
        String pkgKey = PackagingKeys.packageJar(jar, s.dir, s.project, classesTok, contribTok, sbom)
                .key();
        Perf.end("  package-fingerprint", tp);
        boolean hit = ForecastSteps.present(s.actionCache, pkgKey);
        if (hit && !Files.isRegularFile(jar)) {
            // Publish the wiped jar's content sha from this key's record so downstream assembly,
            // native and test-stamp forecasts fingerprint the bytes the live restore produces.
            s.actionCache.lookup(pkgKey).ifPresent(rec -> rec.outputs().entrySet().stream()
                    .filter(e -> e.getKey().endsWith(jar.getFileName().toString()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .or(() -> rec.outputs().values().stream().findFirst())
                    .ifPresent(sha -> s.restored.pinJar(jar, sha)));
        }
        if (hit)
            return new TaskForecast.Task(
                    TaskNames.PACKAGE_JAR, TaskForecast.Status.CACHED, "", ForecastSteps.key8(pkgKey));
        // A source-less module's jar (its copied resources) is one sibling classpaths demand:
        // forecasting nothing would leave it unscheduled while its consumers fail on it, so a
        // missing jar with no record is scheduled until it exists.
        String miss = noSources
                ? "package · module has no sources"
                : s.mainResourceDrift ? "repackage · resources changed" : "repackage";
        return new TaskForecast.Task(TaskNames.PACKAGE_JAR, TaskForecast.Status.RUN, miss, null);
    }
}
