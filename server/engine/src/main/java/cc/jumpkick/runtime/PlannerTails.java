// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerNative.nativeStep;
import static cc.jumpkick.runtime.PlannerPlugin.applicationSbom;
import static cc.jumpkick.runtime.PlannerPlugin.packagePlugin;
import static cc.jumpkick.runtime.PlannerSupport.assemblyDependencyJars;
import static cc.jumpkick.runtime.PlannerSupport.contributionsToken;
import static cc.jumpkick.runtime.PlannerSupport.packagedClasses;
import static cc.jumpkick.runtime.PlannerSupport.packagedDirs;
import static cc.jumpkick.runtime.PlannerSupport.pluginDeclarationsFor;
import static cc.jumpkick.runtime.PlannerSupport.restorePackaged;
import static cc.jumpkick.runtime.PlannerSupport.storePackaged;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.SourcesJar;
import cc.jumpkick.compile.AssemblyPackager;
import cc.jumpkick.compile.PackagedTree;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.InputGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Declared packaging tails (assembly / minified / sources / javadoc) and terminal re-root.
 */
public final class PlannerTails {

    private PlannerTails() {}

    public static void appendDeclaredTails(BuildPlan.Builder b, BuildPlanner.Inputs in) {
        appendDeclaredTails(b, in, null, true);
    }

    /** As {@link #appendDeclaredTails(BuildPlan.Builder, Inputs)} with an explicit Graal home. */
    public static void appendDeclaredTails(BuildPlan.Builder b, BuildPlanner.Inputs in, @Nullable Path graalHome) {
        appendDeclaredTails(b, in, graalHome, true);
    }

    /**
     * As {@link #appendDeclaredTails(BuildPlan.Builder, Inputs, Path)} with {@code allowNative} for
     * workspace prereq modules that must stay JVM-only.
     *
     * <p>{@link BuildPlan.Builder#terminal} keeps only the named task and its <em>upstream</em>
     * requires-closure. Core ends at {@code package-jar}; assembly / native / sources / javadoc are
     * <em>downstream</em> of that terminal, so they must re-root the terminal (via a synthetic
     * join when more than one tail is present) or {@link BuildPlan.Builder#build()} prunes them
     * and fat jars / native images never run on {@code jk build}.
     */
    public static void appendDeclaredTails(
            BuildPlan.Builder b, BuildPlanner.Inputs in, @Nullable Path graalHome, boolean allowNative) {
        // Test and compile plans stop at run-tests / write-stamp: package-jar is not in the
        // plan for tails to hang off, and re-rooting the terminal would run packaging (or
        // fail validation) under `jk test` / `jk compile`.
        if (in.testOnly() || in.compileOnly()) return;
        try {
            JkBuild project = applyAssemblyOverride(JkBuildParser.parse(in.buildFile()), in.session());
            // A coordinator root is the third plan with no package-jar to hang a tail off. Its
            // unit exists to run the workspace's own build logic; re-rooting the
            // terminal here would require a step its plan never had.
            if (CompileSupport.coordinatorOnly(project, in.dir())) return;
            List<String> leaves = new ArrayList<>();
            // The join sits at the latest stage it joins. Hard-coding PACKAGE made a plan with
            // both an assembly tail and a native tail fail validation — the join would be
            // requiring native-image, which is a later stage than itself.
            BuildStage joinStage = BuildStage.PACKAGE;
            // A guard run packages the jars the output lane reads and delivers nothing beside them.
            boolean deliver = !in.session().lanesOnly();
            if (deliver && project.assembly()) {
                b.addTask(assemblyStep(in.cache(), in.lockFile(), !in.ephemeralActions()));
                leaves.add(TaskNames.PACKAGE_ASSEMBLY);
            }
            if (deliver && PlannerWar.declared(project)) {
                b.addTask(PlannerWar.warStep(in.cache(), in.lockFile(), !in.ephemeralActions()));
                leaves.add(TaskNames.PACKAGE_WAR);
            }
            if (deliver && project.minified()) {
                b.addTask(minifiedStep(in, graalHome));
                leaves.add(TaskNames.PACKAGE_MINIFIED);
            }
            if (deliver && allowNative && project.nativeMode() == JkBuild.NativeMode.ALWAYS) {
                b.addTask(nativeStep(in.dir(), in.cache(), in.lockFile(), in.jdksDir(), graalHome, null, List.of()));
                leaves.add(TaskNames.NATIVE_IMAGE);
                joinStage = BuildStage.NATIVE;
            }
            // A library ships what Maven Central requires beside its jar: sources and javadoc.
            if (deliver && PackagingKeys.libraryArtifacts(project, in.dir())) {
                b.addTask(sourcesStep(in.cache(), !in.ephemeralActions()));
                leaves.add(TaskNames.PACKAGE_SOURCES);
                if (project.project().javadocMode().enabled()) {
                    b.addTask(javadocStep(in.cache(), !in.ephemeralActions()));
                    leaves.add(TaskNames.PACKAGE_JAVADOC);
                }
            }
            // The output lane reads what the tails wrote, so it hangs off every packaging leaf and
            // re-roots the terminal; a plan without an output rule never plans it.
            List<String> joined = new ArrayList<>(leaves.isEmpty() ? List.of(TaskNames.PACKAGE_JAR) : leaves);
            boolean outputLane = PlannerResources.invocationRoot(in.dir()) && PlannerGuards.outputLaneWanted(in);
            if (outputLane) {
                b.addTask(PlannerGuards.outputStep(PlannerGuards.env(in), joined.toArray(String[]::new)));
                joined = new ArrayList<>(List.of(TaskNames.GUARD_OUTPUT));
                if (joinStage.ordinal() < BuildStage.NATIVE.ordinal()) joinStage = BuildStage.NATIVE;
            }
            join(b, in, joined, joinStage, !leaves.isEmpty() || outputLane);
        } catch (JkBuildParseException | IOException ignored) {
            // Core planning parses the same file and has already reported an unreadable or
            // malformed jk.toml loudly; re-reporting here would double the diagnostic. Anything
            // else must propagate — swallowing it silently dropped -all.jar/-min.jar/native
            // tails from the plan while the build still reported success.
        }
    }
    /**
     * A test run's plan for a module whose jar a sibling compiles against: {@code package-jar}
     * and the tests, and none of the delivery tails — the sibling reads the jar, never the javadoc,
     * sources, fat jar or native image. {@code launched} adds {@code package-assembly}: a sibling's
     * suite runs this module's fat jar.
     */
    public static void joinTests(BuildPlan.Builder b, BuildPlanner.Inputs in, boolean launched) {
        List<String> joined = new ArrayList<>(List.of(TaskNames.PACKAGE_JAR));
        if (launched) {
            b.addTask(assemblyStep(in.cache(), in.lockFile(), !in.ephemeralActions()));
            joined.add(TaskNames.PACKAGE_ASSEMBLY);
        }
        join(b, in, joined, BuildStage.PACKAGE, launched);
    }

    /**
     * run-tests is a leaf, not a gate: packaging does not require it, so without joining it the
     * terminal's requires-closure would prune the suite out entirely. Joining keeps the tests
     * scheduled, concurrently with packaging, while a failure still fails the plan.
     */
    private static void join(
            BuildPlan.Builder b, BuildPlanner.Inputs in, List<String> joined, BuildStage stage, boolean reroot) {
        if (!PlannerResources.skipJUnit(in)) joined.add(TaskNames.RUN_TESTS);
        if (PlannerResources.runGuardScripts(in) && PlannerResources.invocationRoot(in.dir())) {
            joined.add(TaskNames.BUILD_LOGIC_GUARD);
        }
        if (joined.size() == 1 && !reroot) return; // package-jar stays terminal
        if (joined.size() == 1) {
            b.terminal(joined.get(0));
            return;
        }
        b.addTask(Task.builder(DELIVER_JOIN)
                .forecast(StepForecast.RIDES)
                .stage(stage)
                .requires(joined.toArray(String[]::new))
                .weight(0)
                .ticks(0)
                .execute(ctx -> {
                    /* join only */
                })
                .build());
        b.terminal(DELIVER_JOIN);
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
    static Task minifiedStep(BuildPlanner.Inputs in, @Nullable Path graalHome) {
        return Task.builder(TaskNames.PACKAGE_MINIFIED)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.PACKAGE)
                .label("Minify")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PACKAGE_ASSEMBLY)
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    // The minifier packages beside the main artifact, so among the module's plugins
                    // it is the one with a [packaging] table that does not replace that artifact.
                    ActivePlugin minifier = ActivePlugins.of(project, layout.moduleRoot()).stream()
                            .filter(a -> a.manifest().packaging() != null && !ActivePlugins.packagesMainArtifact(a))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "[application] minified = true requires the minified plugin"
                                            + " — add a [minified] table or remove `minified`"));
                    PluginDeclarations decls = PluginBuild.declarations(
                            minifier, project, layout.moduleRoot(), in.cache(), layout.moduleTargetDir());
                    if (decls.packager() == null) {
                        throw new IllegalStateException("[application] minified = true, but the plugin `"
                                + minifier.manifest().id() + "` declares no packager");
                    }
                    packagePlugin(
                            ctx,
                            in,
                            JkStores.storeCas(),
                            new PluginBuild.StepTools(),
                            project,
                            ctx.require(MAIN_CLASSES),
                            layout.minifiedJar(),
                            minifier,
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
                .forecast(StepForecast.priced(s -> forecastAssembly(ForecastScope.of(s))))
                .stage(BuildStage.PACKAGE)
                .label("Assembly")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(() -> EffortWeights.assemblyWeight(Objects.requireNonNull(lockFile.getParent(), "lock dir")))
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
                            packagedDirs(pluginDeclarationsFor(project, layout, cache), layout, project);
                    Path assemblyJar = layout.assemblyJar();
                    // Module-scoped runtime closure (not the whole workspace lock).
                    List<Path> depJars = assemblyDependencyJars(layout.moduleRoot(), project, lockFile, cache);
                    // Packaging cache: the fat jar's key comes from PackagingKeys, the same body
                    // the forecast calls, so a worker module can forecast up-to-date. The jar is
                    // written with exactly the attributes it is keyed on.
                    Map<String, String> assemblyAttrs = PackagingKeys.assemblyAttributes(layout.moduleRoot(), project);
                    List<Path> guarded = new ArrayList<>(List.of(classes));
                    guarded.addAll(contributed);
                    guarded.addAll(depJars);
                    InputGuard guard = InputGuard.of(guarded);
                    PackagingKeys.Keyed keyed = PackagingKeys.assembly(
                            assemblyJar,
                            layout.moduleRoot(),
                            project,
                            assemblyAttrs,
                            guard.token(classes),
                            contributionsToken(contributed),
                            ClasspathFingerprint.of(depJars));
                    String shTask = keyed.taskId();
                    String shKey = keyed.key();
                    if (restorePackaged(cache, shKey, assemblyJar.getParent())) {
                        ctx.label(assemblyJar.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + assemblyJar.getFileName());
                    PackagedTree tree = packagedClasses(classes, contributed);
                    byte[] assemblySbom = null;
                    if (Files.exists(lockFile)) {
                        assemblySbom = applicationSbom(project, LockfileReader.read(lockFile));
                        assemblyAttrs.put("Sbom-Format", "CycloneDX");
                        assemblyAttrs.put("Sbom-Location", SBOM_JAR_ENTRY);
                    }
                    new AssemblyPackager()
                            .packageAssembly(new AssemblyPackager.AssemblyRequest(
                                    tree,
                                    depJars,
                                    assemblyJar,
                                    PackagingKeys.mainClass(layout.moduleRoot(), project),
                                    assemblyAttrs,
                                    assemblySbom == null ? Map.of() : Map.of(SBOM_JAR_ENTRY, assemblySbom),
                                    PackagingKeys.relocate(project),
                                    0L));
                    storePackaged(
                            ctx,
                            guard,
                            cache,
                            shTask,
                            shKey,
                            keyed.tokens(),
                            assemblyJar.getParent(),
                            List.of(assemblyJar),
                            persist);
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The assembly's manifest attributes: the {@code [manifest]} table, plus {@link
     * BuildIdentity#BUILD_TIME_ATTRIBUTE} when the module is the one jk installs as its own product
     * lib and {@code worktree} describes its checkout. The value is the checkout's commit time, so
     * two engines of one version are ordered by the commit they were built from rather than by when
     * a jar was copied into place. The attributes are the assembly's {@code manifest:} key token
     * ({@link PackagingKeys#assembly}), so the same commit packages the same bytes on every host
     * whatever its action cache holds. Any other assembly, and one built outside a checkout,
     * carries what the table says and nothing more.
     */
    static Map<String, String> assemblyAttributes(JkBuild project, Optional<GitFetcher.Worktree> worktree) {
        Map<String, String> attrs = new LinkedHashMap<>(project.manifest());
        if (PlannerVersions.declared(project)) attrs.putIfAbsent(PlannerVersions.MULTI_RELEASE, "true");
        JkBuild.Install install = project.install();
        if (install != null && install.productLib() != null && worktree.isPresent()) {
            attrs.put(
                    BuildIdentity.BUILD_TIME_ATTRIBUTE,
                    worktree.get().commitTime().toString());
        }
        return attrs;
    }

    /** Sources-jar packaging — writes {@code <artifact>-<version>-sources.jar} to the artifact dir. */
    public static Task sourcesStep(Path cache) {
        return sourcesStep(cache, true);
    }

    /** As {@link #sourcesStep(Path)}; {@code persist=false} keeps verify-scratch keys out of the cache. */
    public static Task sourcesStep(Path cache, boolean persist) {
        return Task.builder(TaskNames.PACKAGE_SOURCES)
                .forecast(StepForecast.RIDES)
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
                    // Cache key: hash of all source roots' content. A root that will not read fails
                    // the step rather than keying as empty.
                    InputGuard guard = InputGuard.of(sourceRoots);
                    List<String> rootTokens = new ArrayList<>();
                    for (Path r : sourceRoots) rootTokens.add(guard.token(r));
                    String srcHash = String.join(";", rootTokens);
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
                    storePackaged(
                            ctx, guard, cache, task, key, tokens, sourcesJar.getParent(), List.of(sourcesJar), persist);
                    ctx.progress(1);
                })
                .build();
    }

    /** Javadoc-jar packaging — a library tail beside {@link #sourcesStep}; see {@link PlannerJavadoc}. */
    public static Task javadocStep(Path cache) {
        return javadocStep(cache, true);
    }

    /** As {@link #javadocStep(Path)}; {@code persist=false} keeps verify-scratch keys out of the cache. */
    public static Task javadocStep(Path cache, boolean persist) {
        return Task.builder(TaskNames.PACKAGE_JAVADOC)
                .forecast(StepForecast.priced(s -> forecastJavadoc(ForecastScope.of(s))))
                .stage(BuildStage.PACKAGE)
                .label("Javadoc")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(W_JAVADOC)
                .ticks(1)
                .execute(ctx -> PlannerJavadoc.run(ctx, cache, JkStores.storeCas(), persist))
                .build();
    }

    // ---- forecast ---------------------------------------------------------------------------

    /** package-assembly's price, under the key {@link PackagingKeys#assembly} derives for the live step. */
    static TaskForecast.@Nullable Task forecastAssembly(ForecastScope s) throws Exception {
        s.projectOwnOutputs();
        return ForecastPackagingTails.assembly(
                s.project,
                s.dir,
                s.prepared(),
                s.lockFile,
                s.actionCache,
                s.cache,
                s.compileMainKey,
                s.restored,
                s.knownResourceDrift,
                s.compileDirty);
    }

    /** package-javadoc's price, under the key {@link PackagingKeys#javadoc} derives for the live step. */
    static TaskForecast.@Nullable Task forecastJavadoc(ForecastScope s) throws Exception {
        s.projectOwnOutputs();
        return ForecastPackagingTails.javadoc(
                s.project, s.dir, s.prepared(), s.mainCp, s.restored.abiToken(), s.actionCache, s.cas, s.compileDirty);
    }
}
