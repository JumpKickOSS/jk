// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class TasksCommandTest {

    @Test
    void tasks_lists_package_jar(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        String out = Capture.stdout(() -> run("tasks", "-C", tempDir.toString()));
        assertThat(out).contains("package-jar");
        assertThat(out).contains("compile-java");
        // plain uppercase headers became BoxTable title-case columns.
        // the column is the task's BuildStage, so it is spelled Stage.
        assertThat(out).contains("Stage");
    }

    @Test
    void show_package_jar_path_after_build(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");
        Path cache = tempDir.resolve("cache");
        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString(), "--no-timeline"))
                .isZero();

        String out = Capture.stdout(() -> run("show", "package-jar", "-C", tempDir.toString()));
        assertThat(out.trim()).contains("widget").contains(".jar");
        assertThat(Files.isRegularFile(Path.of(out.trim()))).isTrue();
    }

    @Test
    void inspect_compile_java(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        String out = Capture.stdout(() -> run("inspect", "compile-java", "-C", tempDir.toString()));
        assertThat(out).contains("task:").contains("compile-java");
        assertThat(out).contains("stage:").contains("compile");
        assertThat(out).contains("output:");
        assertThat(out).contains("cache:");
        // not the old "unknown offline" stub when engine can forecast
        assertThat(out).doesNotContain("unknown offline");
    }

    @Test
    void inspect_cache_hit_after_build(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");
        Path cache = tempDir.resolve("cache");
        assertThat(run(
                        "build",
                        "-C",
                        tempDir.toString(),
                        "--cache-dir",
                        cache.toString(),
                        "--no-timeline",
                        "--skip-tests"))
                .isZero();
        String out = Capture.stdout(
                () -> run("inspect", "package-jar", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(out).contains("cache:");
        // After a successful build, package-jar is typically a forecast hit (or miss with detail).
        assertThat(out).doesNotContain("unknown offline");
    }

    @Test
    void show_prints_the_path_with_no_envelope(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        String out = Capture.stdout(() -> run("show", "package-jar", "-C", tempDir.toString()));
        // The path is consumed by command substitution, so nothing may pad it.
        assertThat(out).doesNotStartWith("\n").doesNotEndWith("\n\n");
    }

    @Test
    void inspect_keeps_the_human_envelope(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        String out = Capture.stdout(() -> run("inspect", "compile-java", "-C", tempDir.toString()));
        assertThat(out).startsWith("\n");
    }

    @Test
    void tasks_show_alias_for_package(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        String out = Capture.stdout(() -> run("tasks", "show", "package", "-C", tempDir.toString()));
        // alias package → package-jar path (may not exist yet)
        assertThat(out.trim()).contains("widget");
    }

    @Test
    void tasks_list_includes_jk_build_logic_names(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        Path logic = tempDir.resolve(".jk");
        Files.createDirectories(logic);
        Files.writeString(logic.resolve("after-compile.groovy"), "outDir.resolve('x.txt').toFile().text = 'x'\n");
        Files.writeString(logic.resolve("before-compile.kts"), "val x = 1\n");
        String out = Capture.stdout(() -> run("tasks", "-C", tempDir.toString()));
        assertThat(out).contains("build-logic:after-compile");
        assertThat(out).contains("build-logic:before-compile");
        assertThat(out).contains("logic");
    }
}
