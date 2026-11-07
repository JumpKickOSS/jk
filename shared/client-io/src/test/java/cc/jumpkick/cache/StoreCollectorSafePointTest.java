// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store collectors stop inside their scans when their caller's {@link SafePoint} says so, not
 * only once a whole collector is done: a large store answers a waiting build within the bound, and
 * what a stopped pass leaves is whole.
 */
class StoreCollectorSafePointTest {

    /** The longest a collector may run on once its caller wants it stopped. */
    private static final Duration BOUND = Duration.ofMillis(500);

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = Duration.ofDays(1).toMillis();

    /** What the caller throws from a safe point to stop the pass. */
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop() {
            super("stop", null, false, false);
        }
    }

    @Test
    void the_store_cas_collector_over_a_large_store_stops_mid_scan_within_the_bound(@TempDir Path root)
            throws Exception {
        Cas cas = new Cas(root);
        List<String> expired = new ArrayList<>();
        List<String> fresh = new ArrayList<>();
        for (int i = 0; i < 1_500; i++) expired.add(put(cas, "expired-" + i, 90));
        for (int i = 0; i < 1_500; i++) fresh.add(put(cas, "fresh-" + i, 2));

        AtomicBoolean wanted = new AtomicBoolean();
        AtomicInteger visits = new AtomicInteger();
        CountDownLatch midScan = new CountDownLatch(1);
        AtomicLong stoppedAt = new AtomicLong();
        SafePoint caller = () -> {
            if (visits.incrementAndGet() == 300) midScan.countDown();
            if (wanted.get()) throw new Stop();
        };
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread pass = Thread.ofPlatform().name("store-gc").start(() -> {
            try {
                StoreCasGc.run(cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(1.0), NOW, false, caller);
            } catch (Throwable t) {
                outcome.set(t);
            }
            stoppedAt.set(System.nanoTime());
        });

        assertThat(midScan.await(60, TimeUnit.SECONDS)).isTrue();
        long askedAt = System.nanoTime();
        wanted.set(true);
        pass.join(TimeUnit.SECONDS.toMillis(60));

        assertThat(outcome.get()).as("the pass stopped at a safe point").isInstanceOf(Stop.class);
        assertThat(Duration.ofNanos(stoppedAt.get() - askedAt))
                .as("the collector stopped at its next safe point, not at the end of its scan")
                .isLessThan(BOUND);
        assertThat(visits.get())
                .as("it stopped inside the scan of %d blobs", expired.size() + fresh.size())
                .isLessThan(expired.size() + fresh.size());
        assertThat(expired).as("a stop in the read-only scan deletes nothing").allMatch(cas::contains);

        StoreCasGc.run(cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(1.0), NOW, false, SafePoint.NONE);
        assertThat(expired).noneMatch(cas::contains);
        assertThat(fresh).allMatch(cas::contains);
    }

    @Test
    void a_store_cas_pass_stopped_mid_delete_has_taken_whole_blobs_and_spared_every_live_one(@TempDir Path root)
            throws Exception {
        Cas cas = new Cas(root);
        List<String> expired = new ArrayList<>();
        List<String> fresh = new ArrayList<>();
        for (int i = 0; i < 300; i++) expired.add(put(cas, "expired-" + i, 90));
        for (int i = 0; i < 300; i++) fresh.add(put(cas, "fresh-" + i, 2));

        SafePoint afterFirstDelete = () -> {
            if (expired.stream().anyMatch(sha -> !cas.contains(sha))) throw new Stop();
        };
        assertThatThrownBy(() -> StoreCasGc.run(
                        cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(1.0), NOW, false, afterFirstDelete))
                .isInstanceOf(Stop.class);

        assertThat(expired.stream().filter(sha -> !cas.contains(sha))).hasSize(1);
        assertThat(fresh).allMatch(cas::contains);
    }

    @Test
    void the_tools_collector_stops_between_installs_and_never_leaves_half_of_one(@TempDir Path tools) throws Exception {
        List<Path> old = new ArrayList<>();
        for (int i = 0; i < 40; i++) old.add(install(tools, "node", "20." + i + ".0", 30 + i));
        Path newest = install(tools, "node", "24.0.0", 0.5);
        AtomicInteger visits = new AtomicInteger();
        SafePoint afterFirstRemoval = () -> {
            visits.incrementAndGet();
            if (old.stream().anyMatch(Files::notExists)) throw new Stop();
        };

        assertThatThrownBy(() -> ToolsGc.run(tools, Set.of("node"), Map.of(), 1.0, NOW, false, afterFirstRemoval))
                .isInstanceOf(Stop.class);

        assertThat(visits.get())
                .as("asked inside the listing and the sizing walk, not once per family")
                .isGreaterThan(old.size());
        assertThat(old.stream().filter(Files::notExists)).hasSize(1);
        for (Path dir : old) {
            if (Files.exists(dir))
                assertThat(dir.resolve("bin/node")).as("an install left whole").exists();
        }
        assertThat(newest).exists();

        ToolsGc.run(tools, Set.of("node"), Map.of(), 1.0, NOW, false, SafePoint.NONE);
        assertThat(old).noneMatch(Files::exists);
        assertThat(newest).exists();
    }

    private static String put(Cas cas, String content, double daysOld) throws IOException {
        Path blob = cas.put(content.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(blob, FileTime.fromMillis(NOW - (long) (daysOld * DAY)));
        return cas.hashFromPath(blob).orElseThrow();
    }

    private static Path install(Path tools, String family, String version, double daysOld) throws IOException {
        Path dir = Files.createDirectories(tools.resolve(family).resolve(version));
        Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("bin/node"), "x".repeat(100));
        Files.writeString(dir.resolve("LICENSE"), "y".repeat(10));
        Files.setLastModifiedTime(dir, FileTime.fromMillis(NOW - (long) (daysOld * DAY)));
        return dir;
    }
}
