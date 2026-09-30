// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.CLASSPATH;
import static cc.jumpkick.runtime.BuildPlanner.JAVAC_ARGS;
import static cc.jumpkick.runtime.BuildPlanner.JAVAC_PROCESSOR_CP;
import static cc.jumpkick.runtime.BuildPlanner.JAVA_HOME;
import static cc.jumpkick.runtime.BuildPlanner.LAYOUT;
import static cc.jumpkick.runtime.BuildPlanner.MAIN_CLASSES;
import static cc.jumpkick.runtime.BuildPlanner.PROCESSOR_CP;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;
import static cc.jumpkick.runtime.BuildPlanner.RELEASE;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ReleaseSources;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code compile-versions}: each {@code [multi-release]} source set compiled against the main classes
 * into {@link BuildLayout#versionedClassesDir}, which the jar carries under {@code
 * META-INF/versions/<N>/} and the module's own tests see ahead of the main classes when their JDK
 * is at least {@code N}.
 *
 * <p>An entry compiles at {@code --release max(N, java)}: javac cannot read the main classes at a
 * lower level, and a JDK that loads the jar is already at least the module's own level.
 */
public final class PlannerVersions {

    /** The jar attribute a JDK needs before it reads {@code META-INF/versions/}. */
    static final String MULTI_RELEASE = Attributes.Name.MULTI_RELEASE.toString();

    private static final String MODULE_INFO = "module-info.java";
    private static final Pattern MODULE_NAME = Pattern.compile("\\bmodule\\s+([\\w.]+)\\s*\\{");
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    private PlannerVersions() {}

    public static boolean declared(@Nullable JkBuild project) {
        return project != null && project.build().isMultiRelease();
    }

    /** The {@code --release} an entry compiles at: its own, raised to the module's. */
    static int compileRelease(ReleaseSources entry, int moduleRelease) {
        return Math.max(entry.release(), moduleRelease);
    }

    /** The entry's {@code .java} sources, every declared root walked; a missing root contributes none. */
    static List<Path> sources(ReleaseSources entry, Path moduleDir) throws IOException {
        Set<Path> out = new LinkedHashSet<>();
        for (String rel : entry.src()) {
            Path root = moduleDir.resolve(rel).normalize();
            if (Files.isDirectory(root)) out.addAll(CompileSupport.collectJavaSources(root));
        }
        return List.copyOf(out);
    }

    /**
     * The versioned output dirs, highest release first — the order a JDK consults them. Each one a
     * test JVM of {@code feature} reads comes before the main classes; the rest stay off its
     * classpath.
     */
    static List<Path> outputDirs(JkBuild project, BuildLayout layout, int feature) {
        List<Path> out = new ArrayList<>();
        List<ReleaseSources> entries = project.build().multiRelease();
        for (int i = entries.size() - 1; i >= 0; i--) {
            int release = entries.get(i).release();
            if (release <= feature) out.add(layout.versionedClassesDir(release));
        }
        return out;
    }

    /**
     * {@code cp} plus every declared versioned output dir: what the run-tests stamp hashes, so a
     * versioned source edit re-runs the suite. The launch orders them itself ({@link #launchClasspath}).
     */
    public static List<Path> withOwnVersions(JkBuild project, BuildLayout layout, List<Path> cp) {
        if (!declared(project)) return cp;
        List<Path> out = new ArrayList<>(cp);
        for (Path dir : outputDirs(project, layout, Integer.MAX_VALUE)) {
            if (!out.contains(dir)) out.add(dir);
        }
        return out;
    }

    /**
     * The test JVM's classpath with the versioned dirs where a JDK of {@code feature} reads a
     * multi-release jar's entries: ahead of {@code mainClasses}, highest release first. A directory
     * classpath has no {@code META-INF/versions} lookup of its own, so the order is what gives the
     * suite the classes the packaged jar would.
     */
    static List<Path> launchClasspath(
            JkBuild project, BuildLayout layout, int feature, Path mainClasses, List<Path> rest) {
        List<Path> out = new ArrayList<>();
        Set<Path> versioned = new HashSet<>(outputDirs(project, layout, Integer.MAX_VALUE));
        if (declared(project)) out.addAll(outputDirs(project, layout, feature));
        out.add(mainClasses);
        for (Path p : rest) {
            if (!versioned.contains(p)) out.add(p);
        }
        return out;
    }

    /** The root packaging merges over the main classes, when the module declares {@code [multi-release]} and it exists. */
    static List<Path> packagedRoot(@Nullable JkBuild project, BuildLayout layout) {
        if (!declared(project)) return List.of();
        Path root = layout.versionedClassesRoot();
        return Files.isDirectory(root) ? List.of(root) : List.of();
    }

    /**
     * compile-versions' request for one entry — the build's javac invocation and the forecast's key,
     * from one body. A source set with a {@code module-info.java} is the versioned descriptor of the
     * module the main classes form, so those classes patch it, as Maven's compiler plugin does.
     */
    public static CompileRequest versionsCompileRequest(
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorPath,
            Path outputDir,
            int release,
            List<String> javacArgs,
            JavacConfig javac,
            Path javaHome,
            Path mainClasses) {
        List<String> options = new ArrayList<>(PlannerCompile.javacOptions(javacArgs, javac));
        String module = moduleName(sources);
        if (module != null) {
            options.add("--patch-module");
            options.add(module + "=" + mainClasses.toAbsolutePath());
        }
        return CompileRequest.builder()
                .sources(sources)
                .classpath(classpath)
                .outputDir(outputDir)
                .release(release)
                .extraOptions(List.copyOf(options))
                .javaHome(javaHome)
                .processorPath(PlannerCompile.effectiveProcessorPath(processorPath, classpath))
                .build();
    }

    /** The module a source set's {@code module-info.java} declares, or null when it has none. */
    static @Nullable String moduleName(List<Path> sources) {
        for (Path source : sources) {
            Path name = source.getFileName();
            if (name == null || !MODULE_INFO.equals(name.toString())) continue;
            try {
                String text = COMMENT.matcher(Files.readString(source)).replaceAll(" ");
                Matcher m = MODULE_NAME.matcher(text);
                if (m.find()) return m.group(1);
            } catch (IOException unreadable) {
                return null;
            }
        }
        return null;
    }

    /** The classpath one entry compiles against: the lower releases' outputs, highest first, then main, then the module's. */
    private static List<Path> classpath(
            JkBuild project, BuildLayout layout, ReleaseSources entry, Path mainClasses, List<Path> base) {
        List<Path> cp = new ArrayList<>(outputDirs(project, layout, entry.release() - 1));
        cp.add(mainClasses);
        cp.addAll(base);
        return cp;
    }

    /** The forecast's caches and resolver, as {@link #addForecast} reads them. */
    record ForecastEnv(
            Path cache,
            ActionCache actionCache,
            @Nullable Path workerJar,
            RestoredOutputs restored,
            ClasspathResolver resolver) {}

    /**
     * Forecast {@code compile-versions} into {@code steps}; whether it is dirty, so packaging and
     * the suite cascade. {@code mainCp} is compile-main's classpath, empty when the forecast did not
     * derive one.
     */
    static boolean addForecast(
            List<TaskForecast.Task> steps,
            boolean compileDirty,
            JkBuild project,
            Path dir,
            ModuleForecast.Prepared prepared,
            List<Path> mainCp,
            ForecastEnv env)
            throws IOException {
        if (!declared(project)) return false;
        if (compileDirty) {
            steps.add(new TaskForecast.Task(
                    TaskNames.COMPILE_VERSIONS, TaskForecast.Status.RUN, "recompile · main changed", null));
            return true;
        }
        BuildLayout layout = prepared.layout();
        List<Path> base = mainCp.isEmpty()
                ? PlannerSupport.mainCompileClasspath(
                        dir,
                        project,
                        prepared.lock(),
                        env.resolver(),
                        WorkspaceClasspath.resolve(dir, project, WorkspaceClasspath.COMPILE_SCOPES),
                        false)
                : mainCp;
        boolean dirty = false;
        for (ReleaseSources entry : project.build().multiRelease()) {
            List<Path> sources = sources(entry, dir);
            if (sources.isEmpty()) continue;
            Path out = layout.versionedClassesDir(entry.release());
            CompileRequest req = versionsCompileRequest(
                    sources,
                    classpath(project, layout, entry, layout.classesDir(), base),
                    prepared.processorCp(),
                    out,
                    compileRelease(entry, prepared.release()),
                    prepared.javacArgs(),
                    project.build().javac(),
                    prepared.javaHome(),
                    layout.classesDir());
            var pred = JavaCompile.predict(
                    ActionKey.qualifiedTaskId(TaskNames.COMPILE_VERSIONS, out),
                    req,
                    BuildIdentity.cacheKeyVersion(),
                    env.actionCache(),
                    ActionKey.stateDir(
                            ActionTree.INCREMENTAL_JAVA.under(CacheTree.ACTIONS.under(env.cache())),
                            TaskNames.COMPILE_VERSIONS,
                            out),
                    env.workerJar(),
                    generatedDir(layout, entry),
                    WorkerEnv.forModule(project.build().env(), layout.moduleRoot(), layout.moduleTargetDir()),
                    env.restored().abiToken());
            TaskForecast.Task step = ForecastSteps.compileStep(TaskNames.COMPILE_VERSIONS, pred, false, req);
            if (!step.cached()) {
                steps.add(step);
                return true;
            }
            dirty |= !ModuleOutputs.compileOutputsOnDisk(env.actionCache(), pred.actionKey(), out);
        }
        steps.add(
                dirty
                        ? new TaskForecast.Task(
                                TaskNames.COMPILE_VERSIONS, TaskForecast.Status.RUN, "restore versioned classes", null)
                        : new TaskForecast.Task(TaskNames.COMPILE_VERSIONS, TaskForecast.Status.CACHED, "", null));
        return dirty;
    }

    private static Path generatedDir(BuildLayout layout, ReleaseSources entry) {
        return layout.generatedSourcesDir("annotations", "versions-" + entry.release());
    }

    static Task compileVersionsStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        return Task.builder(TaskNames.COMPILE_VERSIONS)
                .stage(BuildStage.COMPILE)
                .label("Compiling releases")
                .kind(TaskKind.CPU)
                .requires(cx.mainCompile(), TaskNames.RESOLVE_DEPS, TaskNames.ENSURE_JDK)
                .weight(() -> cx.plan().get().compileJava())
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    dropUndeclared(project, layout);
                    boolean allCached = true;
                    for (ReleaseSources entry : project.build().multiRelease()) {
                        allCached &= compile(ctx, in, cas, actionCache, project, layout, entry);
                    }
                    if (allCached) ctx.cached();
                    ctx.progress(1);
                })
                .build();
    }

    /** One entry's compile; whether it was answered without running javac. */
    private static boolean compile(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            ActionCache actionCache,
            JkBuild project,
            BuildLayout layout,
            ReleaseSources entry)
            throws IOException {
        Path out = layout.versionedClassesDir(entry.release());
        List<Path> sources = sources(entry, in.dir());
        if (sources.isEmpty()) {
            PathUtil.deleteRecursively(out);
            ctx.warn(
                    "multi-release",
                    "[multi-release] " + entry.release() + " names " + String.join(", ", entry.src())
                            + ", which holds no .java sources; the jar has no META-INF/versions/" + entry.release());
            return true;
        }
        Path mainClasses = ctx.require(MAIN_CLASSES);
        CompileRequest request = versionsCompileRequest(
                sources,
                classpath(project, layout, entry, mainClasses, ctx.require(CLASSPATH)),
                ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP)),
                out,
                compileRelease(entry, ctx.require(RELEASE)),
                ctx.require(JAVAC_ARGS),
                project.build().javac(),
                ctx.require(JAVA_HOME),
                mainClasses);
        String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_VERSIONS, out);
        Path stateDir = ActionKey.stateDir(
                ActionTree.INCREMENTAL_JAVA.under(CacheTree.ACTIONS.under(in.cache())),
                TaskNames.COMPILE_VERSIONS,
                out);
        Path genDir = generatedDir(layout, entry);
        Files.createDirectories(genDir);
        boolean rerun = in.session().config().rebuildOr(false);
        ctx.label("compiling " + sources.size() + " sources for Java " + entry.release());
        JavaCompile.Result r = JavaCompile.run(
                taskId,
                PlannerCompile.compileLabel(ctx, TaskNames.COMPILE_VERSIONS),
                request,
                BuildIdentity.cacheKeyVersion(),
                !rerun,
                !in.ephemeralActions(),
                actionCache.cas(),
                actionCache,
                stateDir,
                PluginJar.JAVA_COMPILER.locate(cas),
                genDir,
                WorkerEnv.forModule(project.build().env(), in.dir(), layout.moduleTargetDir()));
        ctx.waited(Duration.ofMillis(r.waitMillis()));
        boolean errored =
                JavacDiagnostics.report(ctx, request.classpath(), ClasspathResolver.COMPILE_MAIN, r.diagnostics());
        if (!r.success()) {
            if (!errored) {
                ctx.error(
                        "javac",
                        "Java " + entry.release() + " compile failed without compiler diagnostics (outcome: "
                                + r.outcome() + ")");
            }
            throw new RuntimeException("javac reported errors");
        }
        return r.cacheHit() || r.compiledSources().isEmpty();
    }

    /** A release the manifest no longer declares leaves no versioned classes behind for the jar to carry. */
    private static void dropUndeclared(JkBuild project, BuildLayout layout) throws IOException {
        Path versions = layout.versionedClassesRoot().resolve("META-INF").resolve("versions");
        Set<String> keep = new HashSet<>();
        for (ReleaseSources entry : project.build().multiRelease()) keep.add(Integer.toString(entry.release()));
        List<Path> stale = new ArrayList<>();
        PathUtil.forEachChild(versions, (child, attrs) -> {
            if (!keep.contains(String.valueOf(child.getFileName()))) stale.add(child);
            return true;
        });
        for (Path p : stale) PathUtil.deleteRecursively(p);
    }
}
