// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
        long headroom = OverbookSignals.headroom(budget, false);
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
        assertThat(OverbookSignals.headroom(budget, false)).isEqualTo(2 * GIB);
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
            assertThat(ledger.snapshot().leasedBytes()).isLessThanOrEqualTo(OverbookSignals.ceiling(budget, false));
            assertStaysQueued(ledger, 600);
        } finally {
            for (WorkerLeases.Grant grant : held) grant.close();
        }
    }

    @Test
    void two_workspaces_requesting_at_once_never_hold_more_than_twice_the_budget() throws Exception {
        long budget = 4 * GIB;
        long cap = OverbookSignals.ceiling(budget, false);
        WorkerLeases.Ledger ledger = ledger(budget, 64, healthy());
        AtomicLong most = new AtomicLong();
        AtomicLong overbooked = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 16; t++) {
            long workspace = 1 + (t % 2);
            long bytes = (256L << 20) * (1 + t % 8);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 40; i++) {
                        try (WorkerLeases.Grant grant = ledger.acquireBytes(bytes, true, workspace)) {
                            most.accumulateAndGet(ledger.snapshot().leasedBytes(), Math::max);
                            if (grant.overbooked()) overbooked.incrementAndGet();
                            Thread.sleep(1);
                        }
                    }
                } catch (InterruptedException | RuntimeException e) {
                    errors.add(e);
                }
            }));
        }
        for (Thread thread : threads)
            assertThat(thread.join(Duration.ofSeconds(30))).isTrue();
        assertThat(errors).isEmpty();
        assertThat(overbooked.get())
                .as("the healthy sample let grants pass the budget")
                .isPositive();
        assertThat(most.get()).isGreaterThan(budget).isLessThanOrEqualTo(cap);
        assertThat(ledger.snapshot().leasedBytes()).isZero();
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
            // Drop the waiter that fits first. Cancelling the head grants the next lease that
            // fits, so the fitting waiter would already be running by the time it was cancelled.
            ledger.cancelRequest(2);
            ledger.cancelRequest(1);
            assertThatThrownBy(() -> behind.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            assertThatThrownBy(() -> head.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            assertThat(ledger.snapshot().leasedBytes()).isEqualTo(held.bytes());
        }
    }

    @Test
    void each_grant_of_a_burst_spends_the_free_memory_the_one_before_it_left() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        FakeSignals raw = healthy();
        long budget = 10 * GIB;
        long headroom = OverbookSignals.headroom(budget, false);
        raw.freeBytes = 8 * GIB;
        OverbookSignals.Source cached = OverbookSignals.caching(raw, clock::get, OverbookSignals.CACHE_NANOS);
        WorkerLeases.Ledger ledger = ledger(budget, 8, cached);
        ledger.spent.clock(clock::get);
        List<WorkerLeases.Grant> held = new ArrayList<>();
        try {
            held.add(ledger.acquireBytes(budget, false, null));
            held.add(ledger.acquireBytes(3 * GIB, false, null));
            held.add(ledger.acquireBytes(3 * GIB, false, null));
            assertThat(held).extracting(WorkerLeases.Grant::overbooked).containsExactly(false, true, true);
            assertStaysQueued(ledger, 3 * GIB);
            assertThat(raw.reads).as("the whole burst saw one sample").isEqualTo(1);
            assertThat(ledger.snapshot().overbookedBytes())
                    .as("the burst spends no more than the sample had free past the headroom")
                    .isLessThanOrEqualTo(raw.freeBytes - headroom);

            clock.addAndGet(OverbookSignals.SETTLE_NANOS);
            held.add(ledger.acquireBytes(3 * GIB, false, null));
            assertThat(held.getLast().overbooked())
                    .as("once the forks have had time to grow, a fresh sample is judged on its own")
                    .isTrue();
        } finally {
            for (WorkerLeases.Grant grant : held) grant.close();
        }
    }

    @Test
    void bursts_on_fresh_samples_spend_one_free_figure_while_their_forks_grow() throws Exception {
        AtomicLong clock = new AtomicLong(1_000);
        FakeSignals raw = healthy();
        long budget = 10 * GIB;
        long headroom = OverbookSignals.headroom(budget, false);
        long free = 8 * GIB;
        raw.freeBytes = free;
        OverbookSignals.Source cached = OverbookSignals.caching(raw, clock::get, OverbookSignals.CACHE_NANOS);
        WorkerLeases.Ledger ledger = ledger(budget, 16, cached).underCi(false);
        ledger.spent.clock(clock::get);
        List<WorkerLeases.Grant> held = new ArrayList<>();
        try {
            held.add(ledger.acquireBytes(budget, false, null));
            held.add(ledger.acquireBytes(3 * GIB, false, null));
            held.add(ledger.acquireBytes(3 * GIB, false, null));
            assertThat(held).extracting(WorkerLeases.Grant::overbooked).containsExactly(false, true, true);

            // Each burst lands on a sample the previous one's cache window no longer covers, while
            // the two overbooked forks have each grown another 256 MiB toward their 3 GiB.
            for (int burst = 1; burst <= 6; burst++) {
                clock.addAndGet(OverbookSignals.CACHE_NANOS + 50_000_000L);
                raw.freeBytes = free - 2 * burst * (256L << 20);
                int reads = raw.reads;
                assertStaysQueued(ledger, 3 * GIB);
                assertThat(raw.reads).as("burst %d read a fresh sample", burst).isGreaterThan(reads);
                assertThat(ledger.snapshot().overbookedBytes())
                        .as("burst %d keeps the series within the first sample's free figure past the headroom", burst)
                        .isLessThanOrEqualTo(free - headroom);
            }

            held.remove(2).close();
            held.add(ledger.acquireBytes(3 * GIB, false, null));
            assertThat(held.getLast().overbooked())
                    .as("a fork of the series that exits gives back what it was counted at")
                    .isTrue();
            assertThat(ledger.snapshot().overbookedBytes()).isLessThanOrEqualTo(free - headroom);
        } finally {
            for (WorkerLeases.Grant grant : held) grant.close();
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
    void the_knob_turns_overbooking_off_and_outside_a_cgroup_the_host_files_must_read() {
        assertThat(OverbookSignals.allowed(true, true, true, false, null)).isTrue();
        assertThat(OverbookSignals.allowed(true, false, false, false, null))
                .as("cgroup mode does not need host files")
                .isTrue();
        assertThat(OverbookSignals.allowed(false, true, true, false, null)).isTrue();
        assertThat(OverbookSignals.allowed(false, false, true, false, null)).isFalse();
        assertThat(OverbookSignals.allowed(false, true, false, false, null)).isFalse();
        for (String off : List.of("0", "false", "no", "off")) {
            assertThat(OverbookSignals.allowed(true, true, true, false, off))
                    .as("JK_OVERBOOK=%s", off)
                    .isFalse();
        }
        assertThat(OverbookSignals.allowed(true, true, true, false, "1")).isTrue();
        assertThat(OverbookSignals.allowed(true, true, true, false, "maybe")).isTrue();
        assertThat(OverbookSignals.policyOff(null)).isFalse();
        assertThat(OverbookSignals.policyOff("0")).isTrue();
    }

    @Test
    void a_ci_engine_overbooks_cautiously_with_more_headroom_a_lower_cap_and_a_pressure_reading() {
        assertThat(OverbookSignals.allowed(true, false, true, true, null))
                .as("CI in a cgroup still needs pressure")
                .isFalse();
        assertThat(OverbookSignals.allowed(true, true, true, true, null)).isTrue();
        assertThat(OverbookSignals.allowed(false, true, true, true, null)).isTrue();
        assertThat(OverbookSignals.allowed(true, true, true, true, "0")).isFalse();
        assertThat(OverbookSignals.headroom(8 * GIB, false)).isEqualTo(GIB);
        assertThat(OverbookSignals.headroom(8 * GIB, true)).isEqualTo(2 * GIB);
        assertThat(OverbookSignals.headroom(30 * GIB, false)).isEqualTo(3 * GIB);
        assertThat(OverbookSignals.headroom(30 * GIB, true)).isEqualTo(6 * GIB);
        assertThat(OverbookSignals.ceiling(10 * GIB, false)).isEqualTo(20 * GIB);
        assertThat(OverbookSignals.ceiling(10 * GIB, true)).isEqualTo(15 * GIB);
        assertThat(OverbookSignals.ceiling(Long.MAX_VALUE, true)).isEqualTo(Long.MAX_VALUE);
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

        assertThat(OverbookSignals.cgroupFree("1000\n", "400\n", null)).isEqualTo(600);
        assertThat(OverbookSignals.cgroupFree("max\n", "400\n", null)).isEqualTo(-1);
        assertThat(OverbookSignals.cgroupFree("100\n", "400\n", null)).isEqualTo(-1);
        assertThat(OverbookSignals.cgroupFree(null, "1\n", null)).isEqualTo(-1);
        assertThat(OverbookSignals.cgroupFree("1000\n", "0\n", null))
                .as("an empty limited group has its whole limit free")
                .isEqualTo(1000);
        assertThat(OverbookSignals.cgroupFree("1000\n", "", null)).isEqualTo(-1);
        String stat = "anon 100\nfile 300\nactive_file 120\ninactive_file 250\nshmem 30\n";
        assertThat(OverbookSignals.cgroupFree("1000\n", "400\n", stat))
                .as("inactive file cache is not used memory")
                .isEqualTo(850);
        assertThat(OverbookSignals.cgroupFree("100\n", "400\n", stat)).isEqualTo(-1);
        assertThat(MemoryProbe.inactiveFileBytes(stat)).isEqualTo(250);
        assertThat(MemoryProbe.inactiveFileBytes(null)).isZero();
    }

    @Test
    void free_memory_in_a_workers_group_is_no_more_than_the_host_has_available() {
        // Another engine's workers sit outside this group but inside the host's MemAvailable.
        assertThat(OverbookSignals.hostFree(12 * GIB, 3 * GIB)).isEqualTo(3 * GIB);
        assertThat(OverbookSignals.hostFree(2 * GIB, 30 * GIB)).isEqualTo(2 * GIB);
        assertThat(OverbookSignals.hostFree(2 * GIB, -1)).isEqualTo(2 * GIB);
        assertThat(OverbookSignals.hostFree(-1, 30 * GIB)).isEqualTo(-1);
    }

    @Test
    void the_engine_log_line_names_the_grant_and_the_sample() {
        String line = OverbookSignals.overbookedLine(
                672L << 20,
                14 * GIB + (700L << 20),
                13 * GIB + GIB / 2,
                new OverbookSignals.Reading(true, 9 * GIB, 0.4, 0),
                true);
        assertThat(line)
                .isEqualTo("overbooked 672 MiB: leased 14.7 GiB of the 13.5 GiB budget, 1.2 GiB over, inside the"
                        + " 27.0 GiB cap (twice the budget); allowed because free 9.0 GiB covers the lease plus"
                        + " 1.3 GiB headroom and pressure is low (psi some 0.40 full 0.00)");
        assertThat(OverbookSignals.overbookedLine(
                        672L << 20, 14 * GIB, 13 * GIB, new OverbookSignals.Reading(true, 9 * GIB, 0, 0), false))
                .as("no pressure signal off Linux")
                .endsWith("covers the lease plus 1.3 GiB headroom");
    }

    @Test
    void off_linux_the_free_physical_memory_decides_and_macos_pressure_past_normal_holds_back() {
        OverbookSignals.Reading windows = OverbookSignals.elsewhere(20 * GIB, 0, null);
        assertThat(windows.enabled()).isTrue();
        assertThat(windows.pressureLow()).isTrue();
        assertThat(windows.covers(4 * GIB, 2 * GIB)).isTrue();
        assertThat(windows.covers(19 * GIB, 2 * GIB)).isFalse();
        assertThat(OverbookSignals.elsewhere(20 * GIB, 1, null).pressureLow()).isTrue();
        assertThat(OverbookSignals.elsewhere(20 * GIB, 2, null).pressureLow()).isFalse();
        assertThat(OverbookSignals.elsewhere(20 * GIB, 4, null).pressureLow()).isFalse();
        assertThat(OverbookSignals.elsewhere(20 * GIB, 0, null).enabled())
                .as("CI does not turn it off")
                .isTrue();
        assertThat(OverbookSignals.elsewhere(20 * GIB, 0, "off").enabled()).isFalse();
        assertThat(OverbookSignals.elsewhere(-1, 0, null).enabled())
                .as("unreadable")
                .isFalse();
    }

    /** Asserts a lease of {@code bytes} stays queued. The caller is holding whatever blocks it. */
    @Test
    void a_second_engine_driving_runner_is_admitted_now_only_when_the_host_has_room_to_overbook() throws Exception {
        long budget = 8 * GIB;
        List<String> runner = List.of("java", "-Xmx1g", "Main");
        JvmOptions.HeapChoice tree = new JvmOptions.HeapChoice(false, GIB, "", 4 * GIB + 400 * WorkerLeases.MIB);
        JobWorkers.Lease drives = JobWorkers.Lease.DRIVES_ENGINES;
        FakeSignals signals = healthy();
        WorkerLeases.Ledger overbooks = ledger(budget, 1, signals).underCi(false);
        WorkerLeases.Ledger off = new WorkerLeases.Ledger(() -> budget, () -> 1, id -> false).underCi(false);
        for (WorkerLeases.Ledger ledger : List.of(overbooks, off)) {
            try (WorkerLeases.Grant first = ledger.acquireWithoutSlot(runner, null, tree);
                    WorkerLeases.Grant other = ledger.acquireBytes(2 * GIB, true, null)) {
                long leased = ledger.snapshot().leasedBytes();
                assertThat(leased)
                        .as("the second runner does not fit the budget")
                        .isGreaterThan(budget - first.bytes());
                assertThat(ledger.snapshot().runningJvms())
                        .as("the one JVM slot is held")
                        .isEqualTo(1);

                assertThat(ledger.admitsNow(runner, tree, drives))
                        .as("a runner that takes no JVM slot, past the budget")
                        .isEqualTo(ledger == overbooks);
                assertThat(ledger.admitsNow(runner, tree, JobWorkers.Lease.SLOT))
                        .as("a runner that needs the held JVM slot")
                        .isFalse();
                assertThat(ledger.snapshot().leasedBytes())
                        .as("asking grants nothing")
                        .isEqualTo(leased);
            }
        }

        signals.freeBytes = 4 * GIB;
        try (WorkerLeases.Grant first = overbooks.acquireWithoutSlot(runner, null, tree)) {
            assertThat(overbooks.admitsNow(runner, tree, drives))
                    .as("the host has no room for its tree")
                    .isFalse();
            signals.freeBytes = 64 * GIB;
            try (WorkerLeases.Grant held = overbooks.acquireBytes(budget, false, null)) {
                CompletableFuture<WorkerLeases.Grant> waiting = spawn(overbooks, 2 * budget, false, 9L);
                awaitQueued(overbooks, 1);
                assertThat(overbooks.admitsNow(runner, tree, drives))
                        .as("a lease is already waiting")
                        .isFalse();
                overbooks.cancelRequest(9);
                assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(InterruptedException.class);
            }
        }
    }

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
