// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.SilentPeer.Grace;
import cc.jumpkick.cli.engine.SilentPeer.Life;
import cc.jumpkick.cli.engine.SilentPeer.Verdict;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The rule for a peer that accepts connections and answers no handshake: life without a reply —
 * youth, worker children, CPU advancing — earns a wait that grows with the load and ends in a
 * refusal, never a kill; only a holder that shows no life twice is displaced.
 */
class SilentPeerTest {

    private static final Grace GRACE =
            new Grace(Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(15), Duration.ofMinutes(3));

    private static Life life(Duration age, int workers, Duration cpu) {
        return new Life(4242, age, workers, cpu);
    }

    @Test
    void a_young_holder_is_waited_out_and_then_left_alone() {
        Life young = life(Duration.ofSeconds(10), 0, Duration.ofMillis(800));

        assertThat(SilentPeer.judge(null, young, Duration.ZERO, GRACE)).isEqualTo(Verdict.KEEP_WAITING);
        assertThat(SilentPeer.judge(young, young, Duration.ofSeconds(29), GRACE))
                .isEqualTo(Verdict.KEEP_WAITING);
        assertThat(SilentPeer.judge(young, young, Duration.ofSeconds(30), GRACE))
                .isEqualTo(Verdict.LEAVE_ALONE);
    }

    @Test
    void workers_extend_the_patience_up_to_the_ceiling() {
        Life six = life(Duration.ofHours(1), 6, Duration.ofMinutes(5));
        assertThat(GRACE.patienceFor(six)).isEqualTo(Duration.ofSeconds(120));
        assertThat(SilentPeer.judge(six, six, Duration.ofSeconds(119), GRACE)).isEqualTo(Verdict.KEEP_WAITING);
        assertThat(SilentPeer.judge(six, six, Duration.ofSeconds(120), GRACE)).isEqualTo(Verdict.LEAVE_ALONE);

        Life twenty = life(Duration.ofHours(1), 20, Duration.ofMinutes(5));
        assertThat(GRACE.patienceFor(twenty)).as("capped").isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void cpu_advancing_is_life_and_a_flat_cpu_past_startup_is_none() {
        Life before = life(Duration.ofHours(1), 0, Duration.ofSeconds(1));
        Life burning = life(Duration.ofHours(1), 0, Duration.ofMillis(1_200));
        Life flat = life(Duration.ofHours(1), 0, Duration.ofMillis(1_010));

        assertThat(burning.cpuAdvancedSince(before)).isTrue();
        assertThat(SilentPeer.judge(before, burning, Duration.ofSeconds(5), GRACE))
                .isEqualTo(Verdict.KEEP_WAITING);
        assertThat(flat.cpuAdvancedSince(before))
                .as("a tick of JIT is not a busy engine")
                .isFalse();
        assertThat(SilentPeer.judge(null, flat, Duration.ZERO, GRACE))
                .as("the first reading cannot show a step: one more round")
                .isEqualTo(Verdict.KEEP_WAITING);
        assertThat(SilentPeer.judge(before, flat, Duration.ofSeconds(3), GRACE)).isEqualTo(Verdict.DISPLACE);
    }

    @Test
    void the_refusal_names_the_pid_the_load_and_the_wait() {
        Life six = life(Duration.ofSeconds(192), 6, Duration.ofMinutes(5));

        assertThat(SilentPeer.refusal(six, Duration.ofSeconds(120)))
                .isEqualTo("the build engine (pid 4242) is alive and busy — up 3m 12s, 6 worker processes — but has"
                        + " not answered a handshake in 2m 00s; it is not displaced, since that would kill the jobs"
                        + " it runs for other terminals. Retry in a moment; `jk engine status` shows its jobs, and"
                        + " `jk engine stop --now` stops it regardless");
        assertThat(life(Duration.ofSeconds(45), 1, Duration.ZERO).describe()).isEqualTo("up 45s, 1 worker process");
        assertThat(SilentPeer.human(Duration.ofSeconds(7_500))).isEqualTo("2h 05m");
    }

    @Test
    void every_child_but_the_windows_console_host_is_a_worker() {
        assertThat(Life.countsAsWorker(Optional.of("C:\\Windows\\system32\\conhost.exe")))
                .isFalse();
        assertThat(Life.countsAsWorker(Optional.of("/usr/lib/jvm/bin/java"))).isTrue();
        assertThat(Life.countsAsWorker(Optional.of("C:\\jdks\\graalvm-25\\bin\\native-image.exe")))
                .as("a native link in flight is life")
                .isTrue();
        assertThat(Life.countsAsWorker(Optional.of("/bin/sh")))
                .as("an import's mvn/gradlew shell")
                .isTrue();
        assertThat(Life.countsAsWorker(Optional.empty()))
                .as("a command the OS hides is no proof of idleness")
                .isTrue();
    }
}
