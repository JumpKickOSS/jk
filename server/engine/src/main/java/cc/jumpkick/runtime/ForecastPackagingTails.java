// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.JavadocTool;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.ForecastScope.Prepared;
import cc.jumpkick.runtime.base.DokkaResolver;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The forecasts of the packaging tails beside {@code package-jar}: the fat jar,
 * the javadoc jar, the native image and the install, each keyed by the same {@link PackagingKeys} body its live step calls and
 * read against the action cache. {@code null} when the module plans no such tail.
 */
final class ForecastPackagingTails {

    private ForecastPackagingTails() {}

    /** {@code package-assembly} — only when configured; the key recipe is {@code PlannerTails.assemblyStep}'s. */
    static TaskForecast.@Nullable Task assembly(
            JkBuild project,
            Path dir,
            Prepared prepared,
            Path lockFile,
            ActionCache actionCache,
            Path cache,
            @Nullable String compileMainKey,
            RestoredOutputs restored,
            @Nullable Boolean knownResourceDrift,
            boolean compileDirty)
            throws IOException {
        BuildLayout layout = prepared.layout();
        boolean noSources = prepared.mainSrc().isEmpty()
                && prepared.ktSrc().isEmpty()
                && prepared.gvSrc().isEmpty();
        if (!project.assembly() || noSources) return null;
        if (compileDirty) {
            return new TaskForecast.Task(
                    TaskNames.PACKAGE_ASSEMBLY, TaskForecast.Status.RUN, "repackage · compile changed", null);
        }
        boolean hit = PackagingKeys.assemblyActionCached(
                dir,
                project,
                layout,
                lockFile,
                actionCache,
                cache,
                compileMainKey,
                restored.jarShas(),
                knownResourceDrift,
                restored.projectedIdentity(layout.classesDir()));
        return hit
                ? new TaskForecast.Task(TaskNames.PACKAGE_ASSEMBLY, TaskForecast.Status.CACHED, "", null)
                : new TaskForecast.Task(TaskNames.PACKAGE_ASSEMBLY, TaskForecast.Status.RUN, "repackage", null);
    }

    /** {@code package-javadoc} — a library tail; the key is {@link PlannerJavadoc}'s. */
    static TaskForecast.@Nullable Task javadoc(
            JkBuild project,
            Path dir,
            Prepared prepared,
            List<Path> mainCp,
            ActionKey.EntryToken abiToken,
            ActionCache actionCache,
            Cas cas,
            boolean compileDirty)
            throws IOException, InterruptedException {
        if (!PackagingKeys.libraryArtifacts(project, dir)
                || !project.project().javadocMode().enabled()) return null;
        if (compileDirty) {
            return new TaskForecast.Task(
                    TaskNames.PACKAGE_JAVADOC, TaskForecast.Status.RUN, "javadoc · compile changed", null);
        }
        BuildLayout layout = prepared.layout();
        List<Path> javaSources = prepared.mainSrc().stream()
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
        // The live step documents against what javac saw plus the module's own classes.
        List<Path> classpath = new ArrayList<>(mainCp);
        classpath.add(layout.classesDir());
        List<String> options = JavadocTool.options(
                project.project().javadocMode(),
                prepared.release(),
                PlannerCompile.javacOptions(
                        prepared.javacArgs(), project.build().javac()));
        // Kotlin sources make it a Dokka run; an exact [dokka] version (the default) needs no catalog.
        String dokkaVersion = prepared.ktSrc().isEmpty()
                ? null
                : DokkaResolver.version(project, RepoGroupBuilder.buildFor(project, null, cas));
        String jdKey = PackagingKeys.javadoc(
                        layout.javadocJar(),
                        dir,
                        javaSources,
                        prepared.ktSrc(),
                        classpath,
                        abiToken,
                        options,
                        prepared.javaHome(),
                        PlannerJavadoc.dokkaToken(project, dokkaVersion))
                .key();
        return ForecastSteps.present(actionCache, jdKey)
                ? new TaskForecast.Task(
                        TaskNames.PACKAGE_JAVADOC, TaskForecast.Status.CACHED, "", ForecastSteps.key8(jdKey))
                : new TaskForecast.Task(TaskNames.PACKAGE_JAVADOC, TaskForecast.Status.RUN, "javadoc", null);
    }

    /**
     * The cache-install step's forecast: cached when the install target already holds this jar
     * (matching SHA) and its POM — the Maven local repo under {@code m2Dir}, the request's {@code
     * --m2-dir} root the step writes to, or the jk-local repository. {@code override} is the
     * request's {@code --destination}. A packaged-but-never-installed module still runs, and so does one
     * whose jar this build rewrites.
     */
    static TaskForecast.Task cacheInstall(
            JkBuild project,
            BuildLayout layout,
            Path cache,
            @Nullable Path m2Dir,
            @Nullable InstallDestination override,
            boolean jarDirty) {
        boolean skip = !jarDirty && InstallPlans.alreadyInstalled(project, layout, cache, m2Dir, override);
        return new TaskForecast.Task(
                TaskNames.CACHE_INSTALL,
                skip ? TaskForecast.Status.CACHED : TaskForecast.Status.RUN,
                skip ? "" : "install to local repo",
                null);
    }

    /**
     * {@code native-image}: run when the jar or the compile it packages is dirty — a binary is never
     * cached under a rebuilt jar — else cached when the producer-keyed action record the live step
     * restores from still hits. An on-disk binary alone is not enough: a previous engine's binary
     * must not hide a miss.
     */
    static TaskForecast.Task nativeImage(
            JkBuild project,
            Path dir,
            BuildLayout layout,
            Path lockFile,
            ActionCache actionCache,
            Path cache,
            RestoredOutputs restored,
            boolean packagedDirty) {
        if (packagedDirty) {
            return new TaskForecast.Task(
                    TaskNames.NATIVE_IMAGE, TaskForecast.Status.RUN, "rebuild · compile changed", null);
        }
        boolean binaryPresent = nativeBinaryPresent(layout);
        if (nativeRestores(project, dir, layout, lockFile, actionCache, cache, restored)) {
            return new TaskForecast.Task(
                    TaskNames.NATIVE_IMAGE,
                    TaskForecast.Status.CACHED,
                    binaryPresent ? "" : "binary missing · restores from cache",
                    null);
        }
        return new TaskForecast.Task(
                TaskNames.NATIVE_IMAGE,
                TaskForecast.Status.RUN,
                binaryPresent
                        ? "native-image · action cache miss"
                        : "native-image · binary missing (restores when its key still hits)",
                null);
    }

    /** The module's native executable or shared library is on disk. */
    static boolean nativeBinaryPresent(BuildLayout layout) {
        return Files.isRegularFile(layout.nativeBinary()) || Files.isRegularFile(layout.nativeLibrary());
    }

    /** The replayed native key hits — a read-only answer that fails safe to "no". */
    private static boolean nativeRestores(
            JkBuild project,
            Path dir,
            BuildLayout layout,
            Path lockFile,
            ActionCache actionCache,
            Path cache,
            RestoredOutputs restored) {
        try {
            return PackagingKeys.nativeActionCached(
                    dir, project, layout, lockFile, actionCache, cache, restored.jarShas());
        } catch (Exception e) {
            Log.debug("nativeImage: the last record could not be replayed read-only", e);
            return false;
        }
    }
}
