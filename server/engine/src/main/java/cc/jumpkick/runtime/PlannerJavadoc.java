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
import cc.jumpkick.model.JavadocMode;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ClasspathAbi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@code package-javadoc} step body: javadoc over the module's Java sources into a scratch
 * tree, zipped as {@code <artifact>-<version>-javadoc.jar}. A module with no Java sources (Kotlin,
 * Groovy) or with no public or protected type gets a README-only jar, which Maven Central accepts.
 * Cached under the key
 * {@link PackagingKeys#javadoc} derives for both this step and {@code jk explain}.
 *
 * <p>Only {@link JavadocMode#STRICT} lets javadoc fail the step. Under the lenient default every
 * line javadoc calls an error is a warning in the results — an older JDK's javadoc reports a
 * malformed comment as an error with doclint off, and a Maven {@code package} of the same project
 * never ran javadoc at all — and the jar holds whatever javadoc wrote, or the README when it wrote
 * nothing.
 */
final class PlannerJavadoc {

    /** Diagnostic code for every javadoc warning and error in the results. */
    static final String CODE = "javadoc";

    static final String NO_JAVA_SOURCES = "The module has no Java sources to document; javadoc reads Java only,"
            + " and jk does not run a Kotlin or Groovy documentation tool.";

    static final String NO_PUBLIC_API =
            "The module declares no public or protected types; javadoc has nothing to document.";

    static final String LENIENT_ERRORS = "javadoc reported errors over these sources and wrote no documentation;"
            + " the errors are warnings in the build's results, and javadoc = \"strict\" makes them fail the step.";

    /** javadoc's own words for a source set without a documentable type. */
    private static final String NO_PUBLIC_API_MARKER = "No public or protected classes found to document";

    /** What the step does with javadoc's exit, its output and the mode. */
    enum Verdict {
        /** Exit 0: the jar is the tree javadoc wrote. */
        DOCUMENTED,
        /** No public or protected type: the README-only jar, no failure in either mode. */
        NO_API,
        /** Lenient, javadoc failed but wrote files: errors become warnings, the jar is that tree. */
        LENIENT_TREE,
        /** Lenient, javadoc failed and wrote nothing: errors become warnings, the README-only jar. */
        LENIENT_README,
        /** Strict, javadoc failed: the errors fail the step. */
        FAIL
    }

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
        JavadocMode mode = project.project().javadocMode();
        List<String> options = JavadocTool.options(mode, ctx.require(RELEASE));
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
                : document(ctx, mode, layout, sources, classpath, javaHome, options);
        Files.createDirectories(artifactDir);
        Files.write(javadocJar, bytes);
        storePackaged(cache, keyed.taskId(), keyed.key(), keyed.tokens(), artifactDir, List.of(javadocJar), persist);
        ctx.progress(1);
    }

    /** Fork javadoc; warnings ride the results, and only strict mode lets an error fail the step. */
    private static byte[] document(
            TaskContext ctx,
            JavadocMode mode,
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
        Verdict verdict = verdict(mode, r, wroteFiles(out));
        switch (verdict) {
            case DOCUMENTED -> {
                return JavadocJar.fromTree(out);
            }
            // A library of package-private types (a rule pack, a fixtures module) is a library all
            // the same; Central takes the README-only jar, and nothing here is the user's mistake.
            case NO_API -> {
                ctx.label("javadoc: no public types; README-only jar");
                return JavadocJar.readmeOnly(NO_PUBLIC_API);
            }
            case LENIENT_TREE -> {
                for (String e : errorLines(r)) ctx.warn(CODE, e);
                ctx.label("javadoc reported errors; jar from what it wrote");
                return JavadocJar.fromTree(out);
            }
            case LENIENT_README -> {
                for (String e : errorLines(r)) ctx.warn(CODE, e);
                ctx.label("javadoc reported errors; README-only jar");
                return JavadocJar.readmeOnly(LENIENT_ERRORS);
            }
            case FAIL -> {
                for (String e : errorLines(r)) ctx.error(CODE, e);
                throw new RuntimeException("javadoc reported errors");
            }
        }
        throw new IllegalStateException("unreachable: " + verdict);
    }

    /** The one decision table, so the lenient and strict arms can be read side by side. */
    static Verdict verdict(JavadocMode mode, JavadocTool.Result r, boolean wroteFiles) {
        if (r.success()) return Verdict.DOCUMENTED;
        if (r.output().contains(NO_PUBLIC_API_MARKER)) return Verdict.NO_API;
        if (mode == JavadocMode.STRICT) return Verdict.FAIL;
        return wroteFiles ? Verdict.LENIENT_TREE : Verdict.LENIENT_README;
    }

    /** javadoc's located errors, or its exit and whole output when it located none. */
    static List<String> errorLines(JavadocTool.Result r) {
        if (!r.errors().isEmpty()) return r.errors();
        return List.of("javadoc exited " + r.exit() + (r.output().isBlank() ? "" : "\n" + r.output()));
    }

    private static boolean wroteFiles(Path out) throws IOException {
        AtomicBoolean any = new AtomicBoolean();
        PathUtil.forEachRegularFile(out, (file, attrs) -> any.set(true));
        return any.get();
    }
}
