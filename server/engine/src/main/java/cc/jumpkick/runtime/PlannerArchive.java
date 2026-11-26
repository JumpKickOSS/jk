// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PackagingCache.restorePackaged;
import static cc.jumpkick.runtime.PackagingCache.storePackaged;

import cc.jumpkick.compile.DistArchive;
import cc.jumpkick.compile.JlinkImage;
import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.LibDir;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.task.KeyContract;
import cc.jumpkick.task.KeyOwner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The distribution archives: the {@code [jlink]} image as {@link BuildLayout#jlinkArchive} and the
 * lib-dir layout as {@link BuildLayout#libDirArchive}, each written after the layout it archives
 * and cached on that layout's bytes ({@link DistArchive}).
 */
final class PlannerArchive {

    private PlannerArchive() {}

    /** What one archive holds: its files under the top directory, the trees they come from, and its time. */
    private record Source(Path archive, List<DistArchive.Entry> entries, List<Path> roots, long epochSeconds) {}

    /** {@code package-jlink-archive}: the runtime image, at the image's own file time so its startup cache still maps. */
    static Task jlinkArchiveStep(Path cache, boolean persist) {
        return archiveStep(TaskNames.PACKAGE_JLINK_ARCHIVE, TaskNames.PACKAGE_JLINK, cache, persist);
    }

    /** {@code package-lib-dir-archive}: the lib-dir layout's jar and lib directory, at jk's archive time. */
    static Task libDirArchiveStep(Path cache, boolean persist) {
        return archiveStep(TaskNames.PACKAGE_LIB_DIR_ARCHIVE, TaskNames.PACKAGE_LIB_DIR, cache, persist);
    }

    @KeyOwner(
            step = {TaskNames.PACKAGE_JLINK_ARCHIVE, TaskNames.PACKAGE_LIB_DIR_ARCHIVE},
            unpaired = "explain does not forecast the distribution archives")
    private static Task archiveStep(String name, String layoutStep, Path cache, boolean persist) {
        return Task.builder(name)
                .forecast(StepForecast.RIDES)
                .stage(BuildStage.PACKAGE)
                .label("Archive")
                .kind(TaskKind.IO)
                .requires(layoutStep)
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(MODULE).project();
                    BuildLayout layout = ctx.require(MODULE).layout();
                    Source source = name.equals(TaskNames.PACKAGE_JLINK_ARCHIVE)
                            ? jlinkSource(layout)
                            : libDirSource(project, layout);
                    Path archive = source.archive();
                    InputGuard guard = InputGuard.of(source.roots());
                    List<String> tokens = new ArrayList<>();
                    tokens.add("top:" + layout.archiveRoot());
                    tokens.add("format:" + DistArchive.Format.of(archive));
                    tokens.add("time:" + source.epochSeconds());
                    for (Path root : source.roots()) tokens.add("tree:" + guard.token(root));
                    // Only a tar carries modes.
                    if (DistArchive.Format.of(archive) == DistArchive.Format.TAR_GZ) {
                        List<String> executables = new ArrayList<>();
                        for (DistArchive.Entry e : source.entries()) if (e.executable()) executables.add(e.name());
                        executables.sort(null);
                        tokens.add("exec:" + String.join(",", executables));
                    }
                    String task = ActionKey.qualifiedTaskId(name, archive);
                    String key = ActionKey.forArtifact(task, KeyContract.ARTIFACT.keyVersion(), tokens);
                    Path base = layout.artifactDir();
                    if (restorePackaged(cache, key, base)) {
                        ctx.label(archive.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("archive " + archive.getFileName());
                    DistArchive.write(archive, layout.archiveRoot(), source.entries(), source.epochSeconds());
                    ctx.put(BUILD_OUTCOME, "built");
                    ctx.label(archive.getFileName() + ": " + mib(Files.size(archive)));
                    storePackaged(ctx, guard, cache, task, key, tokens, base, List.of(archive), persist, List.of());
                    ctx.progress(1);
                })
                .build();
    }

    private static Source jlinkSource(BuildLayout layout) throws IOException {
        Path image = layout.jlinkImage();
        return new Source(
                layout.jlinkArchive(),
                DistArchive.tree(image, ""),
                List.of(image),
                JlinkImage.STAMP.toInstant().getEpochSecond());
    }

    private static Source libDirSource(JkBuild project, BuildLayout layout) throws IOException {
        LibDir config = Objects.requireNonNull(project.libDir(), "[application] lib-dir");
        Path jar = layout.libDirJar();
        Path libDir = layout.libDir(config);
        List<DistArchive.Entry> entries = new ArrayList<>();
        entries.add(DistArchive.file(jar, String.valueOf(jar.getFileName())));
        entries.addAll(DistArchive.tree(libDir, config.dir() + "/"));
        return new Source(layout.libDirArchive(), entries, List.of(jar, libDir), DeterministicZip.EPOCH_SECONDS);
    }

    private static String mib(long bytes) {
        long mib = bytes / (1024 * 1024);
        return mib > 0 ? mib + " MiB" : (bytes / 1024) + " KiB";
    }
}
