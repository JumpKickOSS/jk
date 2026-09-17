// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A fetch fanned out across repositories hands its network legs to the io pool through the leg
 * slots of each repository's host: however many fetches run at once, the legs submitted and not yet
 * finished for one host never exceed its {@link DownloadSlots#legWidth(String)}, a host whose queue
 * is full holds back only the legs bound for it, and a slot is given back exactly once.
 */
class RepoGroupLegSlotsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    /** A second listener on a different host <em>name</em>, so its legs queue in their own pool. */
    @RegisterExtension
    final LoopbackHttp other = new LoopbackHttp().concurrent().host("localhost");

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        SessionContext.reset();
    }

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void a_leg_cancelled_before_it_ran_gives_its_slot_back_once() throws Exception {
        String host = "cancelled.example.org";
        // The fetch loop cancels the legs it no longer needs as soon as an earlier repository
        // answers; a pool that has not started such a leg yet must not keep its slot.
        int legs = DownloadSlots.legsAvailable(host);
        List<Runnable> parked = new ArrayList<>();
        DownloadSlots.acquireLeg(host);
        CompletableFuture<String> leg = RepoGroup.pooledLeg(parked::add, host, () -> "answer");
        assertThat(DownloadSlots.legsAvailable(host)).isEqualTo(legs - 1);
        assertThat(RepoGroup.legsInFlight()).isEqualTo(1);

        leg.cancel(false);
        assertThat(DownloadSlots.legsAvailable(host))
                .as("the cancel returned the slot")
                .isEqualTo(legs);
        assertThat(RepoGroup.legsInFlight()).isZero();

        parked.getFirst().run();
        assertThat(DownloadSlots.legsAvailable(host))
                .as("the body that never ran releases nothing")
                .isEqualTo(legs);
        assertThat(leg).isCancelled();
    }

    @Test
    void a_leg_that_runs_gives_its_slot_back_as_it_ends_and_not_again_on_a_late_cancel() throws Exception {
        String host = "ran.example.org";
        int legs = DownloadSlots.legsAvailable(host);
        List<Runnable> parked = new ArrayList<>();
        DownloadSlots.acquireLeg(host);
        CompletableFuture<String> leg = RepoGroup.pooledLeg(parked::add, host, () -> "answer");
        parked.getFirst().run();
        assertThat(leg.get()).isEqualTo("answer");
        assertThat(DownloadSlots.legsAvailable(host)).isEqualTo(legs);

        leg.cancel(false);
        assertThat(DownloadSlots.legsAvailable(host))
                .as("a completed leg's cancel is a no-op")
                .isEqualTo(legs);
    }

    @Test
    void legs_in_flight_for_a_host_never_exceed_its_leg_slots(@TempDir Path tmp) throws Exception {
        String host = http.base().getHost();
        int width = DownloadSlots.legWidth(host);
        int repos = 3;
        int fetches = width;
        // Every repository answers "not found", but only once the test lets it: until then each
        // leg is a task parked on the server, which is where an unbounded fan-out shows.
        CountDownLatch open = new CountDownLatch(1);
        http.beforeMiss(path -> await(open));
        RepoGroup group = group(tmp, http, repos);

        List<Thread> callers = fetchAll(group, fetches);
        int peak = 0;
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        while (System.nanoTime() < until) {
            peak = Math.max(peak, RepoGroup.legsInFlight());
            Thread.sleep(5);
        }
        open.countDown();
        for (Thread t : callers) t.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(peak)
                .as("%d fetches over %d repositories would be %d legs unbounded", fetches, repos, fetches * repos)
                .isLessThanOrEqualTo(width)
                .isGreaterThan(1);
        assertThat(RepoGroup.legsInFlight()).as("every leg finished").isZero();
        assertThat(DownloadSlots.legsAvailable(host))
                .as("every leg slot came back")
                .isEqualTo(width);
    }

    @Test
    void a_host_whose_queue_is_full_holds_back_only_the_legs_bound_for_it(@TempDir Path tmp) throws Exception {
        // The first repository never answers until released and its queue fills; the second, on
        // another host, must still see every fetch's leg, including the one whose first leg is
        // waiting for room — otherwise one slow repository serializes the whole walk.
        String slowHost = http.base().getHost();
        int slowWidth = DownloadSlots.legWidth(slowHost);
        CountDownLatch open = new CountDownLatch(1);
        http.beforeMiss(path -> await(open));
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup group = new RepoGroup(List.of(
                new MavenRepo("slow", http.base().resolve("/slow/"), new Http(), cas, RepoCredential.ANONYMOUS, false),
                new MavenRepo(
                        "quick", other.base().resolve("/quick/"), new Http(), cas, RepoCredential.ANONYMOUS, false)));
        int fetches = slowWidth + 1;

        List<Thread> callers = fetchAll(group, fetches);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pomsAsked(other) < fetches && System.nanoTime() < deadline) Thread.sleep(10);
        int quickSeen = pomsAsked(other);
        open.countDown();
        for (Thread t : callers) t.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(quickSeen)
                .as("the quick repository was asked for every coordinate while the slow one's queue was full")
                .isEqualTo(fetches);
        assertThat(DownloadSlots.legsAvailable(slowHost)).isEqualTo(slowWidth);
        assertThat(DownloadSlots.legsAvailable(other.base().getHost()))
                .isEqualTo(DownloadSlots.legWidth(other.base().getHost()));
    }

    /** Distinct POMs a listener was asked for; a miss also draws sidecar and last-resort requests. */
    private static int pomsAsked(LoopbackHttp http) {
        return (int) http.requested().stream()
                .filter(path -> path.endsWith(".pom"))
                .distinct()
                .count();
    }

    private static RepoGroup group(Path tmp, LoopbackHttp http, int repos) {
        Cas cas = new Cas(tmp.resolve("cas"));
        List<MavenRepo> candidates = new ArrayList<>();
        for (int r = 0; r < repos; r++) {
            candidates.add(new MavenRepo(
                    "r" + r, http.base().resolve("/r" + r + "/"), new Http(), cas, RepoCredential.ANONYMOUS, false));
        }
        return new RepoGroup(candidates);
    }

    /** One virtual thread per fetch, each asking the group for a distinct POM. */
    private static List<Thread> fetchAll(RepoGroup group, int fetches) {
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < fetches; i++) {
            Coordinate coord = Coordinate.of("com.example", "lib" + i, "1.0");
            callers.add(Thread.ofVirtual().start(() -> {
                try {
                    group.tryFetchPom(coord);
                } catch (Exception e) {
                    // not-found and aborted legs are this test's steady state
                }
            }));
        }
        return callers;
    }

    private static void await(CountDownLatch open) {
        try {
            open.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
