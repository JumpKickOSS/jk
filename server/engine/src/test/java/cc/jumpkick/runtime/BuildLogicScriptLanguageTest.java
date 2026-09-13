// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildLogicFixtures.generated;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.base.BuildLogicAnchor;
import cc.jumpkick.runtime.base.BuildLogicGroovyHost;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build logic written as stem scripts: Groovy, {@code .kts}, scripts-only trees, same-stem
 * priority, and Groovy child-process isolation.
 */
@Tag("integration")
class BuildLogicScriptLanguageTest {

    @Test
    void groovy_stem_script_before_compile_and_cache_hit(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve(".jk/before-compile.groovy"), """
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
    void scripts_only_jk_no_other_files_ok(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(
                project.resolve(".jk/after-resources.groovy"),
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
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve(".jk/before-compile.kts"), """
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

    @Test
    void kts_wins_over_groovy_same_stem(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(
                project.resolve(".jk/before-compile.groovy"),
                "outDir.resolve('from-groovy.txt').toFile().text = 'groovy'\n");
        Files.writeString(project.resolve(".jk/before-compile.kts"), """
                import java.nio.file.Files
                Files.writeString(outDir.resolve("from-kts.txt"), "kts")
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> {}));
        assertTrue(Files.isRegularFile(generated(layout, "before-compile", "from-kts.txt")));
        assertTrue(Files.notExists(generated(layout, "before-compile", "from-groovy.txt")));
    }

    @Test
    void groovy_system_exit_zero_does_not_kill_the_engine_jvm(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve(".jk/after-resources.groovy"), """
                outDir.resolve('survived.txt').toFile().text = 'ok'
                System.exit(0)
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_RESOURCES, s -> {}));
        assertTrue(Files.isRegularFile(classes.resolve("survived.txt")));
    }

    @Test
    void groovy_system_exit_nonzero_fails_the_task(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve(".jk/after-resources.groovy"), "System.exit(1)\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_RESOURCES, s -> {}));
        assertThat(ex.getMessage()).containsAnyOf("failed", "exit");
    }

    /**
     * The Groovy host forks a JVM per script; a step cancelled mid-script — a sibling's failure or
     * Ctrl-C — kills that child and reports the run as cancelled, not as a script failure, instead
     * of waiting for a script nobody wants to finish.
     */
    @Test
    void a_cancelled_step_kills_its_running_groovy_script(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("slow.groovy");
        Files.writeString(script, """
                outDir.resolve('started').toFile().text = '1'
                Thread.sleep(60000)
                outDir.resolve('done').toFile().text = '1'
                """);
        Path project = Files.createDirectories(dir.resolve("p"));
        Path out = Files.createDirectories(dir.resolve("o"));

        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread build = new Thread(() -> {
            try {
                BuildLogicGroovyHost.evaluate(script, project, out, cancelled::get);
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        build.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (!Files.exists(out.resolve("started")) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(out.resolve("started")).as("the script is running").exists();

        long cancelledAt = System.nanoTime();
        cancelled.set(true);
        build.join(TimeUnit.SECONDS.toMillis(30));
        Duration untilCancelled = Duration.ofNanos(System.nanoTime() - cancelledAt);

        assertThat(outcome.get())
                .as("the run reports as cancelled, not as a script failure")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slow.groovy")
                .hasMessageContaining("cancelled with its build");
        assertThat(untilCancelled).isLessThan(Duration.ofSeconds(10));
        assertThat(out.resolve("done")).doesNotExist();
    }

    private static Path scaffold(Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
        return project;
    }
}
