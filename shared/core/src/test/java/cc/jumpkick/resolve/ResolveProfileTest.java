// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lock's phase counters: the member-partition pass has its own line, and a phase that is in
 * flight when the lock dies is still credited, so a lock that fails late says where its time went.
 */
class ResolveProfileTest {

    @BeforeEach
    void on() {
        System.setProperty("jk.resolve.profile", "true");
        ResolveProfile.reset();
    }

    @AfterEach
    void off() {
        System.clearProperty("jk.resolve.profile");
        ResolveProfile.reset();
    }

    @Test
    void the_partition_pass_has_its_own_line_inside_the_resolve_phase() {
        ResolveProfile.phaseResolve(Duration.ofSeconds(9).toNanos());
        ResolveProfile.phasePartition(Duration.ofSeconds(3).toNanos());

        assertThat(ResolveProfile.report())
                .contains(
                        "phaseResolve=9000ms phasePartition=3000ms memberSolve=0ms/0 memberAssemble=0ms/0 phasePost=0ms");
    }

    @Test
    void each_member_s_solve_and_assembly_are_counted_apart_inside_the_partition_pass() {
        ResolveProfile.phasePartition(Duration.ofSeconds(5).toNanos());
        ResolveProfile.memberSolve(Duration.ofSeconds(2).toNanos());
        ResolveProfile.memberSolve(Duration.ofSeconds(1).toNanos());
        ResolveProfile.memberAssemble(Duration.ofMillis(1_500).toNanos());
        ResolveProfile.memberAssemble(Duration.ofMillis(300).toNanos());

        assertThat(ResolveProfile.report())
                .contains("phasePartition=5000ms memberSolve=3000ms/2 memberAssemble=1800ms/2");
    }

    @Test
    void ending_the_phases_credits_the_phase_in_flight() {
        FakeClock clock = new FakeClock();
        AtomicLong prep = new AtomicLong();
        AtomicLong resolve = new AtomicLong();
        ResolveProfile.Phases phases = new ResolveProfile.Phases(clock);

        phases.begin(prep::addAndGet);
        clock.advance(Duration.ofMillis(250));
        phases.begin(resolve::addAndGet);
        clock.advance(Duration.ofMillis(1_500));
        // The lock dies here, mid-resolve; the pipeline's finally ends the phases.
        phases.end();
        phases.end();

        assertThat(prep.get()).isEqualTo(Duration.ofMillis(250).toNanos());
        assertThat(resolve.get()).isEqualTo(Duration.ofMillis(1_500).toNanos());
    }

    @Test
    void reset_clears_the_partition_counter_with_the_rest() {
        ResolveProfile.phasePartition(Duration.ofSeconds(3).toNanos());
        ResolveProfile.reset();

        assertThat(ResolveProfile.report()).contains("phasePartition=0ms");
    }
}
