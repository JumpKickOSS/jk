// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.winhost.MachineSetup;
import cc.jumpkick.winhost.SetupMemo;
import cc.jumpkick.winhost.WindowsFromGuest;
import cc.jumpkick.winhost.WindowsSetup;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A WSL guest offers JumpKick for Windows once, after a person's build, and remembers the answer. */
class GuestWindowsOfferTest {

    private final long[] now = {1_000_000L};
    private final Clock clock = new Clock() {
        @Override
        public long millis() {
            return now[0];
        }

        @Override
        public long nanos() {
            return now[0] * 1_000_000L;
        }
    };
    private final DecisionQueue queue = new DecisionQueue(clock);
    private final AtomicBoolean windowsMissing = new AtomicBoolean(true);
    private final AtomicInteger asked = new AtomicInteger();
    private final AtomicInteger installs = new AtomicInteger();
    private final CountDownLatch installed = new CountDownLatch(1);

    @TempDir
    Path tmp;

    private @Nullable GuestWindowsOffer made;

    /**
     * An install outlives the latch: after the installer returns it remembers the answer in the memo
     * under {@link #tmp}, so a test that ended at the latch had JUnit deleting the dir while it wrote
     * (JK-VT1QM). Every test waits its install out before the dir goes.
     */
    @AfterEach
    void noInstallOutlivesTheTest() throws InterruptedException {
        GuestWindowsOffer offer = made;
        if (offer == null) return;
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (offer.installing() && System.nanoTime() < until) Thread.sleep(10);
        assertThat(offer.installing())
                .as("an install still writing under the test's temp dir")
                .isFalse();
    }

    private GuestWindowsOffer offer() throws InterruptedException {
        GuestWindowsOffer offer = new GuestWindowsOffer(
                tmp.resolve("state").resolve(SetupMemo.FILE),
                () -> {
                    asked.incrementAndGet();
                    return windowsMissing.get();
                },
                progress -> {
                    installs.incrementAndGet();
                    progress.accept("installing");
                    installed.countDown();
                    linger();
                    return new MachineSetup.Result(WindowsFromGuest.ROW, true, "installed JumpKick for Windows 0.52.1");
                },
                clock);
        made = offer;
        offer.watch(queue);
        offer.refresh();
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (offer.missing() == null && System.nanoTime() < until) Thread.sleep(10);
        return offer;
    }

    /** A real install logs and returns a moment after its last step: the test's ends here a little later. */
    private static void linger() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path memo() {
        return tmp.resolve("state").resolve(SetupMemo.FILE);
    }

    @Test
    void a_build_raises_the_one_row_once_while_it_is_out() throws Exception {
        GuestWindowsOffer offer = offer();

        Decision d = Objects.requireNonNull(offer.offer(queue, "/home/ann/app"));

        assertThat(d.kind()).isEqualTo(WindowsSetup.KIND);
        assertThat(d.question()).isEqualTo(WindowsFromGuest.QUESTION);
        assertThat(d.label()).isEqualTo(MachineSetupDecisions.OFFERED_BY);
        assertThat(String.valueOf(d.toMap().get("form")))
                .contains(WindowsFromGuest.ROW)
                .contains("JumpKick for Windows");
        assertThat(offer.offer(queue, "/home/ann/app")).as("one is out").isNull();
        assertThat(queue.list()).hasSize(1);
    }

    @Test
    void declining_is_remembered_and_no_later_build_asks() throws Exception {
        GuestWindowsOffer offer = offer();
        Decision d = Objects.requireNonNull(offer.offer(queue, null));

        queue.decline(d.id(), DecisionSurface.DASHBOARD);

        assertThat(WindowsFromGuest.answered(memo())).isTrue();
        assertThat(SetupMemo.read(memo()).answer()).isEqualTo(SetupMemo.Answer.DECLINED);
        assertThat(offer.offer(queue, null)).isNull();
        assertThat(queue.list().stream().filter(Decision::open)).isEmpty();
    }

    @Test
    void accepting_the_row_installs_windows_once_and_is_remembered() throws Exception {
        GuestWindowsOffer offer = offer();
        Decision d = Objects.requireNonNull(offer.offer(queue, null));

        queue.accept(
                d.id(),
                Map.of(DecisionForms.CHANGES, List.of(WindowsFromGuest.ROW), DecisionForms.ANSWER, DecisionForms.YES),
                DecisionSurface.DASHBOARD);

        assertThat(installed.await(10, TimeUnit.SECONDS)).isTrue();
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!WindowsFromGuest.answered(memo()) && System.nanoTime() < until) Thread.sleep(10);
        assertThat(SetupMemo.read(memo()).answer()).isEqualTo(SetupMemo.Answer.ACCEPTED);
        assertThat(installs.get()).isEqualTo(1);
        assertThat(queue.spent(d.id())).isTrue();
        assertThat(offer.offer(queue, null)).isNull();
        assertThat(installs.get()).isEqualTo(1);
    }

    /** {@code jk decide -y} answers yes and names no rows: the form's default, every row ticked, holds. */
    @Test
    void a_plain_yes_takes_the_ticked_row() throws Exception {
        GuestWindowsOffer offer = offer();
        Decision d = Objects.requireNonNull(offer.offer(queue, null));

        queue.accept(d.id(), Map.of(DecisionForms.ANSWER, DecisionForms.YES), DecisionSurface.CLI);

        assertThat(installed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(installs.get()).isEqualTo(1);
    }

    @Test
    void a_windows_side_with_jumpkick_is_never_offered_it() throws Exception {
        windowsMissing.set(false);
        GuestWindowsOffer offer = offer();

        assertThat(offer.offer(queue, null)).isNull();
        assertThat(queue.list()).isEmpty();
        assertThat(WindowsFromGuest.answered(memo())).as("nothing was asked").isFalse();
    }

    @Test
    void an_answer_already_given_asks_windows_nothing() throws Exception {
        WindowsFromGuest.remember(memo(), false, 1);
        GuestWindowsOffer offer = offer();
        int before = asked.get();

        assertThat(offer.offer(queue, null)).isNull();
        assertThat(asked.get()).as("the memo answers before interop is asked").isEqualTo(before);
    }

    @Test
    void the_answer_about_windows_is_asked_again_once_it_is_stale() throws Exception {
        GuestWindowsOffer offer = offer();
        int first = asked.get();
        assertThat(offer.missing()).isTrue();
        assertThat(asked.get()).as("fresh: not asked again").isEqualTo(first);

        windowsMissing.set(false);
        now[0] += GuestWindowsOffer.FRESH.toMillis() + 1;
        offer.missing();
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Boolean.FALSE.equals(offer.missing()) && System.nanoTime() < until) Thread.sleep(10);
        assertThat(offer.missing()).isFalse();
        assertThat(offer.offer(queue, null)).as("Windows has it now").isNull();
    }

    @Test
    void an_expired_offer_counts_as_declined() throws Exception {
        GuestWindowsOffer offer = offer();
        Decision d = Objects.requireNonNull(offer.offer(queue, null));

        now[0] += DecisionQueue.DEADLINE.toMillis() + 1;
        queue.list();

        assertThat(queue.find(d.id()).map(Decision::state)).contains(Decision.State.EXPIRED);
        assertThat(WindowsFromGuest.answered(memo())).isTrue();
    }
}
