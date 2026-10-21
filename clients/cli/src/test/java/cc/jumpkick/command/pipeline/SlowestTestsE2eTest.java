// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.util.MarkdownReports;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code jk test} with a slow fast-tier method names it three ways — the {@code slowest-tests}
 * line under {@code -O jsonl}, the class-finish line's {@code slow} methods, and the {@code jk
 * results} table — and the root's {@code fast-method-budget} warns about it in a member. A replay
 * from the green stamp times nothing, so it ranks nothing.
 */
// Network: JUnit from Central; two forked test JVMs.
@Tag("integration")
class SlowestTestsE2eTest {

    @Test
    void a_slow_method_is_ranked_and_over_the_roots_budget_warns(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        String cache = tmp.resolve("cache").toString();
        assertThat(run("lock", "-C", ws.toString(), "--cache-dir", cache)).isEqualTo(0);

        String jsonl =
                Capture.stdout(() -> assertThat(run("test", "-O", "jsonl", "-C", ws.toString(), "--cache-dir", cache))
                        .isEqualTo(0));
        System.out.println("=== first run ===\n" + jsonl);
        assertThat(jsonl)
                .contains("\"type\":\"slowest-tests\"")
                .contains("\"method\":\"naps\",\"tier\":\"fast\"")
                .contains("\"tier\":\"fast\",\"slow\":[{\"method\":\"naps\",\"millis\":")
                .contains("\"code\":\"test-budget\"")
                .contains("com.example.NapTest#naps took ")
                .doesNotContain("\"method\":\"quick\"");
        assertThat(jsonl.indexOf("\"type\":\"slowest-tests\""))
                .isLessThan(jsonl.indexOf("\"type\":\"workspace-finish\""));
        String md = MarkdownReports.strip(Files.readString(ws.resolve("target/jk-results.md")));
        assertThat(md).contains("## Slowest tests").contains("| `com.example.NapTest#naps` | com.example:lib | fast |");

        String replay =
                Capture.stdout(() -> assertThat(run("test", "-O", "jsonl", "-C", ws.toString(), "--cache-dir", cache))
                        .isEqualTo(0));
        assertThat(replay).as("a suite served from cache is not timed").doesNotContain("slowest-tests");
    }

    /** One member, {@code lib}, with a 1.2s test and a quick one; the root holds it to 500ms. */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib"]

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                [test]
                fast-method-budget = "500ms"
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                """);
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), """
                package com.example;
                public final class Lib {
                    public static int one() { return 1; }
                }
                """);
        Files.createDirectories(lib.resolve("test/src/com/example"));
        Files.writeString(lib.resolve("test/src/com/example/NapTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class NapTest {
                    @Test
                    void naps() throws InterruptedException {
                        Thread.sleep(1_200);
                        assertEquals(1, Lib.one());
                    }

                    @Test
                    void quick() {
                        assertEquals(1, Lib.one());
                    }
                }
                """);
        return ws;
    }
}
