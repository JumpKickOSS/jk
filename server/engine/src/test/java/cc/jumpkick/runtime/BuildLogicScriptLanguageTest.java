// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildLogicFixtures.generated;
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

/**
 * Build logic written in a language other than Java: a Groovy or {@code .kts} stem script, a Kotlin
 * SPI contributor, and a {@code jk-build} directory with no Java in it at all. Split out of
 * {@code BuildLogicSupportTest} at the 800-line cap (JK-2444) — the seam is the language, and no
 * assertion changed in the move. Shared fixtures live in {@link BuildLogicFixtures}.
 */
@Tag("integration")
class BuildLogicScriptLanguageTest {

    @Test
    void groovy_stem_script_before_compile_and_cache_hit(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
        Files.createDirectories(project.resolve(".jk-build"));
        Files.writeString(project.resolve(".jk-build/before-compile.groovy"), """
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
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s)
                        .append(';')));
        Path groovyOut = generated(layout, "before-compile", "from-groovy.txt");
        assertTrue(Files.isRegularFile(groovyOut), labels.toString());
        assertEquals("hello-from-script", Files.readString(groovyOut).trim());
        assertTrue(labels.toString().contains("before-compile"), labels.toString());

        Files.delete(groovyOut);
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(groovyOut), "a cache hit must restore the source root");
    }

    @Test
    void kotlin_spi_contributor_before_compile(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");

        Path logicSrc = project.resolve(".jk-build/src/demo");
        Files.createDirectories(logicSrc);
        Files.writeString(logicSrc.resolve("KtMarkerLogic.kt"), """
                package demo
                import cc.jumpkick.plugin.buildlogic.*
                import java.nio.file.Files

                class KtMarkerLogic : BuildLogicContributor {
                  override fun register(g: BuildLogicGraph) {
                    g.task("kt-before-compile", BuildLogicAnchor.BEFORE_COMPILE) { ctx ->
                      // joinToString is kotlin-stdlib, first touched HERE — inside the task body,
                      // long after registration. It resolves out of the stdlib jar, so it fails if
                      // the defining loader was closed at the end of discovery.
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
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s)
                        .append(';')));
        Path ktOut = generated(layout, "kt-before-compile", "kt-before.txt");
        assertTrue(Files.isRegularFile(ktOut), labels.toString());
        assertEquals("from-kt", Files.readString(ktOut).trim());
        assertTrue(labels.toString().contains("kt-before-compile"), labels.toString());

        Files.delete(ktOut);
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(ktOut), "a cache hit must restore the source root");
    }

    @Test
    void scripts_only_jk_build_no_java_ok(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve(".jk-build"));
        Files.writeString(project.resolve("jk.toml"), """
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

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_RESOURCES, s -> {}));
        assertTrue(Files.isRegularFile(classes.resolve("script-only.txt")));
    }

    @Test
    void kts_stem_script_before_compile_and_cache_hit(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
        Files.createDirectories(project.resolve(".jk-build"));
        Files.writeString(project.resolve(".jk-build/before-compile.kts"), """
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
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s)
                        .append(';')));
        Path ktsOut = generated(layout, "before-compile", "from-kts.txt");
        assertTrue(Files.isRegularFile(ktsOut), labels.toString());
        assertEquals("hello-from-kts", Files.readString(ktsOut).trim());
        assertTrue(labels.toString().contains("before-compile"), labels.toString());

        Files.delete(ktsOut);
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(ktsOut), "a cache hit must restore the source root");
    }
}
