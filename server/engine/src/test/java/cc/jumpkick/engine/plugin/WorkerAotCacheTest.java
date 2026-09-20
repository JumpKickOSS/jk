// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.testing.Sleepers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker startup cache: one file per tool, recorded only for the JDK this engine runs on,
 * mapped when present, recorded once per key per process when absent, and never a dependency of
 * the build that missed it.
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

    /** The key is a pure function of its inputs, and each input moves it; the name carries the JDK tag. */
    @Test
    void the_name_carries_the_jdk_tag_and_the_key_follows_the_gc_the_classpath_and_the_flags() {
        Path a = WorkerAotCache.cacheFile("kotlinc", HOTSPOT_25, "/w/a.jar;/w/b.jar", List.of());
        assertThat(a).isEqualTo(WorkerAotCache.cacheFile("kotlinc", HOTSPOT_25, "/w/a.jar;/w/b.jar", List.of()));
        assertThat(a.getFileName().toString())
                .startsWith("kotlinc-" + JkVersion.VERSION + "-" + WorkerAotCache.jdkTag(HOTSPOT_25) + "-")
                .matches(".*-[0-9a-f]{8}-[0-9a-f]{16}\\.aot");

        assertThat(WorkerAotCache.cacheFile("kotlinc", HOTSPOT_25, "/w/b.jar;/w/a.jar", List.of()))
                .as("the classpath order is part of the key; the resolver keeps it stable")
                .isNotEqualTo(a);
        assertThat(WorkerAotCache.cacheFile("kotlinc", HOTSPOT_25, "/w/a.jar;/w/b.jar", List.of("-Dprobe=1")))
                .isNotEqualTo(a);
        assertThat(WorkerAotCache.cacheFile("java-compiler", HOTSPOT_25, "/w/a.jar;/w/b.jar", List.of()))
                .isNotEqualTo(a);
        var other = new WorkerAotCache.Host(Path.of("/jdk/temurin-25"), JdkVendor.TEMURIN, "25.0.5");
        assertThat(WorkerAotCache.jdkTag(other)).isNotEqualTo(WorkerAotCache.jdkTag(HOTSPOT_25));
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
    void the_switch_turns_mapping_and_recording_off() {
        System.setProperty("jk.worker.aot", "off");
        assertThat(WorkerAotCache.flags(
                        "kotlinc", JavaHomes.runningJavaHome(), "/w/a.jar", List.of(), (out, scratch) -> List.of()))
                .isEmpty();
        assertThat(WorkerAotCache.trainingInFlight()).isFalse();
    }

    /** A worker forked on a JDK that is not jk's own runs cold: no flag, and no recording either. */
    @Test
    void a_worker_on_another_jdk_gets_no_cache_and_records_none(@TempDir Path dir) throws Exception {
        Path state = Files.createDirectories(dir.resolve("state"));
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path projectJdk = Files.createDirectories(dir.resolve("temurin-25-project"));
        Files.writeString(projectJdk.resolve("release"), "JAVA_VERSION=\"25.0.4.1\"\n");

        int[] forks = new int[1];
        assertThat(WorkerAotCache.flags("java-compiler", projectJdk, "/w/a.jar", List.of(), (out, scratch) -> {
                    forks[0]++;
                    return Sleepers.writesLine("x", out);
                }))
                .isEmpty();
        assertThat(forks[0]).isZero();
        assertThat(state.resolve("aot")).doesNotExist();
    }

    /**
     * A miss records into a temp sibling that is moved into place on a clean exit; the build that
     * missed gets no flag, the next one maps the file, and the tool's older cache is gone.
     */
    @Test
    void a_miss_records_once_the_next_call_maps_and_the_tools_other_cache_goes(@TempDir Path dir) throws Exception {
        WorkerAotCache.Host engine = WorkerAotCache.engineHost();
        Assumptions.assumeTrue(engine != null && engine.eligible(), "this JVM cannot record a cache");
        Path state = Files.createDirectories(dir.resolve("state"));
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path aot = Files.createDirectories(state.resolve("aot"));
        String tag = WorkerAotCache.jdkTag(requireNonNull(engine));
        Path older = Files.writeString(
                aot.resolve("kotlinc-" + JkVersion.VERSION + "-" + tag + "-0123456789abcdef.aot"), "an older key");
        Path otherTool = Files.writeString(
                aot.resolve("java-compiler-" + JkVersion.VERSION + "-" + tag + "-0123456789abcdef.aot"), "other tool");

        Path home = JavaHomes.runningJavaHome();
        List<String> first = WorkerAotCache.flags(
                "kotlinc", home, "/w/a.jar", List.of(), (out, scratch) -> Sleepers.writesLine("cache-bytes", out));
        assertThat(first).isEmpty();
        waitUntil(() -> !WorkerAotCache.trainingInFlight());

        Path cache = WorkerAotCache.cacheFile("kotlinc", engine, "/w/a.jar", List.of());
        assertThat(cache).isRegularFile();
        assertThat(Files.size(cache)).isGreaterThan(0);
        assertThat(older).as("one file per tool").doesNotExist();
        assertThat(otherTool)
                .as("another tool's cache is not this tool's business")
                .exists();
        try (var files = Files.list(aot)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("no temp sibling outlives the recording")
                    .containsExactlyInAnyOrder(
                            cache.getFileName().toString(),
                            otherTool.getFileName().toString());
        }
        assertThat(WorkerAotCache.flags("kotlinc", home, "/w/a.jar", List.of(), (out, scratch) -> List.of()))
                .containsExactly("-XX:AOTCache=" + cache, "-Xlog:aot=off");
    }

    /** A recording that fails is logged and left alone for the rest of this process. */
    @Test
    void a_failed_recording_is_not_retried_in_this_process(@TempDir Path dir) throws Exception {
        WorkerAotCache.Host engine = WorkerAotCache.engineHost();
        Assumptions.assumeTrue(engine != null && engine.eligible(), "this JVM cannot record a cache");
        Path state = Files.createDirectories(dir.resolve("state"));
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path home = JavaHomes.runningJavaHome();
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
        assertThat(WorkerAotCache.cacheFile("java-compiler", requireNonNull(engine), "/w/z.jar", List.of()))
                .doesNotExist();
    }

    /** At engine start, caches for another jk version or another JDK go; this engine's stay. */
    @Test
    void the_start_sweep_keeps_only_this_version_and_this_jdk(@TempDir Path aot) throws Exception {
        String tag = WorkerAotCache.jdkTag(HOTSPOT_25);
        Path mine = Files.writeString(
                aot.resolve("kotlinc-" + JkVersion.VERSION + "-" + tag + "-0123456789abcdef.aot"), "x");
        Path mineToo = Files.writeString(
                aot.resolve("java-compiler-" + JkVersion.VERSION + "-" + tag + "-fedcba9876543210.aot"), "x");
        Path otherJdk =
                Files.writeString(aot.resolve("kotlinc-" + JkVersion.VERSION + "-89abcdef-0123456789abcdef.aot"), "x");
        Path otherVersion = Files.writeString(aot.resolve("kotlinc-0.1.0-" + tag + "-0123456789abcdef.aot"), "x");
        Path legacy = Files.writeString(aot.resolve("kotlinc-0.13.8-3d5aa37c6f2e823d.aot"), "x");
        Path manifest = Files.writeString(aot.resolve("aot.toml"), "not a cache");
        Path freshTmp = Files.writeString(
                aot.resolve("formatter-" + JkVersion.VERSION + "-" + tag + "-0123456789abcdef.aot.tmp-4242"),
                "recording");

        WorkerAotCache.sweepForeign(aot, HOTSPOT_25);

        assertThat(mine).exists();
        assertThat(mineToo).exists();
        assertThat(otherJdk).doesNotExist();
        assertThat(otherVersion).doesNotExist();
        assertThat(legacy)
                .as("a name from before the JDK tag can never map again")
                .doesNotExist();
        assertThat(manifest).as("only .aot files are the sweep's business").exists();
        assertThat(freshTmp).as("a live recording is left alone").exists();
    }

    private static void waitUntil(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("trainer still running after 30s");
            Thread.sleep(20);
        }
    }
}
