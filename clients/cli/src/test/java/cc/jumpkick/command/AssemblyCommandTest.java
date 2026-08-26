// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AssemblyCommandTest {

    private static final String TOML = """
            group = "t"
            name = "t"
            version = "0.0.1"
            jdk = 25
            [application]
            main = "demo.App"
            """;

    @Test
    void without_assembly_prints_fix_and_exits_config(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), TOML);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("assemble", "-C", dir.toString());
        } finally {
            System.setErr(orig);
        }
        assertThat(exit).isEqualTo(2);
        String msg = err.toString(StandardCharsets.UTF_8);
        assertThat(msg).contains("jk assemble");
        assertThat(msg).contains("assembly = true");
        assertThat(msg).contains("--fat");
        assertThat(msg).contains("--minified");
        assertThat(msg).contains("--write-config");
    }

    @Test
    void assembly_alias_dispatches_to_assemble(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), TOML);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("assembly", "-C", dir.toString());
        } finally {
            System.setErr(orig);
        }
        assertThat(exit).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("jk assemble");
    }

    @Test
    void fat_and_minified_are_mutually_exclusive(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), TOML);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("assemble", "-C", dir.toString(), "--fat", "--minified");
        } finally {
            System.setErr(orig);
        }
        assertThat(exit).isEqualTo(64); // Exit.USAGE
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--fat").contains("--minified");
    }

    @Test
    void write_config_without_mode_is_usage(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), TOML);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("assemble", "-C", dir.toString(), "--write-config");
        } finally {
            System.setErr(orig);
        }
        assertThat(exit).isEqualTo(64); // Exit.USAGE
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--write-config");
    }

    @Test
    void write_config_shrink_edits_toml_surgically(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), TOML);
        // No sources / no engine needed for the surgical write path before build may fail.
        // Run with a flag that will still attempt build after write; we only assert the file.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        PrintStream origOut = System.out;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            Jk.execute("assemble", "-C", dir.toString(), "--minified", "--write-config", "--skip-tests");
        } catch (Exception ignored) {
            // engine/build may fail in unit env; config write happens first
        } finally {
            System.setErr(origErr);
            System.setOut(origOut);
        }
        String content = Files.readString(dir.resolve("jk.toml"));
        assertThat(content).contains("main = \"demo.App\"");
        assertThat(content).contains("minified = true").contains("assembly = true");
        JkBuild parsed = JkBuildParser.parse(content);
        assertThat(parsed.minified()).isTrue();
        assertThat(parsed.mainClass()).isEqualTo("demo.App");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("wrote");
    }

    @Test
    void write_config_fat_edits_toml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), TOML);
        PrintStream origErr = System.err;
        PrintStream origOut = System.out;
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            Jk.execute("assemble", "-C", dir.toString(), "--fat", "--write-config", "--skip-tests");
        } catch (Exception ignored) {
            // ignore build failures
        } finally {
            System.setErr(origErr);
            System.setOut(origOut);
        }
        JkBuild written = JkBuildParser.parse(Files.readString(dir.resolve("jk.toml")));
        assertThat(written.assembly()).isTrue();
        assertThat(written.minified()).isFalse();
    }
}
