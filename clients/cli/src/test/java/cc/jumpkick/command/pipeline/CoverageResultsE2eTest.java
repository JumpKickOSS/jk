// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage is a battery: {@code jk test --coverage} over a two-module workspace leaves a {@code ##
 * Coverage} block in {@code jk-results.md} with each module's line and branch percentages, a JaCoCo
 * HTML page per module and a workspace roll-up, and the next coverage run shows the change against
 * this one. {@code [test] coverage = true} turns the same machinery on without the flag.
 */
// Network: JUnit and JaCoCo from Central; three forked test JVM rounds.
@Tag("integration")
class CoverageResultsE2eTest {

    @Test
    void two_coverage_runs_leave_the_block_the_html_and_a_delta(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp, false);
        String cache = tmp.resolve("cache").toString();
        assertThat(run("lock", "-C", ws.toString(), "--cache-dir", cache)).isEqualTo(0);

        assertThat(run("test", "--coverage", "-C", ws.toString(), "--cache-dir", cache))
                .isEqualTo(0);
        String first = Files.readString(ws.resolve("target/jk-results.md"));
        System.out.println("=== first run ===\n" + first);
        assertThat(first)
                .contains("## Coverage")
                .contains("| com.example:lib |")
                .contains("| com.example:app |")
                .contains("| **all** |")
                .contains("- Coverage HTML: `target/reports/coverage/index.html`")
                .doesNotContain("| Δ |");
        assertThat(first).containsPattern("\\| com\\.example:lib \\| 50\\.0% \\(\\d+/\\d+\\) \\|");
        assertThat(ws.resolve("target/lib/reports/jacoco.xml")).isRegularFile();
        assertThat(ws.resolve("target/lib/reports/coverage/index.html")).isRegularFile();
        assertThat(ws.resolve("target/app/reports/coverage/index.html")).isRegularFile();
        assertThat(ws.resolve("target/reports/coverage/index.html")).isRegularFile();
        assertThat(Files.readString(ws.resolve("target/reports/coverage/index.html")))
                .contains("com.example:lib")
                .contains("../../lib/reports/coverage/index.html");

        // Cover the second branch of Lib.twice: lib climbs (its implicit constructor stays the one
        // uncovered line), app is unchanged.
        Files.writeString(ws.resolve("lib/test/src/com/example/LibTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class LibTest {
                    @Test
                    void twice() {
                        assertEquals(4, Lib.twice(2));
                        assertEquals(0, Lib.twice(0));
                    }
                }
                """);
        assertThat(run("test", "--coverage", "-C", ws.toString(), "--cache-dir", cache))
                .isEqualTo(0);
        String second = Files.readString(ws.resolve("target/jk-results.md"));
        System.out.println("=== second run ===\n" + second);
        assertThat(second)
                .contains("| Module | Lines | Δ | Branches | Δ |")
                .containsPattern("_Δ vs run #\\d+_")
                .containsPattern(
                        "\\| com\\.example:lib \\| 75\\.0% \\(3/4\\) \\| \\+25\\.0 \\| 100\\.0% \\(2/2\\) \\| \\+50\\.0 \\|")
                .containsPattern("\\| com\\.example:app \\| [0-9.]+% \\(\\d+/\\d+\\) \\| ±0\\.0 \\|");
    }

    @Test
    void the_manifest_key_turns_coverage_on_without_the_flag(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp, true);
        String cache = tmp.resolve("cache").toString();
        assertThat(run("lock", "-C", ws.toString(), "--cache-dir", cache)).isEqualTo(0);

        assertThat(run("test", "-C", ws.toString(), "--cache-dir", cache)).isEqualTo(0);
        String md = Files.readString(ws.resolve("target/jk-results.md"));
        assertThat(md)
                .as("only lib declares [test] coverage = true")
                .contains("## Coverage")
                .contains("| com.example:lib | 50.0% (2/4) | 50.0% (1/2) |")
                .doesNotContainPattern("\\| com\\.example:app \\| \\d+\\.\\d% ");
        assertThat(ws.resolve("target/lib/reports/coverage/index.html")).isRegularFile();
        assertThat(ws.resolve("target/app/reports/jacoco.xml")).doesNotExist();
    }

    /** {@code lib} (a branch to cover) and {@code app} depending on it; JUnit from Central. */
    private static Path workspace(Path tmp, boolean libDeclaresCoverage) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        String junit = """
                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                """;
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(
                lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                """ + junit + (libDeclaresCoverage ? "\n[test]\ncoverage = true\n" : ""));
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), """
                package com.example;
                public final class Lib {
                    public static int twice(int n) {
                        if (n == 0) {
                            return 0;
                        }
                        return n * 2;
                    }
                }
                """);
        Files.createDirectories(lib.resolve("test/src/com/example"));
        Files.writeString(lib.resolve("test/src/com/example/LibTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class LibTest {
                    @Test
                    void twice() {
                        assertEquals(4, Lib.twice(2));
                    }
                }
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { workspace = true }
                """ + junit);
        Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(app.resolve("src/com/example/App.java"), """
                package com.example;
                public final class App {
                    public static int useLib(int n) { return Lib.twice(n); }
                    public static int unused() { return 1; }
                }
                """);
        Files.createDirectories(app.resolve("test/src/com/example"));
        Files.writeString(app.resolve("test/src/com/example/AppTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class AppTest {
                    @Test
                    void usesLib() {
                        assertEquals(6, App.useLib(3));
                    }
                }
                """);
        return ws;
    }
}
