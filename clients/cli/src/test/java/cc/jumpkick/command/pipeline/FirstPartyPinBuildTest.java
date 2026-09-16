// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkVersion;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock another jk wrote pins a first-party plugin to bytes this jk does not ship. The build is
 * green, the row follows this jk, the library rows stay, and the build says so in one line.
 */
@Tag("integration")
class FirstPartyPinBuildTest {

    private static final String PLUGIN = "cc.jumpkick:jk-spring-boot";
    private static final Lockfile.PluginEntry STALE =
            new Lockfile.PluginEntry(PLUGIN, "0.0.1", "sha256:" + "ee".repeat(32));
    private static final Lockfile.PluginEntry VENDORED =
            new Lockfile.PluginEntry("com.acme:acme-rules", "2.0.0", "sha256:" + "dd".repeat(32));

    @Test
    void a_stale_first_party_row_builds_green_and_follows_this_jk(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "hello"
                version = "0.1.0"
                java    = 25
                """);
        Path src = dir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}\n");
        Path lockFile = dir.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("0.0.1").withPlugins(List.of(STALE, VENDORED)), lockFile);
        Lockfile before = LockfileReader.read(lockFile);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run(
                    "build",
                    "--skip-tests",
                    "-C",
                    dir.toString(),
                    "--cache-dir",
                    dir.resolve("cache").toString());
        } finally {
            System.setOut(originalOut);
        }

        String out = stdout.toString(StandardCharsets.UTF_8);
        assertThat(exit).as(out).isZero();
        assertThat(out.lines().filter(l -> l.contains("first-party plugins follow the running jk")))
                .as("said once:%n%s", out)
                .hasSize(1)
                .first()
                .asString()
                .contains("jk-spring-boot 0.0.1 → " + JkVersion.VERSION);

        Lockfile after = LockfileReader.read(lockFile);
        assertThat(after.generatedBy()).isEqualTo("jk " + JkVersion.VERSION);
        assertThat(after.manifestsSha256()).isEqualTo(before.manifestsSha256());
        assertThat(after.artifacts()).isEqualTo(before.artifacts());
        assertThat(after.plugins())
                .extracting(Lockfile.PluginEntry::coordinate)
                .containsExactlyInAnyOrder(PLUGIN, VENDORED.coordinate());
        assertThat(after.plugins()).contains(VENDORED);
        Lockfile.PluginEntry row = after.plugins().stream()
                .filter(e -> e.coordinate().equals(PLUGIN))
                .findFirst()
                .orElseThrow();
        assertThat(row.version()).isEqualTo(JkVersion.VERSION);
        assertThat(row.isWorkspace()).isFalse();
    }
}
