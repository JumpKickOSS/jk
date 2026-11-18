// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.run.StepCache;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the action cache did for the step running on this thread, gathered as it happens and told
 * to the step once its scope closes ({@link TaskContext#cache}). A store makes the step a miss
 * whatever it restored before; otherwise a restore, or a marker or verdict the step read and
 * replayed, makes it a hit. Outside a step, nothing is kept.
 */
final class StepCacheNotes {

    private static final StepScope.Local<Note> NOTE = new StepScope.Local<>(Note::new);

    static {
        StepScope.onClose(StepCacheNotes::flush);
    }

    private StepCacheNotes() {}

    /** True when a step is running on this thread, so a note would be kept. */
    static boolean inStep() {
        return StepScope.current() != null;
    }

    /** The step restored {@code key}'s outputs, {@code bytes} of them, from here or the remote tier. */
    static void restored(String key, boolean remote, long bytes, long producerMillis) {
        Note n = StepScope.local(NOTE);
        if (n != null) n.hit(key, remote, bytes, producerMillis);
    }

    /** The step read {@code key}'s marker or verdict, a result in itself. */
    static void replayed(String key, boolean remote, long producerMillis) {
        Note n = StepScope.local(NOTE);
        if (n != null) n.hit(key, remote, 0, producerMillis);
    }

    /** A record under {@code key} was refused as untrusted while the step looked for it. */
    static void refused() {
        Note n = StepScope.local(NOTE);
        if (n != null) n.refused();
    }

    /** Whether the running step has met a refused record. */
    static boolean sawRefusal() {
        Note n = StepScope.local(NOTE);
        return n != null && n.sawRefusal();
    }

    /** The step ran and stored {@code bytes} under {@code key}, for {@code reason}. */
    static void stored(String key, long bytes, StepCache.Reason reason, @Nullable String changed) {
        Note n = StepScope.local(NOTE);
        if (n != null) n.stored(key, bytes, reason, changed);
    }

    /**
     * The records of {@code key} were all made by a JDK this build's policy does not take; {@code
     * note} says which and the ways out, and becomes the miss reason if the step goes on to run.
     */
    static void toolchainMismatch(String key, String note) {
        Note n = StepScope.local(NOTE);
        if (n != null) n.mismatch(note);
    }

    /**
     * A record another host stored for this step was on offer but its key differs from the one
     * this host takes, and {@code inputs} names the inputs that differ ({@code x:proc:git}): if the
     * step goes on to run with no record of its own to compare, that is its miss reason rather than
     * {@code no-record}.
     */
    static void differed(String inputs) {
        Note n = StepScope.local(NOTE);
        if (n != null) n.differed(inputs);
    }

    /**
     * The record the step took for {@code key} was made by another JDK than this build uses;
     * {@code note} says which and where ("made on temurin-25.0.5, linux-x86_64; this build uses …"),
     * and is the step's output line and its hit's {@code changed}.
     */
    static void madeOn(String key, String note) {
        Note n = StepScope.local(NOTE);
        if (n != null) n.madeOn(note);
    }

    /** The step derived {@code key} from {@code tokens}, named. */
    static void derived(String key, Map<String, String> tokens) {
        Note n = StepScope.local(NOTE);
        if (n != null) n.derived(key, tokens);
    }

    /** What the running step said {@code key} was derived from; empty when it said nothing. */
    static Map<String, String> derivedFrom(String key) {
        Note n = StepScope.local(NOTE);
        return n == null ? Map.of() : n.derivedFrom(key);
    }

    private static void flush(TaskContext ctx) {
        Note n = StepScope.local(NOTE);
        StepCache c = n == null ? null : n.toStepCache();
        if (c != null) ctx.cache(c);
        String madeOn = n == null ? null : n.madeOnNote();
        if (madeOn != null) ctx.output(madeOn);
    }

    /** One step's notes; steps hand work to pool threads, so every change is under the note's lock. */
    private static final class Note {
        private StepCache.@Nullable Outcome outcome;
        private StepCache.@Nullable Reason reason;
        private @Nullable String changed;
        private @Nullable String key;
        private long bytesIn;
        private long bytesOut;
        private boolean refused;
        private long producerMillis = -1;
        private final Map<String, Map<String, String>> derived = new HashMap<>();

        private @Nullable String mismatch;
        private @Nullable String madeOn;
        private @Nullable String differed;

        synchronized void differed(String inputs) {
            if (differed == null) differed = inputs;
        }

        synchronized void mismatch(String note) {
            if (mismatch == null) mismatch = note;
        }

        synchronized void madeOn(String note) {
            if (madeOn == null) madeOn = note;
        }

        synchronized @Nullable String madeOnNote() {
            return outcome == StepCache.Outcome.MISS ? null : madeOn;
        }

        synchronized void derived(String key, Map<String, String> tokens) {
            derived.put(key, Map.copyOf(tokens));
        }

        synchronized Map<String, String> derivedFrom(String key) {
            return derived.getOrDefault(key, Map.of());
        }

        synchronized void hit(String key, boolean remote, long bytes, long producerMillis) {
            bytesIn += bytes;
            if (outcome == StepCache.Outcome.MISS) return;
            // Several records restored by one step each cost their producer this much.
            if (producerMillis >= 0) this.producerMillis = Math.max(0, this.producerMillis) + producerMillis;
            if (outcome != StepCache.Outcome.REMOTE_HIT) {
                outcome = remote ? StepCache.Outcome.REMOTE_HIT : StepCache.Outcome.LOCAL_HIT;
            }
            if (this.key == null) this.key = key;
        }

        synchronized void stored(String key, long bytes, StepCache.Reason reason, @Nullable String changed) {
            bytesOut += bytes;
            if (outcome != StepCache.Outcome.MISS) {
                outcome = StepCache.Outcome.MISS;
                this.key = key;
                // A miss only because the records on offer came from another JDK says so; one
                // whose only other record was another host's says what differed from it.
                if (mismatch != null) {
                    this.reason = StepCache.Reason.TOOLCHAIN_CHANGED;
                    this.changed = mismatch;
                } else if (differed != null && reason == StepCache.Reason.NO_RECORD) {
                    this.reason = StepCache.Reason.INPUTS_CHANGED;
                    this.changed = differed;
                } else {
                    this.reason = reason;
                    this.changed = changed;
                }
            }
        }

        synchronized void refused() {
            refused = true;
        }

        synchronized boolean sawRefusal() {
            return refused;
        }

        synchronized @Nullable StepCache toStepCache() {
            if (outcome == null) return null;
            long producer = outcome == StepCache.Outcome.MISS ? -1 : producerMillis;
            // A hit made by another JDK says where it came from in changed; a miss its reason.
            String said = outcome == StepCache.Outcome.MISS ? changed : madeOn;
            return new StepCache(outcome, reason, said, key, bytesIn, bytesOut, producer);
        }
    }
}
