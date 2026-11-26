// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk publish --only image} in Dockerfile mode, through the CLI and the engine, with a docker executable that
 * records what it was asked: a push says once that it attaches no SBOM or provenance, and says
 * nothing when {@code [image] referrers = false} asked for none.
 */
@Tag("integration")
@DisabledOnOs(OS.WINDOWS)
class ImageDockerfileNoticeE2eTest {

    private static final String NOTICE =
            "[image] no SBOM or provenance attached to registry.invalid/acme/widget:0.1.0: Dockerfile mode";

    @Test
    void a_dockerfile_push_says_it_attaches_nothing(@TempDir Path dir) throws Exception {
        Path calls = project(dir, "");

        int[] exit = {-1};
        Capture.Streams said = Capture.both(() -> exit[0] = publishImage(dir));

        assertThat(exit[0]).as(said.out() + said.err()).isZero();
        assertThat(Files.readAllLines(calls))
                .as("the image is built from the Dockerfile and pushed by docker")
                .anySatisfy(line -> assertThat(line).startsWith("build -f " + dir.resolve("Dockerfile")))
                .contains("push registry.invalid/acme/widget:0.1.0");
        String console = said.out() + said.err();
        assertThat(console.split(Pattern.quote(NOTICE), -1))
                .as("the notice is said once: %s", console)
                .hasSize(2);
    }

    @Test
    void a_dockerfile_push_with_referrers_off_says_nothing(@TempDir Path dir) throws Exception {
        Path calls = project(dir, "referrers = false\n");

        int[] exit = {-1};
        Capture.Streams said = Capture.both(() -> exit[0] = publishImage(dir));

        assertThat(exit[0]).as(said.out() + said.err()).isZero();
        assertThat(Files.readAllLines(calls)).contains("push registry.invalid/acme/widget:0.1.0");
        assertThat(said.out() + said.err()).doesNotContain("no SBOM or provenance");
    }

    private static int publishImage(Path dir) {
        return run("publish", "-C", dir.toString(), "--only", "image");
    }

    /**
     * A Java application whose {@code [image]} builds from a Dockerfile with {@code docker}, a script
     * that appends each command line to the file it returns and succeeds.
     */
    private static Path project(Path dir, String extraImageKeys) throws IOException {
        Path calls = dir.resolve("docker-calls.txt");
        Path docker = dir.resolve("bin/docker");
        Files.createDirectories(docker.getParent());
        Files.writeString(docker, "#!/bin/sh\necho \"$*\" >> '" + calls + "'\n");
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "widget"
                version = "0.1.0"
                java = 25

                [application]
                main = "example.Main"

                [image]
                registry = "registry.invalid/acme"
                docker-file = "Dockerfile"
                docker-executable = "%s"
                %s""".formatted(docker, extraImageKeys));
        Files.writeString(dir.resolve("Dockerfile"), "FROM scratch\n");
        Path main = dir.resolve("src/main/java/example/Main.java");
        Files.createDirectories(main.getParent());
        Files.writeString(main, """
                package example;
                public final class Main {
                    public static void main(String[] args) {}
                }
                """);
        return calls;
    }
}
