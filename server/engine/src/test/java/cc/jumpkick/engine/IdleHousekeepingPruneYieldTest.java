// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.task.CachePruneScheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build that arrives while the idle-boundary prune holds the cache gate waits for the prune's
 * next safe point, not for the whole pass: the prune stops, lets the build's plan job take the
 * gate's read lock, leaves every surviving key with all of its blobs, and finishes whole at the
 * next boundary.
 *
 * <p>The build is pinned to a known point of the pass by a hook at the prune's safe points, so
 * nothing here races a wall clock to land inside the pass; the bound is on how long the plan
 * job's read lock takes once it queues.
 */
class IdleHousekeepingPruneYieldTest {

    /** "A few hundred ms": the longest a plan job may wait on the gate once it has queued. */
    private static final Duration BOUND = Duration.ofMillis(500);

    private static final int LIVE = 400;
    private static final int EXPIRED = 100;
    private static final int GARBAGE = 1_500;
    private static final Pattern OUTPUT = Pattern.compile("^OUTPUT ([0-9a-f]{64}) ", Pattern.MULTILINE);

    @TempDir
    Path home;

    @TempDir
    Path roots;

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    private final List<Path> liveKeys = new ArrayList<>();
    private final List<Path> liveBlobs = new ArrayList<>();
    private final List<Path> expiredKeys = new ArrayList<>();
    private final List<Path> expiredBlobs = new ArrayList<>();
    private final List<Path> garbage = new ArrayList<>();
    private @Nullable String priorHome;
    private @Nullable String priorAutoPrune;
    private Path cache;

    @BeforeEach
    void largeCache() throws IOException {
        priorHome = System.getProperty("jk.env.JK_HOME");
        priorAutoPrune = System.getProperty("jk.env.JK_AUTO_PRUNE");
        System.setProperty("jk.env.JK_HOME", home.toAbsolutePath().toString());
        System.setProperty("jk.env.JK_AUTO_PRUNE", "true");
        // No host calibration after the prune: it would run a real warmup on this machine.
        Files.writeString(home.resolve("config.toml"), "[engine]\nauto-warmup = false\n");

        cache = roots.resolve("cache");
        Files.createDirectories(cache);
        Cas cas = new Cas(cache);
        long hour = Duration.ofHours(1).toMillis();
        long day = Duration.ofDays(1).toMillis();
        for (int i = 0; i < LIVE; i++) {
            Path blob = blob(cas, "live-" + i, 60 * day);
            liveBlobs.add(blob);
            liveKeys.add(key("live-" + i, "compile@live" + i, 2 * hour, blob));
        }
        // Past the 30-day action window, each a minute apart so the eviction order is known.
        for (int i = 0; i < EXPIRED; i++) {
            Path blob = blob(cas, "expired-" + i, 90 * day);
            expiredBlobs.add(blob);
            expiredKeys.add(key("expired-" + i, "compile@expired" + i, 40 * day + i * 60_000L, blob));
        }
        for (int i = 0; i < GARBAGE; i++) garbage.add(blob(cas, "garbage-" + i, 2 * day));
    }

    @AfterEach
    void restoreEnv() {
        restore("jk.env.JK_HOME", priorHome);
        restore("jk.env.JK_AUTO_PRUNE", priorAutoPrune);
    }

    @Test
    void a_build_arriving_mid_sweep_starts_within_the_bound_and_the_prune_finishes_at_the_next_boundary()
            throws Exception {
        // The sweep unlinks its victims in walk order, so the first garbage blob the walk meets is
        // the first one to go: once it is gone the pass is inside the sweep's delete loop.
        Path firstVictim = firstGarbageInWalkOrder();
        assertYieldsAndFinishes(() -> Files.notExists(firstVictim), true);
    }

    @Test
    void a_build_arriving_mid_eviction_starts_within_the_bound_and_no_key_loses_a_blob() throws Exception {
        // Oldest last use goes first: the expired key aged most is the first entry evicted.
        Path firstEvicted = expiredKeys.getLast();
        Path itsBlob = expiredBlobs.getLast();
        assertYieldsAndFinishes(() -> Files.notExists(firstEvicted), false);
        // Taken whole before the stop, the way the next pass would have taken it.
        assertThat(itsBlob).doesNotExist();
    }

    @Test
    void builds_arriving_faster_than_a_pass_still_let_the_prune_finish_within_a_bounded_number_of_boundaries()
            throws Exception {
        IdleHousekeeping housekeeping = idle();
        AtomicInteger safePoints = new AtomicInteger();
        AtomicReference<@Nullable Thread> build = new AtomicReference<>();
        // Every pass meets a build part-way in: it queues on the gate, the way a plan job does, and
        // runs as soon as the prune lets it.
        housekeeping.onPruneSafePoint(() -> {
            if (safePoints.incrementAndGet() != 200 || build.get() != null) return;
            build.set(Thread.ofPlatform().name("plan-job").start(() -> {
                gate.readLock().lock();
                gate.readLock().unlock();
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!gate.hasQueuedThreads() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000L);
        });
        housekeeping.maybeEnqueuePrune(cache);

        int boundaries = 0;
        while (log.stream().noneMatch(line -> line.contains("cache prune removed"))) {
            assertThat(boundaries)
                    .as("boundaries before the prune finishes")
                    .isLessThanOrEqualTo(IdleHousekeeping.MAX_YIELDED_PASSES);
            safePoints.set(0);
            build.set(null);
            housekeeping.run();
            boundaries++;
            Thread met = Objects.requireNonNull(build.get(), "a build arrived during the boundary");
            met.join(TimeUnit.SECONDS.toMillis(60));
        }

        assertThat(boundaries).isEqualTo(IdleHousekeeping.MAX_YIELDED_PASSES + 1);
        assertThat(log.stream().filter(line -> line.contains("cache prune yielded to a build")))
                .hasSize(IdleHousekeeping.MAX_YIELDED_PASSES);
        assertThat(log)
                .as("the log says the last pass did not yield, and why")
                .anyMatch(line -> line.contains("runs to completion"));
        assertThat(CachePruneScheduler.read(cache)).isPresent();
        assertThat(garbage).noneMatch(Files::exists);
        assertThat(expiredKeys).noneMatch(Files::exists);
        assertThat(liveKeys).allMatch(Files::exists);
        assertEveryKeyHasItsBlobs();

        // The streak ends with the pass that finished: the next one yields again.
        log.clear();
        safePoints.set(0);
        build.set(null);
        CachePruneScheduler.write(cache, 0L, -1L);
        housekeeping.maybeEnqueuePrune(cache);
        housekeeping.run();
        Thread met = Objects.requireNonNull(build.get(), "a build arrived during the next pass");
        met.join(TimeUnit.SECONDS.toMillis(60));
        assertThat(log).anyMatch(line -> line.contains("yielded to a build (1 of"));
    }

    /**
     * Run a boundary, park it at the first safe point after {@code reached}, queue a plan job on
     * the gate, and hold the prune to the bound; then a second boundary must finish the pass. A stop
     * {@code midSweep} leaves garbage behind; a later one finds the sweep already done.
     */
    private void assertYieldsAndFinishes(BooleanSupplier reached, boolean midSweep) throws Exception {
        IdleHousekeeping housekeeping = idle();
        CountDownLatch atSafePoint = new CountDownLatch(1);
        AtomicBoolean parked = new AtomicBoolean();
        housekeeping.onPruneSafePoint(() -> {
            if (parked.get() || !reached.getAsBoolean()) return;
            parked.set(true);
            atSafePoint.countDown();
            // Hold here until the plan job is queued on the gate, so the very next question the
            // prune asks is the one under test.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!gate.hasQueuedThreads() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000L);
        });
        housekeeping.maybeEnqueuePrune(cache);

        Thread boundary = Thread.ofPlatform().name("boundary").start(housekeeping::run);
        assertThat(atSafePoint.await(60, TimeUnit.SECONDS))
                .as("the prune reached the point under test")
                .isTrue();
        assertThat(gate.isWriteLocked()).as("the prune holds the gate").isTrue();

        AtomicLong waitedNanos = new AtomicLong(-1);
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // What JobEnvelope does for every plan job: the gate's read lock for the run.
        Thread build = Thread.ofPlatform().name("plan-job").start(() -> {
            long t0 = System.nanoTime();
            gate.readLock().lock();
            try {
                waitedNanos.set(System.nanoTime() - t0);
                admitted.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                gate.readLock().unlock();
            }
        });
        assertThat(admitted.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(waitedNanos.get()))
                .as("the plan job waited for the prune's next safe point, not the pass")
                .isLessThan(BOUND);

        // The stopped pass left the cache consistent while the build had it.
        assertEveryKeyHasItsBlobs();
        release.countDown();
        build.join();
        boundary.join(TimeUnit.SECONDS.toMillis(60));
        assertThat(log).anyMatch(line -> line.contains("cache prune yielded to a build"));
        assertThat(log).noneMatch(line -> line.contains("cache prune removed"));
        assertThat(CachePruneScheduler.read(cache))
                .as("a stopped pass does not stamp the cadence")
                .isEmpty();
        if (midSweep)
            assertThat(garbage).as("garbage is left for the next pass").anyMatch(Files::exists);

        // The next boundary runs the queued pass again, whole: no enqueue, no cadence check.
        housekeeping.onPruneSafePoint(() -> {});
        housekeeping.run();
        assertThat(log).anyMatch(line -> line.contains("idle-boundary cache prune removed"));
        assertThat(CachePruneScheduler.read(cache)).isPresent();
        assertThat(garbage).noneMatch(Files::exists);
        assertThat(expiredKeys).noneMatch(Files::exists);
        assertThat(expiredBlobs).noneMatch(Files::exists);
        assertThat(liveKeys).allMatch(Files::exists);
        assertThat(liveBlobs).allMatch(Files::exists);
        assertEveryKeyHasItsBlobs();
    }

    private void assertEveryKeyHasItsBlobs() throws IOException {
        Cas cas = new Cas(cache);
        Set<String> missing = new HashSet<>();
        PathUtil.forEachRegularFile(cache.resolve("actions/keys"), (file, attrs) -> {
            Matcher m = OUTPUT.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                if (!cas.contains(m.group(1))) missing.add(file.getFileName() + " -> " + m.group(1));
            }
        });
        assertThat(missing).as("keys whose blobs are gone").isEmpty();
    }

    private Path firstGarbageInWalkOrder() throws IOException {
        Set<Path> victims = new HashSet<>(garbage);
        List<Path> first = new ArrayList<>(1);
        PathUtil.forEachRegularFile(cache.resolve("sha256"), (file, attrs) -> {
            if (first.isEmpty() && victims.contains(file)) first.add(file);
        });
        return first.getFirst();
    }

    private IdleHousekeeping idle() {
        return new IdleHousekeeping(
                new AtomicInteger(0),
                gate,
                new JkHistoryConfig(false, 30, 512),
                new BuildJournal(roots.resolve("builds")),
                new DecisionQueue(),
                () -> roots.resolve("metrics.jsonl"),
                () -> BuildMetrics.Limits.DEFAULTS,
                roots.resolve("engine"),
                System::currentTimeMillis,
                log::add,
                () -> false,
                () -> false,
                () -> {});
    }

    private static Path blob(Cas cas, String content, long ageMillis) throws IOException {
        Path blob = cas.put(content.getBytes(StandardCharsets.UTF_8));
        backdate(blob, ageMillis);
        return blob;
    }

    /** An action record naming {@code blob} as its one output, with its task pointer. */
    private Path key(String actionKey, String taskId, long ageMillis, Path blob) throws IOException {
        String sha = new Cas(cache).hashFromPath(blob).orElseThrow();
        Path keyFile = cache.resolve("actions/keys").resolve(actionKey);
        Files.createDirectories(keyFile.getParent());
        Files.writeString(
                keyFile,
                "TASK " + taskId + "\nKEY " + actionKey + "\nOUTPUT " + sha + " out/0.bin\n",
                StandardCharsets.UTF_8);
        Path pointer = cache.resolve("actions/tasks").resolve(taskId);
        Files.createDirectories(pointer.getParent());
        Files.writeString(pointer, actionKey, StandardCharsets.UTF_8);
        backdate(keyFile, ageMillis);
        return keyFile;
    }

    private static void backdate(Path file, long ageMillis) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - ageMillis));
    }

    private static void restore(String key, @Nullable String prior) {
        if (prior == null) System.clearProperty(key);
        else System.setProperty(key, prior);
    }
}
