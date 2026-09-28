// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Overbooking past the reservation, gated on an injected free-memory and pressure sample. */
class WorkerLeasesOverbookTest {

    private static final long GIB = 1L << 30;

    @Test
    void a_lease_past_the_budget_is_granted_when_the_host_is_healthy() throws Exception {
        long budget = 4 * GIB;
        FakeSignals signals = healthy();
        WorkerLeases.Ledger ledger = ledger(budget, 4, signals);
        try (WorkerLeases.Grant first = ledger.acquireBytes(budget / 2, false, null)) {
            assertThat(signals.reads).isZero();
            assertThat(first.overbooked()).isFalse();
            try (WorkerLeases.Grant second = ledger.acquireBytes(budget, false, null)) {
                assertThat(second.overbooked()).isTrue();
                assertThat(second.bytes()).isEqualTo(budget);
                WorkerLeases.Snapshot snap = ledger.snapshot();
                assertThat(snap.leasedBytes()).isEqualTo(budget + budget / 2);
                assertThat(snap.overbookedBytes()).isEqualTo(budget / 2);
                assertThat(ledger.queued()).isZero();
            }
            assertThat(ledger.snapshot().overbookedBytes()).isZero();
            try (WorkerLeases.Grant third = ledger.acquireBytes(budget / 2, false, null)) {
                assertThat(third.overbooked()).isFalse();
            }
        }
    }

    @Test
    void no_grant_when_free_memory_does_not_cover_the_lease_and_the_headroom() throws Exception {
        long budget = 4 * GIB;
        long headroom = WorkerLeases.headroomBytes(budget);
        assertThat(headroom).isEqualTo(GIB);
        FakeSignals signals = healthy();
        signals.freeBytes = budget + headroom - 1;
        WorkerLeases.Ledger ledger = ledger(budget, 4, signals);
        try (WorkerLeases.Grant held = ledger.acquireBytes(budget, false, null)) {
            assertStaysQueued(ledger, budget);
            signals.freeBytes = budget + headroom;
            try (WorkerLeases.Grant extra = ledger.acquireBytes(budget, false, null)) {
                assertThat(extra.overbooked()).isTrue();
            }
        }
    }

    @Test
    void headroom_grows_to_ten_percent_of_a_large_budget() throws Exception {
        long budget = 20 * GIB;
        assertThat(WorkerLeases.headroomBytes(budget)).isEqualTo(2 * GIB);
        FakeSignals signals = healthy();
        signals.freeBytes = budget + 2 * GIB - 1;
        WorkerLeases.Ledger ledger = ledger(budget, 4, signals);
        try (WorkerLeases.Grant held = ledger.acquireBytes(budget, false, null)) {
            assertStaysQueued(ledger, budget);
        }
    }

    @Test
    void no_grant_when_pressure_is_at_the_limit() throws Exception {
        long budget = 4 * GIB;
        FakeSignals some = healthy();
        some.some = OverbookSignals.SOME_AVG10_LIMIT;
        WorkerLeases.Ledger someLedger = ledger(budget, 4, some);
        try (WorkerLeases.Grant held = someLedger.acquireBytes(budget, false, null)) {
            assertStaysQueued(someLedger, budget);
        }

        FakeSignals full = healthy();
        full.full = OverbookSignals.FULL_AVG10_LIMIT;
        WorkerLeases.Ledger fullLedger = ledger(budget, 4, full);
        try (WorkerLeases.Grant held = fullLedger.acquireBytes(budget, false, null)) {
            assertStaysQueued(fullLedger, budget);
        }

        FakeSignals under = healthy();
        under.some = Math.nextDown(OverbookSignals.SOME_AVG10_LIMIT);
        under.full = Math.nextDown(OverbookSignals.FULL_AVG10_LIMIT);
        WorkerLeases.Ledger ledger = ledger(budget, 4, under);
        try (WorkerLeases.Grant held = ledger.acquireBytes(budget, false, null)) {
            try (WorkerLeases.Grant extra = ledger.acquireBytes(budget, false, null)) {
                assertThat(extra.overbooked()).isTrue();
            }
        }
    }

    @Test
    void pressure_falling_admits_the_waiter_and_rising_pressure_does_not() throws Exception {
        long budget = 4 * GIB;
        FakeSignals signals = healthy();
        signals.some = 9;
        WorkerLeases.Ledger ledger = ledger(budget, 4, signals);
        try (WorkerLeases.Grant held = ledger.acquireBytes(budget, false, null)) {
            CompletableFuture<WorkerLeases.Grant> waiting = spawn(ledger, budget, false, null);
            awaitQueued(ledger, 1);
            assertThat(waiting).isNotDone();
            signals.some = 0;
            try (WorkerLeases.Grant extra = waiting.get(5, TimeUnit.SECONDS)) {
                assertThat(extra.overbooked()).isTrue();
            }
        }
    }

    @Test
    void the_cap_stops_a_burst_while_the_sample_still_says_the_host_is_healthy() throws Exception {
        long budget = 1_000;
        FakeSignals signals = healthy();
        WorkerLeases.Ledger ledger = ledger(budget, 8, signals);
        List<WorkerLeases.Grant> held = new ArrayList<>();
        try {
            held.add(ledger.acquireBytes(600, false, null));
            held.add(ledger.acquireBytes(600, false, null));
            held.add(ledger.acquireBytes(600, false, null));
            assertThat(held).extracting(WorkerLeases.Grant::overbooked).containsExactly(false, true, true);
            assertThat(ledger.snapshot().leasedBytes()).isEqualTo(1_800);
            assertThat(ledger.snapshot().overbookedBytes()).isEqualTo(800);
            assertThat(ledger.snapshot().leasedBytes()).isLessThanOrEqualTo(budget * WorkerLeases.OVERBOOK_CAP_FACTOR);
            assertStaysQueued(ledger, 600);
        } finally {
            for (WorkerLeases.Grant grant : held) grant.close();
        }
    }

    @Test
    void a_disabled_source_does_not_grant_past_the_budget() throws Exception {
        FakeSignals signals = healthy();
        signals.enabled = false;
        long budget = 4 * GIB;
        WorkerLeases.Ledger ledger = ledger(budget, 4, signals);
        try (WorkerLeases.Grant held = ledger.acquireBytes(budget / 2, false, null)) {
            assertThat(signals.reads).isZero();
            assertStaysQueued(ledger, budget);
        }
    }

    @Test
    void a_jvm_still_waits_for_a_core_when_memory_could_be_overbooked() throws Exception {
        FakeSignals signals = healthy();
        WorkerLeases.Ledger ledger = ledger(GIB, 1, signals);
        try (WorkerLeases.Grant held = ledger.acquireBytes(GIB, true, null)) {
            CompletableFuture<WorkerLeases.Grant> second = spawn(ledger, GIB, true, 4L);
            awaitQueued(ledger, 1);
            assertThat(second).isNotDone();
            assertThat(signals.reads).isZero();
            ledger.cancelRequest(4);
            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
        }
    }

    @Test
    void the_head_of_the_queue_is_not_skipped_when_it_cannot_overbook() throws Exception {
        FakeSignals signals = healthy();
        signals.freeBytes = 0;
        WorkerLeases.Ledger ledger = ledger(1_000, 4, signals);
        try (WorkerLeases.Grant held = ledger.acquireBytes(800, false, null)) {
            CompletableFuture<WorkerLeases.Grant> head = spawn(ledger, 400, false, 1L);
            awaitQueued(ledger, 1);
            CompletableFuture<WorkerLeases.Grant> behind = spawn(ledger, 100, false, 2L);
            awaitQueued(ledger, 2);
            assertThat(head).isNotDone();
            assertThat(behind).isNotDone();
            ledger.cancelRequest(1);
            ledger.cancelRequest(2);
            assertThatThrownBy(() -> head.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            assertThatThrownBy(() -> behind.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            assertThat(ledger.snapshot().leasedBytes()).isEqualTo(held.bytes());
        }
    }

    @Test
    void readings_are_reused_until_the_cache_expires_and_the_cap_still_binds() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        FakeSignals raw = healthy();
        OverbookSignals.Source cached = OverbookSignals.caching(raw, clock::get, OverbookSignals.CACHE_NANOS);
        long budget = 1_000;
        WorkerLeases.Ledger ledger = ledger(budget, 8, cached);
        try (WorkerLeases.Grant first = ledger.acquireBytes(600, false, null)) {
            assertThat(raw.reads).isZero();
            try (WorkerLeases.Grant second = ledger.acquireBytes(600, false, null)) {
                assertThat(second.overbooked()).isTrue();
                assertThat(raw.reads).isEqualTo(1);
                raw.some = 80;
                try (WorkerLeases.Grant third = ledger.acquireBytes(600, false, null)) {
                    assertThat(third.overbooked()).isTrue();
                    assertThat(raw.reads)
                            .as("the spiked sample is still inside the cache window")
                            .isEqualTo(1);
                }
                clock.addAndGet(OverbookSignals.CACHE_NANOS);
                assertStaysQueued(ledger, 600);
                assertThat(raw.reads).isEqualTo(2);
            }
        }
    }

    @Test
    void the_cache_returns_the_same_sample_until_the_clock_moves() {
        AtomicLong clock = new AtomicLong(0);
        FakeSignals raw = healthy();
        OverbookSignals.Source cached = OverbookSignals.caching(raw, clock::get, OverbookSignals.CACHE_NANOS);
        assertThat(cached.read().someAvg10()).isZero();
        raw.some = 9;
        assertThat(cached.read().someAvg10()).isZero();
        assertThat(raw.reads).isEqualTo(1);
        clock.addAndGet(OverbookSignals.CACHE_NANOS - 1);
        assertThat(cached.read().someAvg10()).isZero();
        clock.addAndGet(1);
        assertThat(cached.read().someAvg10()).isEqualTo(9);
        assertThat(raw.reads).isEqualTo(2);
    }

    @Test
    void ci_and_the_knob_and_a_non_linux_host_disable_overbooking() {
        assertThat(allowed(true, true, true, true, null, null)).isTrue();
        assertThat(allowed(true, true, false, false, null, null))
                .as("cgroup mode does not need host files")
                .isTrue();
        assertThat(allowed(true, false, true, true, null, null)).isTrue();
        assertThat(allowed(true, false, false, true, null, null)).isFalse();
        assertThat(allowed(true, false, true, false, null, null)).isFalse();
        assertThat(allowed(false, true, true, true, null, null)).isFalse();
        for (String ci : List.of("1", "true", "yes", "on", "TRUE")) {
            assertThat(allowed(true, true, true, true, ci, "1")).as("CI=%s", ci).isFalse();
        }
        for (String off : List.of("0", "false", "no", "off")) {
            assertThat(allowed(true, true, true, true, null, off))
                    .as("JK_OVERBOOK=%s", off)
                    .isFalse();
            assertThat(allowed(true, true, true, true, off, null))
                    .as("CI=%s is not set", off)
                    .isTrue();
        }
        assertThat(allowed(true, true, true, true, null, "1")).isTrue();
        assertThat(allowed(true, true, true, true, null, "maybe")).isTrue();
        assertThat(allowed(true, true, true, true, "", null)).isTrue();
    }

    @Test
    void pressure_text_and_cgroup_free_parse() {
        OverbookSignals.Pressure pressure = Objects.requireNonNull(OverbookSignals.parsePressure("""
                some avg10=1.25 avg60=0.50 avg300=0.10 total=551984920
                full avg10=0.04 avg60=0.01 avg300=0.00 total=540054078
                """));
        assertThat(pressure.some()).isEqualTo(1.25);
        assertThat(pressure.full()).isEqualTo(0.04);
        assertThat(OverbookSignals.parsePressure("some avg10=1.0 avg60=0 total=0\n"))
                .isNull();
        assertThat(OverbookSignals.parsePressure("")).isNull();
        assertThat(OverbookSignals.parsePressure(null)).isNull();

        OverbookSignals.Pressure host = new OverbookSignals.Pressure(1, 0.1);
        OverbookSignals.Pressure group = new OverbookSignals.Pressure(6, 0.2);
        assertThat(OverbookSignals.worse(host, group)).isEqualTo(new OverbookSignals.Pressure(6, 0.2));
        assertThat(OverbookSignals.worse(host, null)).isEqualTo(host);
        assertThat(OverbookSignals.worse(null, group)).isNull();

        assertThat(OverbookSignals.cgroupFree("1000\n", "400\n")).isEqualTo(600);
        assertThat(OverbookSignals.cgroupFree("max\n", "400\n")).isEqualTo(-1);
        assertThat(OverbookSignals.cgroupFree("100\n", "400\n")).isEqualTo(-1);
        assertThat(OverbookSignals.cgroupFree(null, "1\n")).isEqualTo(-1);
    }

    @Test
    void the_engine_log_line_names_the_grant_and_the_sample() {
        String line = WorkerLeases.overbookedLine(
                672L << 20,
                14 * GIB + (700L << 20),
                13 * GIB + GIB / 2,
                new OverbookSignals.Reading(true, 9 * GIB, 0.4, 0));
        assertThat(line).startsWith("overbooked 672 MiB");
        assertThat(line).contains("over the budget").contains("psi some 0.40").contains("full 0.00");
    }

    private static boolean allowed(
            boolean linux,
            boolean cgroup,
            boolean pressure,
            boolean available,
            @Nullable String ci,
            @Nullable String knob) {
        return OverbookSignals.allowed(linux, cgroup, pressure, available, ci, knob);
    }

    /** Asserts a lease of {@code bytes} stays queued. The caller is holding whatever blocks it. */
    private static void assertStaysQueued(WorkerLeases.Ledger ledger, long bytes) throws Exception {
        int already = ledger.queued();
        CompletableFuture<WorkerLeases.Grant> waiting = spawn(ledger, bytes, false, 9L);
        awaitQueued(ledger, already + 1);
        assertThat(waiting).isNotDone();
        ledger.cancelRequest(9);
        assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
        assertThat(ledger.queued()).isEqualTo(already);
    }

    private static CompletableFuture<WorkerLeases.Grant> spawn(
            WorkerLeases.Ledger ledger, long bytes, boolean jvm, @Nullable Long requestId) {
        CompletableFuture<WorkerLeases.Grant> waiting = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                waiting.complete(ledger.acquireBytes(bytes, jvm, requestId));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                waiting.completeExceptionally(e);
            }
        });
        return waiting;
    }

    private static WorkerLeases.Ledger ledger(long capacityBytes, int cpu, OverbookSignals.Source signals) {
        return new WorkerLeases.Ledger(() -> capacityBytes, () -> cpu, id -> false, signals);
    }

    private static FakeSignals healthy() {
        FakeSignals signals = new FakeSignals();
        signals.enabled = true;
        signals.freeBytes = 64 * GIB;
        signals.some = 0;
        signals.full = 0;
        return signals;
    }

    private static void awaitQueued(WorkerLeases.Ledger ledger, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.queued() < n) {
            if (System.nanoTime() > deadline) throw new AssertionError("queued " + ledger.queued());
            Thread.sleep(5);
        }
    }

    private static final class FakeSignals implements OverbookSignals.Source {
        boolean enabled;
        long freeBytes;
        double some;
        double full;
        int reads;

        @Override
        public OverbookSignals.Reading read() {
            reads++;
            return new OverbookSignals.Reading(enabled, freeBytes, some, full);
        }
    }
}
