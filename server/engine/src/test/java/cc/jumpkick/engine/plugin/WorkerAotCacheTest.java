// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.testing.Sleepers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker startup cache: one file per key, mapped when present, recorded once per key per
 * process when absent, and never a dependency of the build that missed it.
 */
class WorkerAotCacheTest {

    private static final WorkerAotCache.Host HOTSPOT_25 =
            new WorkerAotCache.Host(Path.of("/jdk/temurin-25"), JdkVendor.TEMURIN, "25.0.4.1");

    @AfterEach
    void forget() {
        WorkerAotCache.forgetFailuresForTests();
        System.clearProperty("jk.worker.aot");
        System.clearProperty("jk.env.JK_STATE_DIR");
    }

    /** The key is a pure function of its inputs, and each input moves it. */
    @Test
    void the_key_follows_the_jdk_the_gc_the_classpath_and_the_extra_flags() {
        Path a = WorkerAotCache.cacheFile("kotlinc", HOTSPOT_25, "/w/a.jar;/w/b.jar", List.of());
        assertThat(a).isEqualTo(WorkerAotCache.cacheFile("kotlinc", HOTSPOT_25, "/w/a.jar;/w/b.jar", List.of()));
        assertThat(a.getFileName().toString()).matches("kotlinc-[^-]+(-[^-]+)*-[0-9a-f]{16}\\.aot");

        assertThat(WorkerAotCache.cacheFile("kotlinc", HOTSPOT_25, "/w/b.jar;/w/a.jar", List.of()))
                .as("the classpath order is part of the key; the resolver keeps it stable")
                .isNotEqualTo(a);
        assertThat(WorkerAotCache.cacheFile("kotlinc", HOTSPOT_25, "/w/a.jar;/w/b.jar", List.of("-Dprobe=1")))
                .isNotEqualTo(a);
        assertThat(WorkerAotCache.cacheFile("java-compiler", HOTSPOT_25, "/w/a.jar;/w/b.jar", List.of()))
                .isNotEqualTo(a);
        var other = new WorkerAotCache.Host(Path.of("/jdk/temurin-25"), JdkVendor.TEMURIN, "25.0.5");
        assertThat(WorkerAotCache.cacheFile("kotlinc", other, "/w/a.jar;/w/b.jar", List.of()))
                .isNotEqualTo(a);
    }

    @Test
    void only_hotspot_25_and_later_records_a_cache() {
        assertThat(HOTSPOT_25.eligible()).isTrue();
        assertThat(new WorkerAotCache.Host(Path.of("/jdk"), JdkVendor.TEMURIN, "21.0.5").eligible())
                .isFalse();
        assertThat(new WorkerAotCache.Host(Path.of("/jdk"), JdkVendor.ORACLE_GRAALVM, "25.0.1").eligible())
                .isFalse();
        assertThat(new WorkerAotCache.Host(Path.of("/jdk"), JdkVendor.GRAALVM_CE, "25").eligible())
                .isFalse();
    }

    @Test
    void the_gc_is_read_off_the_batch_flags() {
        assertThat(WorkerAotCache.effectiveGc(List.of("-Xmx1g", "-XX:+UseParallelGC")))
                .isEqualTo("parallelgc");
        assertThat(WorkerAotCache.effectiveGc(List.of("-Xmx1g"))).isEqualTo("default");
    }

    @Test
    void the_switch_turns_mapping_and_recording_off(@TempDir Path dir) throws Exception {
        System.setProperty("jk.worker.aot", "off");
        Path home = jdk(dir, "25.0.4.1");
        assertThat(WorkerAotCache.flags("kotlinc", home, "/w/a.jar", List.of(), (out, scratch) -> List.of()))
                .isEmpty();
        assertThat(WorkerAotCache.trainingInFlight()).isFalse();
    }

    /**
     * A miss records into a temp sibling that is moved into place on a clean exit; the build that
     * missed gets no flag, the next one maps the file.
     */
    @Test
    void a_miss_records_once_and_the_next_call_maps(@TempDir Path dir) throws Exception {
        Path state = Files.createDirectories(dir.resolve("state"));
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path home = jdk(dir, "25.0.4.1");

        List<String> first = WorkerAotCache.flags(
                "kotlinc", home, "/w/a.jar", List.of(), (out, scratch) -> Sleepers.writesLine("cache-bytes", out));
        assertThat(first).isEmpty();
        waitUntil(() -> !WorkerAotCache.trainingInFlight());

        Path cache =
                WorkerAotCache.cacheFile("kotlinc", requireNonNull(WorkerAotCache.host(home)), "/w/a.jar", List.of());
        assertThat(cache).isRegularFile();
        assertThat(Files.size(cache)).isGreaterThan(0);
        try (var files = Files.list(state.resolve("aot"))) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("the temp sibling and the scratch dir do not outlive the recording")
                    .containsExactly(cache.getFileName().toString());
        }
        assertThat(WorkerAotCache.flags("kotlinc", home, "/w/a.jar", List.of(), (out, scratch) -> List.of()))
                .containsExactly("-XX:AOTCache=" + cache, "-Xlog:aot=off");
    }

    /** A recording that fails is logged and left alone for the rest of this process. */
    @Test
    void a_failed_recording_is_not_retried_in_this_process(@TempDir Path dir) throws Exception {
        Path state = Files.createDirectories(dir.resolve("state"));
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path home = jdk(dir, "25.0.4.1");
        int[] forks = new int[1];
        WorkerAotCache.Trainer failing = (out, scratch) -> {
            forks[0]++;
            return Sleepers.exits(3);
        };

        assertThat(WorkerAotCache.flags("java-compiler", home, "/w/z.jar", List.of(), failing))
                .isEmpty();
        waitUntil(() -> !WorkerAotCache.trainingInFlight());
        assertThat(WorkerAotCache.flags("java-compiler", home, "/w/z.jar", List.of(), failing))
                .isEmpty();
        waitUntil(() -> !WorkerAotCache.trainingInFlight());

        assertThat(forks[0]).isEqualTo(1);
        Path cache = WorkerAotCache.cacheFile(
                "java-compiler", requireNonNull(WorkerAotCache.host(home)), "/w/z.jar", List.of());
        assertThat(cache).doesNotExist();
    }

    private static Path jdk(Path dir, String version) throws Exception {
        Path home = Files.createDirectories(dir.resolve("jdk"));
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        return home;
    }

    private static void waitUntil(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("trainer still running after 30s");
            Thread.sleep(20);
        }
    }
}
