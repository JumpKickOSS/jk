// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.JavadocTool;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Errors;
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
 * The forecasts of the packaging tails beside {@code package-jar}: the fat jar, the minified jar,
 * the javadoc jar, the native image and the install, each keyed by the same {@link PackagingKeys} body its live step calls and
 * read against the action cache. {@code null} when the module plans no such tail.
 */
final class ForecastPackagingTails {

    private ForecastPackagingTails() {}

    /** {@code package-assembly} — only when configured; the key recipe is {@code PlannerTails.assemblyStep}'s. */
    static TaskForecast.@Nullable Task assembly(ForecastScope s) throws Exception {
        s.projectOwnOutputs();
        JkBuild project = s.project;
        // A workspace root plans no tails. A member with no sources of its own is a distribution
        // when it assembles: the fat jar over its siblings is its whole output, so it is priced
        // like any other. Returning nothing for it forecast a module with no steps, which a
        // sibling's edit never reached, while the build repackaged it.
        if (!project.assembly() || project.isWorkspaceRoot()) return null;
        // The assembly embeds the runtime siblings' jars by their bytes.
        if (s.compileDirty || s.runtimeDepDirty) {
            return new TaskForecast.Task(
                    TaskNames.PACKAGE_ASSEMBLY, TaskForecast.Status.RUN, "repackage · compile changed", null);
        }
        if (PlannerPlugin.transformRuns(s)) {
            // The fat jar packs what the transform writes, and that is unknown until it has run.
            return new TaskForecast.Task(
                    TaskNames.PACKAGE_ASSEMBLY, TaskForecast.Status.RUN, "repackage · classes transform runs", null);
        }
        BuildLayout layout = s.prepared().parsed().layout();
        boolean hit = PackagingKeys.assemblyActionCached(
                s.dir,
                project,
                layout,
                s.lockFile,
                s.actionCache,
                s.cache,
                s.compileMainKey,
                s.restored.jarShas(),
                s.knownResourceDrift,
                s.restored.projectedIdentity(layout.classesDir()),
                s.restored.identity());
        if (hit) return new TaskForecast.Task(TaskNames.PACKAGE_ASSEMBLY, TaskForecast.Status.CACHED, "", null);
        // A fat jar is a heavy output: the cache may never have been allowed to keep the last one.
        String notCached = PackagingCache.heavyNotCached(s.cache, project);
        return new TaskForecast.Task(
                TaskNames.PACKAGE_ASSEMBLY,
                TaskForecast.Status.RUN,
                notCached == null ? "repackage" : "repackage · " + notCached,
                null);
    }

    /**
     * {@code package-minified}'s price, under the key {@link PackagingKeys#minified} derives for the
     * live step. Classes this build rewrites leave the key unknowable until it has, so the jar is
     * minified again; a miss says why the cache could not have kept the last one when it could not.
     */
    static TaskForecast.@Nullable Task minified(ForecastScope s) throws Exception {
        s.projectOwnOutputs();
        if (s.runs(TaskNames.PACKAGE_JAR) || s.compileDirty) return minify("minify · compile changed");
        // The minified jar shrinks the runtime siblings' jars together with the module's classes.
        if (s.runtimeDepDirty) return minify("minify · runtime dependency changed");
        if (PlannerPlugin.transformRuns(s)) return minify("minify · classes transform runs");
        Prepared p = s.prepared();
        BuildLayout layout = p.parsed().layout();
        try {
            PackagingKeys.Keyed keyed =
                    PackagingKeys.minified(s.project, s.dir, s.cache, s.lockFile, s.cas, layout, p.javaHome());
            if (keyed == null) return minify("minify · no [minified] packager");
            if (ForecastSteps.present(s.actionCache, keyed.key())) {
                return new TaskForecast.Task(
                        TaskNames.PACKAGE_MINIFIED, TaskForecast.Status.CACHED, "", ForecastSteps.key8(keyed.key()));
            }
        } catch (IOException | RuntimeException e) {
            Log.debug("minified forecast: an input could not be read", e);
            return minify("minify · provisional: " + Errors.text(e));
        }
        // A minified jar is a heavy output: the cache may never have been allowed to keep the last one.
        String notCached = PackagingCache.heavyNotCached(s.cache, s.project);
        return minify(notCached == null ? "minify · action cache miss" : "minify · " + notCached);
    }

    private static TaskForecast.Task minify(String why) {
        return new TaskForecast.Task(TaskNames.PACKAGE_MINIFIED, TaskForecast.Status.RUN, why, null);
    }

    /** {@code package-javadoc} — a library tail; the key is {@link PlannerJavadoc}'s. */
    static TaskForecast.@Nullable Task javadoc(
            JkBuild project,
            Path dir,
            Prepared prepared,
            ActionKey.EntryToken abiToken,
            ActionCache actionCache,
            Cas cas,
            boolean compileDirty)
            throws IOException, InterruptedException {
        if (!PackagingKeys.javadocJar(project, dir, SessionContext.current())) return null;
        if (compileDirty) {
            return new TaskForecast.Task(
                    TaskNames.PACKAGE_JAVADOC, TaskForecast.Status.RUN, "javadoc · compile changed", null);
        }
        BuildLayout layout = prepared.parsed().layout();
        List<Path> javaSources = prepared.parsed().mainSrc().stream()
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
        // The live step documents against what javac saw plus the module's own classes.
        List<Path> classpath = new ArrayList<>(prepared.classpaths().main());
        classpath.add(layout.classesDir());
        List<String> options = JavadocTool.options(
                project.project().javadocMode(),
                prepared.parsed().release(),
                PlannerCompile.javacOptions(prepared.parsed().javacArgs(), project.javac()));
        // Kotlin sources make it a Dokka run; an exact [dokka] version (the default) needs no catalog.
        String dokkaVersion = prepared.parsed().ktSrc().isEmpty()
                ? null
                : DokkaResolver.version(project, RepoGroupBuilder.buildFor(project, null, cas));
        // Scala sources (in a module without Kotlin) make it a scaladoc run at the compiler's release.
        List<Path> scalaSources = PlannerJavadoc.scaladocSources(
                prepared.parsed().mainSrc(), prepared.parsed().ktSrc());
        String scalaVersion = scalaSources.isEmpty()
                ? null
                : PlannerJavadoc.scaladocVersion(project, prepared.parsed().lock());
        String jdKey = PackagingKeys.javadoc(
                        layout.javadocJar(),
                        dir,
                        javaSources,
                        scalaSources.isEmpty() ? prepared.parsed().ktSrc() : scalaSources,
                        classpath,
                        abiToken,
                        options,
                        prepared.javaHome(),
                        PlannerJavadoc.docToolToken(project, dokkaVersion, scalaVersion))
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

    /** The module's native executable or shared library is on disk. */
    static boolean nativeBinaryPresent(BuildLayout layout) {
        return Files.isRegularFile(layout.nativeBinary()) || Files.isRegularFile(layout.nativeLibrary());
    }
}
