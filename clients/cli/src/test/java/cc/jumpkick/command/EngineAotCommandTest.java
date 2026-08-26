// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.engine.IsolatedState;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.util.AotManifest;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code jk engine aot} against a state root this class owns.
 *
 * <p>{@link IsolatedState} is load-bearing, not hygiene: the tier's shared {@code JK_HOME} keeps one
 * {@code state/aot} for every parallel fork and every past run, and {@code EngineSpawn.aotCachePath}
 * sweeps every {@code engine-<version>-<16hex>} key that is not the live one. Planting a fixture of
 * exactly that shape in the shared directory made this class lose a race with whichever fork was
 * running {@code EngineAotCacheTest} — green alone, red in the tier (JK-2453). With a throwaway
 * root the directory holds exactly what the test put there, so the counts below can be exact.
 */
@IsolatedState
class EngineAotCommandTest {

    @Test
    void lists_caches_with_manifest_details() throws Exception {
        Path aot = JkDirs.state().resolve("aot");
        Files.createDirectories(aot);
        String name = "java-compiler-feedfacecafebeef.aot";
        Files.writeString(aot.resolve(name), "x".repeat(2048));
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
                Capture.stdout(() -> assertThat(Jk.execute("engine", "aot")).isZero()));
        assertThat(plain).contains("AOT Caches");
        assertThat(plain).contains(name);
        assertThat(plain).contains("java-compiler");
        assertThat(plain).contains("ready");
        assertThat(plain).contains("TEMURIN");
        assertThat(plain).contains("25.0.3");
        assertThat(plain).contains("parallel");
        assertThat(plain).contains("/plugins/jk-java-compiler.jar");
        assertThat(plain).contains("-XX:+UseParallelGC");
        // The one cache this test wrote is the only one there — no inherited rows.
        assertThat(plain).contains("1 cache,");
    }

    @Test
    void json_output_includes_directory_and_caches() throws Exception {
        Path aot = JkDirs.state().resolve("aot");
        Files.createDirectories(aot);
        // The live version + a 16-hex key is exactly the shape aotCachePath sweeps, which is what
        // made the shared state root fatal here — keep it, so the isolation stays load-bearing.
        String name = "engine-" + Jk.VERSION + "-deadbeefdeadbeef.aot";
        Files.writeString(aot.resolve(name), "eng");

        String out = Capture.stdout(
                () -> assertThat(Jk.execute("engine", "aot", "-O", "json")).isZero());
        assertThat(out).contains("\"directory\":" + Jsonl.quote(aot.toString()));
        assertThat(out).contains("\"caches\":");
        assertThat(out).contains(name);
        // Exactly one cache row: the listing reports the state this test established, not the
        // leftovers of an earlier run.
        assertThat(out.split("\"file\":", -1)).as("cache rows").hasSize(2);
    }

    @Test
    void empty_missing_dir_is_ok() {
        Path aot = JkDirs.state().resolve("aot");
        assertThat(aot).doesNotExist();

        String plain = TestAnsi.strip(
                Capture.stdout(() -> assertThat(Jk.execute("engine", "aot")).isZero()));
        assertThat(plain).contains("AOT Caches");
        assertThat(plain).contains("(not yet created)");
        assertThat(plain).containsPattern("Caches:\\s+0");
    }
}
