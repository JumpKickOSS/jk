// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.plugin.buildlogic.BuildLogicAnchor;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class BuildLogicSupportTest {

    /**
     * BEFORE_COMPILE output is codegen: it lands in the generated-source root the compilers read,
     * not in classes/ (JK-1602). One dir per task.
     */
    private static Path generated(BuildLayout layout, String task, String file) {
        return BuildLogicSupport.generatedSourceRoot(layout).resolve(task).resolve(file);
    }

    @Test
    void convention_jk_build_dir_runs_and_cache_hits(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(
                project.resolve("src/main/java/demo/App.java"),
                "package demo; public class App { public static void main(String[] a) {} }\n");

        Path logicSrc = project.resolve(".jk-build/src/demo");
        Files.createDirectories(logicSrc);
        writeLineCount(logicSrc.resolve("LineCountBuild.java"), "demo.LineCountBuild");

        runTwice(project, dir.resolve("cache"), "demo.LineCountBuild");
    }

    @Test
    void logic_path_override(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                [build]
                logic = "custom-logic"
                logic-main = "demo.LineCountBuild"
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");

        Path logicSrc = project.resolve("custom-logic/src/demo");
        Files.createDirectories(logicSrc);
        writeLineCount(logicSrc.resolve("LineCountBuild.java"), "demo.LineCountBuild");

        // .jk-build absent; only custom-logic should run
        assertTrue(Files.notExists(project.resolve(".jk-build")));
        runTwice(project, dir.resolve("cache"), "demo.LineCountBuild");
    }

    @Test
    void two_build_mains_are_independently_cached(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(
                project.resolve("src/main/java/demo/App.java"),
                "package demo; public class App { public static void main(String[] a) {} }\n");

        Path logicSrc = project.resolve(".jk-build/src/demo");
        Files.createDirectories(logicSrc);
        writeLineCount(logicSrc.resolve("LineCountBuild.java"), "demo.LineCountBuild");
        writeStamp(logicSrc.resolve("StampBuild.java"), "demo.StampBuild");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, s -> labels.append(s).append(';')));
        assertTrue(Files.isRegularFile(classes.resolve("line-count.txt")));
        assertTrue(Files.isRegularFile(classes.resolve("stamp.txt")));
        assertTrue(labels.toString().contains("LineCountBuild"));
        assertTrue(labels.toString().contains("StampBuild"));

        Files.delete(classes.resolve("line-count.txt"));
        Files.delete(classes.resolve("stamp.txt"));
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, s -> labels.append(s).append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        // Both tasks hit independently
        assertEquals(2, labels.toString().split("cache hit", -1).length - 1);
    }

    @Test
    void spi_contributor_runs_at_two_anchors(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");

        Path logicSrc = project.resolve(".jk-build/src/demo");
        Files.createDirectories(logicSrc);
        Files.writeString(logicSrc.resolve("MultiAnchorLogic.java"), """
                package demo;
                import cc.jumpkick.plugin.buildlogic.*;
                import java.nio.file.*;
                public class MultiAnchorLogic implements BuildLogicContributor {
                  @Override
                  public void register(BuildLogicGraph g) {
                    g.task("before-compile-marker", BuildLogicAnchor.BEFORE_COMPILE, ctx -> {
                      Files.writeString(ctx.outDir().resolve("before-compile.txt"), "g");
                    });
                    g.task("after-compile-marker", BuildLogicAnchor.AFTER_COMPILE, ctx -> {
                      Files.writeString(ctx.outDir().resolve("after-compile.txt"), "c");
                    });
                    g.task("before-package-marker", BuildLogicAnchor.BEFORE_PACKAGE, ctx -> {
                      Files.writeString(ctx.outDir().resolve("before-package.txt"), "p");
                    });
                  }
                }
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertTrue(Files.isRegularFile(generated(layout, "before-compile-marker", "before-compile.txt")));
        assertFalse(Files.exists(classes.resolve("before-compile.txt")), "codegen must not land in classes/");
        assertTrue(labels.toString().contains("before-compile-marker"), labels.toString());
        assertEquals("generate", BuildLogicAnchor.BEFORE_COMPILE.stageWireName());


        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertTrue(Files.isRegularFile(classes.resolve("after-compile.txt")));
        assertTrue(labels.toString().contains("after-compile-marker"), labels.toString());

        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_PACKAGE, s -> labels.append(s)
                        .append(';')));
        assertTrue(Files.isRegularFile(classes.resolve("before-package.txt")));
        assertTrue(labels.toString().contains("before-package-marker"), labels.toString());

        // Second AFTER_COMPILE is a cache hit
        Files.delete(classes.resolve("after-compile.txt"));
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(classes.resolve("after-compile.txt")));
    }

    /**
     * A task reads the project through {@code BuildLogicContext}, so the key must cover it. Keying
     * on the logic sources alone reported `cache hit` after a product edit and replayed the stale
     * output (JK-1603).
     */
    @Test
    void a_product_source_edit_invalidates_a_task_that_reads_the_project(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");

        // Counts the lines of the project's own sources — the shape of the shipped example.
        Path logicSrc = project.resolve(".jk-build/src/demo");
        Files.createDirectories(logicSrc);
        Files.writeString(logicSrc.resolve("CountLogic.java"), """
                package demo;
                import cc.jumpkick.plugin.buildlogic.*;
                import java.nio.file.*;
                import java.util.stream.Stream;
                public class CountLogic implements BuildLogicContributor {
                  @Override
                  public void register(BuildLogicGraph g) {
                    g.task("line-count", BuildLogicAnchor.AFTER_COMPILE, ctx -> {
                      long n = 0;
                      try (Stream<Path> w = Files.walk(ctx.projectDir().resolve("src"))) {
                        for (Path f : w.filter(Files::isRegularFile).toList()) {
                          n += Files.readAllLines(f).size();
                        }
                      }
                      Files.writeString(ctx.outDir().resolve("line-count.txt"), String.valueOf(n));
                    });
                  }
                }
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> {}));
        String first = Files.readString(classes.resolve("line-count.txt")).trim();

        // Same logic, more product source: the count must change.
        Files.writeString(
                project.resolve("src/main/java/demo/More.java"),
                "package demo;\npublic class More {\n}\n");
        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> labels.append(s).append(';')));

        assertFalse(labels.toString().contains("cache hit"), labels.toString());
        assertNotEquals(first, Files.readString(classes.resolve("line-count.txt")).trim());
    }

    /**
     * The loader that defined a task must still be open when the task runs — registration and
     * execution are far apart, and a task body routinely first-touches a class then.
     *
     * <p>This case uses a directory-backed helper, which is the benign half: closing a
     * URLClassLoader shuts its <em>jar</em> handles, so directory entries survive. The failing half
     * is jar-backed and is pinned by {@code kotlin_spi_contributor_before_compile}, whose task body
     * first touches kotlin-stdlib (JK-1604).
     */
    @Test
    void a_task_body_may_first_touch_a_helper_class_at_run_time(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");

        Path logicSrc = project.resolve(".jk-build/src/demo");
        Files.createDirectories(logicSrc);
        // Helper is referenced ONLY from inside the task body, so it is first loaded at run time.
        Files.writeString(logicSrc.resolve("Helper.java"), """
                package demo;
                public final class Helper {
                  public static String text() { return "from-helper"; }
                }
                """);
        Files.writeString(logicSrc.resolve("LateLoadLogic.java"), """
                package demo;
                import cc.jumpkick.plugin.buildlogic.*;
                import java.nio.file.*;
                public class LateLoadLogic implements BuildLogicContributor {
                  @Override
                  public void register(BuildLogicGraph g) {
                    g.task("late-load", BuildLogicAnchor.AFTER_COMPILE, ctx -> {
                      Files.writeString(ctx.outDir().resolve("late.txt"), Helper.text());
                    });
                  }
                }
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> {}));

        assertEquals("from-helper", Files.readString(classes.resolve("late.txt")).trim());
    }

    /**
     * BuildPlanner calls run() once per anchor — four times per module per build — and each call
     * used to delete and recompile the whole logic tree, re-resolving the Kotlin toolchain with it.
     * A sentinel dropped into the classes dir survives a second anchor if no recompile happened,
     * and must not survive a logic-source edit (JK-1606).
     */
    @Test
    void logic_is_compiled_once_per_change_not_once_per_anchor(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");

        Path logicSrc = project.resolve(".jk-build/src/demo");
        Files.createDirectories(logicSrc);
        Path contributor = logicSrc.resolve("TwoAnchorLogic.java");
        Files.writeString(contributor, """
                package demo;
                import cc.jumpkick.plugin.buildlogic.*;
                import java.nio.file.*;
                public class TwoAnchorLogic implements BuildLogicContributor {
                  @Override
                  public void register(BuildLogicGraph g) {
                    g.task("a", BuildLogicAnchor.AFTER_COMPILE, ctx ->
                        Files.writeString(ctx.outDir().resolve("a.txt"), "a"));
                    g.task("b", BuildLogicAnchor.BEFORE_PACKAGE, ctx ->
                        Files.writeString(ctx.outDir().resolve("b.txt"), "b"));
                  }
                }
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);
        Path logicClasses = layout.generatedSourcesDir("jk-build-classes");

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> {}));

        // A recompile deletes the classes dir, so this sentinel is the observation.
        Path sentinel = logicClasses.resolve("sentinel.marker");
        Files.writeString(sentinel, "1");

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.BEFORE_PACKAGE, s -> {}));
        assertTrue(Files.exists(sentinel), "second anchor must reuse the compiled logic");
        assertTrue(Files.isRegularFile(classes.resolve("b.txt")), "the anchor still ran");

        // Editing the logic must still recompile.
        Files.writeString(contributor, Files.readString(contributor).replace("\"a\"))", "\"a2\"))"));
        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> {}));
        assertFalse(Files.exists(sentinel), "a logic edit must recompile");
        assertEquals("a2", Files.readString(classes.resolve("a.txt")).trim());
    }

    @Test
    void logic_off_skips_even_if_jk_build_exists(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve(".jk-build/src"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                [build]
                logic = "off"
                """);
        Files.writeString(project.resolve(".jk-build/src/X.java"), "class X {}");
        assertTrue(BuildLogicSupport.config(project).isEmpty());
    }

    @Test
    void groovy_stem_script_before_compile_and_cache_hit(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
        Files.createDirectories(project.resolve(".jk-build"));
        Files.writeString(
                project.resolve(".jk-build/before-compile.groovy"),
                """
                def stamp = outDir.resolve("from-groovy.txt")
                stamp.toFile().parentFile.mkdirs()
                stamp.toFile().text = "hello-from-script\\n"
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s).append(';')));
        Path groovyOut = generated(layout, "before-compile", "from-groovy.txt");
        assertTrue(Files.isRegularFile(groovyOut), labels.toString());
        assertEquals("hello-from-script", Files.readString(groovyOut).trim());
        assertTrue(labels.toString().contains("before-compile"), labels.toString());

        Files.delete(groovyOut);
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s).append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(groovyOut), "a cache hit must restore the source root");
    }

    @Test
    void kotlin_spi_contributor_before_compile(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");

        Path logicSrc = project.resolve(".jk-build/src/demo");
        Files.createDirectories(logicSrc);
        Files.writeString(
                logicSrc.resolve("KtMarkerLogic.kt"),
                """
                package demo
                import cc.jumpkick.plugin.buildlogic.*
                import java.nio.file.Files

                class KtMarkerLogic : BuildLogicContributor {
                  override fun register(g: BuildLogicGraph) {
                    g.task("kt-before-compile", BuildLogicAnchor.BEFORE_COMPILE) { ctx ->
                      // joinToString is kotlin-stdlib, first touched HERE — inside the task body,
                      // long after registration. It resolves out of the stdlib jar, so it fails if
                      // the defining loader was closed at the end of discovery (JK-1604).
                      val text = listOf("from", "kt").joinToString("-")
                      Files.writeString(ctx.outDir().resolve("kt-before.txt"), text)
                    }
                  }
                }
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s).append(';')));
        Path ktOut = generated(layout, "kt-before-compile", "kt-before.txt");
        assertTrue(Files.isRegularFile(ktOut), labels.toString());
        assertEquals("from-kt", Files.readString(ktOut).trim());
        assertTrue(labels.toString().contains("kt-before-compile"), labels.toString());

        Files.delete(ktOut);
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s).append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(ktOut), "a cache hit must restore the source root");
    }

    @Test
    void scripts_only_jk_build_no_java_ok(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve(".jk-build"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(
                project.resolve(".jk-build/after-resources.groovy"),
                "outDir.resolve('script-only.txt').toFile().text = 'ok\\n'\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_RESOURCES, s -> {}));
        assertTrue(Files.isRegularFile(classes.resolve("script-only.txt")));
    }

    @Test
    void kts_stem_script_before_compile_and_cache_hit(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
        Files.createDirectories(project.resolve(".jk-build"));
        Files.writeString(
                project.resolve(".jk-build/before-compile.kts"),
                """
                // SPDX-License-Identifier: Apache-2.0
                import java.nio.file.Files
                Files.createDirectories(outDir)
                Files.writeString(outDir.resolve("from-kts.txt"), "hello-from-kts")
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s).append(';')));
        Path ktsOut = generated(layout, "before-compile", "from-kts.txt");
        assertTrue(Files.isRegularFile(ktsOut), labels.toString());
        assertEquals("hello-from-kts", Files.readString(ktsOut).trim());
        assertTrue(labels.toString().contains("before-compile"), labels.toString());

        Files.delete(ktsOut);
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s).append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(ktsOut), "a cache hit must restore the source root");
    }

    private static void writeStamp(Path file, String fqcn) throws Exception {
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        String pkg = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf('.')) : "";
        Files.writeString(file, """
                package %s;
                import java.nio.file.*;
                public class %s {
                  public static void main(String[] args) throws Exception {
                    Path out = null;
                    for (int i = 0; i < args.length; i++) {
                      if ("--out".equals(args[i])) out = Path.of(args[++i]);
                    }
                    Files.createDirectories(out);
                    Files.writeString(out.resolve("stamp.txt"), "ok");
                  }
                }
                """.formatted(pkg, simple));
    }

    private static void writeLineCount(Path file, String fqcn) throws Exception {
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        String pkg = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf('.')) : "";
        Files.writeString(file, """
                package %s;
                import java.nio.file.*;
                import java.util.stream.Stream;
                public class %s {
                  public static void main(String[] args) throws Exception {
                    Path project = null, out = null;
                    for (int i = 0; i < args.length; i++) {
                      if ("--project".equals(args[i])) project = Path.of(args[++i]);
                      else if ("--out".equals(args[i])) out = Path.of(args[++i]);
                    }
                    long n = 0;
                    Path src = project.resolve("src");
                    try (Stream<Path> w = Files.walk(src)) {
                      for (Path f : (Iterable<Path>) w::iterator) {
                        if (Files.isRegularFile(f) && f.toString().endsWith(".java"))
                          n += Files.readAllLines(f).size();
                      }
                    }
                    Files.createDirectories(out);
                    Files.writeString(out.resolve("line-count.txt"), Long.toString(n));
                  }
                }
                """.formatted(pkg, simple));
    }

    private static void runTwice(Path project, Path cacheRoot, String ignoredMain) throws Exception {
        ActionCache ac = new ActionCache(new Cas(cacheRoot.resolve("cas")), cacheRoot.resolve("actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, s -> labels.append(s).append(';')));
        Path generated = classes.resolve("line-count.txt");
        assertTrue(Files.isRegularFile(generated), "expected generated resource");
        String first = Files.readString(generated).trim();
        assertTrue(Integer.parseInt(first) > 0);

        Files.delete(generated);
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, s -> labels.append(s).append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertEquals(first, Files.readString(generated).trim());
    }
}
