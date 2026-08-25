// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerNative.groovySources;
import static cc.jumpkick.runtime.PlannerNative.javaSources;
import static cc.jumpkick.runtime.PlannerNative.kotlinSources;
import static cc.jumpkick.runtime.PlannerPlugin.applicationSbom;
import static cc.jumpkick.runtime.PlannerPlugin.packagePlugin;
import static cc.jumpkick.runtime.PlannerSupport.contributionsToken;
import static cc.jumpkick.runtime.PlannerSupport.copyResources;
import static cc.jumpkick.runtime.PlannerSupport.existingContributedDirs;
import static cc.jumpkick.runtime.PlannerSupport.groovyCompileJar;
import static cc.jumpkick.runtime.PlannerSupport.mainStampClasspath;
import static cc.jumpkick.runtime.PlannerSupport.restorePackaged;
import static cc.jumpkick.runtime.PlannerSupport.stageClassesWithContributions;
import static cc.jumpkick.runtime.PlannerSupport.storePackaged;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.JarPackager;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.PluginModule;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.FreshnessStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * package-jar, freshness stamps, and mixed-module class assembly.
 */
public final class PlannerPackage {

    private PlannerPackage() {}

    static Task packageJarStep(
            BuildPlanner.Ctx cx,
            PluginBuild.Active pluginActive,
            PluginBuild.Declarations pluginDecls,
            Map<String, String> variantSecrets) {
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
        boolean groovyModule = cx.groovyModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        boolean javaStamp =
                mixedWithJava || TaskNames.COMPILE_JAVA.equals(mainCompile) || (!kotlinModule && !groovyModule);
        return Task.builder(TaskNames.PACKAGE_JAR)
                .stage(BuildStage.PACKAGE)
                .label("Packaging")
                .kind(TaskKind.CPU)
                .requires(packageRequires(in, pluginDecls, javaStamp, kotlinModule, groovyModule))
                .weight(() -> plan.get().pkg())
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path jarPath = layout.mainJar();
                    if (pluginDecls != null && pluginDecls.packager() != null && ownsMainArtifact(pluginActive)) {
                        // The packager's declared artifact extension replaces.jar (an APK, …).
                        jarPath = PluginBuild.mainArtifactPath(layout, pluginActive);
                        Files.createDirectories(jarPath.getParent());
                        packagePlugin(
                                ctx, in, cas, project, classes, jarPath, pluginActive, pluginDecls, variantSecrets);
                        return;
                    }
                    // Plain/assembly packaging merges plugin contributesClasses (Micronaut AOT, …);
                    // custom packagers (boot-jar) merge step outputs themselves. The dirs are only
                    // *listed* here — staging them is a copy, and it must not happen before the
                    // cache check below.
                    List<Path> contributed = new ArrayList<>(existingContributedDirs(pluginDecls, layout));
                    contributed.addAll(PlannerSupport.workerCodecClassDirs(in.dir(), project));
                    Files.createDirectories(jarPath.getParent());
                    String mainClass = PluginModule.mainClass(in.dir(), project);
                    // Application jars embed the lockfile-derived SBOM (libraries don't:
                    // their consumers' lockfiles are the truth for the final classpath).
                    byte[] sbom = null;
                    if (project.isApplication()) {
                        Lockfile sbomLock = ctx.get(LOCKFILE).orElse(null);
                        if (sbomLock != null) sbom = applicationSbom(project, sbomLock, cas);
                    }
                    // Packaging cache: the jar is a pure function of the main classes
                    // (resources already copied in), the plugin-contributed dirs merged over
                    // them, the main-class, the manifest, and the SBOM content (a lock change
                    // re-embeds).
                    List<String> tokens = List.of(
                            "classes:" + ClasspathFingerprint.entry(classes),
                            "contrib:" + contributionsToken(contributed),
                            "main:" + (mainClass == null ? "" : mainClass),
                            "sbom:" + (sbom == null ? "" : cc.jumpkick.host.Hashing.sha256Hex(sbom)),
                            "manifest:" + project.manifest());
                    String pkgTask = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAR, jarPath);
                    String pkgKey = ActionKey.forArtifact(pkgTask, BuildIdentity.cacheKeyVersion(), tokens);
                    if (restorePackaged(in.cache(), pkgKey, jarPath.getParent())) {
                        ctx.put(JAR_PATH, jarPath);
                        writeSidecarPom(project, layout, jarPath);
                        ctx.label(jarPath.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + jarPath.getFileName());
                    classes = stageClassesWithContributions(ctx, classes, contributed, layout);
                    JarPackager.JarRequest jarRequest = JarPackager.JarRequest.of(classes, jarPath);
                    if (mainClass != null && !mainClass.isBlank()) jarRequest = jarRequest.withMainClass(mainClass);
                    Map<String, String> jarAttrs = new LinkedHashMap<>(project.manifest());
                    if (sbom != null) {
                        jarAttrs.put("Sbom-Format", "CycloneDX");
                        jarAttrs.put("Sbom-Location", SBOM_JAR_ENTRY);
                        jarRequest = jarRequest.withExtraEntries(Map.of(SBOM_JAR_ENTRY, sbom));
                    }
                    if (!jarAttrs.isEmpty()) jarRequest = jarRequest.withAttributes(jarAttrs);
                    new JarPackager().packageJar(jarRequest);
                    storePackaged(
                            in.cache(),
                            pkgTask,
                            pkgKey,
                            tokens,
                            jarPath.getParent(),
                            List.of(jarPath),
                            !in.ephemeralActions());
                    ctx.put(JAR_PATH, jarPath);
                    writeSidecarPom(project, layout, jarPath);
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
            Files.write(pom, InstallPlans.renderedPomBytes(project, layout));
        } catch (IOException ignored) {
            // Launch still fails clearly if the POM is missing; packaging must not.
        }
    }

    /**
     * package-jar's requires: SPI BEFORE_PACKAGE (which itself waits on resources/tests), plus
     * every before-PACKAGE plugin step.
     */
    static String[] packageRequires(
            BuildPlanner.Inputs in,
            PluginBuild.Declarations decls,
            boolean useJava,
            boolean useKotlin,
            boolean useGroovy) {
        List<String> requires = new ArrayList<>();
        requires.add(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE);
        // Freshness stamps must stay on the package path so target-closure prune retains them.
        if (useJava) requires.add(TaskNames.WRITE_STAMP);
        if (useKotlin) requires.add(TaskNames.WRITE_STAMP_KOTLIN);
        if (useGroovy) requires.add(TaskNames.WRITE_STAMP_GROOVY);
        if (decls != null) {
            for (PluginBuild.TaskDecl step : decls.steps()) {
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
        AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.WRITE_STAMP)
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
                    @SuppressWarnings("unchecked")
                    List<Path> sources = (List<Path>) ctx.require(JAVA_SOURCES);
                    @SuppressWarnings("unchecked")
                    List<Path> baseClasspath = (List<Path>) ctx.require(CLASSPATH);
                    @SuppressWarnings("unchecked")
                    List<Path> processorCp =
                            (List<Path>) ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
                    // Match compile-java's freshness inputs exactly, including the processor path and
                    // the Scala stdlib jars (JK-2295).
                    List<Path> stampInputs = new ArrayList<>(mainStampClasspath(
                            baseClasspath,
                            processorCp,
                            mixed,
                            cx.mixedGroovy(),
                            ctx.require(LAYOUT),
                            cx.mixedGroovy() ? groovyCompileJar(ctx, cx.cas()) : null));
                    stampInputs.addAll(PlannerSupport.scalaStampLibs(ctx, in.dir(), compact, cas));
                    String actionKey = ctx.get(ACTION_KEY).orElse("");
                    FreshnessStamp.write(
                            javaOut,
                            BuildStamps.JAVA,
                            "compile-main",
                            actionKey,
                            sources,
                            stampInputs,
                            ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    static Task writeStampKotlinStep(BuildPlanner.Ctx cx) {
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
        return Task.builder(TaskNames.WRITE_STAMP_KOTLIN)
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
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> freshInputs = new ArrayList<>(kotlinSources(ctx));
                    if (mixedWithJava) freshInputs.addAll(javaSources(ctx));
                    FreshnessStamp.write(
                            classes,
                            BuildStamps.KOTLIN,
                            TaskNames.COMPILE_KOTLIN,
                            "",
                            freshInputs,
                            classpath,
                            ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    static Task writeStampGroovyStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        boolean mixedGroovy = cx.mixedGroovy();
        return Task.builder(TaskNames.WRITE_STAMP_GROOVY)
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
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> freshInputs = new ArrayList<>(groovySources(ctx));
                    if (mixedGroovy) freshInputs.addAll(javaSources(ctx));
                    FreshnessStamp.write(
                            classes,
                            BuildStamps.GROOVY,
                            TaskNames.COMPILE_GROOVY,
                            "",
                            freshInputs,
                            classpath,
                            ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    static Task assembleClassesStep(BuildPlanner.Ctx cx) {
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
        boolean mixedGroovy = cx.mixedGroovy();
        List<String> requires = new ArrayList<>();
        requires.add(TaskNames.COMPILE_JAVA);
        if (mixed) requires.add(TaskNames.COMPILE_KOTLIN);
        if (mixedGroovy) requires.add(TaskNames.COMPILE_GROOVY);
        return Task.builder(TaskNames.ASSEMBLE_CLASSES)
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
                    // Java output already lives in classes (java/main/); merge the other
                    // language dirs in. The Groovy merge runs after javac, so real Groovy
                    // classes overwrite any stub-compiled duplicates.
                    if (mixed) copyResources(ctx.require(LAYOUT).kotlinClassesDir(), classes);
                    if (mixedGroovy) copyResources(ctx.require(LAYOUT).groovyClassesDir(), classes);
                    ctx.progress(1);
                })
                .build();
    }

    static boolean settledOutcome(String outcome) {
        return outcome.equals("up-to-date") || outcome.equals("no-sources");
    }

    static boolean ownsMainArtifact(PluginBuild.Active active) {
        if (active == null) return true;
        var packaging = active.manifest().packaging();
        if (packaging == null) return true;
        return packaging.resolve(active.config()).mainArtifact();
    }
}
