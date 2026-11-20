// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.host.Settled;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.testing.Sleepers;
import cc.jumpkick.testing.Symlinks;
import cc.jumpkick.version.Versions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void fresh() {
        WorkerAotCache.forgetFailuresForTests();
    }

    /** Pins that read a test's tree as roomy, whatever the host's disk says: a critical volume records nothing. */
    private final List<DiskGuard.Pin> pins = new ArrayList<>();

    private void roomy(Path dir) {
        pins.add(DiskGuard.pinForTest(dir, new JkCacheConfig.DiskSpace(100L << 30, 50L << 30)));
    }

    @AfterEach
    void forget() {
        pins.forEach(DiskGuard.Pin::close);
        pins.clear();
        WorkerAotCache.forgetFailuresForTests();
        System.clearProperty("jk.worker.aot");
        Settled.aheadForTests(null);
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

    /**
     * A worker forked on a pinned HotSpot 25+ JDK that is not jk's own records a cache of its own;
     * one on an older JDK runs cold and records nothing, since that JDK cannot map one.
     */
    @Test
    void a_pinned_25_plus_jdk_records_its_own_cache_and_an_older_one_records_none(@TempDir Path dir) throws Exception {
        Path state = Files.createDirectories(dir.resolve("state"));
        roomy(dir);
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path older = Files.createDirectories(dir.resolve("temurin-21-project"));
        Files.writeString(older.resolve("release"), "JAVA_VERSION=\"21.0.12\"\n");
        AtomicInteger forks = new AtomicInteger();
        assertThat(WorkerAotCache.flags("java-compiler", older, "/w/a.jar", List.of(), (out, scratch) -> {
                    forks.incrementAndGet();
                    return Sleepers.writesLine("x", out);
                }))
                .isEmpty();
        assertThat(forks.get()).isZero();
        assertThat(state.resolve("aot")).doesNotExist();

        Path pinned = Files.createDirectories(dir.resolve("temurin-27-project"));
        Files.writeString(pinned.resolve("release"), "JAVA_VERSION=\"27.0.1\"\n");
        assertThat(WorkerAotCache.flags("java-compiler", pinned, "/w/a.jar", List.of(), (out, scratch) -> {
                    forks.incrementAndGet();
                    return Sleepers.writesLine("x", out);
                }))
                .as("the miss runs cold")
                .isEmpty();
        waitUntil(() -> forks.get() == 1 && !WorkerAotCache.trainingInFlight());
        assertThat(WorkerAotCache.flags("java-compiler", pinned, "/w/a.jar", List.of(), (out, scratch) -> {
                    forks.incrementAndGet();
                    return Sleepers.writesLine("x", out);
                }))
                .as("the next worker on that JDK maps its own cache")
                .anyMatch(f -> f.startsWith("-XX:AOTCache="));
    }

    /**
     * A JAVA_HOME that is a pointer reads the JDK it points at now: repointed from a 25+ JDK to an
     * older one, its worker maps no cache that JDK cannot open.
     */
    @Test
    void a_pointer_repointed_to_an_older_jdk_maps_no_cache(@TempDir Path dir) throws Exception {
        Path state = Files.createDirectories(dir.resolve("state"));
        roomy(dir);
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path newer = Files.createDirectories(dir.resolve("temurin-27"));
        Files.writeString(newer.resolve("release"), "JAVA_VERSION=\"27.0.1\"\n");
        Path older = Files.createDirectories(dir.resolve("temurin-21"));
        Files.writeString(older.resolve("release"), "JAVA_VERSION=\"21.0.12\"\n");
        Path current = dir.resolve("current");
        Symlinks.create(current, newer);
        // Judged as settled, so a reading is remembered rather than re-read for a fresh write.
        Settled.aheadForTests(Duration.ofMinutes(1));
        AtomicInteger forks = new AtomicInteger();
        WorkerAotCache.Trainer trainer = (out, scratch) -> {
            forks.incrementAndGet();
            return Sleepers.writesLine("x", out);
        };
        assertThat(WorkerAotCache.flags("java-compiler", current, "/w/a.jar", List.of(), trainer))
                .isEmpty();
        waitUntil(() -> forks.get() == 1 && !WorkerAotCache.trainingInFlight());
        assertThat(WorkerAotCache.flags("java-compiler", current, "/w/a.jar", List.of(), trainer))
                .anyMatch(f -> f.startsWith("-XX:AOTCache="));

        Files.delete(current);
        Symlinks.create(current, older);

        assertThat(WorkerAotCache.flags("java-compiler", current, "/w/a.jar", List.of(), trainer))
                .isEmpty();
        assertThat(WorkerAotCache.trainingInFlight()).isFalse();
        assertThat(forks.get()).isEqualTo(1);
    }

    /**
     * When a cache lands, the sweep runs; a cache a live worker maps stays, however long ago it was
     * last touched (on a snapshot jk the age rule would take it otherwise), and is touched again.
     */
    @Test
    void a_landing_cache_sweeps_but_spares_what_this_process_maps(@TempDir Path dir) throws Exception {
        Path state = Files.createDirectories(dir.resolve("state"));
        roomy(dir);
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path pinned = Files.createDirectories(dir.resolve("temurin-27"));
        Files.writeString(pinned.resolve("release"), "JAVA_VERSION=\"27.0.1\"\n");
        WorkerAotCache.Host host = requireNonNull(WorkerAotCache.hostOf(pinned));
        Path mapped = WorkerAotCache.cacheFile("kotlinc", host, "/w/old-kotlin.jar", List.of());
        Files.createDirectories(mapped.getParent());
        Files.writeString(mapped, "an older Kotlin's cache");
        List<String> mapping =
                WorkerAotCache.flags("kotlinc", pinned, "/w/old-kotlin.jar", List.of(), (out, scratch) -> List.of());
        assertThat(mapping).contains("-XX:AOTCache=" + mapped);
        Assumptions.assumeTrue(
                ProcessHandle.current().info().arguments().isPresent(),
                "this platform shows no process arguments; a mapped file there refuses its delete instead");
        // A warm worker still running on the old Kotlin: its command line names the cache.
        Process worker = Sleepers.sleeperMentioning(60, mapping.getFirst()).start();
        FileTime longAgo = FileTime.fromMillis(
                System.currentTimeMillis() - Duration.ofHours(13).toMillis());
        Files.setLastModifiedTime(mapped, longAgo);
        Path unmapped = Files.writeString(
                mapped.resolveSibling(
                        "kotlinc-" + JkVersion.VERSION + "-" + WorkerAotCache.jdkTag(host) + "-0123456789abcdef.aot"),
                "a key nobody maps");
        Files.setLastModifiedTime(unmapped, longAgo);
        Path otherVersion = Files.writeString(
                mapped.resolveSibling("kotlinc-0.1.0-" + WorkerAotCache.jdkTag(host) + "-0123456789abcdef.aot"), "x");

        AtomicInteger forks = new AtomicInteger();
        try {
            WorkerAotCache.flags("kotlinc", pinned, "/w/new-kotlin.jar", List.of(), (out, scratch) -> {
                forks.incrementAndGet();
                return Sleepers.writesLine("x", out);
            });
            waitUntil(() -> forks.get() == 1 && !WorkerAotCache.trainingInFlight());
        } finally {
            worker.destroyForcibly().waitFor();
        }

        assertThat(WorkerAotCache.cacheFile("kotlinc", host, "/w/new-kotlin.jar", List.of()))
                .isRegularFile();
        assertThat(mapped).as("a live worker maps it").exists();
        assertThat(Files.getLastModifiedTime(mapped))
                .as("and the sweep says so")
                .isGreaterThan(longAgo);
        assertThat(otherVersion).as("another jk version's never maps again").doesNotExist();
        if (Versions.isSnapshot(JkVersion.VERSION)) {
            assertThat(unmapped).as("a snapshot's cache unmapped for 12 h").doesNotExist();
        } else {
            assertThat(unmapped).as("a release deletes nothing by age").exists();
        }
    }

    /** A recording is a heavy write: none while the state volume is critical. */
    @Test
    void no_cache_is_recorded_while_the_volume_is_nearly_full(@TempDir Path dir) throws Exception {
        Path state = Files.createDirectories(dir.resolve("state"));
        roomy(dir);
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path pinned = Files.createDirectories(dir.resolve("temurin-27"));
        Files.writeString(pinned.resolve("release"), "JAVA_VERSION=\"27.0.1\"\n");
        AtomicInteger forks = new AtomicInteger();
        WorkerAotCache.Trainer trainer = (out, scratch) -> {
            forks.incrementAndGet();
            return Sleepers.writesLine("x", out);
        };
        try (var full = DiskGuard.pinForTest(state, new JkCacheConfig.DiskSpace(100L << 30, 1L << 30))) {
            assertThat(WorkerAotCache.flags("java-compiler", pinned, "/w/a.jar", List.of(), trainer))
                    .isEmpty();
            assertThat(WorkerAotCache.trainingInFlight()).isFalse();
        }
        assertThat(forks.get()).isZero();
        assertThat(WorkerAotCache.flags("java-compiler", pinned, "/w/a.jar", List.of(), trainer))
                .as("off the line, the miss records again")
                .isEmpty();
        waitUntil(() -> forks.get() == 1 && !WorkerAotCache.trainingInFlight());
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
        roomy(dir);
        System.setProperty("jk.env.JK_STATE_DIR", state.toString());
        Path aot = Files.createDirectories(state.resolve("aot"));
        String tag = WorkerAotCache.jdkTag(requireNonNull(engine));
        Path older = Files.writeString(
                aot.resolve("kotlinc-" + JkVersion.VERSION + "-" + tag + "-0123456789abcdef.aot"), "an older key");
        Files.setLastModifiedTime(
                older,
                FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofHours(13).toMillis()));
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
        if (Versions.isSnapshot(JkVersion.VERSION)) {
            assertThat(older).as("a snapshot's key unmapped for 12 h goes").doesNotExist();
        } else {
            assertThat(older).as("a release deletes nothing by age").exists();
        }
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
        roomy(dir);
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

    private static void waitUntil(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("trainer still running after 30s");
            Thread.sleep(20);
        }
    }
}
