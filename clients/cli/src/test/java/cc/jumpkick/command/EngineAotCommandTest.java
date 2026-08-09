// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.util.AotManifest;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Uses the suite's isolated {@code JK_HOME} (Gradle / pure-jk test harness) so ambient
 * {@link JkDirs#state()} points at a throwaway tree.
 */
class EngineAotCommandTest {

    @Test
    void lists_caches_with_manifest_details() throws Exception {
        Path aot = JkDirs.state().resolve("aot");
        Files.createDirectories(aot);
        String name = "java-compiler-feedfacecafebeef.aot";
        Path cache = aot.resolve(name);
        try {
            Files.writeString(cache, "x".repeat(2048));
            AotManifest.upsert(
                    aot,
                    AotManifest.Entry.builder(name)
                            .tool("java-compiler")
                            .key("feedfacecafebeef")
                            .status("ready")
                            .jdkVendor("TEMURIN")
                            .jdkVersion("25.0.3")
                            .gc("parallel")
                            .classpath(List.of("/plugins/jk-java-compiler.jar"))
                            .jvmFlags(List.of("-XX:+UseParallelGC"))
                            .build());

            String plain = TestAnsi.strip(
                    capture(() -> assertThat(Jk.execute("engine", "aot")).isZero()));
            assertThat(plain).contains("AOT Caches");
            assertThat(plain).contains(name);
            assertThat(plain).contains("java-compiler");
            assertThat(plain).contains("ready");
            assertThat(plain).contains("TEMURIN");
            assertThat(plain).contains("25.0.3");
            assertThat(plain).contains("parallel");
            assertThat(plain).contains("/plugins/jk-java-compiler.jar");
            assertThat(plain).contains("-XX:+UseParallelGC");
        } finally {
            Files.deleteIfExists(cache);
            AotManifest.remove(aot, name);
        }
    }

    @Test
    void json_output_includes_directory_and_caches() throws Exception {
        Path aot = JkDirs.state().resolve("aot");
        Files.createDirectories(aot);
        String name = "engine-0.11.0-deadbeefdeadbeef.aot";
        Path cache = aot.resolve(name);
        try {
            Files.writeString(cache, "eng");
            String out = capture(
                    () -> assertThat(Jk.execute("engine", "aot", "-O", "json")).isZero());
            assertThat(out).contains("\"directory\"");
            assertThat(out).contains(name);
            assertThat(out).contains("\"caches\"");
        } finally {
            Files.deleteIfExists(cache);
            AotManifest.remove(aot, name);
        }
    }

    @Test
    void empty_missing_dir_is_ok() {
        // Don't delete ambient aot if other tests use it — just assert command exits 0 and has title.
        String plain = TestAnsi.strip(
                capture(() -> assertThat(Jk.execute("engine", "aot")).isZero()));
        assertThat(plain).contains("AOT Caches");
    }

    private static String capture(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
