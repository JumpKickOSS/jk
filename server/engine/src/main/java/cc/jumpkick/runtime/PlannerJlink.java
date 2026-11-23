// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PackagingCache.restorePackaged;
import static cc.jumpkick.runtime.PackagingCache.storePackaged;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.JlinkImage;
import cc.jumpkick.compile.LibDirLayout;
import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Jlink;
import cc.jumpkick.model.LibDir;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.task.JdkToolchain;
import cc.jumpkick.task.KeyContract;
import cc.jumpkick.task.KeyOwner;
import cc.jumpkick.task.ModuleEnvKeys;
import cc.jumpkick.task.ParsedBuild;
import cc.jumpkick.task.RecordToolchain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The {@code [jlink]} packaging tail: {@code target/<name>-jri/}, a runtime image of the
 * application linked from the project JDK, with its startup cache ({@link JlinkImage}).
 */
final class PlannerJlink {

    private PlannerJlink() {}

    static Task jlinkStep(Path cache, Path lockFile, boolean persist) {
        return Task.builder(TaskNames.PACKAGE_JLINK)
                .forecast(StepForecast.priced(s -> forecast(ForecastScope.of(s), lockFile)))
                .stage(BuildStage.PACKAGE)
                .label("Runtime image")
                .kind(TaskKind.IO)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(() -> EffortWeights.assemblyWeight(Objects.requireNonNull(lockFile.getParent(), "lock dir")))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(MODULE).project();
                    BuildLayout layout = ctx.require(MODULE).layout();
                    Jlink config = Objects.requireNonNull(project.build().jlink(), "[jlink]");
                    boolean selfContained = PluginBuild.shape(project, layout.moduleRoot())
                            .map(sh -> sh.selfContained())
                            .orElse(false);
                    if (selfContained) {
                        String message = "[jlink] links the thin jar and its runtime jars, but a plugin packages this"
                                + " module as a self-contained jar; drop [jlink], or ship the plugin's own artifact";
                        ctx.error("jlink", message);
                        throw new IllegalStateException(message);
                    }
                    String declaredMain = PackagingKeys.mainClass(layout.moduleRoot(), project);
                    if (declaredMain == null || declaredMain.isBlank()) {
                        String message = "[jlink] needs a main class to start the image: set [application] main";
                        ctx.error("jlink", message);
                        throw new IllegalStateException(message);
                    }
                    Path javaHome = ctx.require(JAVA_HOME);
                    int release =
                            ctx.get(PARSED_BUILD).map(ParsedBuild::release).orElse(0);
                    Path thinJar = layout.mainJar();
                    Path image = layout.jlinkImage();
                    List<LibDirLayout.Lib> libs = libs(project, layout, lockFile);
                    List<Path> guarded = new ArrayList<>(List.of(thinJar));
                    libs.forEach(lib -> guarded.add(lib.jar()));
                    InputGuard guard = InputGuard.of(guarded);
                    PackagingKeys.Keyed keyed =
                            key(project, layout, libs, declaredMain, javaHome, release, guard::token);
                    String task = keyed.taskId();
                    String key = keyed.key();
                    List<String> tokens = keyed.tokens();
                    Path base = layout.artifactDir();
                    if (restorePackaged(cache, key, base)) {
                        // A restore writes fresh times, and the cache is judged by them.
                        JlinkImage.stamp(image);
                        PackagingCache.recordBundled(layout, TaskNames.PACKAGE_JLINK, guarded);
                        ctx.label(image.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("link " + image.getFileName());
                    JlinkImage.Result result = JlinkImage.build(
                            new JlinkImage.Plan(
                                    javaHome,
                                    release,
                                    thinJar,
                                    layout.artifact(),
                                    libs,
                                    declaredMain,
                                    config,
                                    image,
                                    layout.artifactDir().resolve("jlink-train"),
                                    WorkerEnv.forModule(
                                                    project.build().env(), layout.moduleRoot(), layout.artifactDir())
                                            .withJavaHome(image)
                                            .with(config.trainingEnv())
                                            .environment()),
                            ctx::label);
                    ctx.put(BUILD_OUTCOME, "built");
                    String summary = image.getFileName() + ": " + (result.bytes() / (1024 * 1024)) + " MiB, "
                            + result.modules().size() + " JDK modules"
                            + switch (result.cache()) {
                                case AOT -> ", AOT cache";
                                case CDS -> ", CDS archive";
                                case NONE -> "";
                            };
                    ctx.label(summary);
                    storePackaged(ctx, guard, cache, task, key, tokens, base, result.files(), persist, List.of());
                    PackagingCache.recordBundled(layout, TaskNames.PACKAGE_JLINK, guarded);
                    ctx.progress(1);
                })
                .build();
    }

    /** The runtime jars the image links beside the thin jar, as the {@code [application]} lib directory names them. */
    static List<LibDirLayout.Lib> libs(JkBuild project, BuildLayout layout, Path lockFile) throws IOException {
        LibDir libConfig = project.libDir() != null ? project.libDir() : LibDir.defaults();
        return LibDirLayout.libs(layout.moduleRoot(), project, lockFile, JkStores.storeCas(), libConfig);
    }

    /**
     * The image's action key: the thin jar and {@code libs} read through {@code identity} (the
     * build's input guard, or the forecast's view of a jar a restore brings back), the main class,
     * the JDK and release, the {@code [jlink]} table and, when the image is trained, the module's
     * {@code [env]}.
     */
    @KeyOwner(step = TaskNames.PACKAGE_JLINK)
    static PackagingKeys.Keyed key(
            JkBuild project,
            BuildLayout layout,
            List<LibDirLayout.Lib> libs,
            String mainClass,
            Path javaHome,
            int release,
            ClasspathFingerprint.EntryIdentity identity)
            throws IOException {
        Jlink config = Objects.requireNonNull(project.build().jlink(), "[jlink]");
        JlinkImage.Cache cacheKind = JlinkImage.cache(config, JvmOptions.hostFeature(javaHome));
        List<String> tokens = new ArrayList<>(tokens(
                identity.of(layout.mainJar()),
                ClasspathFingerprint.of(libs.stream().map(LibDirLayout.Lib::jar).toList(), identity),
                LibDirLayout.classPath("libs", libs),
                mainClass,
                javaHome,
                release,
                config,
                cacheKind));
        // The training run starts the application under the module's [env].
        if (cacheKind != JlinkImage.Cache.NONE) tokens.addAll(ModuleEnvKeys.worker(project, layout.moduleRoot()));
        String task = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JLINK, layout.jlinkImage());
        return new PackagingKeys.Keyed(
                task, List.copyOf(tokens), ActionKey.forArtifact(task, KeyContract.ARTIFACT.keyVersion(), tokens));
    }

    /**
     * package-jlink's price, under the key {@link #key} derives for the live step. A jar this build
     * rewrites leaves the key unknowable until it has, so the image relinks; a miss says why the
     * cache could not have kept the last image when it could not.
     */
    static TaskForecast.@Nullable Task forecast(ForecastScope s, Path lockFile) throws Exception {
        if (s.project.isWorkspaceRoot()) return null;
        s.projectOwnOutputs();
        if (s.runs(TaskNames.PACKAGE_JAR) || s.compileDirty) return run("relink · compile changed");
        // The image links the runtime siblings' jars by their bytes.
        if (s.runtimeDepDirty) return run("relink · runtime dependency changed");
        ForecastScope.Prepared p = s.prepared();
        BuildLayout layout = p.parsed().layout();
        String mainClass = PackagingKeys.mainClass(layout.moduleRoot(), s.project);
        if (mainClass == null || mainClass.isBlank()) return run("link · [jlink] needs a main class");
        Path image = layout.jlinkImage();
        try {
            PackagingKeys.Keyed keyed = key(
                    s.project,
                    layout,
                    libs(s.project, layout, lockFile),
                    mainClass,
                    p.javaHome(),
                    p.parsed().release(),
                    jar -> PackagingKeys.fingerprintJarOrCached(jar, s.actionCache, s.restored.jarShas()));
            if (ForecastSteps.present(s.actionCache, keyed.key())) {
                return new TaskForecast.Task(
                        TaskNames.PACKAGE_JLINK, TaskForecast.Status.CACHED, "", ForecastSteps.key8(keyed.key()));
            }
        } catch (IOException | RuntimeException e) {
            Log.debug("jlink forecast: an input could not be read", e);
            return run("link · provisional: " + Errors.text(e));
        }
        if (!Files.isDirectory(image)) return run("link · image missing");
        // An image is heavy by its size: the one on disk says whether the cache may have refused it.
        String notCached = heavy(image) ? PackagingCache.heavyNotCached(s.cache, s.project) : null;
        return run(notCached == null ? "relink · action cache miss" : "relink · " + notCached);
    }

    private static TaskForecast.Task run(String why) {
        return new TaskForecast.Task(TaskNames.PACKAGE_JLINK, TaskForecast.Status.RUN, why, null);
    }

    /** Whether a file of {@code image} is as large as a heavy output, so the cache treats the image as one. */
    private static boolean heavy(Path image) {
        boolean[] heavy = {false};
        try {
            PathUtil.forEachReadableFile(image, (file, attrs) -> heavy[0] |= attrs.size() >= DiskGuard.HEAVY_BYTES);
        } catch (IOException unreadable) {
            // an image that cannot be read says nothing about the cache
        }
        return heavy[0];
    }

    /**
     * The key's inputs: the jars, the JDK and every key of the table. The image embeds the JDK's
     * own runtime, so the JDK keys by its toolchain and the platform it is for ({@link
     * JdkToolchain#target}).
     */
    static List<String> tokens(
            String jarToken,
            String libsToken,
            List<String> libNames,
            String mainClass,
            Path javaHome,
            int release,
            Jlink config,
            JlinkImage.Cache cacheKind)
            throws IOException {
        Map<String, String> env = new TreeMap<>(config.trainingEnv());
        return List.of(
                "jar:" + jarToken,
                "libs:" + libsToken,
                "names:" + String.join(",", libNames),
                "main:" + mainClass,
                "jdk:" + JdkToolchain.token(javaHome),
                RecordToolchain.TARGET_TOKEN + JdkToolchain.target(javaHome),
                "release:" + release,
                "modules:" + String.join(",", config.modules()),
                "strip-debug:" + config.stripDebug(),
                "cache:" + cacheKind,
                "jvm-args:" + String.join("\u0000", config.jvmArgs()),
                "args:" + String.join("\u0000", config.args()),
                "start:" + config.testStart() + "/" + config.startTimeoutSeconds(),
                "training-env:" + env);
    }

    /** Whether {@code project} declares a runtime image. */
    static boolean declared(JkBuild project) {
        return project.build().jlink() != null;
    }
}
