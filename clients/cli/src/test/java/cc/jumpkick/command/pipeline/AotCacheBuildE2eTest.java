// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.AotCacheFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Protects the user-facing {@code jk build --aot-cache}: the build trains {@code
 * target/aot-cache/app.aot}, the JVM the manifest names maps it without refusal, and a build that
 * changes the application discards the now-void cache.
 */
@Tag("integration")
class AotCacheBuildE2eTest {

    @Test
    void build_with_aot_cache_trains_a_cache_the_jvm_maps_and_a_changed_app_discards_it(@TempDir Path tmp)
            throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group   = "com.example"
                name    = "aotapp"
                version = "0.1.0"
                java    = 25

                [application]
                main = "example.Main"
                """);
        Path main =
                Files.createDirectories(tmp.resolve("src/main/java/example")).resolve("Main.java");
        Files.writeString(main, """
                package example;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println("Hello from aotapp");
                    }
                }
                """);
        Path cache = tmp.resolve("cache");

        int[] exit = new int[1];
        Capture.Streams streams = Capture.both(
                () -> exit[0] = run("build", "--aot-cache", "-C", tmp.toString(), "--cache-dir", cache.toString()));
        String console = streams.out() + streams.err();
        assertThat(exit[0]).as(console).isEqualTo(0);
        assertThat(console).contains("verified");

        Path outDir = tmp.resolve("target/aot-cache");
        Path aot = outDir.resolve("app.aot");
        assertThat(aot).isRegularFile();
        assertThat(Files.size(aot)).isGreaterThan(0);
        String manifest = Files.readString(outDir.resolve(AotCachePackage.MANIFEST));
        assertThat(manifest)
                .contains("cache        = \"app.aot\"")
                .contains("built-from")
                .contains("lock-sha256");
        Path launcher = Files.exists(outDir.resolve("run.cmd")) ? outDir.resolve("run.cmd") : outDir.resolve("run.sh");
        assertThat(Files.readString(launcher)).contains("-XX:AOTCache=app.aot");

        // The JVM the manifest pins maps the cache: -Xlog:aot is the only place a refusal shows.
        String java = value(manifest, "java-home");
        String appJar;
        try (Stream<Path> files = Files.list(outDir)) {
            appJar = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jar"))
                    .sorted()
                    .findFirst()
                    .orElseThrow();
        }
        String verify = output(outDir, List.of(java, "-Xlog:aot=info", "-XX:AOTCache=app.aot", "-jar", appJar));
        assertThat(AotCacheFiles.refusal(verify)).as(verify).isNull();
        assertThat(verify).contains("Hello from aotapp");

        // A changed application voids the cache; the next plain build removes it rather than
        // leaving tens of MiB that look like a deliverable.
        Files.writeString(main, Files.readString(main).replace("Hello from", "Hello again from"));
        assertThat(run("build", "-C", tmp.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        assertThat(outDir).doesNotExist();
    }

    private static String output(Path dir, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] bytes = process.getInputStream().readAllBytes();
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The value of a {@code key = "value"} line in the cache manifest. */
    private static String value(String toml, String key) {
        for (String line : toml.split("\n")) {
            String t = line.trim();
            if (!t.startsWith(key)) continue;
            int q = t.indexOf('"');
            int end = t.lastIndexOf('"');
            if (q > 0 && end > q) return t.substring(q + 1, end);
        }
        throw new AssertionError("no `" + key + "` in\n" + toml);
    }
}
