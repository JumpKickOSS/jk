// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.layout.NativePreflight;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk native} pre-fails before any compile when Graal or a unique main is missing. A project
 * without {@code [native]} is no longer refused for that reason.
 */
@Tag("integration")
class NativeCommandTest {

    @Test
    void refuses_before_build_when_preflight_fails(@TempDir Path tempDir) throws Exception {
        Files.writeString(
                tempDir.resolve("jk.toml"),
                "[project]\ngroup = \"com.example\"\nname = \"widget\"\nversion = \"0.1.0\"\n" + "java = 25\n");
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example;\npublic class Hello { public int n() { return 1; } }\n");

        var err = new ByteArrayOutputStream();
        PrintStream prev = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = run(
                    "native",
                    "-C",
                    tempDir.toString(),
                    "--cache-dir",
                    tempDir.resolve("cache").toString());
        } finally {
            System.setErr(prev);
        }

        assertThat(exit).isEqualTo(2);
        assertThat(tempDir.resolve("target/widget-0.1.0.jar")).doesNotExist();
        String text = err.toString(StandardCharsets.UTF_8);
        assertThat(text)
                .containsAnyOf(
                        NativePreflight.GRAAL_UNSET,
                        NativePreflight.NATIVE_IMAGE_MISSING,
                        NativePreflight.NO_MAIN,
                        NativePreflight.MANY_MAINS);
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
