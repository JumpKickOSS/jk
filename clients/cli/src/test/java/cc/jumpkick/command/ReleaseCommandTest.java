// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.model.JkVersion;
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
    void dry_run_detects_engine_and_cli_modules(@TempDir Path dir) throws Exception {
        Path eng = dir.resolve("server/engine");
        Path cli = dir.resolve("clients/cli");
        Files.createDirectories(eng);
        Files.createDirectories(cli);
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "cc.jumpkick"
                name = "jk"
                version = "0.10.1"
                jdk = 25
                [workspace]
                modules = ["server/engine", "clients/cli"]
                """);
        Files.writeString(
                eng.resolve("jk.toml"),
                """
                [project]
                group = "cc.jumpkick"
                name = "jk-engine"
                version = "0.10.1"
                jdk = 25
                [application]
                main = "cc.jumpkick.engine.EngineMain"
                assembly = true
                """);
        Files.writeString(
                cli.resolve("jk.toml"),
                """
                [project]
                group = "cc.jumpkick"
                name = "jk-cli"
                version = "0.10.1"
                jdk = 25
                [application]
                main = "cc.jumpkick.cli.Jk"
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("release", "-C", dir.toString(), "--dry-run");
        } finally {
            System.setOut(orig);
        }
        assertThat(exit).isZero();
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("server/engine");
        assertThat(text).contains("clients/cli");
        assertThat(text).contains("jk plugin install-local");
    }

    @Test
    void help_lists_release() {
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

    /**
     * Minimal engine workspace: compile + assembly + stage. With no CLI module / native binary,
     * the running {@code jk} is staged as the bootstrap client — enough to exercise the dist layout.
     */
    @Test
    void stages_renamed_engine_assembly_into_out_lib(@TempDir Path dir) throws Exception {
        Path eng = dir.resolve("server/engine");
        Path engSrc = eng.resolve("src/main/java/cc/jumpkick/engine");
        Files.createDirectories(engSrc);
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "cc.jumpkick"
                name = "jk"
                version = "0.10.1"
                jdk = 25
                java = 25
                [workspace]
                modules = ["server/engine"]
                """);
        Files.writeString(
                eng.resolve("jk.toml"),
                """
                [project]
                group = "cc.jumpkick"
                name = "jk-engine"
                version = "0.10.1"
                jdk = 25
                java = 25
                [application]
                main = "cc.jumpkick.engine.EngineMain"
                assembly = true
                """);
        Files.writeString(
                engSrc.resolve("EngineMain.java"),
                """
                package cc.jumpkick.engine;
                public class EngineMain {
                  public static void main(String[] args) {}
                }
                """);

        Path outDir = dir.resolve("dist-out");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            // No CLI module → stages the running jk as bootstrap client + engine assembly.
            // --skip-native avoids a Graal build for this unit fixture.
            exit = Jk.execute(
                    "release",
                    "-C",
                    dir.toString(),
                    "--skip-tests",
                    "--skip-native",
                    "--out",
                    outDir.toString());
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }

        String combined = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
        assertThat(exit).as(combined).isZero();
        Path engineOut = outDir.resolve("lib/jk-engine-" + JkVersion.VERSION + ".jar");
        assertThat(engineOut).exists();
        assertThat(outDir.resolve("jk")).exists();
        assertThat(combined).contains("distribution ready");
        // Must not ship the fat-jar (*-all.jar) filename in lib/
        assertThat(Files.list(outDir.resolve("lib")).map(p -> p.getFileName().toString()).toList())
                .containsExactly("jk-engine-" + JkVersion.VERSION + ".jar");
    }
}
