// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk explain} renders the unified composite build plan (BuildGraph). */
@Tag("integration")
class ExplainCommandTest {

    private static void project(Path dir, String name, String... deps) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("""
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                jdk     = 25
                java    = 25
                """.formatted(name));
        if (deps.length > 0) {
            // Sibling modules are declared by coordinate (inline path deps were removed); the
            // workspace root resolves them to the local module builds.
            sb.append("\n[dependencies]\n");
            for (String d : deps) {
                sb.append("%s = { group = \"com.example\", name = \"%s\", version = \"1.0.0\" }\n".formatted(d, d));
            }
        }
        Files.writeString(dir.resolve("jk.toml"), sb.toString());
    }

    /** A workspace-root jk.toml listing the given modules. */
    private static void workspace(Path dir, String... modules) throws IOException {
        Files.createDirectories(dir);
        String mods =
                String.join(", ", Arrays.stream(modules).map(m -> '"' + m + '"').toList());
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "root"
                version = "1.0.0"

                [workspace]
                modules = [%s]
                """.formatted(mods));
    }

    private static String runExplainCapturingStdout(Path dir) {
        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            System.setOut(ps);
            int exit = Jk.execute(new String[] {"explain", "-C", dir.toString()});
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(orig);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void renders_units_in_dependency_order(@TempDir Path tmp) throws Exception {
        // A two-module workspace: app depends on sibling module lib by coordinate.
        workspace(tmp, "lib", "app");
        project(tmp.resolve("lib"), "lib");
        project(tmp.resolve("app"), "app", "lib");

        // Strip ANSI so color spans between number and unit do not break substring matches.
        String out = TestAnsi.strip(runExplainCapturingStdout(tmp));

        assertThat(out).contains("Build Graph");
        assertThat(out).contains("Plan Item");
        assertThat(out).contains("Modules");
        assertThat(out).contains("2 in workspace");
        // Root keeps group:artifact; dirty module rows are name-only pills (no index).
        assertThat(out).contains("com.example").contains("app").contains("lib");
        assertThat(out).contains("modules are dirty");
        assertThat(out).doesNotContain("[01]").doesNotContain("01");
        // Phase rollup (empty fixtures typically only forecast Compile).
        assertThat(out).contains("Compile");
        // Both modules rebuild (fresh), listed dependency-first: lib before app.
        assertThat(out.indexOf("lib")).isLessThan(out.lastIndexOf("app"));
        // ETA footer still present (value depends on host calibration / history).
        assertThat(out).contains("Build time estimate");
        assertThat(out).contains("Total rebuild effort");
    }
}
