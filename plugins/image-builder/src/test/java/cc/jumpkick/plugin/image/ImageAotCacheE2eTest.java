// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.host.Processes;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.testing.TestCaches;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
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

    /**
     * The image is linux/amd64 on every host. On an arm64 one (a Mac) the training runs and the
     * checks below run the amd64 JVM under emulation, and the cache it maps is the amd64 one.
     */
    @Test
    void an_image_with_aot_cache_ships_a_cache_its_jvm_maps(@TempDir Path tmp) throws Exception {
        String runtime = AotCacheTrainer.containerRuntime(null);
        Assumptions.assumeTrue(runtime != null, "no container runtime on PATH");
        // A bare `info`: podman's report has no ServerVersion field, so a Docker-only format
        // skipped this test on every podman host.
        Assumptions.assumeTrue(command(List.of(runtime, "info")).exit() == 0, "the container daemon is not answering");

        // The runtime holds the host's own variant of the base, as it does after any native run of
        // it: a training run that does not ask for the image's platform takes that one.
        String hostArch = System.getProperty("os.arch").equals("aarch64") ? "arm64" : "amd64";
        Output native_ = command(List.of(runtime, "pull", "-q", "--platform", "linux/" + hostArch, BASE));
        assertThat(native_.exit()).as(native_.text()).isZero();

        String version = "0.1.0-" + UUID.randomUUID().toString().substring(0, 8);
        ImageConfig config = new ImageConfig(
                BASE,
                "jk-aot-e2e",
                null,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                List.of(),
                "example.Main",
                null,
                null,
                true,
                true,
                false);
        // A release jar the application calls and a snapshot jar beside it: each ships in a layer
        // of its own below the application, and the cache trained over all three still maps.
        Path greeter = jar(tmp, "greeter", "example.lib", "Greeter", """
                package example.lib;

                public class Greeter {
                    public static String greeting() {
                        return "Hello from the image";
                    }
                }
                """, null, List.of());
        Path sibling = jar(tmp, "sibling", "example.sibling", "Sibling", """
                package example.sibling;

                public class Sibling {}
                """, null, List.of());
        Path app = jar(tmp, "jk-aot-e2e", "example", "Main", """
                package example;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println(example.lib.Greeter.greeting());
                    }
                }
                """, "example.Main", List.of(greeter));
        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                config,
                "jk-aot-e2e",
                version,
                "example.Main",
                app,
                List.of(greeter),
                List.of(sibling),
                null,
                Map.of(greeter, "greeter-1.0.jar", sibling, "sibling-0.1.0-SNAPSHOT.jar"),
                null,
                null,
                null);
        String ref = config.targetReference("jk-aot-e2e", version);

        try {
            // Loaded into the runtime the test found: Jib's own default is docker, which a podman host lacks.
            ImageBuilder.Result result = ImageBuilder.loadToLocalDaemon(
                    plan, Path.of(runtime), TestCaches.dir("image-aot-e2e"), RegistryAuth.NONE);
            assertThat(result.imageReference()).isEqualTo(ref);
            assertThat(result.aotNotice()).isNull();
            Output arch = command(List.of(runtime, "image", "inspect", ref, "--format", "{{.Architecture}}"));
            assertThat(lastLine(arch)).isEqualTo("amd64");

            Output inspect =
                    command(List.of(runtime, "image", "inspect", ref, "--format", "{{json .Config.Entrypoint}}"));
            assertThat(inspect.exit()).as(inspect.text()).isZero();
            assertThat(inspect.text()).contains("-XX:AOTCache=app.aot");

            // Base layers, then release jars, snapshot jars, the application and the cache.
            Output history = command(List.of(runtime, "history", "--format", "{{.Size}}", ref));
            assertThat(history.exit()).as(history.text()).isZero();
            Output layers = command(List.of(runtime, "image", "inspect", ref, "--format", "{{len .RootFS.Layers}}"));
            Output pulled = command(List.of(runtime, "pull", "-q", "--platform", "linux/amd64", BASE));
            assertThat(pulled.exit()).as(pulled.text()).isZero();
            Output baseLayers =
                    command(List.of(runtime, "image", "inspect", BASE, "--format", "{{len .RootFS.Layers}}"));
            assertThat(layers.exit()).as(layers.text()).isZero();
            assertThat(baseLayers.exit()).as(baseLayers.text()).isZero();
            assertThat(Integer.parseInt(lastLine(layers)) - Integer.parseInt(lastLine(baseLayers)))
                    .as("four layers above the base: " + history.text())
                    .isEqualTo(4);

            Output listing = command(List.of(runtime, "run", "--rm", "--entrypoint", "ls", ref, "-l", "/app/app.aot"));
            assertThat(listing.exit()).as(listing.text()).isZero();

            // The image's JVM maps the cache: JAVA_TOOL_OPTIONS reaches the entrypoint's java and
            // -Xlog:aot is the only place a refusal shows.
            Output run = command(List.of(
                    runtime,
                    "run",
                    "--rm",
                    "--platform",
                    "linux/amd64",
                    "-e",
                    "JAVA_TOOL_OPTIONS=-Xlog:aot=info",
                    ref));
            assertThat(run.exit()).as(run.text()).isZero();
            assertThat(AotCacheFiles.refusal(run.text())).as(run.text()).isNull();
            assertThat(run.text()).contains("Hello from the image");

            // -XX:AOTMode=on refuses to start at all when the cache does not match the classpath:
            // the layered tree is the one it was trained over.
            Output strict = command(List.of(
                    runtime,
                    "run",
                    "--rm",
                    "--platform",
                    "linux/amd64",
                    "-e",
                    "JAVA_TOOL_OPTIONS=-XX:AOTMode=on",
                    ref));
            assertThat(strict.exit()).as(strict.text()).isZero();
            assertThat(strict.text()).contains("Hello from the image");
        } finally {
            command(List.of(runtime, "rmi", "-f", ref));
        }
    }

    /** A one-class jar {@code name}.jar, compiled by this JVM against {@code classpath}, with {@code Main-Class} when given. */
    private static Path jar(
            Path tmp,
            String name,
            String pkg,
            String cls,
            String source,
            @Nullable String mainClass,
            List<Path> classpath)
            throws IOException {
        Path src = Files.createDirectories(tmp.resolve(name + "-src/" + pkg.replace('.', '/')))
                .resolve(cls + ".java");
        Files.writeString(src, source);
        Path classes = Files.createDirectories(tmp.resolve(name + "-classes"));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        if (!classpath.isEmpty()) {
            args.add("-cp");
            args.add(String.join(
                    File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        }
        args.add(src.toString());
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        assertThat(rc).isZero();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        Path jar = tmp.resolve(name + ".jar");
        String entry = pkg.replace('.', '/') + "/" + cls + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry(entry));
            out.write(Files.readAllBytes(classes.resolve(entry)));
            out.closeEntry();
        }
        return jar;
    }

    private record Output(int exit, String text) {}

    /** The command's last line: a docker shim over podman prints a notice before the answer. */
    private static String lastLine(Output output) {
        List<String> lines = output.text().strip().lines().toList();
        return lines.isEmpty() ? "" : lines.getLast().strip();
    }

    private static Output command(List<String> command) throws IOException {
        Processes.Captured run = Processes.capture(command, Duration.ofSeconds(180));
        if (run.timedOut()) return new Output(-1, "timed out: " + String.join(" ", command));
        return new Output(run.exit(), run.output());
    }
}
