// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk image} builds from source through the shared pipeline — no prior {@code jk build}. A
 * project with no main class compiles + packages (proving the pipeline ran), then the image tail
 * rejects it with EX_USAGE (64).
 */
@Tag("integration")
class ImageCommandTest {

    @Test
    void builds_from_source_then_fails_image_without_main(@TempDir Path tempDir) throws Exception {
        Files.writeString(
                tempDir.resolve("jk.toml"),
                "[project]\ngroup = \"com.example\"\nname = \"widget\"\nversion = \"0.1.0\"\njava = 25\n");
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example;\npublic class Hello {}\n");

        int exit = run(
                "image",
                "-C",
                tempDir.toString(),
                "--tarball",
                tempDir.resolve("out.oci.tar").toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());

        assertThat(exit).isEqualTo(64);
        assertThat(tempDir.resolve("target/lib/widget-0.1.0.jar")).exists();
        assertThat(tempDir.resolve("jk-lock.toml")).exists();
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
