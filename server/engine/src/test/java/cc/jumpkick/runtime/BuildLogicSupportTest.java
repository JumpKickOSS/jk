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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;

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
