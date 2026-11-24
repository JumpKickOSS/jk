// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.model.command.Exit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk test -S <module>:<class>}: one run takes one module's whole suite and only the named
 * class of another; a qualified class that matches nothing in its module fails the run naming
 * that module; a qualifier that names no module of the run is refused before anything builds.
 */
// Network: JUnit from Central; forked test JVMs.
@Tag("integration")
class ModuleQualifiedClassE2eTest {

    @Test
    void one_run_takes_one_modules_whole_suite_and_one_class_of_another(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        String dir = ws.toString();
        String cache = tmp.resolve("cache").toString();
        assertThat(run("lock", "-C", dir, "--cache-dir", cache)).isEqualTo(Exit.SUCCESS);

        AtomicInteger exit = new AtomicInteger(-1);
        String out = Capture.stdout(() -> exit.set(run(
                "test", "-C", dir, "--cache-dir", cache, "--no-ansi", "-X", "-m", "a", "-m", "b", "-S", "b:BTwoTest")));
        assertThat(exit.get()).as(out).isEqualTo(Exit.SUCCESS);
        String ran = Capture.stdout(() -> run("results", "-C", dir, "-O", "jsonl"));
        assertThat(ran)
                .as("a's two classes and b's one named class")
                .contains("com.example.a.ATest", "com.example.a.AOtherTest", "com.example.b.BTwoTest")
                .doesNotContain("com.example.b.BOneTest");

        String missing = Capture.both(() -> exit.set(run(
                        "test",
                        "-C",
                        dir,
                        "--cache-dir",
                        cache,
                        "--no-ansi",
                        "-X",
                        "-m",
                        "a",
                        "-m",
                        "b",
                        "-S",
                        "b:ATest")))
                .toString();
        assertThat(exit.get()).as(missing).isEqualTo(Exit.USAGE);
        assertThat(missing).contains("b: no test classes matched --class ATest");

        String outside = Capture.both(() -> exit.set(
                        run("test", "-C", dir, "--cache-dir", cache, "--no-ansi", "-X", "-m", "a", "-S", "b:BTwoTest")))
                .toString();
        assertThat(exit.get()).as(outside).isEqualTo(Exit.USAGE);
        assertThat(outside).contains("--class b:BTwoTest: b is not in this run; add -m b");
    }

    /** Two modules, each with two passing test classes; JUnit from Central. */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["a", "b"]
                """);
        module(ws, "a", "ATest", "AOtherTest");
        module(ws, "b", "BOneTest", "BTwoTest");
        return ws;
    }

    private static void module(Path ws, String name, String... testClasses) throws IOException {
        Path module = Files.createDirectories(ws.resolve(name));
        Files.writeString(module.resolve("jk.toml"), """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                java    = 25

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                """.formatted(name));
        Path tests = Files.createDirectories(module.resolve("test/src/com/example/" + name));
        for (String cls : testClasses) {
            Files.writeString(tests.resolve(cls + ".java"), """
                    package com.example.%s;

                    import static org.junit.jupiter.api.Assertions.assertEquals;

                    import org.junit.jupiter.api.Test;

                    class %s {
                        @Test
                        void adds() {
                            assertEquals(3, 1 + 2);
                        }
                    }
                    """.formatted(name, cls));
        }
    }
}
