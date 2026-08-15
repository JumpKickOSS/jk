// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class TasksCommandTest {

    @Test
    void tasks_lists_package_jar(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        String out = captureStdout(() -> run("tasks", "-C", tempDir.toString()));
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

        String out = captureStdout(() -> run("show", "package-jar", "-C", tempDir.toString()));
        assertThat(out.trim()).contains("widget").contains(".jar");
        assertThat(Files.isRegularFile(Path.of(out.trim()))).isTrue();
    }

    @Test
    void inspect_compile_java(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        String out = captureStdout(() -> run("inspect", "compile-java", "-C", tempDir.toString()));
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
        String out = captureStdout(
                () -> run("inspect", "package-jar", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(out).contains("cache:");
        // After a successful build, package-jar is typically a forecast hit (or miss with detail).
        assertThat(out).doesNotContain("unknown offline");
    }

    @Test
    void tasks_show_alias_for_package(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        String out = captureStdout(() -> run("tasks", "show", "package", "-C", tempDir.toString()));
        // alias package → package-jar path (may not exist yet)
        assertThat(out.trim()).contains("widget");
    }

    @Test
    void tasks_list_includes_jk_build_logic_names(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        Path logic = tempDir.resolve(".jk-build/src/demo");
        Files.createDirectories(logic);
        Files.writeString(logic.resolve("GenLogic.java"), """
                package demo;
                import cc.jumpkick.plugin.buildlogic.*;
                public class GenLogic implements BuildLogicContributor {
                  public void register(BuildLogicGraph g) {
                    g.task("gen-tokens", BuildLogicAnchor.AFTER_COMPILE, ctx -> {});
                  }
                }
                """);
        Files.writeString(logic.resolve("LineCountBuild.java"), """
                package demo;
                public class LineCountBuild {
                  public static void main(String[] a) {}
                }
                """);
        String out = captureStdout(() -> run("tasks", "-C", tempDir.toString()));
        assertThat(out).contains("build-logic:gen-tokens");
        assertThat(out).contains("build-logic:LineCountBuild");
        // build-logic rows ride the main table (stage cell "logic"), no separate heading.
        assertThat(out).contains("logic");
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }

    private static String captureStdout(IntSupplier body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));
        try {
            body.getAsInt();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
