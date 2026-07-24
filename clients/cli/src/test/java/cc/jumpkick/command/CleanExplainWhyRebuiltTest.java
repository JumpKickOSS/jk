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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class CleanExplainWhyRebuiltTest {

    // --- clean -------------------------------------------------------------

    @Test
    void clean_removes_target_and_build_and_generated(@TempDir Path tempDir) throws Exception {
        run("new", "--layout", "traditional", tempDir.toString());
        // Intermediates live under build/ in the v1 two-tier layout.
        Files.createDirectories(tempDir.resolve("target/build/classes/main/example"));
        Files.writeString(tempDir.resolve("target/build/classes/main/example/Hello.class"), "fake");
        // Final artifacts live under target/.
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("target/widget-0.1.0.jar"), "fake");
        // Foreign build systems' dirs are not jk's to clean.
        Files.createDirectories(tempDir.resolve("build"));

        int exit = run("clean", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("target")).doesNotExist();
        assertThat(tempDir.resolve("build")).exists();
    }

    @Test
    void clean_keep_artifacts_preserves_target(@TempDir Path tempDir) throws Exception {
        run("new", "--layout", "traditional", tempDir.toString());
        Files.createDirectories(tempDir.resolve("target/build/classes/main"));
        Files.writeString(tempDir.resolve("target/build/classes/main/Hello.class"), "fake");
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("target/widget-0.1.0.jar"), "fake-jar");

        int exit = run("clean", "--keep-artifacts", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("build")).doesNotExist();
        assertThat(tempDir.resolve("target/widget-0.1.0.jar")).exists();
    }

    @Test
    void clean_idempotent_on_empty_project(@TempDir Path tempDir) {
        run("new", "--layout", "traditional", tempDir.toString());
        int exit = run("clean", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
    }

    // --- explain -----------------------------------------------------------

    @Test
    void why_rebuilt_alias_dispatches_to_explain(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        ScaffoldTestSupport.writeEmptyLock(tempDir);
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");

        String stdout = captureStdout(() -> run(
                "why-rebuilt",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString()));
        assertThat(stdout).contains("Build Plan").contains("widget");
        assertThat(stdout).contains("compile-main");
    }

    @Test
    void explain_lists_compile_tasks_with_cache_status(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        ScaffoldTestSupport.writeEmptyLock(tempDir); // jk new no longer locks; explain needs a lock
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");

        String stdout = captureStdout(() -> run(
                "explain",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString()));
        // Header "- Build Plan" chip + the ● root coord; a single never-built project
        // lands in the "Rebuild" section.
        assertThat(stdout).contains("Build Plan").contains("widget");
        assertThat(stdout).containsIgnoringCase("rebuild");
        assertThat(stdout).contains("compile-main");
        // Never built → the module rebuilds: "□ full compile · N sources".
        assertThat(stdout).contains("full compile");
    }

    @Test
    void explain_reports_hit_after_build(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");

        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());

        String stdout = captureStdout(() -> run("explain", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        // After a real build the module is cached: the "Fully Cached" section (or a
        // "✓ cached <key>" step if a downstream step still rebuilds).
        assertThat(stdout).containsIgnoringCase("cached");
    }

    @Test
    void explain_graph_dot_single_module(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        // --graph does not need a lock or engine
        String stdout = captureStdout(() -> run("explain", "--graph", "dot", "-C", tempDir.toString()));
        assertThat(stdout).contains("digraph modules");
        assertThat(stdout).contains("widget");
        assertThat(stdout).doesNotContain("Build Plan");
    }

    @Test
    void explain_graph_dot_workspace_edge(@TempDir Path tempDir) throws Exception {
        // Minimal workspace: root + lib + app (app depends on lib)
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(tempDir.resolve("lib/jk.toml"), """
                [project]
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(tempDir.resolve("app/jk.toml"), """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0.0" }
                """);

        String stdout = captureStdout(() -> run("explain", "--graph", "dot", "-C", tempDir.toString()));
        assertThat(stdout).contains("com.example:lib");
        assertThat(stdout).contains("com.example:app");
        assertThat(stdout).contains("->");

        Path out = tempDir.resolve("modules.dot");
        int exit = run("explain", "--graph", "dot", "--graph-out", out.toString(), "-C", tempDir.toString());
        assertThat(exit).isZero();
        assertThat(Files.readString(out)).contains("digraph modules");
    }

    // --- helpers -----------------------------------------------------------

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
