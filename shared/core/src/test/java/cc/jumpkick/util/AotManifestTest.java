// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
        Path cache = Files.writeString(dir.resolve("engine-0.11.0-bbbbbbbbbbbbbbbb.aot"), "x");
        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder(cache.getFileName().toString())
                        .tool("engine")
                        .key("bbbbbbbbbbbbbbbb")
                        .jkVersion("0.11.0")
                        .status("ready")
                        .build());
        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder("java-compiler-cccccccccccccccc.aot")
                        .tool("java-compiler")
                        .status("ready")
                        .build());

        AotManifest.remove(dir, "java-compiler-cccccccccccccccc.aot");
        assertThat(AotManifest.load(dir))
                .extracting(AotManifest.Entry::file)
                .containsExactly(cache.getFileName().toString());

        Files.delete(cache);
        AotManifest.reconcile(dir);
        assertThat(AotManifest.load(dir)).isEmpty();
    }

    @Test
    void fillToolKey_parses_engine_and_worker_names() {
        AotManifest.Entry.Builder eng = AotManifest.Entry.builder("engine-0.11.0-178d424d005e0594.aot");
        AotManifest.fillToolKey(eng, "engine-0.11.0-178d424d005e0594.aot");
        AotManifest.Entry e = eng.build();
        assertThat(e.tool()).isEqualTo("engine");
        assertThat(e.key()).isEqualTo("178d424d005e0594");
        assertThat(e.jkVersion()).isEqualTo("0.11.0");

        // Versioned worker (JK-1452): java-compiler-<jk-version>-<16hex>
        AotManifest.Entry.Builder w = AotManifest.Entry.builder("java-compiler-0.11.0-0ce11dbb0a66be53.aot");
        AotManifest.fillToolKey(w, "java-compiler-0.11.0-0ce11dbb0a66be53.aot");
        AotManifest.Entry we = w.build();
        assertThat(we.tool()).isEqualTo("java-compiler");
        assertThat(we.key()).isEqualTo("0ce11dbb0a66be53");
        assertThat(we.jkVersion()).isEqualTo("0.11.0");

        // Legacy unversioned worker name still parses tool+key
        AotManifest.Entry.Builder legacy = AotManifest.Entry.builder("java-compiler-0ce11dbb0a66be53.aot");
        AotManifest.fillToolKey(legacy, "java-compiler-0ce11dbb0a66be53.aot");
        assertThat(legacy.build().tool()).isEqualTo("java-compiler");
        assertThat(legacy.build().key()).isEqualTo("0ce11dbb0a66be53");
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

    @Test
    void list_merges_manifest_with_on_disk_files_without_manifest() throws Exception {
        Path orphan = Files.writeString(dir.resolve("java-compiler-eeeeeeeeeeeeeeee.aot"), "x".repeat(100));
        Files.writeString(dir.resolve("kotlinc-ffffffffffffffff.aot.noaot"), "");
        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder("engine-0.11.0-aaaaaaaaaaaaaaaa.aot")
                        .tool("engine")
                        .key("aaaaaaaaaaaaaaaa")
                        .status("pending")
                        .jdkVersion("25.0.3")
                        .build());
        Files.writeString(dir.resolve("engine-0.11.0-aaaaaaaaaaaaaaaa.aot"), "engine-bytes");

        List<AotManifest.Entry> listed = AotManifest.list(dir);
        assertThat(listed)
                .extracting(AotManifest.Entry::file)
                .containsExactlyInAnyOrder(
                        "engine-0.11.0-aaaaaaaaaaaaaaaa.aot",
                        "java-compiler-eeeeeeeeeeeeeeee.aot",
                        "kotlinc-ffffffffffffffff.aot");

        AotManifest.Entry engine = listed.stream()
                .filter(e -> e.file().startsWith("engine-"))
                .findFirst()
                .orElseThrow();
        assertThat(engine.status()).isEqualTo("ready");
        assertThat(engine.jdkVersion()).isEqualTo("25.0.3");
        assertThat(engine.sizeBytes()).isEqualTo(Files.size(dir.resolve(engine.file())));

        AotManifest.Entry orphanEntry = listed.stream()
                .filter(e -> e.file().equals(orphan.getFileName().toString()))
                .findFirst()
                .orElseThrow();
        assertThat(orphanEntry.tool()).isEqualTo("java-compiler");
        assertThat(orphanEntry.key()).isEqualTo("eeeeeeeeeeeeeeee");
        assertThat(orphanEntry.status()).isEqualTo("ready");

        AotManifest.Entry noaot = listed.stream()
                .filter(e -> e.file().startsWith("kotlinc-"))
                .findFirst()
                .orElseThrow();
        assertThat(noaot.status()).isEqualTo("noaot");
    }

    @Test
    void corrupt_manifest_with_bad_unicode_escape_recovers_on_next_upsert() throws Exception {
        // A unicode escape followed by non-hex — the classic hand-edited Windows path.
        Files.writeString(AotManifest.path(dir), """
                schema = 1
                [[cache]]
                file = "java-compiler-abc.aot"
                jdk_home = "C:\\upgrade\\jdk"
                status = "ready"
                """);

        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder("kotlinc-def.aot").tool("kotlinc").build());

        var listed = AotManifest.load(dir);
        assertThat(listed).extracting(AotManifest.Entry::file).contains("kotlinc-def.aot");
        // The bad escape parses as literal text rather than wedging every future write.
        var recovered = listed.stream()
                .filter(e -> e.file().startsWith("java-compiler"))
                .findFirst();
        assertThat(recovered).isPresent();
        assertThat(recovered.orElseThrow().jdkHome()).contains("upgrade");
    }

    @Test
    void hand_edited_toml_variants_round_trip() throws Exception {
        AotManifest.upsert(dir, AotManifest.Entry.builder("seed.aot").build());
        Files.writeString(AotManifest.path(dir), """
                schema = 1
                [[cache]]
                status = "ready"  # field placed before file=, with a trailing comment
                file = "javac-1.aot"
                classpath = ["/a.jar", "/b.jar"]
                """);

        var byFile = AotManifest.load(dir).stream().collect(Collectors.toMap(AotManifest.Entry::file, e -> e));
        AotManifest.Entry e = byFile.get("javac-1.aot");
        assertThat(e).isNotNull();
        assertThat(e.status()).isEqualTo("ready");
        assertThat(e.classpath()).containsExactly("/a.jar", "/b.jar");
    }

    @Test
    void concurrent_upserts_from_many_threads_all_land() throws Exception {
        int n = 16;
        var pool = Executors.newFixedThreadPool(8);
        try {
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                AotManifest.upsert(
                        dir, AotManifest.Entry.builder("doomed-" + i + ".aot").build());
            }
            for (int i = 0; i < n; i++) {
                final int id = i;
                pool.execute(() -> {
                    try {
                        start.await();
                        // Interleave upserts with removes: both must serialize losslessly.
                        AotManifest.upsert(
                                dir,
                                AotManifest.Entry.builder("tool-" + id + ".aot")
                                        .tool("tool-" + id)
                                        .status("ready")
                                        .build());
                        AotManifest.remove(dir, "doomed-" + id + ".aot");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        // Same-JVM overlap used to throw OverlappingFileLockException inside withLock and
        // silently drop the losing update.
        var files = AotManifest.load(dir).stream().map(AotManifest.Entry::file).toList();
        assertThat(files).hasSize(n);
        assertThat(files).allMatch(f -> f.startsWith("tool-"));
        // The lock file must survive: unlinking it while a process holds the flock hands a
        // racing process a fresh inode to lock — two writers at once.
        assertThat(dir.resolve(AotManifest.FILE_NAME + ".lock")).exists();
    }

    @Test
    void engine_noaot_row_keeps_recorded_manifest_details() throws Exception {
        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder("engine-0.11.0-ab12.aot")
                        .status("pending")
                        .jdkHome("/opt/jdk-25")
                        .jdkVendor("TEMURIN")
                        .jdkVersion("25.0.3")
                        .gc("serial")
                        .build());
        // Sticky marker present, .aot file absent — the exact state after a failed engine train.
        Files.writeString(dir.resolve("engine-0.11.0-ab12.noaot"), "");

        var listed = AotManifest.list(dir);
        AotManifest.Entry e = listed.stream()
                .filter(x -> x.file().equals("engine-0.11.0-ab12.aot"))
                .findFirst()
                .orElseThrow();
        assertThat(e.status()).isEqualTo("noaot");
        assertThat(e.jdkHome()).isEqualTo("/opt/jdk-25");
        assertThat(e.jdkVendor()).isEqualTo("TEMURIN");
        assertThat(e.gc()).isEqualTo("serial");
    }

    @Test
    void reconcile_keeps_pending_rows_and_drops_dead_ones() throws Exception {
        // Pending = train in flight; its .aot deliberately doesn't exist yet.
        AotManifest.upsert(
                dir,
                AotManifest.Entry.builder("engine-0.11.0-cd34.aot")
                        .status("pending")
                        .build());
        AotManifest.upsert(
                dir, AotManifest.Entry.builder("stale.aot").status("ready").build());

        AotManifest.reconcile(dir);

        var files = AotManifest.load(dir).stream().map(AotManifest.Entry::file).toList();
        assertThat(files).contains("engine-0.11.0-cd34.aot");
        assertThat(files).doesNotContain("stale.aot");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
