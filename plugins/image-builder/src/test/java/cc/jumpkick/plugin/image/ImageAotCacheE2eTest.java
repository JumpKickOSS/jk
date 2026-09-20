// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.testing.TestCaches;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Protects the user-facing {@code [image] aot-cache = true}: the image ships {@code /app/app.aot},
 * its entrypoint maps it, and the image's own JVM accepts the cache without refusal. Needs a
 * container runtime and the network for the base image; skipped without either.
 */
@Tag("slow")
class ImageAotCacheE2eTest {

    private static final String BASE = "docker.io/bellsoft/liberica-runtime-container:jre-25-slim-glibc";

    @Test
    void an_image_with_aot_cache_ships_a_cache_its_jvm_maps(@TempDir Path tmp) throws Exception {
        String runtime = AotCacheTrainer.containerRuntime(null);
        Assumptions.assumeTrue(runtime != null, "no container runtime on PATH");
        Assumptions.assumeTrue(
                command(List.of(runtime, "info", "--format", "{{.ServerVersion}}"))
                                .exit()
                        == 0,
                "the container daemon is not answering");

        String version = "0.1.0-" + UUID.randomUUID().toString().substring(0, 8);
        ImageConfig config = new ImageConfig(
                BASE,
                "jk-aot-e2e",
                null,
                List.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                List.of(),
                "example.Main",
                null,
                null,
                true);
        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                config, "jk-aot-e2e", version, "example.Main", helloJar(tmp), List.of(), List.of(), null);
        String ref = config.targetReference("jk-aot-e2e", version);

        try {
            ImageBuilder.Result result =
                    ImageBuilder.loadToLocalDaemon(plan, null, TestCaches.dir("image-aot-e2e"), RegistryAuth.NONE);
            assertThat(result.imageReference()).isEqualTo(ref);

            Output inspect =
                    command(List.of(runtime, "image", "inspect", ref, "--format", "{{json .Config.Entrypoint}}"));
            assertThat(inspect.exit()).as(inspect.text()).isZero();
            assertThat(inspect.text()).contains("-XX:AOTCache=app.aot");

            Output listing = command(List.of(runtime, "run", "--rm", "--entrypoint", "ls", ref, "-l", "/app/app.aot"));
            assertThat(listing.exit()).as(listing.text()).isZero();

            // The image's JVM maps the cache: JAVA_TOOL_OPTIONS reaches the entrypoint's java and
            // -Xlog:aot is the only place a refusal shows.
            Output run = command(List.of(runtime, "run", "--rm", "-e", "JAVA_TOOL_OPTIONS=-Xlog:aot=info", ref));
            assertThat(run.exit()).as(run.text()).isZero();
            assertThat(AotCacheFiles.refusal(run.text())).as(run.text()).isNull();
            assertThat(run.text()).contains("Hello from the image");
        } finally {
            command(List.of(runtime, "rmi", "-f", ref));
        }
    }

    /** A one-class application jar with {@code Main-Class}, compiled by this JVM. */
    private static Path helloJar(Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/example")).resolve("Main.java");
        Files.writeString(src, """
                package example;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println("Hello from the image");
                    }
                }
                """);
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.toString());
        assertThat(rc).isZero();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "example.Main");
        Path jar = tmp.resolve("jk-aot-e2e.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("example/Main.class"));
            out.write(Files.readAllBytes(classes.resolve("example/Main.class")));
            out.closeEntry();
        }
        return jar;
    }

    private record Output(int exit, String text) {}

    private static Output command(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(new ArrayList<>(command))
                .redirectErrorStream(true)
                .start();
        byte[] bytes = process.getInputStream().readAllBytes();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new Output(-1, "timed out: " + String.join(" ", command));
        }
        return new Output(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
    }
}
