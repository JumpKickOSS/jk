// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import cc.jumpkick.run.TestSummary;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * How a suite that ran out of memory runs again, and when it stops. A memory kill runs its classes
 * once more at the same heap. A heap exhaustion climbs: each rung runs the classes that still ran
 * out on the next heap, until none does, no heap is left to try, or a rung's JVM dies of something
 * else. Each rung's run replaces what the run so far held for its classes ({@link RetryPolicy#merge}).
 * The engine forks each rung and picks each heap ({@link Forks}); which rung comes next, what it
 * runs and what the suite's summary becomes is decided here.
 */
public final class RetryLoop {

    /** What the engine does for the loop: the heaps, the notes it shows and the forks. */
    public interface Forks {

        /** The heap to try after every heap in {@code ranOut} (oldest first) ran out; null when none is left. */
        @Nullable
        Long nextHeap(List<Long> ranOut);

        /** A JVM filled {@code heap}: the next plan of this suite starts above it. */
        void filled(long heap);

        /** {@code heap} carried every class it ran. */
        void sufficed(long heap);

        /** A rung starts at {@code next} after a JVM ran out at {@code failed}, or was {@code killed} at it. */
        void climbing(long next, long failed, boolean killed);

        /** What the run so far recorded for {@code classes}, which a rung's run replaces. */
        RetryPolicy.Share share(List<String> classes);

        /**
         * One suite JVM for {@code classes} (the whole selection when empty) at {@code heap}. A
         * launcher failure that is not its heap running out propagates.
         */
        Rung fork(List<String> classes, long heap) throws IOException, InterruptedException;

        /** The failure the loop ends in when every heap ran out and no rung reported a run. */
        RuntimeException exhausted(List<Long> ranOut, Rung.Died last);
    }

    /** What one rung came to. */
    public sealed interface Rung {

        /**
         * The rung's JVM reported a run. {@code restore} puts back what the reports held for its
         * classes before it, when the run is discarded; {@code output} is what it printed outside
         * the protocol.
         */
        record Ran(TestSummary run, boolean ranOut, Runnable restore, String output) implements Rung {}

        /**
         * The rung's JVM died of its heap: before it reported a run, with the launcher's {@code
         * failure}, or after reporting only its heap running out, with none.
         */
        record Died(@Nullable RuntimeException failure, int exit, String output, List<String> command)
                implements Rung {}
    }

    private final RetryPolicy policy;
    private final Forks forks;

    public RetryLoop(RetryPolicy policy, Forks forks) {
        this.policy = policy;
        this.forks = forks;
    }

    /**
     * The suite's summary once the retries {@code first} calls for have run, or {@code first} when it
     * calls for none. {@code selection} is what the run was asked for, {@code undispatched} what a
     * pool left queued, {@code ranOut} whether a JVM of the run marked its own heap exhaustion and
     * {@code heap} the {@code -Xmx} its JVMs ran at. A JVM that died after reporting tests names no
     * class, so the whole selection runs again.
     */
    public TestSummary afterRun(
            TestSummary first, List<String> selection, List<String> undispatched, boolean ranOut, long heap) {
        boolean whole = policy.wholeSuite(first, ranOut) && policy.onlyHeap(first, ranOut);
        List<String> again = whole ? selection : policy.classes(first, undispatched, ranOut);
        if (!whole && again.isEmpty()) return first;
        boolean killed = policy.onlyKilled(first, ranOut);
        TestSummary second = rerun(heap, again, killed, whole ? null : first, ranOut);
        return second == null ? first : second;
    }

    /**
     * The run of {@code selection} after its launch died of its heap, or was {@code killed} for
     * memory, at {@code heap} before reporting any run; null when no heap is left to try.
     */
    public @Nullable TestSummary afterDeath(List<String> selection, boolean killed, long heap) {
        return rerun(heap, selection, killed, null, true);
    }

    /**
     * Rungs for {@code classes} until they stop running out; {@code heap} is the {@code -Xmx} the
     * JVMs that ran out had. {@code first} is folded in when a class-level retry replaces its
     * failures; a retry after a launch died passes {@code null} and gets only the rungs' runs.
     * Returns {@code first} when the heap cannot grow at all.
     */
    private @Nullable TestSummary rerun(
            long heap, List<String> classes, boolean killed, @Nullable TestSummary first, boolean firstRanOut) {
        try {
            if (killed) {
                forks.climbing(heap, heap, true);
                RetryPolicy.Share share = forks.share(classes);
                Rung rung = forks.fork(classes, heap);
                if (rung instanceof Rung.Died died) throw Objects.requireNonNull(died.failure(), "failure");
                TestSummary again = ((Rung.Ran) rung).run();
                return first == null ? again : policy.merge(first, classes, share, again, firstRanOut);
            }
            List<Long> ranOut = new ArrayList<>(List.of(heap));
            TestSummary settled = first;
            boolean settledRanOut = firstRanOut;
            @Nullable TestSummary partial = null;
            List<String> selection = classes;
            RetryPolicy.Share share = forks.share(selection);
            Rung.Died last = new Rung.Died(null, policy.heapExit(), "", List.of());
            while (true) {
                long failed = ranOut.getLast();
                forks.filled(failed);
                Long next = forks.nextHeap(ranOut);
                if (next == null) {
                    if (ranOut.size() == 1) return first;
                    if (partial != null) return partial;
                    throw forks.exhausted(ranOut, last);
                }
                forks.climbing(next, failed, false);
                ranOut.add(next);
                Rung rung = forks.fork(selection, next);
                if (rung instanceof Rung.Died died) {
                    last = died;
                    continue;
                }
                Rung.Ran ran = (Rung.Ran) rung;
                TestSummary run = ran.run();
                if (policy.onlyHeap(run, ran.ranOut())) {
                    // Nothing this rung reported outlives it; the record it displaced is the report's.
                    ran.restore().run();
                    last = new Rung.Died(null, policy.heapExit(), ran.output(), List.of());
                    continue;
                }
                TestSummary merged =
                        settled == null ? run : policy.merge(settled, selection, share, run, settledRanOut);
                List<String> still = policy.heapClasses(run, ran.ranOut());
                boolean whole = policy.wholeSuite(run, ran.ranOut());
                if (still.isEmpty() && !whole) {
                    forks.sufficed(next);
                    return merged;
                }
                partial = merged;
                if (!whole) {
                    settledRanOut = ran.ranOut();
                    settled = merged;
                    selection = still;
                    share = forks.share(still);
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return first;
        }
    }
}
