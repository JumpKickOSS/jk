// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PackagingCache.restorePackaged;
import static cc.jumpkick.runtime.PackagingCache.storePackaged;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.LibDirLayout;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.LibDir;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.InputGuard;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * The {@code [application] lib-dir} packaging tail: {@code target/<name>.jar} with a {@code
 * Class-Path} manifest and the runtime jars under {@code target/<lib-dir>/}, written from the thin
 * jar ({@link LibDirLayout}).
 */
final class PlannerLibDir {

    private PlannerLibDir() {}

    static Task libDirStep(Path cache, Path lockFile, boolean persist) {
        return Task.builder(TaskNames.PACKAGE_LIB_DIR)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.PACKAGE)
                .label("Lib dir")
                .kind(TaskKind.IO)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(() -> EffortWeights.assemblyWeight(Objects.requireNonNull(lockFile.getParent(), "lock dir")))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    LibDir config = Objects.requireNonNull(project.libDir(), "[application] lib-dir");
                    boolean selfContained = PluginBuild.shape(project, layout.moduleRoot())
                            .map(sh -> sh.selfContained())
                            .orElse(false);
                    if (selfContained) {
                        String message = "[application] lib-dir lays out the thin jar and its runtime jars, but a"
                                + " plugin packages this module as a self-contained jar; drop lib-dir, or ship"
                                + " the plugin's own artifact";
                        ctx.error("lib-dir", message);
                        throw new IllegalStateException(message);
                    }
                    Path thinJar = layout.mainJar();
                    Path appJar = layout.libDirJar();
                    Path libDir = layout.libDir(config);
                    String declaredMain = PackagingKeys.mainClass(layout.moduleRoot(), project);
                    String mainClass = declaredMain == null ? "" : declaredMain;
                    List<LibDirLayout.Lib> libs =
                            LibDirLayout.libs(layout.moduleRoot(), project, lockFile, JkStores.storeCas(), config);
                    List<Path> guarded = new ArrayList<>(List.of(thinJar));
                    libs.forEach(lib -> guarded.add(lib.jar()));
                    InputGuard guard = InputGuard.of(guarded);
                    List<String> tokens = List.of(
                            "jar:" + guard.token(thinJar),
                            "libs:"
                                    + ClasspathFingerprint.of(libs.stream()
                                            .map(LibDirLayout.Lib::jar)
                                            .toList()),
                            "names:" + String.join(",", LibDirLayout.classPath(config.dir(), libs)),
                            "main:" + mainClass);
                    String task = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_LIB_DIR, appJar);
                    String key = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokens);
                    Path base = layout.artifactDir();
                    HashSet<String> names = new HashSet<>();
                    libs.forEach(lib -> names.add(lib.name()));
                    if (restorePackaged(cache, key, base)) {
                        LibDirLayout.prune(libDir, names);
                        ctx.label(appJar.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("lay out " + appJar.getFileName() + " + " + config.dir() + "/");
                    List<Path> outputs = LibDirLayout.write(thinJar, appJar, libDir, config.dir(), libs, mainClass);
                    ctx.put(BUILD_OUTCOME, "built");
                    storePackaged(ctx, guard, cache, task, key, tokens, base, outputs, persist, List.of());
                    ctx.progress(1);
                })
                .build();
    }

    /** Whether {@code project} declares the lib-dir layout. */
    static boolean declared(JkBuild project) {
        return project.libDir() != null;
    }
}
