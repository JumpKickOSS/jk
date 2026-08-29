// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.testing.Await;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class PluginAotTest {

    @TempDir
    Path tmp;

    /**
     * Let every background trainer finish before JUnit deletes {@link #tmp}.
     *
     * <p>These tests await an <em>observable</em> signal — a marker appearing, a claim's mtime
     * moving — and that signal fires while {@link PluginAot#runTrainer} is still cleaning up
     * underneath the fixture. The teardown then loses a race with it and the tier goes red on a
     * {@code DirectoryNotEmptyException} rather than on anything the test asserts. Waiting here
     * fixes the fixture's lifetime; it deliberately does not make production join its trainers,
     * which are fire-and-forget by design (JK-1072).
     */
    @AfterEach
    void awaitTrainersQuiescent() throws InterruptedException {
        Await.until(Duration.ofSeconds(30), () -> !PluginAot.trainingInFlight());
    }

    // ---- keying -----------------------------------------------------------------------------

    @Test
    void effective_gc_reads_bare_and_launcher_prefixed_flags() {
        assertThat(PluginAot.effectiveGc(List.of("-XX:MaxRAMPercentage=25", "-XX:+UseZGC")))
                .isEqualTo("zgc");
        assertThat(PluginAot.effectiveGc(List.of("-J-XX:+UseG1GC", "-J-Xmx1g"))).isEqualTo("g1gc");
        assertThat(PluginAot.effectiveGc(List.of("-J-XX:+UseSerialGC"))).isEqualTo("serialgc");
        assertThat(PluginAot.effectiveGc(List.of("-Xmx1g"))).isEqualTo("default");
    }

    @Test
    void key_changes_with_gc_and_classpath_but_is_stable_otherwise() throws IOException {
        PluginAot.JdkId id = new PluginAot.JdkId(tmp.resolve("jdk"), JdkVendor.TEMURIN, "25.0.3");
        String base = PluginAot.key(id, "zgc", "");
        assertThat(PluginAot.key(id, "zgc", "")).isEqualTo(base);
        assertThat(PluginAot.key(id, "g1", "")).isNotEqualTo(base);
        assertThat(PluginAot.key(id, "zgc", "a.jar:b.jar")).isNotEqualTo(base);
        assertThat(base).hasSize(16);
    }

    @Test
    void jdk_id_parses_the_release_file_and_eligibility_gates_on_feature_and_vendor() throws IOException {
        Path jdk = Files.createDirectories(tmp.resolve("jdk25"));
        Files.writeString(jdk.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"25.0.3\"\n");
        PluginAot.JdkId id = PluginAot.jdkId(jdk);
        assertThat(id).isNotNull();
        assertThat(id.version()).isEqualTo("25.0.3");
        assertThat(PluginAot.eligible(id)).isTrue();

        Path old = Files.createDirectories(tmp.resolve("jdk21"));
        Files.writeString(old.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"21.0.2\"\n");
        assertThat(PluginAot.eligible(PluginAot.jdkId(old))).isFalse();

        Path graal = Files.createDirectories(tmp.resolve("graal25"));
        Files.writeString(
                graal.resolve("release"),
                "IMPLEMENTOR=\"Oracle Corporation\"\nIMPLEMENTOR_VERSION=\"Oracle GraalVM 25\"\nJAVA_VERSION=\"25\"\n");
        PluginAot.JdkId graalId = PluginAot.jdkId(graal);
        if (graalId.vendor() == JdkVendor.ORACLE_GRAALVM) {
            assertThat(PluginAot.eligible(graalId)).isFalse();
        }

        assertThat(PluginAot.jdkId(tmp.resolve("no-such-jdk"))).isNull(); // no release file
    }

    @Test
    void hostEligible_wraps_jdk_id_and_eligibility() throws IOException {
        Path jdk = Files.createDirectories(tmp.resolve("he-jdk25"));
        Files.writeString(jdk.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"25.0.3\"\n");
        assertThat(PluginAot.hostEligible(jdk)).isTrue();

        Path old = Files.createDirectories(tmp.resolve("he-jdk21"));
        Files.writeString(old.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"21.0.2\"\n");
        assertThat(PluginAot.hostEligible(old)).isFalse();

        assertThat(PluginAot.hostEligible(null)).isFalse();
        assertThat(PluginAot.hostEligible(tmp.resolve("he-none"))).isFalse();
    }

    @Test
    void sweep_prefix_is_the_full_tool_tag_not_up_to_the_first_hyphen() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("aot-prefix"));
        long day = 24L * 60 * 60 * 1_000;
        // Six old java-runner keys: a first-hyphen prefix ("java-") would sweep them as
        // overflow of the java-compiler pool; the full tag ("java-compiler-") must not.
        Path[] runner = new Path[6];
        for (int i = 0; i < 6; i++) {
            runner[i] = Files.writeString(dir.resolve("java-runner-000000000000000" + i + ".aot"), "r" + i);
            Files.setLastModifiedTime(runner[i], FileTime.fromMillis(System.currentTimeMillis() - (i + 1) * day));
        }
        Path cache = dir.resolve("java-compiler-0000000000000000.aot");
        PluginAot.trainAsync(
                "test", cache, (aotOutput, scratch) -> List.of("bash", "-c", "echo trained > '" + aotOutput + "'"));
        // The sweep runs inside runTrainer, between publishing the cache and dropping the claim —
        // so the claim's disappearance is the "trainer done, sweep included" signal. Waiting on
        // Files.exists(cache) alone races the sweep, which is why this used to carry a bare
        // Thread.sleep(100): a guess about this machine, and no assertion at all (JK-2446).
        Path claim = cache.resolveSibling(cache.getFileName() + ".training");
        Await.until(Duration.ofSeconds(30), () -> Files.exists(cache) && !Files.exists(claim));
        for (Path p : runner) assertThat(p).exists();
    }

    // ---- training lifecycle -------------------------------------------------------------------

    @Test
    void publish_keeps_the_n_most_recently_used_caches_and_expires_dead_keys() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("aot"));
        long day = 24L * 60 * 60 * 1_000;
        // Five live-ish keys with staggered last-use ages (1..5 days) — several keys are
        // legitimately live at once (different toolchain JDKs / Kotlin versions / GC pins).
        Path[] stale = new Path[5];
        for (int i = 0; i < 5; i++) {
            stale[i] = Files.writeString(dir.resolve("javac-stalekey000000" + i + "00.aot"), "old" + i);
            Files.setLastModifiedTime(stale[i], FileTime.fromMillis(System.currentTimeMillis() - (i + 1) * day));
        }
        // A key untouched for 40 days is dead regardless of count; an orphaned failure marker
        // that old gets a fresh training chance; a young orphan marker stays sticky.
        Path dead = Files.writeString(dir.resolve("javac-deadkey000000000a.aot"), "dead");
        Files.setLastModifiedTime(dead, FileTime.fromMillis(System.currentTimeMillis() - 40 * day));
        Path deadMarker = Files.writeString(dir.resolve("javac-failkey000000000b.aot.noaot"), "");
        Files.setLastModifiedTime(deadMarker, FileTime.fromMillis(System.currentTimeMillis() - 40 * day));
        Path freshMarker = Files.writeString(dir.resolve("javac-failkey000000000c.aot.noaot"), "");
        // Engine caches share the directory but are version-lifecycle-owned — never swept here.
        Path engine = Files.writeString(dir.resolve("engine-1.0.0-aaaaaaaaaaaaaaaa.aot"), "engine");
        Files.setLastModifiedTime(engine, FileTime.fromMillis(System.currentTimeMillis() - 400 * day));

        Path cache = dir.resolve("javac-newkey0000000000.aot");
        // A stand-in trainer: any command that writes the aot output and exits 0.
        PluginAot.trainAsync(
                "test", cache, (aotOutput, scratch) -> List.of("bash", "-c", "echo trained > '" + aotOutput + "'"));
        // The sweep, dead-key expiry and manifest rewrite all run inside runTrainer between
        // publishing the cache and dropping the claim — the claim's disappearance is the
        // "trainer done, sweep included" signal. Awaiting any single swept file instead races
        // whatever the sweep deletes after it.
        Path claim = cache.resolveSibling(cache.getFileName() + ".training");
        Await.until(Duration.ofSeconds(30), () -> Files.exists(cache) && !Files.exists(claim));

        // Keep 4 by recency: the new cache + the 3 youngest stale keys; the rest reclaimed.
        assertThat(stale[0]).exists();
        assertThat(stale[1]).exists();
        assertThat(stale[2]).exists();
        assertThat(stale[3]).doesNotExist();
        assertThat(dead).doesNotExist();
        assertThat(deadMarker).doesNotExist();
        assertThat(freshMarker).exists();
        assertThat(engine).exists();
    }

    @Test
    void failed_training_leaves_a_sticky_noaot_marker_instead_of_a_cache() throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("aot2")).resolve("javac-failkey000000000.aot");
        PluginAot.trainAsync("test", cache, (aotOutput, scratch) -> List.of("bash", "-c", "exit 1"));
        Await.until(Duration.ofSeconds(10), () -> Files.exists(AotCacheFiles.marker(cache)));
        assertThat(cache).doesNotExist();
    }

    @Test
    void trainer_timeout_leaves_no_sticky_marker_and_backs_off_via_the_claim_file() throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("aot4")).resolve("javac-timeout0000000000.aot");
        Path claim = cache.resolveSibling(cache.getFileName() + ".training");
        long prevTimeout = PluginAot.trainingTimeoutMillis;
        PluginAot.trainingTimeoutMillis = 200;
        try {
            PluginAot.trainAsync("test", cache, (aotOutput, scratch) -> List.of("bash", "-c", "sleep 30"));
            long claimedAt = Files.getLastModifiedTime(claim).toMillis();
            // The timeout branch refreshes the claim's mtime — the observable "overran" signal.
            Await.until(Duration.ofSeconds(10), () -> {
                try {
                    return Files.getLastModifiedTime(claim).toMillis() > claimedAt;
                } catch (IOException e) {
                    return false;
                }
            });
            // One transient overrun must NOT permanently disable AOT for the key: no sticky
            // .noaot — only the (staleness-bounded) claim file paces the retry.
            assertThat(AotCacheFiles.marker(cache)).doesNotExist();
            assertThat(cache).doesNotExist();
            assertThat(claim).exists();
        } finally {
            PluginAot.trainingTimeoutMillis = prevTimeout;
        }
    }

    @Test
    void a_fresh_claim_file_from_another_process_blocks_training() throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("aot3")).resolve("javac-claimed000000000.aot");
        Files.createFile(cache.resolveSibling(cache.getFileName() + ".training")); // fresh foreign claim
        // Observe the forbidden ACTION, not a side effect of it three steps downstream. The trainer
        // command is only ever built inside runTrainer, i.e. only if trainAsync spawned a thread —
        // so the latch firing IS "training started". Sleeping 300ms and then checking the cache file
        // could not tell "never spawned" from "spawned but slow" (JK-2446).
        CountDownLatch trainerBuilt = new CountDownLatch(1);
        PluginAot.trainAsync("test", cache, (aotOutput, scratch) -> {
            trainerBuilt.countDown();
            return List.of("bash", "-c", "echo trained > '" + aotOutput + "'");
        });
        // trainAsync rejects a fresh foreign claim synchronously — claimed() runs before any
        // trainer thread exists, and a refused claim leaves TRAINING before trainAsync returns.
        // So "still in TRAINING right after the call" is the buggy path's immediate signature,
        // and no timed wait is needed to prove the green one.
        assertThat(PluginAot.trainingInFlight())
                .as("a fresh foreign claim must reject the train before any thread spawns")
                .isFalse();
        assertThat(trainerBuilt.getCount())
                .as("a fresh foreign claim must block training, but the trainer command was built")
                .isEqualTo(1);
        assertThat(cache).doesNotExist();
    }
}
