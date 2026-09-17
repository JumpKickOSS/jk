// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.JavadocTool;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.ModuleForecast.Prepared;
import cc.jumpkick.runtime.base.DokkaResolver;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@link ModuleForecast} arms for the packaging tails beside {@code package-jar}: the fat jar
 * and the javadoc jar, each keyed by the same {@link PackagingKeys} body its live step calls and
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
        List<String> options = JavadocTool.options(project.project().javadocMode(), prepared.release());
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
        return TaskForecaster.present(actionCache, jdKey)
                ? new TaskForecast.Task(
                        TaskNames.PACKAGE_JAVADOC, TaskForecast.Status.CACHED, "", TaskForecaster.key8(jdKey))
                : new TaskForecast.Task(TaskNames.PACKAGE_JAVADOC, TaskForecast.Status.RUN, "javadoc", null);
    }

    /**
     * The cache-install step's forecast: cached when the shelf already holds this jar (matching
     * SHA) and its POM — and, with {@code [m2] install} on, the Maven local repo under {@code
     * m2Dir}, the request's {@code --m2-dir} root the step writes to. A packaged-but-never-installed
     * module still runs, and so does one whose jar this build rewrites.
     */
    static TaskForecast.Task cacheInstall(
            JkBuild project, BuildLayout layout, Path cache, @Nullable Path m2Dir, boolean jarDirty) {
        boolean skip = !jarDirty && InstallPlans.alreadyInstalled(project, layout, cache, m2Dir);
        return new TaskForecast.Task(
                TaskNames.CACHE_INSTALL,
                skip ? TaskForecast.Status.CACHED : TaskForecast.Status.RUN,
                skip ? "" : "install to local repo",
                null);
    }
}
