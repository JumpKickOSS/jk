// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

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
        assertTrue(Files.isRegularFile(classes.resolve("before-compile.txt")));
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
        assertTrue(Files.isRegularFile(classes.resolve("from-groovy.txt")), labels.toString());
        assertEquals("hello-from-script", Files.readString(classes.resolve("from-groovy.txt")).trim());
        assertTrue(labels.toString().contains("before-compile"), labels.toString());

        Files.delete(classes.resolve("from-groovy.txt"));
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s).append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(classes.resolve("from-groovy.txt")));
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
                      Files.writeString(ctx.outDir().resolve("kt-before.txt"), "from-kt")
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
        assertTrue(Files.isRegularFile(classes.resolve("kt-before.txt")), labels.toString());
        assertEquals("from-kt", Files.readString(classes.resolve("kt-before.txt")).trim());
        assertTrue(labels.toString().contains("kt-before-compile"), labels.toString());

        Files.delete(classes.resolve("kt-before.txt"));
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s).append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(classes.resolve("kt-before.txt")));
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
        assertTrue(Files.isRegularFile(classes.resolve("from-kts.txt")), labels.toString());
        assertEquals("hello-from-kts", Files.readString(classes.resolve("from-kts.txt")).trim());
        assertTrue(labels.toString().contains("before-compile"), labels.toString());

        Files.delete(classes.resolve("from-kts.txt"));
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s).append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(classes.resolve("from-kts.txt")));
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
