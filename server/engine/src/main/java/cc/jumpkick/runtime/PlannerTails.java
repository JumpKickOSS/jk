// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.SourcesJar;
import cc.jumpkick.compile.AssemblyPackager;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declared packaging tails (assembly / minified / sources) and terminal re-root.
 */
public final class PlannerTails {

    private PlannerTails() {}

    public static void appendDeclaredTails(BuildPlan.Builder b, BuildPlanner.Inputs in) {
        appendDeclaredTails(b, in, null, true);
    }

    /** As {@link #appendDeclaredTails(BuildPlan.Builder, Inputs)} with an explicit Graal home. */
    public static void appendDeclaredTails(BuildPlan.Builder b, BuildPlanner.Inputs in, Path graalHome) {
        appendDeclaredTails(b, in, graalHome, true);
    }

    /**
     * As {@link #appendDeclaredTails(BuildPlan.Builder, Inputs, Path)} with {@code allowNative} for
     * workspace prereq modules that must stay JVM-only.
     *
     * <p>{@link BuildPlan.Builder#terminal} keeps only the named task and its <em>upstream</em>
     * requires-closure. Core ends at {@code package-jar}; assembly / native / sources-jar are
     * <em>downstream</em> of that terminal, so they must re-root the terminal (via a synthetic
     * join when more than one tail is present) or {@link BuildPlan.Builder#build()} prunes them
     * and fat jars / native images never run on {@code jk build}.
     */
    public static void appendDeclaredTails(
            BuildPlan.Builder b, BuildPlanner.Inputs in, Path graalHome, boolean allowNative) {
        // Test and compile plans stop at run-tests / write-stamp: package-jar is not in the
        // plan for tails to hang off, and re-rooting the terminal would run packaging (or
        // fail validation) under `jk test` / `jk compile`.
        if (in.testOnly() || in.compileOnly()) return;
        try {
            JkBuild project = applyAssemblyOverride(JkBuildParser.parse(in.buildFile()), in.session());
            List<String> leaves = new ArrayList<>();
            // The join sits at the latest stage it joins. Hard-coding PACKAGE made a plan with
            // both an assembly tail and a native tail fail validation — the join would be
            // requiring native-image, which is a later stage than itself.
            BuildStage joinStage = BuildStage.PACKAGE;
            if (project.assembly()) {
                b.addTask(assemblyStep(in.cache(), in.lockFile(), !in.ephemeralActions()));
                leaves.add(TaskNames.PACKAGE_ASSEMBLY);
            }
            if (project.minified()) {
                b.addTask(minifiedStep(in, graalHome));
                leaves.add(TaskNames.PACKAGE_MINIFIED);
            }
            if (allowNative && project.nativeMode() == JkBuild.NativeMode.ALWAYS) {
                b.addTask(nativeStep(in.dir(), in.cache(), in.lockFile(), in.jdksDir(), graalHome, null, List.of()));
                leaves.add(TaskNames.NATIVE_IMAGE);
                joinStage = BuildStage.NATIVE;
            }
            if (project.project().sourcesMode() == JkBuild.SourcesMode.ALWAYS) {
                b.addTask(sourcesStep(in.cache(), !in.ephemeralActions()));
                leaves.add(TaskNames.PACKAGE_SOURCES);
            }
            // run-tests is a LEAF, not a gate (JK-2211): packaging no longer requires it, so
            // without joining it here the terminal's requires-closure would prune the suite
            // out of `jk build` entirely. Joining keeps tests scheduled — concurrently with
            // packaging — while a failure still fails the plan.
            List<String> joined = new ArrayList<>(leaves.isEmpty() ? List.of(TaskNames.PACKAGE_JAR) : leaves);
            if (!in.skipTests()) {
                joined.add(TaskNames.RUN_TESTS);
            }
            if (joined.size() == 1 && leaves.isEmpty()) return; // skip-tests, no tails: package-jar stays terminal
            if (joined.size() == 1) {
                b.terminal(joined.get(0));
                return;
            }
            // Independent branches (package tails + the test leaf) — join so prune keeps each.
            b.addTask(Task.builder(DELIVER_JOIN)
                    .stage(joinStage)
                    .requires(joined.toArray(String[]::new))
                    .weight(0)
                    .ticks(0)
                    .execute(ctx -> {
                        /* join only */
                    })
                    .build());
            b.terminal(DELIVER_JOIN);
        } catch (JkBuildParseException | IOException ignored) {
            // Core planning parses the same file and has already reported an unreadable or
            // malformed jk.toml loudly; re-reporting here would double the diagnostic. Anything
            // else must propagate — swallowing it silently dropped -all.jar/-min.jar/native
            // tails from the plan while the build still reported success.
        }
    }
    // ---- tail steps ----------------------------------------------------

    /**
     * {@code -min.jar} — the R8-minified artifact, produced by a packager that does not own the
     * module's main artifact.
     *
     * <p>A tail beside {@code package-assembly}, not a replacement for {@code package-jar}:
     * artifacts are additive, so a minified build ships the thin jar and the fat jar too. That is
     * deliberate — a minified jar can be silently wrong for an application that resolves types by
     * runtime generic matching, and the fat jar beside it is what makes that testable.
     */
    static Task minifiedStep(BuildPlanner.Inputs in, Path graalHome) {
        return Task.builder(TaskNames.PACKAGE_MINIFIED)
                .stage(BuildStage.PACKAGE)
                .label("Minify")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PACKAGE_ASSEMBLY)
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    var active = PluginBuild.activeCodePlugin(project, layout.moduleRoot());
                    if (active.isEmpty()) {
                        throw new IllegalStateException("[application] minified = true requires the minified plugin"
                                + " — add a [minified] table or remove `minified`");
                    }
                    PluginBuild.Declarations decls = PluginBuild.declarations(
                            active.get(), project, layout.moduleRoot(), in.cache(), layout.moduleTargetDir());
                    if (decls.packager() == null) {
                        throw new IllegalStateException("[application] minified = true, but the active plugin `"
                                + active.get().manifest().id() + "` declares no packager");
                    }
                    packagePlugin(
                            ctx,
                            in,
                            JkStores.cas(in.cache()),
                            project,
                            ctx.require(MAIN_CLASSES),
                            layout.minifiedJar(),
                            active.get(),
                            decls,
                            Map.of());
                    ctx.progress(1);
                })
                .build();
    }

    /** Assembly-jar packaging — requires package-jar. */
    public static Task assemblyStep(Path cache, Path lockFile) {
        return assemblyStep(cache, lockFile, true);
    }

    /** As {@link #assemblyStep(Path, Path)}; {@code persist=false} keeps verify-scratch keys out of the cache. */
    public static Task assemblyStep(Path cache, Path lockFile, boolean persist) {
        return Task.builder(TaskNames.PACKAGE_ASSEMBLY)
                .stage(BuildStage.PACKAGE)
                .label("Assembly")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(() -> EffortWeights.assemblyWeight(lockFile.getParent()))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    // Listed, not staged — see packageJarStep. package-jar runs first (hard
                    // requires edge) and publishes its stage, so the copy below is usually a
                    // no-op; when package-jar restored from cache there is nothing to reuse and
                    // this task stages for itself.

                    // Declarations are re-derived rather than threaded in from the plan: this is
                    // a tail step assembled by appendDeclaredTails, which has no plugin context,
                    // and hoisting the lookup into plan construction would risk forking the
                    // plugin worker while merely *planning*. PluginBuild.declarations is
                    // file-cached under the module target, so this is a read, not a fork.
                    List<Path> contributed =
                            existingContributedDirs(pluginDeclarationsFor(project, layout, cache), layout);
                    Path assemblyJar = layout.assemblyJar();
                    // Module-scoped runtime closure (not the whole workspace lock).
                    List<Path> depJars = assemblyDependencyJars(layout.moduleRoot(), project, lockFile, cache);
                    // Packaging cache: the fat jar is a pure function of the main
                    // classes, the plugin-contributed dirs merged over them, the bundled
                    // dependency jars' content, the main-class, and the manifest.
                    List<String> tokens = List.of(
                            "classes:" + ClasspathFingerprint.entry(classes),
                            "contrib:" + contributionsToken(contributed),
                            "deps:" + ClasspathFingerprint.of(depJars),
                            "main:" + (project.mainClass() == null ? "" : project.mainClass()),
                            "manifest:" + project.manifest(),
                            "packaging:fat"); // distinct from shrink / thin package-jar
                    String shTask = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_ASSEMBLY, assemblyJar);
                    String shKey = ActionKey.forArtifact(shTask, BuildIdentity.cacheKeyVersion(), tokens);
                    if (restorePackaged(cache, shKey, assemblyJar.getParent())) {
                        ctx.label(assemblyJar.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + assemblyJar.getFileName());
                    classes = stageClassesWithContributions(ctx, classes, contributed, layout);
                    byte[] assemblySbom = null;
                    Map<String, String> assemblyAttrs = new LinkedHashMap<>(project.manifest());
                    if (Files.exists(lockFile)) {
                        assemblySbom = applicationSbom(project, LockfileReader.read(lockFile), JkStores.cas(cache));
                        assemblyAttrs.put("Sbom-Format", "CycloneDX");
                        assemblyAttrs.put("Sbom-Location", SBOM_JAR_ENTRY);
                    }
                    new AssemblyPackager()
                            .packageAssembly(new AssemblyPackager.AssemblyRequest(
                                    classes,
                                    depJars,
                                    assemblyJar,
                                    project.mainClass(),
                                    assemblyAttrs,
                                    assemblySbom == null ? Map.of() : Map.of(SBOM_JAR_ENTRY, assemblySbom),
                                    0L));
                    storePackaged(cache, shTask, shKey, tokens, assemblyJar.getParent(), List.of(assemblyJar), persist);
                    ctx.progress(1);
                })
                .build();
    }

    /** Sources-jar packaging — writes {@code <artifact>-<version>-sources.jar} to the artifact dir. */
    public static Task sourcesStep(Path cache) {
        return sourcesStep(cache, true);
    }

    /** As {@link #sourcesStep(Path)}; {@code persist=false} keeps verify-scratch keys out of the cache. */
    public static Task sourcesStep(Path cache, boolean persist) {
        return Task.builder(TaskNames.PACKAGE_SOURCES)
                .stage(BuildStage.PACKAGE)
                .label("Sources")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(W_SOURCES)
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path moduleRoot = layout.moduleRoot();
                    Path sourcesJar = layout.sourcesJar();
                    // Source roots: simple layout uses src/, traditional uses src/main/java + src/main/kotlin.
                    boolean compact = CompileSupport.isSimpleLayout(project.project(), moduleRoot);
                    List<Path> sourceRoots = compact
                            ? List.of(moduleRoot.resolve("src"))
                            : List.of(moduleRoot.resolve("src/main/java"), moduleRoot.resolve("src/main/kotlin"));
                    // Cache key: hash of all source roots' content.
                    String srcHash = String.join(
                            ";",
                            sourceRoots.stream()
                                    .map(r -> {
                                        try {
                                            return ClasspathFingerprint.entry(r);
                                        } catch (Exception e) {
                                            return "";
                                        }
                                    })
                                    .toList());
                    List<String> tokens = List.of("sources:" + srcHash);
                    String task = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_SOURCES, sourcesJar);
                    String key = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokens);
                    if (restorePackaged(cache, key, sourcesJar.getParent())) {
                        ctx.label(sourcesJar.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + sourcesJar.getFileName());
                    byte[] bytes = SourcesJar.build(sourceRoots);
                    Files.createDirectories(sourcesJar.getParent());
                    Files.write(sourcesJar, bytes);
                    storePackaged(cache, task, key, tokens, sourcesJar.getParent(), List.of(sourcesJar), persist);
                    ctx.progress(1);
                })
                .build();
    }
}
