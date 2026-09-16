// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.CLASSPATH;
import static cc.jumpkick.runtime.BuildPlanner.JAVA_HOME;
import static cc.jumpkick.runtime.BuildPlanner.JAVA_SOURCES;
import static cc.jumpkick.runtime.BuildPlanner.LAYOUT;
import static cc.jumpkick.runtime.BuildPlanner.MAIN_CLASSES;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;
import static cc.jumpkick.runtime.BuildPlanner.RELEASE;
import static cc.jumpkick.runtime.PlannerSupport.restorePackaged;
import static cc.jumpkick.runtime.PlannerSupport.storePackaged;

import cc.jumpkick.cache.JavadocJar;
import cc.jumpkick.compile.JavadocTool;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ClasspathAbi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code package-javadoc} step body: javadoc over the module's Java sources into a scratch
 * tree, zipped as {@code <artifact>-<version>-javadoc.jar}. A module with no Java sources (Kotlin,
 * Groovy) or with no public or protected type gets a README-only jar, which Maven Central accepts.
 * Cached under the key
 * {@link PackagingKeys#javadoc} derives for both this step and {@code jk explain}.
 */
final class PlannerJavadoc {

    /** Diagnostic code for every javadoc warning and error in the results. */
    static final String CODE = "javadoc";

    static final String NO_JAVA_SOURCES = "The module has no Java sources to document; javadoc reads Java only,"
            + " and jk does not run a Kotlin or Groovy documentation tool.";

    static final String NO_PUBLIC_API =
            "The module declares no public or protected types; javadoc has nothing to document.";

    /** javadoc's own words for a source set without a documentable type. */
    private static final String NO_PUBLIC_API_MARKER = "No public or protected classes found to document";

    private PlannerJavadoc() {}

    static void run(TaskContext ctx, Path cache, boolean persist) throws Exception {
        JkBuild project = ctx.require(PROJECT);
        BuildLayout layout = ctx.require(LAYOUT);
        Path javadocJar = layout.javadocJar();
        List<Path> sources = ctx.require(JAVA_SOURCES).stream()
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
        // What javac saw, plus the module's own classes: a Java source may name a Kotlin type.
        List<Path> classpath = new ArrayList<>(ctx.require(CLASSPATH));
        classpath.add(ctx.require(MAIN_CLASSES));
        Path javaHome = ctx.require(JAVA_HOME);
        List<String> options = JavadocTool.options(project.project().javadocMode(), ctx.require(RELEASE));
        PackagingKeys.Keyed keyed = PackagingKeys.javadoc(
                javadocJar, layout.moduleRoot(), sources, classpath, ClasspathAbi::token, options, javaHome);
        Path artifactDir = javadocJar.getParent();
        if (restorePackaged(cache, keyed.key(), artifactDir)) {
            ctx.label(javadocJar.getFileName() + " up-to-date");
            ctx.cached();
            ctx.progress(1);
            return;
        }
        ctx.label("javadoc " + javadocJar.getFileName());
        byte[] bytes = sources.isEmpty()
                ? JavadocJar.readmeOnly(NO_JAVA_SOURCES)
                : document(ctx, layout, sources, classpath, javaHome, options);
        Files.createDirectories(artifactDir);
        Files.write(javadocJar, bytes);
        storePackaged(cache, keyed.taskId(), keyed.key(), keyed.tokens(), artifactDir, List.of(javadocJar), persist);
        ctx.progress(1);
    }

    /** Fork javadoc; warnings ride the results, errors (strict doclint, a broken source) fail the step. */
    private static byte[] document(
            TaskContext ctx,
            BuildLayout layout,
            List<Path> sources,
            List<Path> classpath,
            Path javaHome,
            List<String> options)
            throws Exception {
        Path out = layout.moduleTargetDir().resolve("javadoc");
        PathUtil.deleteRecursivelyOrThrow(out);
        Files.createDirectories(out);
        JavadocTool.Result r = JavadocTool.run(javaHome, out, sources, classpath, options, layout.moduleRoot());
        for (String w : r.warnings()) ctx.warn(CODE, w);
        // A library of package-private types (a rule pack, a fixtures module) is a library all the
        // same; Central takes the README-only jar, and nothing here is the user's mistake.
        if (!r.success() && r.output().contains(NO_PUBLIC_API_MARKER)) {
            ctx.label("javadoc: no public types; README-only jar");
            return JavadocJar.readmeOnly(NO_PUBLIC_API);
        }
        if (!r.success()) {
            for (String e : r.errors()) ctx.error(CODE, e);
            if (r.errors().isEmpty()) {
                ctx.error(CODE, "javadoc exited " + r.exit() + (r.output().isBlank() ? "" : "\n" + r.output()));
            }
            throw new RuntimeException("javadoc reported errors");
        }
        return JavadocJar.fromTree(out);
    }
}
