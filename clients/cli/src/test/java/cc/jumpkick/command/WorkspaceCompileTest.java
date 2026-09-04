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

/**
 * {@code jk compile} over a workspace: at the root, at a member directory, at a nested member,
 * with {@code -m}, and under {@code --output json}.
 *
 * <p>A workspace compiles through a different orchestrator than a standalone project and settles
 * a different terminal, so a green standalone compile says nothing about this arm.
 */
@Tag("integration")
class WorkspaceCompileTest {

    @Test
    void compiles_the_whole_workspace_from_the_root(@TempDir Path tempDir) throws Exception {
        Path root = workspace(tempDir);
        assertThat(run("compile", "-C", root.toString(), "--cache-dir", cache(tempDir)))
                .isZero();
        assertThat(root.resolve("target/libs/core/classes")).isDirectory();
        assertThat(root.resolve("target/lib/classes")).isDirectory();
        assertThat(root.resolve("target/app/classes")).isDirectory();
    }

    @Test
    void compiles_a_single_module_selected_by_name(@TempDir Path tempDir) throws Exception {
        Path root = workspace(tempDir);
        assertThat(run("compile", "-C", root.toString(), "--cache-dir", cache(tempDir), "-m", "lib"))
                .isZero();
        assertThat(root.resolve("target/lib/classes")).isDirectory();
        assertThat(root.resolve("target/app/classes")).doesNotExist();
    }

    @Test
    void compiles_from_inside_a_member_directory(@TempDir Path tempDir) throws Exception {
        Path root = workspace(tempDir);
        assertThat(run("compile", "-C", root.resolve("app").toString(), "--cache-dir", cache(tempDir)))
                .isZero();
        assertThat(root.resolve("target/app/classes")).isDirectory();
    }

    /** A member below the root's own directory — the module path has a separator in it. */
    @Test
    void compiles_from_inside_a_nested_member_directory(@TempDir Path tempDir) throws Exception {
        Path root = workspace(tempDir);
        assertThat(run("compile", "-C", root.resolve("libs/core").toString(), "--cache-dir", cache(tempDir)))
                .isZero();
        assertThat(root.resolve("target/libs/core/classes")).isDirectory();
    }

    @Test
    void compiles_the_workspace_under_json_output(@TempDir Path tempDir) throws Exception {
        Path root = workspace(tempDir);
        assertThat(run("compile", "-C", root.toString(), "--cache-dir", cache(tempDir), "--output", "json"))
                .isZero();
        assertThat(root.resolve("target/app/classes")).isDirectory();
    }

    /**
     * The failure arrives as a compiler diagnostic on the terminal — the whole point of a stream
     * both ends agree on. A workspace run that ends with no terminal at all can only be reported
     * as a lost connection, whatever actually went wrong.
     */
    @Test
    void a_broken_member_fails_with_its_compiler_diagnostic(@TempDir Path tempDir) throws Exception {
        Path root = workspace(tempDir);
        Files.writeString(
                root.resolve("lib/src/main/java/com/example/lib/Greet.java"),
                "package com.example.lib; public class Greet { void f( }\n");

        String out =
                Capture.stdout(() -> assertThat(run("compile", "-C", root.toString(), "--cache-dir", cache(tempDir)))
                        .isNotZero());

        assertThat(out).contains("Greet.java");
    }

    private static String cache(Path tempDir) {
        return tempDir.resolve("cache").toString();
    }

    /** {@code libs/core} ← {@code lib} ← {@code app}: a nested member, and two jar-consuming edges. */
    private static Path workspace(Path tempDir) throws Exception {
        Path root = tempDir.resolve("ws");
        Files.createDirectories(root);
        Files.writeString(root.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "0.0.1"
                java    = 25

                [workspace]
                modules = ["libs/core", "lib", "app"]
                """);
        module(root, "libs/core", "core", null, "Core", """
                package com.example.core;

                public final class Core {
                    public static String name() {
                        return "core";
                    }
                }
                """);
        module(root, "lib", "lib", "core", "Greet", """
                package com.example.lib;

                import com.example.core.Core;

                public final class Greet {
                    public static String hello() {
                        return "hello " + Core.name();
                    }
                }
                """);
        module(root, "app", "app", "lib", "Main", """
                package com.example.app;

                import com.example.lib.Greet;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println(Greet.hello());
                    }
                }
                """);
        return root;
    }

    private static void module(Path root, String rel, String name, String dependsOn, String type, String source)
            throws Exception {
        Path dir = root.resolve(rel);
        Path src = dir.resolve("src/main/java/com/example/" + name);
        Files.createDirectories(src);
        Files.writeString(
                dir.resolve("jk.toml"),
                "name = \"" + name + "\"\n"
                        + (dependsOn == null ? "" : "\n[dependencies]\n" + dependsOn + ".workspace = true\n"));
        Files.writeString(src.resolve(type + ".java"), source);
    }
}
