// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseCommandTest {

    @Test
    void dist_is_alias_for_release(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("dist", "-C", dir.toString(), "--dry-run");
        } finally {
            System.setOut(orig);
        }
        assertThat(exit).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("jk release plan");
    }

    @Test
    void dry_run_prints_plan_without_building(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "0.0.1"
                jdk = 25
                [application]
                main = "demo.App"
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("release", "-C", dir.toString(), "--dry-run", "--skip-tests");
        } finally {
            System.setOut(orig);
        }
        assertThat(exit).isZero();
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("out:");
        assertThat(text).contains("skip-tests:  true");
        assertThat(Files.exists(dir.resolve("target/dist"))).isFalse();
    }

    @Test
    void help_lists_release(@TempDir Path dir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            Jk.execute("--help");
        } finally {
            System.setOut(orig);
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("release");
    }
}
