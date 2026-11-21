// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk image} refuses a hand-written base whose reference names a Java older than the
 * module's level, before it fetches the base or forks the image worker: a {@code java = 25}
 * application on {@code eclipse-temurin:21-jre} would build an image whose JVM cannot load its
 * classes. On the base, the image was written and failed only when started.
 */
@Tag("integration")
class ImageBaseJavaE2eTest {

    @Test
    void a_base_older_than_the_module_s_java_is_refused_naming_both(@TempDir Path dir) throws Exception {
        write(dir.resolve("jk.toml"), """
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [application]
                main = "example.Main"

                [image]
                base = "eclipse-temurin:21-jre"
                """);
        write(dir.resolve("src/main/java/example/Main.java"), """
                package example;
                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);
        Path tarball = dir.resolve("out/app.tar");
        int[] code = new int[1];
        Capture.Streams out = Capture.both(() -> code[0] = run(
                "image",
                "-C",
                dir.toString(),
                "--cache-dir",
                dir.resolve("cache").toString(),
                "--tarball",
                tarball.toString()));

        assertThat(code[0]).as(out.toString()).isNotZero();
        assertThat(out.out() + out.err())
                .contains("eclipse-temurin:21-jre", "Java 21", "Java 25", "Remove [image] base");
        assertThat(tarball).doesNotExist();
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}
