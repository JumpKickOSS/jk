// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A WSL guest's claim on 8910: priority (a guest outranked waits longer), the takeover debounce and
 * its backoff, the check from Windows, and the step-aside the host asks for.
 */
class FrontDoorRoleTest {

    private final FakeClock clock = new FakeClock();
    private final List<String> calls = new ArrayList<>();
    private final List<String> logged = new ArrayList<>();
    private boolean free = true;
    private FrontDoorRole.Reach reach = FrontDoorRole.Reach.REACHABLE;
    private final AtomicInteger rank = new AtomicInteger();

    private final FrontDoorRole.Seat seat = new FrontDoorRole.Seat() {
        @Override
        public boolean take() {
            calls.add("take");
            return free;
        }

        @Override
        public void release() {
            calls.add("release");
        }

        @Override
        public FrontDoorRole.Reach verify() {
            calls.add("verify");
            return reach;
        }
    };

    private final FrontDoorRole role = new FrontDoorRole(seat, clock, rank::get, logged::add);

    /** Ticks once a second for {@code seconds}. */
    private void run(int seconds) {
        for (int i = 0; i < seconds; i++) {
            clock.advance(Duration.ofSeconds(1));
            role.tick();
        }
    }

    @Test
    void a_heard_front_door_keeps_the_guest_off_8910() {
        for (int i = 0; i < 30; i++) {
            run(2);
            role.present();
        }
        assertThat(calls).isEmpty();
        assertThat(role.holding()).isFalse();
    }

    @Test
    void after_the_debounce_with_no_front_door_the_guest_takes_8910_and_checks_it_from_windows() {
        run(4);
        assertThat(calls).isEmpty();
        run(1);
        assertThat(calls).containsExactly("take", "verify");
        assertThat(role.holding()).isTrue();
        run(60);
        assertThat(calls)
                .as("a holder only checks again, every 30 s")
                .containsExactly("take", "verify", "verify", "verify");
        reach = FrontDoorRole.Reach.UNREACHABLE;
        run(30);
        assertThat(role.holding()).as("Windows stopped reaching it").isFalse();
        assertThat(calls).endsWith("verify", "release");
        assertThat(logged).anyMatch(l -> l.contains("gave up 8910"));
    }

    @Test
    void each_outranking_guest_adds_two_seconds() {
        rank.set(2);
        run(8);
        assertThat(calls).isEmpty();
        run(1);
        assertThat(calls).containsExactly("take", "verify");
    }

    @Test
    void a_front_door_that_says_it_is_leaving_is_replaced_without_the_debounce() {
        rank.set(1);
        role.present();
        role.leaving();
        run(1);
        assertThat(calls).isEmpty();
        run(1);
        assertThat(calls).containsExactly("take", "verify");
        role.stepAside();
        assertThat(role.delayMs()).as("a handover is not flapping").isEqualTo(5_000 + 2_000);
    }

    @Test
    void a_port_another_guest_holds_is_tried_again_only_after_a_full_delay() {
        free = false;
        run(5);
        assertThat(calls).containsExactly("take");
        run(4);
        assertThat(calls).hasSize(1);
        run(1);
        assertThat(calls).containsExactly("take", "take");
    }

    @Test
    void the_host_asking_for_8910_gets_it_back() {
        run(5);
        assertThat(role.holding()).isTrue();
        assertThat(role.stepAside()).isTrue();
        assertThat(role.holding()).isFalse();
        assertThat(calls).containsExactly("take", "verify", "release");
        assertThat(role.stepAside()).as("nothing to give back").isFalse();
        run(4);
        assertThat(calls).as("the host's heartbeat now has a delay to arrive").hasSize(3);
    }

    @Test
    void unreachable_from_windows_releases_and_unknown_keeps_with_one_line() {
        reach = FrontDoorRole.Reach.UNREACHABLE;
        run(5);
        assertThat(calls).containsExactly("take", "verify", "release");
        assertThat(role.holding()).isFalse();
        assertThat(logged).anyMatch(l -> l.contains("front door unreachable from Windows"));

        assertThat(role.delayMs())
                .as("a takeover it could not hold is no step of the backoff")
                .isEqualTo(5_000);
        reach = FrontDoorRole.Reach.UNKNOWN;
        run(5);
        assertThat(role.holding()).isTrue();
        role.stepAside();
        run(60);
        assertThat(role.holding()).isTrue();
        assertThat(logged.stream().filter(l -> l.contains("could not check")).count())
                .isEqualTo(1);
    }

    @Test
    void the_debounce_backs_off_with_each_takeover_and_resets_after_ten_quiet_minutes() {
        assertThat(role.delayMs()).isEqualTo(5_000);
        run(5);
        role.stepAside();
        assertThat(role.delayMs()).isEqualTo(15_000);
        run(15);
        role.stepAside();
        assertThat(role.delayMs()).isEqualTo(60_000);
        run(60);
        role.stepAside();
        assertThat(role.delayMs()).isEqualTo(300_000);
        run(300);
        role.stepAside();
        assertThat(role.delayMs()).as("capped").isEqualTo(300_000);

        clock.advance(Duration.ofMinutes(10));
        assertThat(role.delayMs()).isEqualTo(5_000);
    }
}
