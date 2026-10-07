// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Member solves overlap within their slots and heap budget, start in order, and answer in order. */
class MemberSolvesTest {

    private static final long MIB = 1L << 20;

    @Test
    void results_come_back_in_job_order_whatever_order_the_solves_finish_in() throws Exception {
        List<MemberSolves.Job<String>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int n = i;
            // Earlier jobs sleep longer, so they finish last.
            jobs.add(new MemberSolves.Job<>(MIB, () -> {
                Thread.sleep(5L * (8 - n));
                return "member-" + n;
            }));
        }
        assertThat(new MemberSolves(4, 64 * MIB).runAll(jobs))
                .containsExactly(
                        "member-0", "member-1", "member-2", "member-3", "member-4", "member-5", "member-6", "member-7");
    }

    @Test
    void no_more_solves_run_at_once_than_the_slots_allow() throws Exception {
        MemberSolves solves = new MemberSolves(3, 1024 * MIB);
        solves.runAll(sleepers(10, MIB));
        assertThat(solves.peak()).isBetween(2, 3);
    }

    @Test
    void the_heap_budget_holds_solves_back_and_one_too_large_for_it_runs_alone() throws Exception {
        MemberSolves tight = new MemberSolves(4, 10 * MIB);
        tight.runAll(sleepers(6, 6 * MIB));
        assertThat(tight.peak())
                .as("two 6 MiB solves do not fit a 10 MiB budget")
                .isEqualTo(1);

        MemberSolves roomy = new MemberSolves(4, 10 * MIB);
        assertThat(roomy.runAll(sleepers(2, 64 * MIB))).hasSize(2);
        assertThat(roomy.peak())
                .as("a solve past the whole budget still runs, by itself")
                .isEqualTo(1);
    }

    @Test
    void the_first_failure_in_job_order_is_the_one_thrown() {
        CountDownLatch laterFailed = new CountDownLatch(1);
        List<MemberSolves.Job<String>> jobs = List.of(
                new MemberSolves.Job<>(MIB, () -> {
                    // Fails only once the later job has already failed.
                    assertThat(laterFailed.await(10, TimeUnit.SECONDS)).isTrue();
                    throw new IOException("first member");
                }),
                new MemberSolves.Job<>(MIB, () -> {
                    laterFailed.countDown();
                    throw new IOException("second member");
                }));
        assertThatThrownBy(() -> new MemberSolves(2, 64 * MIB).runAll(jobs))
                .isInstanceOf(IOException.class)
                .hasMessage("first member");
    }

    @Test
    void one_slot_runs_the_solves_one_at_a_time_in_order() throws Exception {
        List<Integer> started = new ArrayList<>();
        List<MemberSolves.Job<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int n = i;
            jobs.add(new MemberSolves.Job<>(MIB, () -> {
                synchronized (started) {
                    started.add(n);
                }
                return n;
            }));
        }
        MemberSolves solves = new MemberSolves(1, 64 * MIB);
        assertThat(solves.runAll(jobs)).containsExactly(0, 1, 2, 3, 4);
        assertThat(started).containsExactly(0, 1, 2, 3, 4);
        assertThat(solves.peak()).isEqualTo(1);
    }

    private static List<MemberSolves.Job<Integer>> sleepers(int count, long bytes) {
        List<MemberSolves.Job<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = i;
            jobs.add(new MemberSolves.Job<>(bytes, () -> {
                Thread.sleep(30);
                return n;
            }));
        }
        return jobs;
    }
}
