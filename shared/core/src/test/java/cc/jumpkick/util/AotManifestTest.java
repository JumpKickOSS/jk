// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AotManifestTest {

    @TempDir
    Path dir;

    @Test
    void upsert_round_trips_worker_entry() {
        AotManifest.Entry e = AotManifest.Entry.builder("java-compiler-0ce11dbb0a66be53.aot")
                .tool("java-compiler")
                .key("0ce11dbb0a66be53")
                .status("ready")
                .sizeBytes(25632768L)
                .jdkHome("/opt/jdk-25")
                .jdkVendor("TEMURIN")
                .jdkVersion("25.0.3")
                .gc("parallel")
                .classpath(List.of("/plugins/jk-java-compiler.jar", "/store/lib/asm.jar"))
                .jvmFlags(List.of("-XX:MaxRAMPercentage=50.0", "-XX:+UseParallelGC"))
                .created("2026-08-02T23:58:00Z")
                .lastUsed("2026-08-03T00:44:00Z")
                .build();

        AotManifest.upsert(dir, e);

        Path toml = AotManifest.path(dir);
        assertThat(toml).exists();
        String text = read(toml);
        assertThat(text).contains("schema = 1");
        assertThat(text).contains("tool = \"java-compiler\"");
        assertThat(text).contains("jdk_version = \"25.0.3\"");
        assertThat(text).contains("\"-XX:+UseParallelGC\"");

        List<AotManifest.Entry> loaded = AotManifest.load(dir);
        assertThat(loaded).hasSize(1);
        AotManifest.Entry got = loaded.getFirst();
        assertThat(got.file()).isEqualTo(e.file());
        assertThat(got.tool()).isEqualTo("java-compiler");
        assertThat(got.key()).isEqualTo("0ce11dbb0a66be53");
        assertThat(got.status()).isEqualTo("ready");
        assertThat(got.sizeBytes()).isEqualTo(25632768L);
        assertThat(got.jdkHome()).isEqualTo("/opt/jdk-25");
        assertThat(got.jdkVendor()).isEqualTo("TEMURIN");
        assertThat(got.jdkVersion()).isEqualTo("25.0.3");
        assertThat(got.gc()).isEqualTo("parallel");
        assertThat(got.classpath()).containsExactly("/plugins/jk-java-compiler.jar", "/store/lib/asm.jar");
        assertThat(got.jvmFlags()).containsExactly("-XX:MaxRAMPercentage=50.0", "-XX:+UseParallelGC");
        assertThat(got.created()).isEqualTo("2026-08-02T23:58:00Z");
        assertThat(got.lastUsed()).isEqualTo("2026-08-03T00:44:00Z");
    }

    @Test
    void upsert_merges_and_preserves_created() {
        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder("kotlinc-aaaaaaaaaaaaaaaa.aot")
                        .tool("kotlinc")
                        .key("aaaaaaaaaaaaaaaa")
                        .status("ready")
                        .jdkVersion("25.0.3")
                        .created("2026-01-01T00:00:00Z")
                        .build());
        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder("kotlinc-aaaaaaaaaaaaaaaa.aot")
                        .status("ready")
                        .sizeBytes(100L)
                        .lastUsed("2026-08-03T12:00:00Z")
                        .build());

        AotManifest.Entry got = AotManifest.load(dir).getFirst();
        assertThat(got.created()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(got.sizeBytes()).isEqualTo(100L);
        assertThat(got.lastUsed()).isEqualTo("2026-08-03T12:00:00Z");
        assertThat(got.tool()).isEqualTo("kotlinc"); // preserved from first write
        assertThat(got.jdkVersion()).isEqualTo("25.0.3");
    }

    @Test
    void remove_and_reconcile_drop_gone_files() throws Exception {
        Path cache = Files.writeString(dir.resolve("engine-0.10.1-bbbbbbbbbbbbbbbb.aot"), "x");
        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder(cache.getFileName().toString())
                        .tool("engine")
                        .key("bbbbbbbbbbbbbbbb")
                        .jkVersion("0.10.1")
                        .status("ready")
                        .build());
        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder("java-compiler-cccccccccccccccc.aot")
                        .tool("java-compiler")
                        .status("ready")
                        .build());

        AotManifest.remove(dir, "java-compiler-cccccccccccccccc.aot");
        assertThat(AotManifest.load(dir)).extracting(AotManifest.Entry::file).containsExactly(cache.getFileName().toString());

        Files.delete(cache);
        AotManifest.reconcile(dir);
        assertThat(AotManifest.load(dir)).isEmpty();
    }

    @Test
    void fillToolKey_parses_engine_and_worker_names() {
        AotManifest.Entry.Builder eng = AotManifest.Entry.builder("engine-0.10.1-178d424d005e0594.aot");
        AotManifest.fillToolKey(eng, "engine-0.10.1-178d424d005e0594.aot");
        AotManifest.Entry e = eng.build();
        assertThat(e.tool()).isEqualTo("engine");
        assertThat(e.key()).isEqualTo("178d424d005e0594");
        assertThat(e.jkVersion()).isEqualTo("0.10.1");

        AotManifest.Entry.Builder w = AotManifest.Entry.builder("java-compiler-0ce11dbb0a66be53.aot");
        AotManifest.fillToolKey(w, "java-compiler-0ce11dbb0a66be53.aot");
        assertThat(w.build().tool()).isEqualTo("java-compiler");
        assertThat(w.build().key()).isEqualTo("0ce11dbb0a66be53");
    }

    @Test
    void reconcile_keeps_entry_when_only_noaot_marker_remains() throws Exception {
        Files.writeString(dir.resolve("kotlinc-dddddddddddddddd.aot.noaot"), "");
        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder("kotlinc-dddddddddddddddd.aot")
                        .tool("kotlinc")
                        .status("noaot")
                        .build());
        AotManifest.reconcile(dir);
        assertThat(AotManifest.load(dir)).hasSize(1);
        assertThat(AotManifest.load(dir).getFirst().status()).isEqualTo("noaot");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
