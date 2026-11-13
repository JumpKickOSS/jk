// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PackagingCache.restorePackaged;
import static cc.jumpkick.runtime.PackagingCache.storePackaged;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.JlinkImage;
import cc.jumpkick.compile.LibDirLayout;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Jlink;
import cc.jumpkick.model.LibDir;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.task.KeyOwner;
import cc.jumpkick.task.ParsedBuild;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The {@code [jlink]} packaging tail: {@code target/<name>-jri/}, a runtime image of the
 * application linked from the project JDK, with its startup cache ({@link JlinkImage}).
 */
final class PlannerJlink {

    private PlannerJlink() {}

    @KeyOwner(step = TaskNames.PACKAGE_JLINK, unpaired = "explain does not forecast the runtime image at all")
    static Task jlinkStep(Path cache, Path lockFile, boolean persist) {
        return Task.builder(TaskNames.PACKAGE_JLINK)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.PACKAGE)
                .label("Runtime image")
                .kind(TaskKind.IO)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(() -> EffortWeights.assemblyWeight(Objects.requireNonNull(lockFile.getParent(), "lock dir")))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
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
                    LibDir libConfig = project.libDir() != null ? project.libDir() : LibDir.defaults();
                    List<LibDirLayout.Lib> libs =
                            LibDirLayout.libs(layout.moduleRoot(), project, lockFile, JkStores.storeCas(), libConfig);
                    List<Path> guarded = new ArrayList<>(List.of(thinJar));
                    libs.forEach(lib -> guarded.add(lib.jar()));
                    InputGuard guard = InputGuard.of(guarded);
                    JlinkImage.Cache cacheKind = JlinkImage.cache(config, JvmOptions.hostFeature(javaHome));
                    List<String> tokens = tokens(
                            guard.token(thinJar),
                            ClasspathFingerprint.of(
                                    libs.stream().map(LibDirLayout.Lib::jar).toList()),
                            LibDirLayout.classPath("libs", libs),
                            declaredMain,
                            ActionKey.jdkToken(javaHome),
                            release,
                            config,
                            cacheKind);
                    String task = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JLINK, image);
                    String key = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokens);
                    Path base = layout.artifactDir();
                    if (restorePackaged(cache, key, base)) {
                        // A restore writes fresh times, and the cache is judged by them.
                        JlinkImage.stamp(image);
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
                    ctx.progress(1);
                })
                .build();
    }

    /** The key's inputs: the jars, the JDK, and every key of the table. */
    static List<String> tokens(
            String jarToken,
            String libsToken,
            List<String> libNames,
            String mainClass,
            String jdkToken,
            int release,
            Jlink config,
            JlinkImage.Cache cacheKind) {
        Map<String, String> env = new TreeMap<>(config.trainingEnv());
        return List.of(
                "jar:" + jarToken,
                "libs:" + libsToken,
                "names:" + String.join(",", libNames),
                "main:" + mainClass,
                "jdk:" + jdkToken,
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
