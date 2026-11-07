// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.CLASSPATH;
import static cc.jumpkick.runtime.BuildPlanner.JAVAC_ARGS;
import static cc.jumpkick.runtime.BuildPlanner.JAVA_HOME;
import static cc.jumpkick.runtime.BuildPlanner.JAVA_SOURCES;
import static cc.jumpkick.runtime.BuildPlanner.KOTLIN_SOURCES;
import static cc.jumpkick.runtime.BuildPlanner.LAYOUT;
import static cc.jumpkick.runtime.BuildPlanner.LOCKFILE;
import static cc.jumpkick.runtime.BuildPlanner.MAIN_CLASSES;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;
import static cc.jumpkick.runtime.BuildPlanner.RELEASE;
import static cc.jumpkick.runtime.PackagingCache.restorePackaged;
import static cc.jumpkick.runtime.PackagingCache.storePackaged;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JavadocJar;
import cc.jumpkick.compile.DokkaTool;
import cc.jumpkick.compile.JavadocTool;
import cc.jumpkick.compile.ScaladocTool;
import cc.jumpkick.engine.plugin.WorkerContainment;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JavadocMode;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.runtime.base.DokkaResolver;
import cc.jumpkick.runtime.base.ScalaToolResolver;
import cc.jumpkick.scala.ScalaResolver;
import cc.jumpkick.task.ClasspathAbi;
import cc.jumpkick.task.InputGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * The {@code package-javadoc} step body: javadoc over the module's Java sources — Dokka over a
 * module with Kotlin sources, its Java sources included, and scaladoc over a module with Scala
 * sources — into a scratch tree, zipped as {@code <artifact>-<version>-javadoc.jar}. A module with
 * none of those languages, or with no public or protected type, gets a README-only jar, which
 * Maven Central accepts. Cached under the key
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

    static final String NO_JAVA_SOURCES = "The module has no Java, Kotlin or Scala sources to document; javadoc"
            + " reads Java, Dokka reads Kotlin and scaladoc reads Scala, and jk does not run a Groovy documentation tool.";

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

    static void run(TaskContext ctx, Path cache, Cas cas, boolean persist) throws Exception {
        JkBuild project = ctx.require(PROJECT);
        BuildLayout layout = ctx.require(LAYOUT);
        Path javadocJar = layout.javadocJar();
        List<Path> javaAndScala = ctx.require(JAVA_SOURCES);
        List<Path> sources = javaAndScala.stream()
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
        List<Path> kotlinSources = ctx.get(KOTLIN_SOURCES).orElse(List.of());
        List<Path> scalaSources = scaladocSources(javaAndScala, kotlinSources);
        String scalaVersion = scalaSources.isEmpty() ? null : scaladocVersion(project, ctx.require(LOCKFILE));
        // What javac saw, plus the module's own classes: a Java source may name a Kotlin type.
        List<Path> classpath = new ArrayList<>(ctx.require(CLASSPATH));
        classpath.add(ctx.require(MAIN_CLASSES));
        Path javaHome = ctx.require(JAVA_HOME);
        JavadocMode mode = project.project().javadocMode();
        int release = ctx.require(RELEASE);
        // The module graph javac compiled under: the plugin-contributed and profile args, then the
        // module's own [javac] table, as the compile step assembles them.
        List<String> javacArgs = PlannerCompile.javacOptions(ctx.require(JAVAC_ARGS), project.javac());
        List<String> options = JavadocTool.options(mode, release, javacArgs);
        // Kotlin sources make it a Dokka run; the release documents with is part of the key.
        RepoGroup repos = kotlinSources.isEmpty() ? null : RepoGroupBuilder.buildFor(project, null, cas);
        String dokkaVersion = repos == null ? null : DokkaResolver.version(project, repos);
        List<Path> otherSources = scalaSources.isEmpty() ? kotlinSources : scalaSources;
        List<Path> guarded = new ArrayList<>(sources);
        guarded.addAll(otherSources);
        InputGuard guard = InputGuard.of(guarded);
        PackagingKeys.Keyed keyed = PackagingKeys.javadoc(
                javadocJar,
                layout.moduleRoot(),
                sources,
                otherSources,
                classpath,
                ClasspathAbi::token,
                options,
                javaHome,
                docToolToken(project, dokkaVersion, scalaVersion));
        Path artifactDir = javadocJar.getParent();
        if (restorePackaged(cache, keyed.key(), artifactDir)) {
            ctx.label(javadocJar.getFileName() + " up-to-date");
            ctx.cached();
            ctx.progress(1);
            return;
        }
        Files.createDirectories(artifactDir);
        if (repos != null && dokkaVersion != null) {
            ctx.label("dokka " + javadocJar.getFileName());
            DokkaResolver.Tool tool = DokkaResolver.resolve(
                    repos, cas, dokkaVersion, project.build().dokka().format());
            List<Path> documented = new ArrayList<>(kotlinSources);
            documented.addAll(sources);
            documentWithDokka(ctx, mode, project, layout, tool, documented, classpath, javaHome, release, javadocJar);
        } else if (scalaVersion != null) {
            ctx.label("scaladoc " + javadocJar.getFileName());
            documentWithScaladoc(ctx, mode, project, layout, cas, classpath, javaHome, javadocJar);
        } else if (sources.isEmpty()) {
            ctx.label("javadoc " + javadocJar.getFileName());
            JavadocJar.writeReadme(NO_JAVA_SOURCES, javadocJar);
        } else {
            ctx.label("javadoc " + javadocJar.getFileName());
            document(ctx, mode, layout, sources, classpath, javaHome, options, javadocJar);
        }
        storePackaged(
                ctx,
                guard,
                cache,
                keyed.taskId(),
                keyed.key(),
                keyed.tokens(),
                artifactDir,
                List.of(javadocJar),
                persist,
                List.of());
        ctx.progress(1);
    }

    /**
     * The key token naming the tool that documents the module in javadoc's place — Dokka's release
     * and format, or scaladoc's release — or null when javadoc documents it.
     */
    static @Nullable String docToolToken(
            JkBuild project, @Nullable String dokkaVersion, @Nullable String scaladocVersion) {
        if (dokkaVersion != null)
            return "dokka " + dokkaVersion + ":"
                    + project.build().dokka().format().wireName();
        if (scaladocVersion != null) return "scaladoc " + scaladocVersion;
        return null;
    }

    /** The Scala sources scaladoc documents: none when the module has Kotlin, which Dokka documents. */
    static List<Path> scaladocSources(List<Path> javaAndScala, List<Path> kotlinSources) {
        if (!kotlinSources.isEmpty()) return List.of();
        return javaAndScala.stream()
                .filter(p -> p.toString().endsWith(".scala"))
                .toList();
    }

    /** The scaladoc release: the one matching the module's Scala compiler. */
    static String scaladocVersion(JkBuild project, @Nullable Lockfile lock) {
        return ScalaResolver.floored(ScalaCompile.version(project, lock));
    }

    /**
     * Fork scaladoc over the module's TASTy, with the Scala stdlib its compile put on the classpath;
     * the same verdict table as javadoc decides {@code jar}.
     */
    private static void documentWithScaladoc(
            TaskContext ctx,
            JavadocMode mode,
            JkBuild project,
            BuildLayout layout,
            Cas cas,
            List<Path> classpath,
            Path javaHome,
            Path jar)
            throws Exception {
        ScalaCompile.Setup scala = ScalaCompile.prepare(project, ctx.require(LOCKFILE), cas);
        List<Path> tool =
                ScalaToolResolver.resolveScaladoc(RepoGroupBuilder.buildFor(project, null, cas), cas, scala.version());
        List<Path> docClasspath = new ArrayList<>(scala.libraryJars());
        for (Path p : classpath) {
            if (!docClasspath.contains(p)) docClasspath.add(p);
        }
        Path out = layout.apidocsDir();
        PathUtil.deleteRecursivelyOrThrow(out);
        Files.createDirectories(out);
        JavadocTool.Result r = ScaladocTool.run(
                javaHome,
                tool,
                out,
                project.project().name(),
                project.project().version(),
                ctx.require(MAIN_CLASSES),
                docClasspath);
        writeJar("scaladoc", ctx, mode, r, out, jar);
    }

    /** Fork Dokka over the Kotlin and Java sources; the same verdict table as javadoc decides {@code jar}. */
    private static void documentWithDokka(
            TaskContext ctx,
            JavadocMode mode,
            JkBuild project,
            BuildLayout layout,
            DokkaResolver.Tool tool,
            List<Path> sources,
            List<Path> classpath,
            Path javaHome,
            int release,
            Path jar)
            throws Exception {
        Path out = layout.apidocsDir();
        PathUtil.deleteRecursivelyOrThrow(out);
        Files.createDirectories(out);
        JavadocTool.Result r = DokkaTool.run(
                javaHome,
                tool,
                out,
                project.project().name(),
                project.project().version(),
                sources,
                classpath,
                release,
                layout.moduleRoot());
        writeJar("dokka", ctx, mode, r, out, jar);
    }

    /** Fork javadoc; warnings ride the results, and only strict mode lets an error fail the step. */
    private static void document(
            TaskContext ctx,
            JavadocMode mode,
            BuildLayout layout,
            List<Path> sources,
            List<Path> classpath,
            Path javaHome,
            List<String> options,
            Path jar)
            throws Exception {
        Path out = layout.apidocsDir();
        PathUtil.deleteRecursivelyOrThrow(out);
        Files.createDirectories(out);
        JavadocTool.Result r = JavadocTool.run(javaHome, out, sources, classpath, options, layout.moduleRoot());
        writeJar("javadoc", ctx, mode, r, out, jar);
    }

    /** Write {@code jar} as the verdict table says for {@code tool}'s run over {@code out}; warnings ride the results. */
    private static void writeJar(
            String tool, TaskContext ctx, JavadocMode mode, JavadocTool.Result r, Path out, Path jar)
            throws IOException {
        for (String w : r.warnings()) ctx.warn(CODE, w);
        Verdict verdict = verdict(mode, r, wroteFiles(out));
        switch (verdict) {
            case DOCUMENTED -> JavadocJar.writeTree(out, jar);
            // A library of package-private types (a rule pack, a fixtures module) is a library all
            // the same; Central takes the README-only jar, and nothing here is the user's mistake.
            case NO_API -> {
                ctx.label(tool + ": no public types; README-only jar");
                JavadocJar.writeReadme(NO_PUBLIC_API, jar);
            }
            case LENIENT_TREE -> {
                for (String e : errorLines(tool, r)) ctx.warn(CODE, e);
                ctx.label(tool + " reported errors; jar from what it wrote");
                JavadocJar.writeTree(out, jar);
            }
            case LENIENT_README -> {
                for (String e : errorLines(tool, r)) ctx.warn(CODE, e);
                ctx.label(tool + " reported errors; README-only jar");
                JavadocJar.writeReadme(LENIENT_ERRORS, jar);
            }
            case FAIL -> {
                for (String e : errorLines(tool, r)) ctx.error(CODE, e);
                throw new RuntimeException(tool + " reported errors");
            }
        }
    }

    /** The one decision table, so the lenient and strict arms can be read side by side. */
    static Verdict verdict(JavadocMode mode, JavadocTool.Result r, boolean wroteFiles) {
        if (r.success()) return Verdict.DOCUMENTED;
        if (r.output().contains(NO_PUBLIC_API_MARKER)) return Verdict.NO_API;
        if (mode == JavadocMode.STRICT) return Verdict.FAIL;
        return wroteFiles ? Verdict.LENIENT_TREE : Verdict.LENIENT_README;
    }

    /** The tool's located errors, or its exit and whole output when it located none. */
    static List<String> errorLines(String tool, JavadocTool.Result r) {
        if (!r.errors().isEmpty()) return r.errors();
        return List.of(tool + " "
                + WorkerContainment.failure(r.exit(), "exited " + r.exit())
                + (r.output().isBlank() ? "" : "\n" + r.output()));
    }

    private static boolean wroteFiles(Path out) throws IOException {
        AtomicBoolean any = new AtomicBoolean();
        PathUtil.forEachRegularFile(out, (file, attrs) -> any.set(true));
        return any.get();
    }
}
